package cl.mitag

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private val BG = 0xFF10151C.toInt()
    private val CARD = 0xFF1A222D.toInt()
    private val LINE = 0xFF2A3544.toInt()
    private val TX = 0xFFE8EDF3.toInt()
    private val MUT = 0xFF8B98A8.toInt()
    private val ACC = 0xFF3DDC97.toInt()
    private val WARN = 0xFFFFB454.toInt()
    private val BAD = 0xFFFF6B6B.toInt()

    private val REQ_PERMS = 1
    private val REQ_EXPORT = 2
    private val REQ_IMPORT = 3

    private val ui = Handler(Looper.getMainLooper())
    private var viewDay = dayKey(System.currentTimeMillis())
    private var downloading = false

    private lateinit var dayLabel: TextView
    private lateinit var total: TextView
    private lateinit var statusTv: TextView
    private lateinit var btnTrack: Button
    private lateinit var list: LinearLayout
    private lateinit var days: LinearLayout
    private lateinit var gInfo: TextView
    private lateinit var defPrice: EditText
    private lateinit var sections: List<View>
    private lateinit var tabs: List<Button>
    private val onChange: () -> Unit = { render() }

    // ---------- helpers de UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float = 16f, color: Int = TX, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun shape(fill: Int, stroke: Int = 0, radius: Int = 10) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun btn(text: String, fill: Int = CARD, fg: Int = TX, border: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 15f; setTextColor(fg)
        background = shape(fill, if (border) LINE else 0)
        stateListAnimator = null
        setPadding(dp(8), dp(10), dp(8), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun scroll(content: View) = ScrollView(this).apply {
        addView(content); visibility = View.GONE
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(24))
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = shape(CARD, LINE); setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(8) }
    }

    // ---------- ciclo de vida ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        dayLabel = tv("", 14f, MUT)
        total = tv("$0", 44f, ACC, true)
        statusTv = tv("", 13f, MUT)
        header.addView(dayLabel); header.addView(total); header.addView(statusTv)
        root.addView(header)

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf("Día", "Historial", "Ajustes")
        tabs = names.mapIndexed { i, n ->
            Button(this).apply {
                text = n; isAllCaps = false; textSize = 15f; setTextColor(MUT); background = null; stateListAnimator = null
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { if (i == 0) viewDay = dayKey(System.currentTimeMillis()); showTab(i); render() }
            }
        }
        tabs.forEach { nav.addView(it) }
        root.addView(nav)

        // Día
        btnTrack = btn("Iniciar seguimiento", ACC, 0xFF06281A.toInt(), false) { toggleTracking() }
        btnTrack.setTypeface(btnTrack.typeface, Typeface.BOLD)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dayCol = column()
        dayCol.addView(btnTrack)
        dayCol.addView(btn("Importar recorrido (GPX / CSV)") { pickTrack() })
        dayCol.addView(list)

        // Historial
        days = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val histCol = column(); histCol.addView(days)

        // Ajustes
        val setCol = column()
        setCol.addView(tv("Tarifa por defecto para pórticos sin tarifa (CLP)", 13f, MUT))
        defPrice = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(TX); setHintTextColor(MUT); hint = "0"
            background = shape(CARD, LINE); setPadding(dp(12), dp(10), dp(12), dp(10))
            if (Store.defPrice > 0) setText(Math.round(Store.defPrice).toString())
            setOnFocusChangeListener { _, focus -> if (!focus) saveDefPrice() }
            setOnEditorActionListener { _, _, _ -> saveDefPrice(); false }
        }
        setCol.addView(defPrice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        setCol.addView(btn("Actualizar pórticos (OpenStreetMap)") { downloadGantries(null) })
        setCol.addView(btn("Excluir de la optimización de batería") { requestBatteryExemption() })
        setCol.addView(btn("Exportar registros a CSV") { exportCsv() })
        setCol.addView(btn("Borrar todos los registros", CARD, BAD) { confirmClear() })
        gInfo = tv("", 13f, MUT)
        setCol.addView(gInfo)
        setCol.addView(tv(
            "Los pórticos vienen de OpenStreetMap. Las tarifas no vienen incluidas: toca el monto de un registro " +
                "para fijar la tarifa de ese pórtico; queda guardada para las próximas pasadas.", 13f, MUT,
        ).apply { setPadding(0, dp(8), 0, 0) })

        val content = FrameLayout(this)
        sections = listOf(scroll(dayCol), scroll(histCol), scroll(setCol))
        sections.forEach { content.addView(it) }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        showTab(0)
    }

    override fun onStart() { super.onStart(); Store.addListener(onChange); render() }
    override fun onStop() { Store.removeListener(onChange); saveDefPrice(); super.onStop() }

    private fun saveDefPrice() {
        val v = defPrice.text.toString().toDoubleOrNull() ?: 0.0
        if (v != Store.defPrice) Store.setDefPrice(maxOf(0.0, v))
    }

    private fun showTab(i: Int) {
        sections.forEachIndexed { k, s -> s.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { k, b -> b.setTextColor(if (k == i) TX else MUT); b.setTypeface(null, if (k == i) Typeface.BOLD else Typeface.NORMAL) }
    }

    // ---------- render ----------
    private fun render() {
        val today = dayKey(System.currentTimeMillis())
        val all = Store.snapshot()
        val dayList = all.filter { dayKey(it.t) == viewDay }
        total.text = money(dayList.sumOf { Store.priceOf(it) ?: 0.0 })
        dayLabel.text = (if (viewDay == today) "Hoy · " else "") + viewDay + " · " + dayList.size + " pórtico" + (if (dayList.size == 1) "" else "s")
        statusTv.text = Store.status
        val running = TrackingService.running
        btnTrack.text = if (running) "Detener seguimiento" else "Iniciar seguimiento"
        btnTrack.background = shape(if (running) BAD else ACC)
        btnTrack.setTextColor(if (running) Color.WHITE else 0xFF06281A.toInt())

        list.removeAllViews()
        if (dayList.isEmpty()) list.addView(tv("Sin pasadas registradas este día.", 14f, MUT).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(24)) })
        for (p in dayList.asReversed()) {
            val (name, sub) = Store.label(p.id)
            val r = row()
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = p.t }
            r.addView(tv("%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)), 13f, MUT),
                LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
            val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            nameCol.addView(tv(name, 16f))
            if (sub.isNotEmpty()) nameCol.addView(tv(sub, 12f, MUT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            r.addView(nameCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val pr = Store.priceOf(p)
            r.addView(tv(if (pr == null) "fijar tarifa" else money(pr), if (pr == null) 13f else 15f, if (pr == null) WARN else TX, pr != null).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { editPrice(p) }
            })
            r.addView(tv("×", 20f, MUT).apply { setPadding(dp(8), dp(4), dp(4), dp(4)); setOnClickListener { Store.deletePass(p.t, p.id) } })
            list.addView(r)
        }

        days.removeAllViews()
        val byDay = all.groupBy { dayKey(it.t) }.toSortedMap(compareByDescending<String> { it })
        if (byDay.isEmpty()) days.addView(tv("Aún no hay registros.", 14f, MUT).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(24)) })
        for ((k, ps) in byDay) {
            val r = row()
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(tv(k, 16f)); c.addView(tv(ps.size.toString() + " pórticos", 12f, MUT))
            r.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(tv(money(ps.sumOf { Store.priceOf(it) ?: 0.0 }), 15f, TX, true))
            r.setOnClickListener { viewDay = k; showTab(0); render() }
            days.addView(r)
        }
        gInfo.text = if (Store.gantries.isNotEmpty()) "${Store.gantries.size} pórticos cargados." else "No hay pórticos cargados todavía."
    }

    private fun editPrice(p: Pass) {
        val (name, _) = Store.label(p.id)
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(TX)
            Store.priceOf(p)?.let { setText(Math.round(it).toString()) }
        }
        dialog().setTitle("Tarifa de $name").setMessage("Se usará también en las próximas pasadas por este pórtico.")
            .setView(et)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val v = et.text.toString().toDoubleOrNull()
                if (v != null && v >= 0) Store.setTariff(p.t, p.id, v)
            }.show()
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun confirmClear() {
        dialog().setTitle("Borrar registros").setMessage("Se borrarán todas las pasadas guardadas. Las tarifas se conservan.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ -> Store.clearPasses() }.show()
    }

    private fun setStatus(msg: String) { Store.status = msg; Store.notifyChanged() }

    // ---------- pórticos ----------
    private fun downloadGantries(then: (() -> Unit)?) {
        if (downloading) return
        downloading = true
        setStatus("Descargando pórticos…")
        thread {
            val list = Overpass.download()
            ui.post {
                downloading = false
                if (list != null) {
                    Store.setGantries(list); setStatus("${list.size} pórticos cargados."); then?.invoke()
                } else setStatus("No se pudieron descargar los pórticos. Revisa la conexión e intenta de nuevo.")
            }
        }
    }

    private fun ensureGantries(then: () -> Unit) {
        if (Store.gantries.isNotEmpty()) then() else downloadGantries(then)
    }

    // ---------- seguimiento ----------
    private fun toggleTracking() {
        if (TrackingService.running) { stopService(Intent(this, TrackingService::class.java)); return }
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION); need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERMS) else beginTracking()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) beginTracking()
        else setStatus("Permiso de ubicación denegado.")
    }

    private fun beginTracking() {
        ensureGantries {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!Store.batteryAsked && !pm.isIgnoringBatteryOptimizations(packageName)) {
                Store.setBatteryAsked()
                dialog().setTitle("Seguimiento en segundo plano")
                    .setMessage("Para que el registro no se corte con la pantalla apagada, excluye TAG TRACKER de la optimización de batería.")
                    .setNegativeButton("Ahora no") { _, _ -> launchService() }
                    .setPositiveButton("Excluir") { _, _ -> requestBatteryExemption(); launchService() }
                    .show()
            } else launchService()
        }
    }

    private fun launchService() {
        startForegroundService(Intent(this, TrackingService::class.java))
        setStatus("Buscando GPS…")
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { setStatus("Ya está excluida de la optimización de batería."); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------- importar / exportar ----------
    private fun pickTrack() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT)
    }

    private fun exportCsv() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv")
                .putExtra(Intent.EXTRA_TITLE, "tag_" + dayKey(System.currentTimeMillis()) + ".csv"),
            REQ_EXPORT,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_EXPORT -> try {
                val csv = buildCsv(Store.snapshot(), { Store.gantry(it) }, { Store.priceOf(it) })
                contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                setStatus("CSV exportado.")
            } catch (e: Exception) { setStatus("No se pudo exportar: ${e.message}") }
            REQ_IMPORT -> importTrack(uri)
        }
    }

    private fun importTrack(uri: Uri) {
        ensureGantries {
            setStatus("Procesando recorrido…")
            thread {
                try {
                    val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                    val pts = parseTrack(text)
                    if (pts.isEmpty()) { ui.post { setStatus("El archivo no tiene puntos con hora y coordenadas.") }; return@thread }
                    val det = Detector(Store.gantries)
                    val found = pts.flatMap { det.feed(it) }
                    val n = Store.addPasses(found)
                    ui.post {
                        if (found.isNotEmpty()) viewDay = dayKey(found.last().t)
                        setStatus("${pts.size} puntos leídos · ${found.size} pórticos detectados · $n nuevos.")
                        render()
                    }
                } catch (e: Exception) { ui.post { setStatus("No se pudo leer el archivo: ${e.message}") } }
            }
        }
    }
}

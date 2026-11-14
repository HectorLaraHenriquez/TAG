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
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
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
    private val REQ_TARIFFS = 4
    private val REQ_GANTRIES_CSV = 5
    private val TAB_MAP = 2

    private lateinit var web: WebView
    private var mapReady = false
    private var mapMode = 0          // 0 = ruta del día, 1 = pórticos y precios
    private var mapShown = ""        // lo último dibujado, para no redibujar igual
    private var tab = 0
    private lateinit var mapBtns: List<Button>

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
        val names = listOf("Día", "Historial", "Mapa", "Ajustes")
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
        setCol.addView(btn("Aplicar tarifas 2026 incluidas (MOP)") { applyBundledTariffs(true) })
        setCol.addView(btn("Importar tarifas (JSON)") { pick(REQ_TARIFFS) })
        setCol.addView(btn("Exportar lista de pórticos (CSV)") { create(REQ_GANTRIES_CSV, "porticos.csv") })
        setCol.addView(btn("Excluir de la optimización de batería") { requestBatteryExemption() })
        setCol.addView(btn("Permitir aviso flotante sobre otras apps") { requestOverlay() })
        setCol.addView(btn("Exportar registros a CSV") { exportCsv() })
        setCol.addView(btn("Borrar todos los registros", CARD, BAD) { confirmClear() })
        gInfo = tv("", 13f, MUT)
        setCol.addView(gInfo)
        setCol.addView(tv(
            "Los pórticos vienen de OpenStreetMap. Las tarifas no vienen incluidas: toca el monto de un registro " +
                "para fijar las tarifas de ese pórtico (base, punta, saturación y sus horarios), o importa un archivo de tarifas. " +
                "Al pasar un pórtico aparece un aviso por unos segundos con el botón Descartar.", 13f, MUT,
        ).apply { setPadding(0, dp(8), 0, 0) })

        // Mapa
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString + " TAGTracker/1.1"
            setBackgroundColor(BG)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { mapReady = true; mapShown = ""; drawMap() }
            }
            loadUrl("file:///android_asset/map.html")
        }
        val mapBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), 0) }
        mapBtns = listOf("Ruta del día", "Pórticos y precios").mapIndexed { i, n ->
            btn(n) { mapMode = i; mapShown = ""; drawMap() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = if (i == 0) dp(8) else 0; bottomMargin = dp(8) }
            }
        }
        mapBtns.forEach { mapBar.addView(it) }
        val mapCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        mapCol.addView(mapBar)
        mapCol.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val content = FrameLayout(this)
        sections = listOf(scroll(dayCol), scroll(histCol), mapCol, scroll(setCol))
        sections.forEach { content.addView(it) }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        showTab(0)
    }

    override fun onStart() {
        super.onStart(); Store.addListener(onChange)
        if (Store.gantries.isNotEmpty() && Store.tariffCount() == 0) applyBundledTariffs(false)
        render()
    }
    override fun onStop() { Store.removeListener(onChange); saveDefPrice(); super.onStop() }

    private fun saveDefPrice() {
        val v = defPrice.text.toString().toDoubleOrNull() ?: 0.0
        if (v != Store.defPrice) Store.setDefPrice(maxOf(0.0, v))
    }

    private fun showTab(i: Int) {
        tab = i
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
        if (tab == TAB_MAP) drawMap()
        gInfo.text = if (Store.gantries.isNotEmpty()) "${Store.gantries.size} pórticos cargados · ${Store.tariffCount()} con tarifa." else "No hay pórticos cargados todavía."
    }

    private fun field(hint: String, value: String, number: Boolean = true) = EditText(this).apply {
        this.hint = hint; setText(value); setTextColor(TX); setHintTextColor(MUT); textSize = 15f
        inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
    }

    private fun editPrice(p: Pass) {
        val (name, _) = Store.label(p.id)
        val cur = Store.tariff(p.id) ?: Tariff(null)
        fun n(v: Double?) = v?.let { Math.round(it).toString() } ?: ""
        val base = field("Base / fuera de punta (CLP)", n(cur.base))
        val punta = field("Punta (opcional)", n(cur.punta))
        val sat = field("Saturación (opcional)", n(cur.saturacion))
        val win = field("Horarios, ej: LV 07:30-10:00 P; LV 18:00-20:00 S", formatWindows(cur.windows), false)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
        listOf(base, punta, sat, win).forEach { box.addView(it) }
        box.addView(tv("Días: LV, S, D, SD, LS, LD. Tipo: P = punta, S = saturación. Sin horarios se cobra siempre la base.", 12f, MUT))
        dialog().setTitle("Tarifas de $name")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                try {
                    val b = base.text.toString().toDoubleOrNull()
                    if (b == null || b < 0) { setStatus("Falta la tarifa base."); return@setPositiveButton }
                    Store.setTariff(p.id, Tariff(b, punta.text.toString().toDoubleOrNull(), sat.text.toString().toDoubleOrNull(), parseWindows(win.text.toString())))
                    setStatus("Tarifa guardada.")
                } catch (e: IllegalArgumentException) { setStatus(e.message ?: "Horario no válido.") }
            }.show()
    }

    // ---------- mapa ----------
    private fun drawMap() {
        if (!mapReady) return
        mapBtns.forEachIndexed { i, b -> b.background = shape(if (i == mapMode) LINE else CARD, LINE) }
        val now = System.currentTimeMillis()
        val key: String
        val d = JSONObject()
        val marks = JSONArray()
        if (mapMode == 0) {
            val track = Store.readTrack(viewDay)
            val passes = Store.snapshot().filter { dayKey(it.t) == viewDay }
            key = "r|$viewDay|${track.size}|${passes.size}|" + passes.sumOf { Store.priceOf(it) ?: 0.0 }
            if (key == mapShown) return
            val segs = JSONArray()
            var seg = JSONArray()
            var prev: Fix? = null
            for (f in track) {
                if (prev != null && f.t - prev.t > 120_000) { segs.put(seg); seg = JSONArray() }
                seg.put(JSONArray().put(f.lat).put(f.lon)); prev = f
            }
            segs.put(seg)
            d.put("track", segs)
            for (p in passes) {
                val g = Store.gantry(p.id) ?: continue
                val (name, sub) = Store.label(p.id)
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = p.t }
                val pr = Store.priceOf(p)
                marks.put(JSONObject().put("lat", g.lat).put("lon", g.lon).put("c", "#3ddc97").put("r", 8)
                    .put("title", name).put("sub", sub)
                    .put("info", "%02d:%02d · %s".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), pr?.let { money(it) } ?: "sin tarifa")))
            }
            d.put("fit", true)
        } else {
            val gs = Store.gantries
            key = "g|${gs.size}|${now / 60000}|${Store.defPrice}|" + gs.count { Store.tariff(it.id) != null }
            if (key == mapShown) return
            for (g in gs) {
                val (name, sub) = Store.label(g.id)
                val tf = Store.tariff(g.id)
                val pr = Store.priceAt(g.id, now)
                val info = when {
                    tf != null && pr != null -> "Ahora: ${money(pr)} (${bandName(tf.bandAt(now))})"
                    pr != null -> "Ahora: ${money(pr)} (tarifa por defecto)"
                    else -> "Sin tarifa"
                }
                marks.put(JSONObject().put("lat", g.lat).put("lon", g.lon).put("r", 6)
                    .put("c", if (tf == null) "#8b98a8" else when (tf.bandAt(now)) { 'S' -> "#ff6b6b"; 'P' -> "#ffb454"; else -> "#3ddc97" })
                    .put("title", name).put("sub", sub).put("info", info))
            }
            d.put("fit", mapShown.isEmpty() || !mapShown.startsWith("g|"))
        }
        d.put("marks", marks)
        mapShown = key
        web.evaluateJavascript("show($d)", null)
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
                    Store.setGantries(list)
                    val n = applyBundledTariffs(false)
                    setStatus("${list.size} pórticos cargados · tarifas 2026 asignadas a $n."); then?.invoke()
                } else setStatus("No se pudieron descargar los pórticos. Revisa la conexión e intenta de nuevo.")
            }
        }
    }

    /**
     * Asigna las tarifas incluidas en la app (tablas 2026 del MOP) a los pórticos cargados.
     * Con [overwrite] reemplaza también las que el usuario editó; si no, solo completa las que faltan.
     */
    private fun applyBundledTariffs(overwrite: Boolean): Int {
        return try {
            val text = assets.open("tarifas_2026.json").bufferedReader().use { it.readText() }
            val (m, _) = parseTariffFile(text, Store.gantries)
            if (overwrite) {
                Store.mergeTariffs(m)
                setStatus(if (Store.gantries.isEmpty()) "Primero descarga los pórticos." else "Tarifas 2026 asignadas a ${m.size} de ${Store.gantries.size} pórticos.")
                m.size
            } else Store.mergeTariffsIfAbsent(m)
        } catch (e: Exception) { setStatus("No se pudieron aplicar las tarifas: ${e.message}"); 0 }
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
                    .setNegativeButton("Ahora no") { _, _ -> askOverlayThenLaunch() }
                    .setPositiveButton("Excluir") { _, _ -> requestBatteryExemption(); askOverlayThenLaunch() }
                    .show()
            } else askOverlayThenLaunch()
        }
    }

    private fun askOverlayThenLaunch() {
        if (!Store.overlayAsked && !Settings.canDrawOverlays(this)) {
            Store.setOverlayAsked()
            dialog().setTitle("Aviso al pasar un pórtico")
                .setMessage("Para mostrar una ventanita con el botón Descartar encima de Waze u otras apps, permite \"Mostrar sobre otras apps\". Sin ese permiso se usa una notificación emergente.")
                .setNegativeButton("Ahora no") { _, _ -> launchService() }
                .setPositiveButton("Permitir") { _, _ -> requestOverlay(); launchService() }
                .show()
        } else launchService()
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) { setStatus("El aviso flotante ya está permitido."); return }
        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
    }

    private fun pick(req: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), req)
    }

    private fun create(req: Int, name: String) {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv").putExtra(Intent.EXTRA_TITLE, name), req)
    }

    private fun importTariffs(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val (m, un) = parseTariffFile(text, Store.gantries)
            Store.mergeTariffs(m)
            setStatus("Tarifas cargadas para ${m.size} pórticos" + if (un > 0) " · $un entradas sin pórtico." else ".")
        } catch (e: Exception) { setStatus("No se pudo leer el archivo de tarifas: ${e.message}") }
    }

    private fun exportGantries(uri: Uri) {
        try {
            fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
            val sb = StringBuilder("\uFEFFid,ref,operador,via,rumbo,lat,lon,base,punta,saturacion,horarios\n")
            for (g in Store.gantries) {
                val t = Store.tariff(g.id)
                fun n(v: Double?) = v?.let { Math.round(it).toString() } ?: ""
                sb.append(listOf(g.id, q(g.name), q(g.op), q(g.road), g.brg ?: "", g.lat, g.lon, n(t?.base), n(t?.punta), n(t?.saturacion), q(t?.let { formatWindows(it.windows) } ?: "")).joinToString(",")).append('\n')
            }
            contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
            setStatus("Lista de pórticos exportada.")
        } catch (e: Exception) { setStatus("No se pudo exportar: ${e.message}") }
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
            REQ_TARIFFS -> importTariffs(uri)
            REQ_GANTRIES_CSV -> exportGantries(uri)
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
                    val n = Store.addPasses(found).size
                    Store.saveImportedTrack(pts)
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

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
    private val REQ_GUIDE = 6
    private val TAB_MAP = 2
    private lateinit var guide: ScrollView
    private lateinit var guideCol: LinearLayout

    private lateinit var web: WebView
    private var mapReady = false
    private var mapMode = 0          // 0 = ruta del día, 1 = pórticos y precios
    private var mapShown = ""        // lo último dibujado, para no redibujar igual
    private var tab = 0
    private lateinit var mapBtns: List<Button>
    private lateinit var simBar: LinearLayout
    private lateinit var simLabel: TextView
    private var simOn = false        // false = precios de ahora; true = día/hora simulados
    private var simDay = 0           // 0 = lunes a viernes, 1 = sábado, 2 = domingo
    private var simMin = 8 * 60

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
        setCol.addView(btn("Actualizar pórticos y tarifas") { downloadGantries(null) })
        setCol.addView(btn("Guía de configuración") { showGuide() })
        setCol.addView(btn("Importar tarifas (JSON)") { pick(REQ_TARIFFS) })
        setCol.addView(btn("Exportar lista de pórticos (CSV)") { create(REQ_GANTRIES_CSV, "porticos.csv") })
        setCol.addView(btn("Excluir de la optimización de batería") { requestBatteryExemption() })
        setCol.addView(btn("Permitir aviso flotante sobre otras apps") { requestOverlay() })
        setCol.addView(btn("Exportar registros a CSV") { exportCsv() })
        setCol.addView(btn("Borrar todos los registros", CARD, BAD) { confirmClear() })
        gInfo = tv("", 13f, MUT)
        setCol.addView(gInfo)
        setCol.addView(tv(
            "Los pórticos (OpenStreetMap) y sus tarifas (tablas del MOP) se descargan solos y se actualizan una vez al día. " +
                "Si un pórtico no tiene tarifa o está mal, toca el monto de un registro para fijarla a mano. " +
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
        // simulador de horario (solo en "Pórticos y precios")
        simBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0); visibility = View.GONE }
        fun small(text: String, weight: Float, onClick: () -> Unit) = btn(text, onClick = onClick).apply {
            textSize = 14f; setPadding(dp(4), dp(8), dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { marginEnd = dp(6); bottomMargin = dp(8) }
        }
        simBar.addView(small("Ahora", 1.3f) { simOn = false; drawMap() })
        simBar.addView(small("Día", 1f) { simDay = if (simOn) (simDay + 1) % 3 else simDay; simOn = true; drawMap() })
        simBar.addView(small("−30", 1f) { simMin = if (simOn) (simMin + 1410) % 1440 else simMin; simOn = true; drawMap() })
        simBar.addView(small("+30", 1f) { simMin = if (simOn) (simMin + 30) % 1440 else simMin; simOn = true; drawMap() })
        simLabel = tv("", 14f, TX, true).apply { gravity = Gravity.CENTER }
        simBar.addView(simLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { bottomMargin = dp(8) })
        mapCol.addView(simBar)
        mapCol.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val content = FrameLayout(this)
        sections = listOf(scroll(dayCol), scroll(histCol), mapCol, scroll(setCol))
        sections.forEach { content.addView(it) }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        guideCol = column().apply { setPadding(dp(16), dp(24), dp(16), dp(32)) }
        guide = ScrollView(this).apply { setBackgroundColor(BG); isClickable = true; addView(guideCol); visibility = View.GONE }
        val top = FrameLayout(this)
        top.addView(root); top.addView(guide)
        setContentView(top)
        showTab(0)
        // Versiones anteriores guardaban las tarifas incluidas como si fueran manuales: se limpian una vez.
        if (Store.lastSync == 0L && Store.gantries.isNotEmpty()) {
            try { Store.dropManualEqualTo(parseTariffFile(bundledTariffs(), Store.gantries).first) } catch (e: Exception) {}
        }
        if (!Store.onboarded) showGuide()
    }

    override fun onStart() {
        super.onStart(); Store.addListener(onChange)
        if (System.currentTimeMillis() - Store.lastSync > 24 * 3600 * 1000L) downloadGantries(null, silent = true)
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
        if (guide.visibility == View.VISIBLE) renderGuide()
        gInfo.text = if (Store.gantries.isNotEmpty()) "${Store.gantries.size} pórticos cargados · ${Store.tariffCount()} con tarifa" +
            (if (Store.lastSync > 0) " · actualizado " + dayKey(Store.lastSync) else "") + "." else "No hay pórticos cargados todavía."
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
        val dlg = dialog().setTitle("Tarifas de $name")
        if (Store.isManual(p.id)) dlg.setNeutralButton("Restablecer") { _, _ -> Store.removeManual(p.id); setStatus("Tarifa restablecida a la publicada.") }
        dlg
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
    /** Instante del próximo día del tipo elegido (L-V, sábado o domingo) a la hora simulada. */
    private fun simTime(): Long {
        val c = java.util.Calendar.getInstance()
        fun type(): Int = when (c.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.SATURDAY -> 1; java.util.Calendar.SUNDAY -> 2; else -> 0
        }
        var guard = 0
        while (type() != simDay && guard++ < 7) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        c.set(java.util.Calendar.HOUR_OF_DAY, simMin / 60); c.set(java.util.Calendar.MINUTE, simMin % 60)
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun drawMap() {
        if (!mapReady) return
        mapBtns.forEachIndexed { i, b -> b.background = shape(if (i == mapMode) LINE else CARD, LINE) }
        simBar.visibility = if (mapMode == 1) View.VISIBLE else View.GONE
        val now = if (mapMode == 1 && simOn) simTime() else System.currentTimeMillis()
        val whenTxt = if (simOn) listOf("Lun-Vie", "Sábado", "Domingo")[simDay] + " %02d:%02d".format(simMin / 60, simMin % 60) else "Ahora"
        simLabel.text = whenTxt
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
            key = "g|${gs.size}|${now / 60000}|$simOn|${Store.defPrice}|" + gs.count { Store.tariff(it.id) != null }
            if (key == mapShown) return
            for (g in gs) {
                val (name, sub) = Store.label(g.id)
                val tf = Store.tariff(g.id)
                val pr = Store.priceAt(g.id, now)
                val info = when {
                    tf != null && pr != null -> "$whenTxt: ${money(pr)} (${bandName(tf.bandAt(now))})"
                    pr != null -> "$whenTxt: ${money(pr)} (tarifa por defecto)"
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
    private fun bundledTariffs(): String = assets.open("tarifas_2026.json").bufferedReader().use { it.readText() }

    /**
     * Descarga pórticos y tarifas desde los datos alojados (data/porticos.json del repositorio).
     * Si no están disponibles, consulta OpenStreetMap y cruza con las tarifas incluidas en la app.
     */
    private fun downloadGantries(then: (() -> Unit)?, silent: Boolean = false) {
        if (downloading) return
        downloading = true
        if (!silent) setStatus("Descargando pórticos y tarifas…")
        thread {
            var gs: List<Gantry>? = null
            var ts: Map<Long, Tariff> = emptyMap()
            val r = Remote.download()
            if (r != null) { gs = r.gantries; ts = r.tariffs }
            else if (!silent || Store.gantries.isEmpty()) {
                gs = Overpass.download()
                if (gs != null) ts = try { parseTariffFile(bundledTariffs(), gs).first } catch (e: Exception) { emptyMap() }
            }
            ui.post {
                downloading = false
                val list = gs
                if (list != null) {
                    Store.setRemote(list, ts)
                    if (!silent) setStatus("${list.size} pórticos cargados · ${Store.tariffCount()} con tarifa.")
                    then?.invoke()
                } else if (!silent) setStatus("No se pudieron descargar los pórticos. Revisa la conexión e intenta de nuevo.")
            }
        }
    }

    // ---------- guía de primera configuración ----------
    private fun showGuide() { guide.visibility = View.VISIBLE; renderGuide() }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
    }

    private fun renderGuide() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val gpsOn = try { lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }
        class Step(val title: String, val desc: String, val ok: Boolean, val label: String, val action: () -> Unit)
        val steps = ArrayList<Step>()
        steps.add(Step("Ubicación precisa", "Para detectar los pórticos. Elige \"Precisa\" y \"Mientras se usa la app\".",
            granted(Manifest.permission.ACCESS_FINE_LOCATION), "Permitir") {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_GUIDE)
        })
        if (Build.VERSION.SDK_INT >= 33) steps.add(Step("Notificaciones", "Muestra el seguimiento activo y el total del día.",
            granted(Manifest.permission.POST_NOTIFICATIONS), "Permitir") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_GUIDE)
        })
        steps.add(Step("GPS encendido", "Activa la ubicación del teléfono.", gpsOn, "Abrir ajustes") {
            try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (e: Exception) {}
        })
        steps.add(Step("Batería sin restricciones", "Evita que Android detenga el registro con la pantalla apagada o con otras apps abiertas.",
            pm.isIgnoringBatteryOptimizations(packageName), "Permitir") { requestBatteryExemption() })
        steps.add(Step("Aviso flotante", "Muestra unos segundos cada pórtico detectado, con el botón Descartar, encima de Waze u otras apps.",
            Settings.canDrawOverlays(this), "Permitir") { requestOverlay() })
        steps.add(Step("Pórticos y tarifas", "Se descargan de internet y se actualizan solos.",
            Store.gantries.isNotEmpty(), "Descargar") { downloadGantries(null) })

        guideCol.removeAllViews()
        guideCol.addView(tv("Configuración inicial", 24f, TX, true))
        guideCol.addView(tv("TAG TRACKER registra los pórticos mientras manejas, aunque la pantalla esté apagada o uses otra app. Para eso necesita lo siguiente:", 14f, MUT)
            .apply { setPadding(0, dp(6), 0, dp(16)) })
        steps.forEachIndexed { i, st ->
            val r = row()
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(tv((if (st.ok) "✓ " else "${i + 1}. ") + st.title, 16f, if (st.ok) ACC else TX, true))
            c.addView(tv(st.desc, 12f, MUT))
            r.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!st.ok) r.addView(tv(st.label, 14f, 0xFF06281A.toInt(), true).apply {
                background = shape(ACC); setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { st.action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            guideCol.addView(r)
        }
        guideCol.addView(tv("En Xiaomi, Huawei, Oppo, Vivo y algunos Samsung hay un paso extra: en los ajustes de la app, deja Batería en \"Sin restricciones\" y activa \"Inicio automático\" si aparece.", 12f, MUT)
            .apply { setPadding(0, dp(8), 0, dp(8)) })
        guideCol.addView(btn("Abrir ajustes de la app") { openAppSettings() })
        guideCol.addView(tv("Después, toca \"Iniciar seguimiento\" antes de salir: el registro sigue solo hasta que lo detengas.", 12f, MUT)
            .apply { setPadding(0, 0, 0, dp(12)) })
        val pending = steps.count { !it.ok }
        guideCol.addView(btn(if (pending == 0) "Listo" else "Continuar de todos modos ($pending pendientes)", if (pending == 0) ACC else CARD,
            if (pending == 0) 0xFF06281A.toInt() else TX, pending != 0) { Store.setOnboarded(); guide.visibility = View.GONE })
    }

    override fun onResume() { super.onResume(); if (guide.visibility == View.VISIBLE) renderGuide() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (guide.visibility == View.VISIBLE) { if (Store.onboarded) guide.visibility = View.GONE; return }
        super.onBackPressed()
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
        if (requestCode == REQ_GUIDE) {
            // Si Android ya no muestra el diálogo (denegado antes), se abre la ficha de la app.
            val denied = permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED }
            if (denied.isNotEmpty() && denied.none { shouldShowRequestPermissionRationale(it) }) openAppSettings()
            renderGuide(); return
        }
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) beginTracking()
        else setStatus("Permiso de ubicación denegado.")
    }

    private fun beginTracking() { ensureGantries { launchService() } }

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

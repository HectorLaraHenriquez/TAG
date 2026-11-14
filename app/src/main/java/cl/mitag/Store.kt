package cl.mitag

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToLong

/** Estado compartido (UI + servicio) con persistencia en archivos JSON internos. */
object Store {
    private lateinit var dir: File
    private var inited = false
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var gantries: List<Gantry> = emptyList()
        private set
    private var gById: Map<Long, Gantry> = emptyMap()
    private val passes = ArrayList<Pass>()
    private val tariffs = HashMap<Long, Tariff>()         // fijadas por el usuario (tienen prioridad)
    private val remoteTariffs = HashMap<Long, Tariff>()   // descargadas junto con los pórticos
    @Volatile var lastSync = 0L
        private set
    @Volatile var onboarded = false
        private set

    @Volatile var defPrice = 0.0
        private set
    @Volatile var batteryAsked = false
        private set
    @Volatile var overlayAsked = false
        private set
    @Volatile var status = ""

    fun init(ctx: Context) {
        synchronized(lock) { initLocked(ctx) }
    }

    private fun initLocked(ctx: Context) {
        if (inited) return
        dir = ctx.filesDir
        gantries = readArr("gantries.json")?.let { a ->
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Gantry(
                    o.getLong("id"), o.getDouble("lat"), o.getDouble("lon"),
                    o.optString("name"), o.optString("op"), o.optString("road"),
                    if (o.isNull("brg")) null else o.getInt("brg"),
                )
            }
        } ?: emptyList()
        gById = gantries.associateBy { it.id }
        readArr("passes.json")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                passes.add(Pass(o.getLong("t"), o.getLong("id"), if (o.isNull("price")) null else o.getDouble("price")))
            }
        }
        readTariffs("tariffs.json", tariffs)
        readTariffs("remote_tariffs.json", remoteTariffs)
        readObj("cfg.json")?.let { lastSync = it.optLong("lastSync"); onboarded = it.optBoolean("onboarded"); defPrice = it.optDouble("def", 0.0); batteryAsked = it.optBoolean("batteryAsked"); overlayAsked = it.optBoolean("overlayAsked") }
        inited = true
    }

    private fun readTariffs(name: String, into: HashMap<Long, Tariff>) {
        val a = readArr(name) ?: return
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            fun d(k: String) = if (o.isNull(k)) null else o.getDouble(k)
            val win = try { parseWindows(o.optString("win")) } catch (e: Exception) { emptyList() }
            into[o.getLong("id")] = Tariff(d("base"), d("punta"), d("saturacion"), win)
        }
    }

    private fun writeTariffs(name: String, from: Map<Long, Tariff>) {
        val a = JSONArray()
        for ((k, tf) in from) a.put(JSONObject().put("id", k).put("base", tf.base ?: JSONObject.NULL)
            .put("punta", tf.punta ?: JSONObject.NULL).put("saturacion", tf.saturacion ?: JSONObject.NULL)
            .put("win", formatWindows(tf.windows)))
        write(name, a.toString())
    }

    // ---- observadores ----
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun notifyChanged() { main.post { listeners.forEach { it() } } }

    // ---- consultas ----
    fun gantry(id: Long): Gantry? = gById[id]
    fun snapshot(): List<Pass> = synchronized(lock) { passes.map { it.copy() } }

    fun tariff(id: Long): Tariff? = synchronized(lock) { tariffs[id] ?: remoteTariffs[id] }
    fun isManual(id: Long): Boolean = synchronized(lock) { tariffs.containsKey(id) }

    /** Precio de un pórtico en el instante t (tarifa según horario, o la tarifa por defecto). */
    fun priceAt(id: Long, t: Long): Double? =
        tariff(id)?.priceAt(t) ?: if (defPrice > 0) defPrice else null

    fun priceOf(p: Pass): Double? = p.price ?: priceAt(p.id, p.t)

    fun label(id: Long): Pair<String, String> {
        val g = gById[id] ?: return "Pórtico $id" to ""
        val name = g.name.ifEmpty { g.road.ifEmpty { "Pórtico" } }
        val sub = listOf(if (g.name.isNotEmpty()) g.road else "", g.op).filter { it.isNotEmpty() }.joinToString(" · ")
        return name to sub
    }

    fun recentFound(): List<Found> = synchronized(lock) { passes.map { Found(it.t, it.id) } }

    // ---- cambios ----
    fun setGantries(list: List<Gantry>) {
        synchronized(lock) {
            gantries = list
            gById = list.associateBy { it.id }
            val a = JSONArray()
            for (g in list) a.put(JSONObject().put("id", g.id).put("lat", g.lat).put("lon", g.lon)
                .put("name", g.name).put("op", g.op).put("road", g.road).put("brg", g.brg ?: JSONObject.NULL))
            write("gantries.json", a.toString())
        }
        notifyChanged()
    }

    /** Agrega las pasadas nuevas y devuelve las que efectivamente se agregaron. */
    fun addPasses(found: List<Found>): List<Found> {
        val added = ArrayList<Found>()
        synchronized(lock) {
            for (f in found) {
                if (passes.any { it.id == f.id && Math.abs(it.t - f.t) < COOLDOWN_MS }) continue
                passes.add(Pass(f.t, f.id, null))
                added.add(f)
            }
            if (added.isNotEmpty()) { passes.sortBy { it.t }; savePasses() }
        }
        if (added.isNotEmpty()) notifyChanged()
        return added
    }

    fun deletePass(t: Long, id: Long) {
        synchronized(lock) { passes.removeAll { it.t == t && it.id == id }; savePasses() }
        notifyChanged()
    }

    /** Fija las tarifas del pórtico; aplica a todas sus pasadas (anteriores y futuras). */
    fun setTariff(id: Long, t: Tariff) {
        synchronized(lock) {
            tariffs[id] = t
            for (q in passes) if (q.id == id) q.price = null
            saveTariffs(); savePasses()
        }
        notifyChanged()
    }

    fun mergeTariffs(m: Map<Long, Tariff>) {
        synchronized(lock) {
            tariffs.putAll(m)
            for (q in passes) if (m.containsKey(q.id)) q.price = null
            saveTariffs(); savePasses()
        }
        notifyChanged()
    }

    /** Pórticos y tarifas descargados. Las tarifas fijadas a mano se conservan y tienen prioridad. */
    fun setRemote(list: List<Gantry>, remote: Map<Long, Tariff>) {
        synchronized(lock) {
            remoteTariffs.clear(); remoteTariffs.putAll(remote)
            writeTariffs("remote_tariffs.json", remoteTariffs)
            lastSync = System.currentTimeMillis(); saveCfg()
        }
        setGantries(list)
    }

    /** Quita las tarifas manuales que son idénticas a las indicadas (migración desde versiones anteriores). */
    fun dropManualEqualTo(m: Map<Long, Tariff>) {
        synchronized(lock) {
            val n = tariffs.size
            tariffs.entries.removeAll { m[it.key] == it.value }
            if (tariffs.size != n) saveTariffs()
        }
    }

    fun removeManual(id: Long) {
        synchronized(lock) { if (tariffs.remove(id) != null) saveTariffs() }
        notifyChanged()
    }

    fun tariffCount(): Int = synchronized(lock) { gantries.count { tariffs.containsKey(it.id) || remoteTariffs.containsKey(it.id) } }

    private fun saveTariffs() = writeTariffs("tariffs.json", tariffs)

    // ---- recorrido del día (un CSV por día: t,lat,lon) ----
    private fun trackFile(day: String) = File(File(dir, "tracks").apply { mkdirs() }, "$day.csv")

    fun appendTrack(f: Fix) {
        try { trackFile(dayKey(f.t)).appendText("${f.t},${f.lat},${f.lon}\n") } catch (e: Exception) {}
    }

    fun readTrack(day: String): List<Fix> = try {
        val f = trackFile(day)
        if (!f.exists()) emptyList() else f.readLines().mapNotNull { ln ->
            val c = ln.split(',')
            if (c.size < 3) null else Fix(c[1].toDouble(), c[2].toDouble(), c[0].toLong())
        }
    } catch (e: Exception) { emptyList() }

    /** Guarda un recorrido importado en los días que aún no tienen recorrido. */
    fun saveImportedTrack(pts: List<Fix>) {
        for ((day, l) in pts.groupBy { dayKey(it.t) }) {
            val f = trackFile(day)
            if (f.exists() && f.length() > 0) continue
            val sb = StringBuilder()
            var last: Fix? = null
            for (p in l) {
                if (last != null && haversine(last, p) < 10) continue
                sb.append(p.t).append(',').append(p.lat).append(',').append(p.lon).append('\n'); last = p
            }
            try { f.writeText(sb.toString()) } catch (e: Exception) {}
        }
    }

    fun setDefPrice(v: Double) { defPrice = v; saveCfg(); notifyChanged() }
    fun setBatteryAsked() { batteryAsked = true; saveCfg() }
    fun setOverlayAsked() { overlayAsked = true; saveCfg() }
    fun setOnboarded() { onboarded = true; saveCfg() }

    fun clearPasses() {
        synchronized(lock) { passes.clear(); savePasses() }
        try { File(dir, "tracks").deleteRecursively() } catch (e: Exception) {}
        notifyChanged()
    }

    fun dayStats(day: String): Pair<Int, Double> {
        val l = snapshot().filter { dayKey(it.t) == day }
        return l.size to l.sumOf { priceOf(it) ?: 0.0 }
    }

    // ---- archivos ----
    private fun savePasses() {
        val a = JSONArray()
        for (p in passes) a.put(JSONObject().put("t", p.t).put("id", p.id).put("price", p.price ?: JSONObject.NULL))
        write("passes.json", a.toString())
    }
    private fun saveCfg() = write("cfg.json", JSONObject().put("def", defPrice).put("batteryAsked", batteryAsked).put("overlayAsked", overlayAsked).put("lastSync", lastSync).put("onboarded", onboarded).toString())
    private fun write(name: String, text: String) {
        try {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(dir, name))) { File(dir, name).writeText(text); tmp.delete() }
        } catch (e: Exception) {
        }
    }
    private fun readText(name: String): String? = try { File(dir, name).takeIf { it.exists() }?.readText() } catch (e: Exception) { null }
    private fun readArr(name: String): JSONArray? = try { readText(name)?.let { JSONArray(it) } } catch (e: Exception) { null }
    private fun readObj(name: String): JSONObject? = try { readText(name)?.let { JSONObject(it) } } catch (e: Exception) { null }
}

fun money(n: Double): String =
    "$" + NumberFormat.getIntegerInstance(Locale("es", "CL")).format(n.roundToLong())

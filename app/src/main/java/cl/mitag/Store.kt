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
    private val tariffs = HashMap<Long, Tariff>()

    @Volatile var defPrice = 0.0
        private set
    @Volatile var batteryAsked = false
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
        readArr("tariffs.json")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                fun d(k: String) = if (o.isNull(k)) null else o.getDouble(k)
                tariffs[o.getLong("id")] = Tariff(d("base"), d("punta"), d("saturacion"))
            }
        }
        readObj("cfg.json")?.let { defPrice = it.optDouble("def", 0.0); batteryAsked = it.optBoolean("batteryAsked") }
        inited = true
    }

    // ---- observadores ----
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun notifyChanged() { main.post { listeners.forEach { it() } } }

    // ---- consultas ----
    fun gantry(id: Long): Gantry? = gById[id]
    fun snapshot(): List<Pass> = synchronized(lock) { passes.map { it.copy() } }

    fun priceOf(p: Pass): Double? =
        p.price ?: tariffs[p.id]?.priceAt(p.t) ?: if (defPrice > 0) defPrice else null

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

    fun addPasses(found: List<Found>): Int {
        var added = 0
        synchronized(lock) {
            for (f in found) {
                if (passes.any { it.id == f.id && Math.abs(it.t - f.t) < COOLDOWN_MS }) continue
                passes.add(Pass(f.t, f.id, tariffs[f.id]?.priceAt(f.t)))
                added++
            }
            if (added > 0) { passes.sortBy { it.t }; savePasses() }
        }
        if (added > 0) notifyChanged()
        return added
    }

    fun deletePass(t: Long, id: Long) {
        synchronized(lock) { passes.removeAll { it.t == t && it.id == id }; savePasses() }
        notifyChanged()
    }

    /** Fija la tarifa del pórtico: la pasada indicada y todas las anteriores/posteriores sin tarifa. */
    fun setTariff(t: Long, id: Long, v: Double) {
        synchronized(lock) {
            tariffs[id] = (tariffs[id] ?: Tariff(null)).copy(base = v)
            for (q in passes) if (q.id == id && (q.price == null || (q.t == t))) q.price = v
            val a = JSONArray()
            for ((k, tf) in tariffs) a.put(JSONObject().put("id", k).put("base", tf.base ?: JSONObject.NULL)
                .put("punta", tf.punta ?: JSONObject.NULL).put("saturacion", tf.saturacion ?: JSONObject.NULL))
            write("tariffs.json", a.toString())
            savePasses()
        }
        notifyChanged()
    }

    fun setDefPrice(v: Double) { defPrice = v; saveCfg(); notifyChanged() }
    fun setBatteryAsked() { batteryAsked = true; saveCfg() }

    fun clearPasses() {
        synchronized(lock) { passes.clear(); savePasses() }
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
    private fun saveCfg() = write("cfg.json", JSONObject().put("def", defPrice).put("batteryAsked", batteryAsked).toString())
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

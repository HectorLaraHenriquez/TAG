package cl.mitag

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Pórticos con su tarifa ya asignada, alojados en el repositorio (data/porticos.json). */
class RemoteData(val gantries: List<Gantry>, val tariffs: Map<Long, Tariff>, val generated: String)

fun parseRemoteData(json: String): RemoteData {
    val root = JSONObject(json)
    val arr = root.getJSONArray("porticos")
    val gs = ArrayList<Gantry>(arr.length())
    val ts = HashMap<Long, Tariff>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val id = o.getLong("id")
        gs.add(
            Gantry(
                id, o.getDouble("lat"), o.getDouble("lon"), o.optString("name"), o.optString("op"), o.optString("road"),
                if (o.isNull("brg")) null else o.getInt("brg"),
            )
        )
        val t = o.optJSONObject("t") ?: continue
        fun d(k: String) = if (t.has(k) && !t.isNull(k)) t.getDouble(k) else null
        val win = try { parseWindows(t.optString("horarios")) } catch (e: Exception) { emptyList() }
        ts[id] = Tariff(d("base"), d("punta"), d("saturacion"), win)
    }
    return RemoteData(gs, ts, root.optString("generado"))
}

object Remote {
    private const val BASE = "https://raw.githubusercontent.com/HectorLaraHenriquez/TAG"
    val URLS = listOf(
        "$BASE/main/data/porticos.json",
        "$BASE/claude/elegant-einstein-qwq0pp/data/porticos.json",
    )

    /** Bloqueante. null si no se pudo descargar. */
    fun download(): RemoteData? {
        for (u in URLS) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "TAG-TRACKER/1.4")
                if (c.responseCode != 200) { c.disconnect(); continue }
                val text = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val d = parseRemoteData(text)
                if (d.gantries.isNotEmpty()) return d
            } catch (e: Exception) {
            }
        }
        return null
    }
}

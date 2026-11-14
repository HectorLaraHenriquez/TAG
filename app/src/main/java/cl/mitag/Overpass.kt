package cl.mitag

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.floor

/** Convierte la respuesta de Overpass en pórticos con el rumbo de la vía. */
fun parseOverpass(json: String): List<Gantry> {
    val els = JSONObject(json).getJSONArray("elements")
    val nodes = LinkedHashMap<Long, Gantry>()
    for (i in 0 until els.length()) {
        val e = els.getJSONObject(i)
        if (e.optString("type") != "node") continue
        val tags = e.optJSONObject("tags")
        val name = tags?.optString("name")?.ifEmpty { tags.optString("ref") } ?: ""
        val op = tags?.optString("operator") ?: ""
        val id = e.getLong("id")
        nodes[id] = Gantry(id, e.getDouble("lat"), e.getDouble("lon"), name, op)
    }
    for (i in 0 until els.length()) {
        val w = els.getJSONObject(i)
        if (w.optString("type") != "way") continue
        val wn = w.optJSONArray("nodes") ?: continue
        val geom = w.optJSONArray("geometry") ?: continue
        val tg = w.optJSONObject("tags")
        val oneway = tg?.optString("oneway") ?: ""
        val wayRoad = tg?.optString("name")?.ifEmpty { tg.optString("ref") } ?: ""
        for (k in 0 until wn.length()) {
            val g = nodes[wn.getLong(k)] ?: continue
            if (g.road.isEmpty()) g.road = wayRoad
            if (g.brg == null && oneway != "no" && geom.length() > 1) {
                val a = geom.getJSONObject(maxOf(0, k - 1))
                val b = geom.getJSONObject(minOf(geom.length() - 1, k + 1))
                var brg = bearing(
                    LatLon(a.getDouble("lat"), a.getDouble("lon")),
                    LatLon(b.getDouble("lat"), b.getDouble("lon")),
                )
                if (oneway == "-1") brg = (brg + 180) % 360
                g.brg = floor(brg + 0.5).toInt()
            }
        }
    }
    return nodes.values.toList()
}

data class LatLon(override val lat: Double, override val lon: Double) : Coord

object Overpass {
    const val QUERY = "[out:json][timeout:60];node[\"highway\"=\"toll_gantry\"](-34.3,-71.7,-32.8,-70.2)->.g;" +
        ".g out body;way(bn.g)[\"highway\"];out geom;"
    val HOSTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    /** Descarga los pórticos probando cada servidor en orden. Bloqueante. null si todos fallan. */
    fun download(): List<Gantry>? {
        val body = "data=" + URLEncoder.encode(QUERY, "UTF-8")
        for (h in HOSTS) {
            try {
                val c = URL(h).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 20000
                c.readTimeout = 90000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.setRequestProperty("User-Agent", "TAG-TRACKER/1.0")
                c.outputStream.use { it.write(body.toByteArray()) }
                if (c.responseCode != 200) { c.disconnect(); continue }
                val text = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val list = parseOverpass(text)
                if (list.isNotEmpty()) return list
            } catch (e: Exception) {
            }
        }
        return null
    }
}

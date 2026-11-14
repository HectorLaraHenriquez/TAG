package cl.mitag

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

private fun parseTime(raw: String): Long? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    if (s.all { it.isDigit() }) return if (s.length > 11) s.toLong() else s.toLong() * 1000
    try { return Instant.parse(s).toEpochMilli() } catch (e: Exception) {}
    try { return OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (e: Exception) {}
    try {
        return LocalDateTime.parse(s.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {}
    return null
}

/** Lee puntos de un GPX o de un CSV (columnas time, lat, lon). */
fun parseTrack(text: String): List<Fix> {
    val pts = ArrayList<Fix>()
    if (text.trimStart().startsWith("<")) {
        val re = Regex("<trkpt\\b([^>]*)>([\\s\\S]*?)</trkpt>")
        val reLat = Regex("lat=\"([^\"]+)\"")
        val reLon = Regex("lon=\"([^\"]+)\"")
        val reTime = Regex("<time>([^<]+)</time>")
        for (m in re.findAll(text)) {
            val lat = reLat.find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull()
            val lon = reLon.find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull()
            val t = reTime.find(m.groupValues[2])?.groupValues?.get(1)?.let { parseTime(it) }
            if (lat != null && lon != null && t != null) pts.add(Fix(lat, lon, t))
        }
    } else {
        val lines = text.split(Regex("\r?\n")).filter { it.isNotEmpty() }
        if (lines.isEmpty()) return pts
        val sep = if (lines[0].contains(';')) ';' else ','
        val head = lines[0].split(sep).map { it.trim().lowercase() }
        val iLat = head.indexOfFirst { it == "lat" || it == "latitude" }
        val iLon = head.indexOfFirst { it == "lon" || it == "lng" || it == "longitude" }
        val iT = head.indexOfFirst { it == "time" || it == "timestamp" || it == "date" }
        if (iLat < 0 || iLon < 0 || iT < 0) throw IllegalArgumentException("El CSV necesita columnas time, lat y lon")
        for (ln in lines.drop(1)) {
            val c = ln.split(sep)
            val lat = c.getOrNull(iLat)?.trim()?.toDoubleOrNull()
            val lon = c.getOrNull(iLon)?.trim()?.toDoubleOrNull()
            val t = c.getOrNull(iT)?.let { parseTime(it) }
            if (lat != null && lon != null && t != null) pts.add(Fix(lat, lon, t))
        }
    }
    return pts.sortedBy { it.t }
}

package cl.mitag

import kotlin.math.*

const val R_EARTH = 6371000.0
const val RAD = PI / 180

interface Coord {
    val lat: Double
    val lon: Double
}

fun haversine(a: Coord, b: Coord): Double {
    val dLat = (b.lat - a.lat) * RAD
    val dLon = (b.lon - a.lon) * RAD
    val h = sin(dLat / 2).pow(2) + cos(a.lat * RAD) * cos(b.lat * RAD) * sin(dLon / 2).pow(2)
    return 2 * R_EARTH * asin(sqrt(h))
}

fun bearing(a: Coord, b: Coord): Double {
    val y = sin((b.lon - a.lon) * RAD) * cos(b.lat * RAD)
    val x = cos(a.lat * RAD) * sin(b.lat * RAD) - sin(a.lat * RAD) * cos(b.lat * RAD) * cos((b.lon - a.lon) * RAD)
    return (atan2(y, x) / RAD + 360) % 360
}

fun angDiff(a: Double, b: Double): Double {
    val d = abs(a - b) % 360
    return if (d > 180) 360 - d else d
}

/** Distancia (m) del punto p al segmento a-b, en proyección local. */
fun distToSegment(p: Coord, a: Coord, b: Coord): Double {
    val k = cos(p.lat * RAD) * R_EARTH * RAD
    val m = R_EARTH * RAD
    val ax = (a.lon - p.lon) * k
    val ay = (a.lat - p.lat) * m
    val bx = (b.lon - p.lon) * k
    val by = (b.lat - p.lat) * m
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    var t = if (len2 != 0.0) -(ax * dx + ay * dy) / len2 else 0.0
    t = max(0.0, min(1.0, t))
    return hypot(ax + t * dx, ay + t * dy)
}

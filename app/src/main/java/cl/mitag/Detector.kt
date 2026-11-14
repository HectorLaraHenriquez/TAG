package cl.mitag

import kotlin.math.abs
import kotlin.math.max

const val RADIUS_M = 30.0          // distancia máxima al pórtico
const val HEADING_TOL = 60.0       // tolerancia de rumbo (grados)
const val COOLDOWN_MS = 3 * 60 * 1000L

/** Recibe posiciones en orden y devuelve las pasadas por pórticos. */
class Detector(private val gantries: List<Gantry>, recent: List<Found> = emptyList()) {
    private var prev: Fix? = null
    private var lastHeading: Double? = null
    private val lastPass = HashMap<Long, Long>()
    private val recentPasses = ArrayList<Found>(recent)
    private val byId = gantries.associateBy { it.id }

    init {
        for (p in recentPasses) lastPass[p.id] = max(lastPass[p.id] ?: 0L, p.t)
    }

    fun feed(cur: Fix): List<Found> {
        val found = ArrayList<Found>()
        var a: Coord = cur
        var heading = lastHeading
        val pv = prev
        if (pv != null) {
            val dt = cur.t - pv.t
            val d = haversine(pv, cur)
            if (dt in 0..60000 && d < 2500) {
                a = pv
                if (d > 5) heading = bearing(pv, cur)
            } else {
                heading = null
            }
        }
        val cands = ArrayList<Pair<Gantry, Double>>()
        for (g in gantries) {
            if (abs(g.lat - cur.lat) > 0.03 || abs(g.lon - cur.lon) > 0.03) continue
            val d = distToSegment(g, a, cur)
            if (d > RADIUS_M) continue
            val gb = g.brg
            if (gb != null && heading != null && angDiff(gb.toDouble(), heading) > HEADING_TOL) continue
            cands.add(g to d)
        }
        cands.sortBy { it.second }
        for ((g, _) in cands) {
            val lp = lastPass[g.id]
            if (lp != null && abs(cur.t - lp) < COOLDOWN_MS) continue
            // mismo pórtico físico mapeado con varios nodos
            val dup = recentPasses.any { p ->
                val o = byId[p.id]
                abs(cur.t - p.t) < 20000 && o != null && haversine(o, g) < 100
            }
            if (dup) continue
            lastPass[g.id] = cur.t
            recentPasses.add(Found(cur.t, g.id))
            found.add(Found(cur.t, g.id))
        }
        prev = cur
        lastHeading = heading
        return found
    }
}

package cl.mitag

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

const val DAYS_LV = 1   // lunes a viernes
const val DAYS_SAT = 2
const val DAYS_SUN = 4

/** Franja horaria: [from, to) en minutos del día; band 'P' = punta, 'S' = saturación. */
data class Window(val days: Int, val from: Int, val to: Int, val band: Char)

/**
 * Tarifas de un pórtico: base (fuera de punta), punta y saturación, con las franjas
 * en que aplican las dos últimas. Sin franjas, siempre se cobra la base.
 */
data class Tariff(
    val base: Double?,
    val punta: Double? = null,
    val saturacion: Double? = null,
    val windows: List<Window> = emptyList(),
) {
    /** 'B' base, 'P' punta, 'S' saturación. Los festivos no se distinguen (se usa el día de la semana). */
    fun bandAt(t: Long, zone: ZoneId = ZoneId.systemDefault()): Char {
        if (windows.isEmpty()) return 'B'
        val dt = Instant.ofEpochMilli(t).atZone(zone)
        val day = when (dt.dayOfWeek) {
            DayOfWeek.SATURDAY -> DAYS_SAT
            DayOfWeek.SUNDAY -> DAYS_SUN
            else -> DAYS_LV
        }
        val min = dt.hour * 60 + dt.minute
        var band = 'B'
        for (w in windows) {
            if (w.days and day == 0 || min < w.from || min >= w.to) continue
            if (w.band == 'S') return 'S'
            band = 'P'
        }
        return band
    }

    fun priceAt(t: Long, zone: ZoneId = ZoneId.systemDefault()): Double? = when (bandAt(t, zone)) {
        'S' -> saturacion ?: punta ?: base
        'P' -> punta ?: base
        else -> base
    }
}

fun bandName(b: Char) = when (b) { 'S' -> "saturación"; 'P' -> "punta"; else -> "base" }

private val DAY_TOKENS = mapOf(
    "LV" to DAYS_LV, "S" to DAYS_SAT, "D" to DAYS_SUN,
    "SD" to (DAYS_SAT or DAYS_SUN), "LS" to (DAYS_LV or DAYS_SAT), "LD" to 7,
)

/**
 * Lee franjas con el formato "LV 07:30-10:00 P; LV 18:00-20:00 S; S 11:00-14:00 P".
 * Días: LV, S, D, SD, LS, LD. Tipo: P (punta) o S (saturación). Lanza IllegalArgumentException si no calza.
 */
fun parseWindows(text: String): List<Window> {
    val out = ArrayList<Window>()
    val re = Regex("^([A-Z]+)\\s+(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})\\s+([PS])$")
    for (part in text.split(';', ',', '\n')) {
        val s = part.trim().uppercase()
        if (s.isEmpty()) continue
        val m = re.find(s) ?: throw IllegalArgumentException("Franja no válida: \"${part.trim()}\"")
        val days = DAY_TOKENS[m.groupValues[1]] ?: throw IllegalArgumentException("Días no válidos: ${m.groupValues[1]}")
        val from = m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
        val to = m.groupValues[4].toInt() * 60 + m.groupValues[5].toInt()
        if (from >= to || to > 24 * 60) throw IllegalArgumentException("Horario no válido: \"${part.trim()}\"")
        out.add(Window(days, from, to, m.groupValues[6][0]))
    }
    return out
}

fun formatWindows(ws: List<Window>): String = ws.joinToString("; ") { w ->
    val d = DAY_TOKENS.entries.firstOrNull { it.value == w.days }?.key ?: "LD"
    "%s %02d:%02d-%02d:%02d %s".format(d, w.from / 60, w.from % 60, w.to / 60, w.to % 60, w.band)
}

private fun fold(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").uppercase()

/** Códigos de pórtico presentes en un nombre: "Pórtico PA 32" -> [PORTICOPA32, PA32, 32, ...]. */
private fun refKeys(name: String): Set<String> {
    val f = fold(name)
    val toks = f.split(Regex("[^A-Z0-9.]+")).filter { it.isNotEmpty() }
    val keys = HashSet<String>()
    keys.add(toks.joinToString(""))
    keys.addAll(toks)
    for (i in 0 until toks.size - 1) keys.add(toks[i] + toks[i + 1])
    return keys
}

private fun refMatches(ref: String, name: String): Boolean {
    val r = fold(ref).replace(Regex("[^A-Z0-9.]"), "")
    if (r.isEmpty()) return false
    val keys = refKeys(name)
    if (r in keys) return true
    // "P2.1" también calza con "P21" (solo si el código tiene letras, para no confundir "1.3" con "13")
    return r.any { it.isLetter() } && r.contains('.') && r.replace(".", "") in keys.map { it.replace(".", "") }
}

const val RUMBO_TOL = 100.0

/**
 * Lee un archivo de tarifas y lo asocia a los pórticos cargados.
 * Formato: {"tarifas":[{"id":123, "ref":"P1", "operador":"Costanera", "via":["Kennedy"], "rumbo":270,
 * "base":500, "punta":900, "saturacion":1300, "horarios":"LV 07:30-10:00 P; LV 18:00-20:00 S"}]}
 * Cada entrada se asocia por "id" (id del nodo en OSM) o, si no viene, por:
 *  - "ref": código del pórtico, buscado en el nombre/ref del pórtico en OSM;
 *  - "operador" / "via": texto contenido en la concesionaria o en el nombre de la vía (basta uno);
 *  - "rumbo": sentido de circulación en grados (±100°), para distinguir las dos calzadas.
 * Devuelve las tarifas por id de pórtico y la cantidad de entradas que no calzaron con ninguno.
 */
fun parseTariffFile(json: String, gantries: List<Gantry>): Pair<Map<Long, Tariff>, Int> {
    val arr = JSONObject(json).getJSONArray("tarifas")
    val byId = gantries.associateBy { it.id }
    val out = HashMap<Long, Tariff>()
    var unmatched = 0
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        fun d(k: String) = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
        val t = Tariff(d("base"), d("punta"), d("saturacion"), parseWindows(o.optString("horarios")))
        val ids: List<Long> = if (o.has("id")) {
            listOfNotNull(byId[o.getLong("id")]?.id)
        } else {
            val ref = o.optString("ref").trim()
            val op = fold(o.optString("operador").trim())
            val vias = o.optJSONArray("via")?.let { a -> List(a.length()) { fold(a.getString(it)) } } ?: emptyList()
            val rumbo = d("rumbo")
            if (ref.isEmpty() && op.isEmpty() && vias.isEmpty()) emptyList() else gantries.filter { g ->
                val place = (op.isEmpty() && vias.isEmpty()) ||
                    (op.isNotEmpty() && fold(g.op).contains(op)) || vias.any { fold(g.road).contains(it) }
                val gb = g.brg
                place && (ref.isEmpty() || refMatches(ref, g.name)) &&
                    (rumbo == null || gb == null || angDiff(gb.toDouble(), rumbo) <= RUMBO_TOL)
            }.map { it.id }
        }
        if (ids.isEmpty()) unmatched++
        for (id in ids) out[id] = t
    }
    return out to unmatched
}

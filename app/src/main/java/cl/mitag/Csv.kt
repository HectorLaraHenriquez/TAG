package cl.mitag

import java.time.Instant
import java.time.ZoneId

fun dayKey(t: Long): String = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate().toString()

fun buildCsv(passes: List<Pass>, gantry: (Long) -> Gantry?, price: (Pass) -> Double?): String {
    fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    val rows = ArrayList<String>()
    rows.add("fecha,hora,portico,via,concesionaria,tarifa")
    for (p in passes) {
        val g = gantry(p.id)
        val time = Instant.ofEpochMilli(p.t).atZone(ZoneId.systemDefault()).toLocalTime().withNano(0).toString()
            .let { if (it.length == 5) "$it:00" else it }
        val pr = price(p)
        rows.add(
            listOf(
                dayKey(p.t), time,
                q(g?.name?.ifEmpty { null } ?: p.id.toString()), q(g?.road ?: ""), q(g?.op ?: ""),
                pr?.let { Math.round(it).toString() } ?: "",
            ).joinToString(",")
        )
    }
    return "﻿" + rows.joinToString("\n")
}

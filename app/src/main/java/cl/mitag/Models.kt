package cl.mitag

data class Gantry(
    val id: Long,
    override val lat: Double,
    override val lon: Double,
    val name: String = "",
    val op: String = "",
    var road: String = "",
    var brg: Int? = null,
) : Coord

data class Fix(override val lat: Double, override val lon: Double, val t: Long) : Coord

/** Pasada detectada por un pórtico. */
data class Found(val t: Long, val id: Long)

/** Pasada registrada; [price] es una tarifa propia de la pasada (null = usa la del pórtico). */
data class Pass(val t: Long, val id: Long, var price: Double?)

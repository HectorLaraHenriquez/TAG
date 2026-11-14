package cl.mitag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

private const val M_LAT = 111194.93
private const val LAT0 = -33.45
private const val LON0 = -70.65

private fun gantry(id: Long, eastM: Double, northM: Double, brg: Int?) =
    Gantry(id, LAT0 + northM / M_LAT, LON0 + eastM / (M_LAT * cos(LAT0 * RAD)), brg = brg)

private fun fix(eastM: Double, northM: Double, t: Long) =
    Fix(LAT0 + northM / M_LAT, LON0 + eastM / (M_LAT * cos(LAT0 * RAD)), t)

/** Recorrido recto de sur a norte (o al revés) sobre x = eastM. */
private fun line(eastM: Double, fromN: Double, toN: Double, stepM: Double, t0: Long, dtMs: Long): List<Fix> {
    val out = ArrayList<Fix>()
    var y = fromN
    var t = t0
    val dir = if (toN >= fromN) 1 else -1
    while (if (dir > 0) y <= toN else y >= toN) {
        out.add(fix(eastM, y, t))
        y += dir * stepM
        t += dtMs
    }
    return out
}

private fun run(det: Detector, pts: List<Fix>) = pts.flatMap { det.feed(it) }

class DetectorTest {
    private val north = 0
    private val south = 180

    @Test
    fun calzadasParalelasSoloMarcaElSentidoDeMarcha() {
        val gs = listOf(gantry(1, 0.0, 0.0, north), gantry(2, 25.0, 0.0, south))
        // yendo al norte por la calzada del pórtico 1
        val a = run(Detector(gs), line(0.0, -300.0, 300.0, 40.0, 0, 2000))
        assertEquals(listOf(1L), a.map { it.id })
        // yendo al sur por la calzada del pórtico 2
        val b = run(Detector(gs), line(25.0, 300.0, -300.0, 40.0, 0, 2000))
        assertEquals(listOf(2L), b.map { it.id })
    }

    @Test
    fun recorridoA80mEnParaleloNoMarcaNada() {
        val gs = listOf(gantry(1, 0.0, 0.0, north), gantry(2, 0.0, 0.0, null))
        val r = run(Detector(gs), line(80.0, -300.0, 300.0, 40.0, 0, 2000))
        assertTrue(r.isEmpty())
    }

    @Test
    fun puntosCada10sA100kmhMarcaIgual() {
        val gs = listOf(gantry(1, 0.0, 0.0, north))
        val pts = line(0.0, -2000.0, 2000.0, 278.0, 0, 10_000)
        assertTrue(pts.none { Math.abs(it.lat - gs[0].lat) * M_LAT < 30 })
        val r = run(Detector(gs), pts)
        assertEquals(listOf(1L), r.map { it.id })
    }

    @Test
    fun mismoPorticoDosVecesEnMenosDe3MinUnaSolaPasada() {
        val gs = listOf(gantry(1, 0.0, 0.0, null))
        val det = Detector(gs)
        val all = ArrayList<Found>()
        all += run(det, line(0.0, -300.0, 300.0, 40.0, 0, 2000))          // ida (t = 0..30 s)
        all += run(det, line(0.0, 300.0, -300.0, 40.0, 32_000, 2000))     // vuelta (< 3 min)
        assertEquals(1, all.size)
        // 4 min después vuelve a contar
        all += run(det, line(0.0, -300.0, 300.0, 40.0, 300_000, 2000))
        assertEquals(2, all.size)
    }

    @Test
    fun cooldownIncluyePasadasRecientesYaRegistradas() {
        val gs = listOf(gantry(1, 0.0, 0.0, null))
        val det = Detector(gs, listOf(Found(10_000, 1)))
        assertTrue(run(det, line(0.0, -300.0, 300.0, 40.0, 0, 2000)).isEmpty())
    }

    @Test
    fun nodosDuplicadosDelMismoPorticoFisicoCuentanUnaVez() {
        val gs = listOf(gantry(1, 0.0, 0.0, null), gantry(2, 0.0, 40.0, null))
        val r = run(Detector(gs), line(0.0, -300.0, 300.0, 40.0, 0, 2000))
        assertEquals(1, r.size)
    }

    @Test
    fun parseOverpassCalculaRumbo() {
        val json = """{"elements":[
          {"type":"node","id":1,"lat":-33.45,"lon":-70.65,"tags":{"name":"P1","operator":"Costanera"}},
          {"type":"node","id":2,"lat":-33.45,"lon":-70.64,"tags":{"ref":"P2"}},
          {"type":"node","id":3,"lat":-33.45,"lon":-70.63},
          {"type":"way","id":10,"nodes":[11,1,12],"tags":{"name":"Autopista","oneway":"yes"},
           "geometry":[{"lat":-33.451,"lon":-70.65},{"lat":-33.45,"lon":-70.65},{"lat":-33.449,"lon":-70.65}]},
          {"type":"way","id":11,"nodes":[21,2,22],"tags":{"ref":"R5","oneway":"no"},
           "geometry":[{"lat":-33.451,"lon":-70.64},{"lat":-33.45,"lon":-70.64},{"lat":-33.449,"lon":-70.64}]},
          {"type":"way","id":12,"nodes":[31,3,32],"tags":{"name":"Inv","oneway":"-1"},
           "geometry":[{"lat":-33.451,"lon":-70.63},{"lat":-33.45,"lon":-70.63},{"lat":-33.449,"lon":-70.63}]}
        ]}"""
        val g = parseOverpass(json).associateBy { it.id }
        assertEquals("P1", g[1]!!.name); assertEquals("Costanera", g[1]!!.op); assertEquals("Autopista", g[1]!!.road)
        assertEquals(0, g[1]!!.brg)
        assertEquals("P2", g[2]!!.name); assertEquals("R5", g[2]!!.road); assertNull(g[2]!!.brg)
        assertEquals(180, g[3]!!.brg)
    }

    @Test
    fun parseTrackGpxYCsv() {
        val gpx = """<gpx><trk><trkseg>
            <trkpt lat="-33.45" lon="-70.65"><time>2024-05-01T12:00:10Z</time></trkpt>
            <trkpt lat="-33.46" lon="-70.65"><time>2024-05-01T12:00:00Z</time></trkpt>
            </trkseg></trk></gpx>"""
        val a = parseTrack(gpx)
        assertEquals(2, a.size); assertEquals(-33.46, a[0].lat, 1e-9)
        val csv = "time,lat,lon,elevation\n2024-05-01T12:00:00.000Z,-33.45,-70.65,500\n1714564810,-33.46,-70.65,500\n"
        val b = parseTrack(csv)
        assertEquals(2, b.size); assertEquals(10_000L, b[1].t - b[0].t)
    }

    @Test
    fun csvExportEscapa() {
        val p = listOf(Pass(1_700_000_000_000, 1, null))
        val out = buildCsv(p, { Gantry(1, 0.0, 0.0, "A \"x\"", "Op", "Via") }, { 1500.0 })
        assertTrue(out.contains("\"A \"\"x\"\"\",\"Via\",\"Op\",1500"))
    }
}

class TariffTest {
    private val zone = java.time.ZoneId.of("America/Santiago")
    private fun at(iso: String) = java.time.LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()
    private val t = Tariff(500.0, 900.0, 1300.0, parseWindows("LV 07:30-10:00 P; LV 08:00-09:00 S; S 11:00-14:00 P"))

    @Test
    fun tarifaSegunHorario() {
        assertEquals(500.0, t.priceAt(at("2026-11-11T06:00:00"), zone)!!, 0.0)   // miércoles, base
        assertEquals(900.0, t.priceAt(at("2026-11-11T07:30:00"), zone)!!, 0.0)   // punta
        assertEquals(1300.0, t.priceAt(at("2026-11-11T08:30:00"), zone)!!, 0.0)  // saturación gana a punta
        assertEquals(500.0, t.priceAt(at("2026-11-11T10:00:00"), zone)!!, 0.0)   // fin exclusivo
        assertEquals(900.0, t.priceAt(at("2026-11-14T12:00:00"), zone)!!, 0.0)   // sábado punta
        assertEquals(500.0, t.priceAt(at("2026-11-15T08:30:00"), zone)!!, 0.0)   // domingo
        assertEquals(500.0, Tariff(500.0).priceAt(at("2026-11-11T08:30:00"), zone)!!, 0.0)
        assertEquals(500.0, Tariff(500.0, windows = t.windows).priceAt(at("2026-11-11T08:30:00"), zone)!!, 0.0)
    }

    @Test
    fun franjasIdaYVuelta() {
        assertEquals(t.windows, parseWindows(formatWindows(t.windows)))
        assertTrue(parseWindows("  ").isEmpty())
        try { parseWindows("LV 10:00-09:00 P"); org.junit.Assert.fail() } catch (e: IllegalArgumentException) {}
        try { parseWindows("XX 10:00-11:00 P"); org.junit.Assert.fail() } catch (e: IllegalArgumentException) {}
    }

    @Test
    fun archivoDeTarifasAsociaPorIdORef() {
        val gs = listOf(Gantry(1, 0.0, 0.0, "P1", "Costanera Norte"), Gantry(2, 0.0, 0.0, "p1", "Autopista Central"), Gantry(3, 0.0, 0.0, "X"))
        val json = """{"tarifas":[
          {"id":3,"base":100},
          {"ref":"P1","operador":"costanera","base":500,"punta":900,"horarios":"LV 07:30-10:00 P"},
          {"ref":"NOEXISTE","base":1}]}"""
        val (m, un) = parseTariffFile(json, gs)
        assertEquals(1, un)
        assertEquals(setOf(1L, 3L), m.keys)
        assertEquals(900.0, m[1]!!.punta!!, 0.0); assertEquals(1, m[1]!!.windows.size)
    }
}

class TariffFileTest {
    private val gs = listOf(
        Gantry(1, 0.0, 0.0, "Pórtico P2.1", "Sociedad Concesionaria Costanera Norte", "Costanera Norte", 265),
        Gantry(2, 0.0, 0.0, "P21", "", "Avenida Presidente Kennedy", 80),
        Gantry(3, 0.0, 0.0, "PA 32", "", "Ruta 5", 185),
        Gantry(4, 0.0, 0.0, "P1", "Vespucio Norte Express", "Américo Vespucio", 10),
        Gantry(5, 0.0, 0.0, "1.3", "", "Autopista Vespucio Sur", 120),
        Gantry(6, 0.0, 0.0, "", "", "Túnel San Cristóbal", 175),
        Gantry(7, 0.0, 0.0, "P13", "", "Américo Vespucio", 270),
    )

    @Test
    fun asociaPorRefViaYRumbo() {
        val json = """{"tarifas":[
          {"ref":"P2.1","operador":"Costanera Norte","via":["Kennedy"],"rumbo":270,"base":1},
          {"ref":"P2.1","operador":"Costanera Norte","via":["Kennedy"],"rumbo":90,"base":2},
          {"ref":"PA32","via":["Ruta 5"],"rumbo":180,"base":3},
          {"ref":"P1","operador":"Vespucio Norte","via":["Vespucio"],"rumbo":0,"base":4},
          {"ref":"1.3","operador":"Vespucio Sur","via":["Vespucio"],"rumbo":135,"base":5},
          {"operador":"San Cristóbal","via":["San Cristobal"],"rumbo":180,"base":6},
          {"operador":"San Cristóbal","via":["San Cristobal"],"rumbo":0,"base":7}]}"""
        val (m, un) = parseTariffFile(json, gs)
        assertEquals(1, un)   // el túnel en sentido norte no tiene pórtico en la lista
        assertEquals(mapOf(1L to 1.0, 2L to 2.0, 3L to 3.0, 4L to 4.0, 5L to 5.0, 6L to 6.0), m.mapValues { it.value.base })
    }

    @Test
    fun archivoIncluidoEsValido() {
        val f = listOf("src/main/assets/tarifas_2026.json", "app/src/main/assets/tarifas_2026.json", "/home/user/TAG/app/src/main/assets/tarifas_2026.json")
            .map { java.io.File(it) }.first { it.exists() }
        val text = f.readText()
        val n = org.json.JSONObject(text).getJSONArray("tarifas").length()
        val (_, un) = parseTariffFile(text, emptyList())   // valida horarios; sin pórticos nada calza
        assertEquals(n, un)
        assertTrue(n > 90)
    }
}

class RemoteDataTest {
    @Test
    fun leeDatosAlojados() {
        val json = """{"version":1,"generado":"2026-11-14T00:00:00Z","porticos":[
          {"id":1,"lat":-33.4,"lon":-70.6,"name":"P01 Gran Vía","op":"","road":"Costanera Norte","brg":122,
           "t":{"base":449.4,"punta":865.2,"saturacion":1310.4,"horarios":"LV 12:00-13:00 P; LV 13:00-14:00 S"}},
          {"id":2,"lat":-33.5,"lon":-70.7,"name":"","op":"","road":"","brg":null}]}"""
        val d = parseRemoteData(json)
        assertEquals(2, d.gantries.size)
        assertEquals(122, d.gantries[0].brg); assertNull(d.gantries[1].brg)
        assertEquals(setOf(1L), d.tariffs.keys)
        assertEquals(2, d.tariffs[1]!!.windows.size)
    }

    @Test
    fun cerosALaIzquierdaYNombre() {
        val gs = listOf(Gantry(1, 0.0, 0.0, "P01 Gran Vía - Puente La Dehesa", "", "Autopista Costanera Norte", 122),
            Gantry(2, 0.0, 0.0, "Peaje Gabriela Oriente", "", "", 333))
        val (m, _) = parseTariffFile("""{"tarifas":[{"ref":"P1","via":["Costanera Norte"],"rumbo":90,"base":1},
            {"nombre":"Peaje Gabriela","base":2}]}""", gs)
        assertEquals(mapOf(1L to 1.0, 2L to 2.0), m.mapValues { it.value.base })
    }
}

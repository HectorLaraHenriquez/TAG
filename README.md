# TAG TRACKER

App Android nativa (Kotlin) que registra automáticamente, en segundo plano mientras manejas, los pórticos de peaje (TAG) de las autopistas de Santiago de Chile por los que pasas y suma el gasto diario.

## Qué hace
- Servicio en primer plano con GPS (`LocationManager`, sin Google Play Services), cada 2 s; descarta posiciones con precisión peor a 50 m.
- Pórticos desde OpenStreetMap (Overpass), descargados al primer uso y con "Actualizar pórticos"; se guardan localmente.
- Detección: un pórtico cuenta si el segmento entre dos posiciones pasa a ≤ 30 m y el rumbo de marcha difiere ≤ 60° del rumbo de la vía. No repite el mismo pórtico antes de 3 min y descarta nodos duplicados (< 100 m, < 20 s).
- Al pasar un pórtico aparece por ~7 s una ventanita sobre cualquier app (Waze, Uber…) con el pórtico ya agregado y un botón **Descartar**. Sin el permiso "Mostrar sobre otras apps" se usa una notificación emergente con la misma acción.
- Tarifas por horario: cada pórtico tiene base, punta y saturación con sus franjas; el monto de cada pasada se calcula según la hora.
- Pantallas: **Día** (total, iniciar/detener, lista editable), **Historial** (por día), **Mapa** (ruta del día con los pórticos usados, o todos los pórticos con su precio en este momento, con un simulador de día y hora para ver cómo cambian) y **Ajustes**.
- Guarda el recorrido GPS de cada día (un punto cada ≥ 10 m) para mostrarlo en el mapa.
- Importa recorridos GPX o CSV (columnas `time`, `lat`, `lon`, p. ej. de GPSLogger) y exporta registros a CSV con el selector de archivos del sistema.
- Notificación persistente con el total del día y botón **Detener**.

## Instalar el APK
1. En GitHub: pestaña **Actions** → último run verde de `build` → artifact `tag-tracker-debug-apk` (o el Release si hay un tag `v*`).
2. Descomprime el artifact y pasa `tag-tracker-debug.apk` al teléfono.
3. Ábrelo y acepta **Instalar apps de orígenes desconocidos** para el navegador o gestor de archivos que uses.

## Permisos
- Ubicación precisa (`ACCESS_FINE_LOCATION`) y servicio en primer plano de tipo ubicación (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`).
- Notificaciones (`POST_NOTIFICATIONS`, Android 13+), obligatoria para ver el servicio activo.
- Internet, solo para descargar pórticos.
- "Mostrar sobre otras apps" (`SYSTEM_ALERT_WINDOW`), opcional, para el aviso flotante con Descartar.
- `WAKE_LOCK` y exclusión de optimización de batería, para seguir con la pantalla apagada y con Waze/Uber en primer plano. La app lo ofrece al iniciar; también está en Ajustes.

Inicia el seguimiento con la app abierta (Android exige eso para permitir ubicación en segundo plano desde un servicio en primer plano).

## Limitaciones
- **Tarifas**: vienen de las tablas 2026 del MOP (categoría autos) y se descargan junto con los pórticos; no hay que cargarlas a mano.
  - Con horarios punta/saturación: Costanera Norte, Autopista Central, Vespucio Norte, Vespucio Sur, Túnel San Cristóbal y La Pirámide (AVO).
  - Solo tarifa normal (el MOP no publica el horario punta, o es de temporada): Acceso Nororiente, Ruta 5 Norte, Ruta 68, Ruta 78. También Ruta 5 Sur/Acceso Sur, Los Libertadores, Ruta de la Fruta y Acceso AMB.
  - Sin tarifa: AVO I (cobra por par entrada/salida y OSM no tiene todos sus pórticos) y pórticos sin nombre en OSM. Usan la tarifa por defecto o se fijan a mano tocando el monto ("Restablecer" vuelve a la publicada).
  - Los festivos se tratan como su día de semana. Las tarifas cambian cada enero: se actualiza `tools/gen_tarifas.py`.
- **Falsos positivos** posibles en caleteras y vías paralelas cercanas a una autopista (si OSM no marca bien el sentido o la vía), y falsos negativos si OSM no tiene el pórtico o GPS débil (túneles).
- Cobertura de pórticos: Región Metropolitana (bbox de la consulta).
- Datos solo locales; no hay backend ni respaldo.

## Datos alojados
La app descarga `data/porticos.json` de este repositorio (pórticos de OpenStreetMap con su tarifa ya asignada) al primer uso y luego una vez al día. Lo genera el workflow `.github/workflows/data.yml` (cada lunes, o al cambiar `tools/`), con `tools/build_data.py` y las tablas de `tools/gen_tarifas.py`; `data/reporte.txt` muestra qué pórtico quedó con qué tarifa. Si la descarga falla, la app consulta OpenStreetMap directamente y usa las tarifas incluidas en el APK.

## Primera vez
Al abrir la app aparece una guía que pide, paso a paso: ubicación precisa, notificaciones, GPS encendido, batería sin restricciones, aviso flotante y la descarga de datos. Se puede volver a abrir en Ajustes → "Guía de configuración".

## Archivo de tarifas (importación manual, opcional)
```json
{"tarifas":[
  {"id":123456789, "base":500, "punta":900, "saturacion":1300, "horarios":"LV 07:30-10:00 P; LV 18:00-20:00 S"},
  {"ref":"P1", "operador":"Costanera Norte", "via":["Kennedy"], "rumbo":270, "base":450}
]}
```
Cada entrada se asocia por `id` (nodo OSM) o por `ref` (código en el nombre/ref del pórtico), `operador` o `via` (texto contenido en la concesionaria o en la vía) y `rumbo` (sentido en grados, ±100°). Horarios: días `LV`, `S`, `D`, `SD`, `LS`, `LD`; `P` punta, `S` saturación.

El mapa usa teselas de OpenStreetMap (necesita internet) y Leaflet incluido en la app.

## Desarrollo
```bash
./gradlew testDebugUnitTest assembleDebug
```
APK en `app/build/outputs/apk/debug/`. CI: `.github/workflows/build.yml`.

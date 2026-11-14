# TAG TRACKER

App Android nativa (Kotlin) que registra automáticamente, en segundo plano mientras manejas, los pórticos de peaje (TAG) de las autopistas de Santiago de Chile por los que pasas y suma el gasto diario.

## Qué hace
- Servicio en primer plano con GPS (`LocationManager`, sin Google Play Services), cada 2 s; descarta posiciones con precisión peor a 50 m.
- Pórticos desde OpenStreetMap (Overpass), descargados al primer uso y con "Actualizar pórticos"; se guardan localmente.
- Detección: un pórtico cuenta si el segmento entre dos posiciones pasa a ≤ 30 m y el rumbo de marcha difiere ≤ 60° del rumbo de la vía. No repite el mismo pórtico antes de 3 min y descarta nodos duplicados (< 100 m, < 20 s).
- Pantallas: **Día** (total, iniciar/detener, lista editable), **Historial** (por día) y **Ajustes** (tarifa por defecto, actualizar pórticos, exclusión de batería, exportar CSV, borrar).
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
- `WAKE_LOCK` y exclusión de optimización de batería, para seguir con la pantalla apagada y con Waze/Uber en primer plano. La app lo ofrece al iniciar; también está en Ajustes.

Inicia el seguimiento con la app abierta (Android exige eso para permitir ubicación en segundo plano desde un servicio en primer plano).

## Limitaciones
- **Tarifas manuales**: no hay fuente de datos. Toca el monto de una pasada para fijar la tarifa del pórtico; se aplica a pasadas futuras y a las anteriores sin tarifa. Hay una tarifa por defecto para pórticos sin tarifa. El modelo ya tiene campos base/punta/saturación, pero esta versión usa solo una.
- **Falsos positivos** posibles en caleteras y vías paralelas cercanas a una autopista (si OSM no marca bien el sentido o la vía), y falsos negativos si OSM no tiene el pórtico o GPS débil (túneles).
- Cobertura de pórticos: Región Metropolitana (bbox de la consulta).
- Datos solo locales; no hay backend ni respaldo.

## Desarrollo
```bash
./gradlew testDebugUnitTest assembleDebug
```
APK en `app/build/outputs/apk/debug/`. CI: `.github/workflows/build.yml`.

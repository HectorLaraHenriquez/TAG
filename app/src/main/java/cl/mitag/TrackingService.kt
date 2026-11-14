package cl.mitag

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.Looper

class TrackingService : Service() {
    companion object {
        const val ACTION_STOP = "cl.mitag.STOP"
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 1
        @Volatile var running = false
            private set
    }

    private lateinit var lm: LocationManager
    private var detector: Detector? = null
    private var wake: PowerManager.WakeLock? = null
    private var listening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = onFix(loc)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { Store.status = "GPS desactivado"; Store.notifyChanged() }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(applicationContext)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Seguimiento", NotificationManager.IMPORTANCE_LOW))
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Exception) {
            Store.status = "No se pudo iniciar el servicio: ${e.message}"; Store.notifyChanged()
            stopSelf(); return START_NOT_STICKY
        }
        if (Store.gantries.isEmpty()) {
            Store.status = "No hay pórticos cargados."; Store.notifyChanged()
            stopSelf(); return START_NOT_STICKY
        }
        if (!listening) {
            detector = Detector(Store.gantries, Store.recentFound())
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, listener, Looper.getMainLooper())
                listening = true
            } catch (e: Exception) {
                Store.status = "Sin permiso de ubicación o GPS no disponible."; Store.notifyChanged()
                stopSelf(); return START_NOT_STICKY
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mitag:tracking").apply { acquire(12 * 60 * 60 * 1000L) }
            running = true
            Store.status = "Buscando GPS…"
            Store.notifyChanged()
        }
        return START_STICKY
    }

    private fun onFix(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 50) {
            Store.status = "Señal GPS débil (±${Math.round(loc.accuracy)} m)…"; Store.notifyChanged(); return
        }
        val found = detector?.feed(Fix(loc.latitude, loc.longitude, loc.time)) ?: emptyList()
        val n = Store.addPasses(found)
        Store.status = "Siguiendo · ±${Math.round(loc.accuracy)} m" + if (n > 0) " · pórtico registrado" else ""
        if (n > 0) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        else Store.notifyChanged()
    }

    private fun buildNotification(): Notification {
        val (n, total) = Store.dayStats(dayKey(System.currentTimeMillis()))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("TAG TRACKER · Hoy ${money(total)}")
            .setContentText("$n pórtico" + (if (n == 1) "" else "s"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stop).build())
            .build()
    }

    override fun onDestroy() {
        try { lm.removeUpdates(listener) } catch (e: Exception) {}
        listening = false
        try { wake?.takeIf { it.isHeld }?.release() } catch (e: Exception) {}
        wake = null
        running = false
        Store.status = "Seguimiento detenido."
        Store.notifyChanged()
        super.onDestroy()
    }
}

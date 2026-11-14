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
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class TrackingService : Service() {
    companion object {
        const val ACTION_STOP = "cl.mitag.STOP"
        const val ACTION_DISCARD = "cl.mitag.DISCARD"
        private const val CHANNEL_PASS = "pasadas"
        private const val NOTIF_PASS_ID = 2
        private const val POPUP_MS = 7000L
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 1
        @Volatile var running = false
            private set
    }

    private lateinit var lm: LocationManager
    private var detector: Detector? = null
    private var wake: PowerManager.WakeLock? = null
    private var listening = false
    private var lastSaved: Fix? = null
    private val ui = Handler(Looper.getMainLooper())
    private var popup: View? = null
    private val hidePopup = Runnable { removePopup() }

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
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PASS, "Pórticos detectados", NotificationManager.IMPORTANCE_HIGH))
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISCARD) {
            discard(intent.getLongExtra("t", 0), intent.getLongExtra("id", 0))
            return if (running) START_STICKY else { stopSelf(); START_NOT_STICKY }
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
        val fix = Fix(loc.latitude, loc.longitude, loc.time)
        val ls = lastSaved
        if (ls == null || haversine(ls, fix) >= 10 || dayKey(ls.t) != dayKey(fix.t)) { Store.appendTrack(fix); lastSaved = fix }
        val found = detector?.feed(fix) ?: emptyList()
        val added = Store.addPasses(found)
        Store.status = "Siguiendo · ±${Math.round(loc.accuracy)} m" + if (added.isNotEmpty()) " · pórtico registrado" else ""
        if (added.isNotEmpty()) {
            refreshNotification()
            announce(added.last())
        } else Store.notifyChanged()
    }

    private fun refreshNotification() = getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())

    private fun discard(t: Long, id: Long) {
        Store.deletePass(t, id)
        removePopup()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_PASS_ID)
        if (running) refreshNotification()
    }

    /** Avisa la pasada: ventanita sobre otras apps (si hay permiso) o notificación emergente. Ambas con "Descartar". */
    private fun announce(f: Found) {
        val (name, sub) = Store.label(f.id)
        val price = Store.priceAt(f.id, f.t)
        val text = name + (price?.let { " · " + money(it) } ?: "")
        if (Settings.canDrawOverlays(this)) {
            try { showPopup(f, text, sub); return } catch (e: Exception) {}
        }
        val pi = PendingIntent.getService(
            this, 2,
            Intent(this, TrackingService::class.java).setAction(ACTION_DISCARD).putExtra("t", f.t).putExtra("id", f.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL_PASS)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Pórtico registrado")
            .setContentText(text)
            .setTimeoutAfter(POPUP_MS + 3000)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Descartar", pi).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_PASS_ID, n)
    }

    private fun showPopup(f: Found, text: String, sub: String) {
        removePopup()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0xF01A222D.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF3DDC97.toInt()) }
            setPadding(dp(14), dp(10), dp(10), dp(10))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { this.text = "✓ $text"; textSize = 16f; setTextColor(0xFFE8EDF3.toInt()); setTypeface(typeface, Typeface.BOLD); maxLines = 1 })
        if (sub.isNotEmpty()) col.addView(TextView(this).apply { this.text = sub; textSize = 12f; setTextColor(0xFF8B98A8.toInt()); maxLines = 1 })
        box.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(TextView(this).apply {
            this.text = "Descartar"; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(0xFFFF6B6B.toInt()); cornerRadius = dp(10).toFloat() }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { discard(f.t, f.id) }
        })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP; y = dp(48) }
        val outer = android.widget.FrameLayout(this).apply { setPadding(dp(12), 0, dp(12), 0); addView(box) }
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(outer, lp)
        popup = outer
        ui.postDelayed(hidePopup, POPUP_MS)
    }

    private fun removePopup() {
        ui.removeCallbacks(hidePopup)
        popup?.let { try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (e: Exception) {} }
        popup = null
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
        removePopup()
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

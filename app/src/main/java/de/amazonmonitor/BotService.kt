package de.amazonmonitor

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dauerbetrieb: prüft Amazon in kurzem Abstand (Standard alle 2 Min.) und Müller/MediaMarkt im
 * eigenen Intervall. Läuft als Vordergrund-Dienst mit fester Benachrichtigung – daran siehst du,
 * dass der Bot läuft. Android erlaubt kürzere Abstände als 15 Min. nur auf diese Weise.
 *
 * Hält das Handy während des Betriebs wach (Teil-Wakelock), damit die Prüfungen auch bei
 * ausgeschaltetem Bildschirm pünktlich laufen – das kostet etwas Akku.
 */
class BotService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Store.setBotWanted(this, false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(this, "Startet …"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        Store.setBotWanted(this, true)
        acquireWake()
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
        return START_STICKY
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWake() {
        if (wake?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MrFoxBot:check").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private suspend fun runLoop() {
        val ctx = applicationContext
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            val amazonMs = Store.amazonInterval(ctx) * 60_000L
            val shopsMs = Store.interval(ctx).coerceAtLeast(5) * 60_000L
            try {
                if (now - Store.lastRun(ctx, Store.RUN_AMAZON) >= amazonMs) {
                    Store.setLastRun(ctx, Store.RUN_AMAZON, now)
                    update("Prüfe Amazon …")
                    Checker.checkAll(ctx) { !it.isWebShop }
                }
                if (now - Store.lastRun(ctx, Store.RUN_SHOPS) >= shopsMs) {
                    Store.setLastRun(ctx, Store.RUN_SHOPS, System.currentTimeMillis())
                    update("Prüfe Müller/MediaMarkt …")
                    Checker.checkAll(ctx) { it.isWebShop }
                }
            } catch (e: Exception) {
                Store.setStatus(ctx, "Fehler im Dauerbetrieb: ${e.message}")
            }
            update(null)
            delay(15_000L)
        }
    }

    private fun update(activity: String?) {
        val text = activity ?: summary(this)
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(this, text))
        } catch (e: SecurityException) {
            // Benachrichtigungs-Erlaubnis fehlt
        }
    }

    override fun onDestroy() {
        alive = false
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    /** Startet den Dienst nach einem Neustart des Handys wieder (wenn er vorher lief). */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED && Store.botWanted(context)) {
                try {
                    start(context)
                } catch (e: Exception) {
                    // Start nicht erlaubt – die 15-Min.-Prüfung (WorkManager) läuft trotzdem.
                }
            }
        }
    }

    companion object {
        private const val CHANNEL = "bot_status"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "de.amazonmonitor.STOP"

        /** Läuft der Dienst gerade (in diesem Prozess)? */
        @Volatile
        var alive = false
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, BotService::class.java))
        }

        fun stop(ctx: Context) {
            Store.setBotWanted(ctx, false)
            ctx.startService(Intent(ctx, BotService::class.java).setAction(ACTION_STOP))
        }

        private fun time(t: Long) =
            if (t == 0L) "–" else SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date(t))

        /** Kurzer Statustext für Benachrichtigung und App. */
        fun summary(ctx: Context): String {
            val a = Store.lastRun(ctx, Store.RUN_AMAZON)
            val nextA = if (a == 0L) "gleich" else time(a + Store.amazonInterval(ctx) * 60_000L)
            val s = Store.lastRun(ctx, Store.RUN_SHOPS)
            val n = Store.load(ctx).size
            return "Amazon alle ${Store.amazonInterval(ctx)} Min. (zuletzt ${time(a)}, nächste ~$nextA) · " +
                "Müller/MediaMarkt alle ${Store.interval(ctx).coerceAtLeast(5)} Min. (zuletzt ${time(s)}) · $n Produkte"
        }

        private fun buildNotification(ctx: Context, text: String): android.app.Notification {
            NotificationManagerCompat.from(ctx).createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Bot-Status (läuft)")
                    .build()
            )
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val stop = PendingIntent.getService(
                ctx, 1, Intent(ctx, BotService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("🟢 Mr.Fox Bot läuft")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text + "\nStatus: " + Store.status(ctx)))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stoppen", stop)
                .build()
        }
    }
}

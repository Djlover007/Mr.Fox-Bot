package de.amazonmonitor

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Automatisches Update: liest version.json aus dem neuesten GitHub-Release. Ist dort eine höhere
 * versionCode, kommt eine Benachrichtigung; „Installieren“ lädt die APK und öffnet den Android-Installer
 * (Android verlangt dabei immer einen Tipp auf „Aktualisieren“ – still installieren darf eine App nicht).
 */
object Updater {
    private const val VERSION_URL = "https://github.com/Djlover007/Mr.Fox-Bot/releases/latest/download/version.json"
    private const val CHANNEL = "updates"
    private const val NOTIFY_ID = 4712
    const val EXTRA_UPDATE = "mrfox_update"

    data class Info(val versionCode: Int, val versionName: String, val url: String)

    private fun prefs(c: Context) = c.getSharedPreferences("monitor", Context.MODE_PRIVATE)

    fun currentCode(c: Context): Long = try {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (e: Exception) {
        0L
    }

    /** Bekanntes, noch nicht installiertes Update (oder null). */
    fun pending(c: Context): Info? {
        val code = prefs(c).getInt("upd_code", 0)
        if (code <= currentCode(c)) return null
        return Info(code, prefs(c).getString("upd_name", "") ?: "", prefs(c).getString("upd_url", "") ?: "")
    }

    /** Blockierend – nur im Hintergrund. Gibt das Update zurück oder null; wirft bei Netzwerkfehlern. */
    fun fetch(c: Context): Info? {
        val conn = URL("$VERSION_URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        if (code == 404) throw IllegalStateException("Keine Update-Infos gefunden – ist das GitHub-Repo öffentlich?")
        if (code != 200) throw IllegalStateException("Update-Prüfung: HTTP $code")
        val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONObject("android")
        conn.disconnect()
        val info = Info(o.getInt("versionCode"), o.optString("versionName"), o.getString("url"))
        prefs(c).edit().putLong("upd_checked", System.currentTimeMillis()).apply()
        if (info.versionCode <= currentCode(c)) return null
        prefs(c).edit().putInt("upd_code", info.versionCode).putString("upd_name", info.versionName).putString("upd_url", info.url).apply()
        return info
    }

    /**
     * Im Hintergrund prüfen (höchstens alle [minHours] Stunden) und bei einem neuen Update einmal benachrichtigen.
     * Fehler werden verschluckt.
     */
    fun checkQuietly(c: Context, minHours: Int = 6) {
        if (System.currentTimeMillis() - prefs(c).getLong("upd_checked", 0L) < minHours * 3600_000L) return
        val info = try { fetch(c) } catch (e: Exception) { null } ?: return
        if (prefs(c).getInt("upd_notified", 0) == info.versionCode) return
        prefs(c).edit().putInt("upd_notified", info.versionCode).apply()
        notifyUpdate(c, info)
    }

    private fun notifyUpdate(c: Context, info: Info) {
        val nm = NotificationManagerCompat.from(c)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName("Updates").build()
        )
        val pi = PendingIntent.getActivity(
            c, 77,
            Intent(c, MainActivity::class.java).putExtra(EXTRA_UPDATE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("⬆️ Mr.Fox Bot ${info.versionName} ist da")
            .setContentText("Antippen zum Installieren")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIFY_ID, n)
        } catch (e: SecurityException) {
            // Benachrichtigungs-Erlaubnis fehlt
        }
    }

    /**
     * APK laden und den Android-Installer öffnen. Fehlt die Erlaubnis „Unbekannte Apps installieren“,
     * öffnet sich zuerst diese Einstellung (danach noch einmal „Installieren“ tippen).
     */
    suspend fun downloadAndInstall(c: Context, info: Info, status: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
            status("Bitte „Apps aus dieser Quelle erlauben“ einschalten, dann noch einmal „Installieren“ tippen.")
            c.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        status("Lade Update ${info.versionName} …")
        val file = withContext(Dispatchers.IO) {
            val dir = File(c.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, "mrfox-${info.versionCode}.apk")
            val conn = URL(info.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) throw IllegalStateException("Download fehlgeschlagen: HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            if (f.length() < 500_000) throw IllegalStateException("Download unvollständig")
            f
        }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", file)
        status("Installer wird geöffnet – „Aktualisieren“ tippen.")
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_LONG).show()
}

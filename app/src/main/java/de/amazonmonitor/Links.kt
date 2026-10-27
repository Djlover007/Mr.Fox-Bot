package de.amazonmonitor

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Öffnet Amazon-Links je nach Einstellung im App-Fenster oder in Chrome. */
object Links {
    private const val CHROME = "com.android.chrome"

    private fun chromeInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(CHROME, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** In-App-Fenster (mit Anmeldung der App und automatischem Klick auf „Zur Kasse“). */
    fun inApp(ctx: Context, url: String, checkout: Boolean = false): Intent =
        Intent(ctx, WebActivity::class.java).putExtra("url", url).putExtra("checkout", checkout)

    fun intent(ctx: Context, url: String, checkout: Boolean = false): Intent =
        if (Store.externalBrowser(ctx)) {
            // Chrome erzwingen, sonst würde evtl. die Amazon-App den Link übernehmen.
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (chromeInstalled(ctx)) setPackage(CHROME)
            }
        } else {
            inApp(ctx, url, checkout)
        }

    /** Aus einer Activity heraus öffnen. */
    fun open(ctx: Context, url: String, checkout: Boolean = false) {
        try {
            ctx.startActivity(intent(ctx, url, checkout))
        } catch (e: Exception) {
            ctx.startActivity(inApp(ctx, url, checkout))
        }
    }

    fun pending(ctx: Context, url: String, checkout: Boolean, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, requestCode,
            intent(ctx, url, checkout).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** Darf die App aus dem Hintergrund Fenster öffnen? (Erlaubnis „Über anderen Apps einblenden“) */
    fun canOpenFromBackground(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** Aus dem Hintergrund öffnen. Gibt false zurück, wenn Android das nicht erlaubt. */
    fun openFromBackground(ctx: Context, url: String, checkout: Boolean = false): Boolean {
        if (!canOpenFromBackground(ctx)) return false
        return try {
            ctx.startActivity(intent(ctx, url, checkout).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }
}

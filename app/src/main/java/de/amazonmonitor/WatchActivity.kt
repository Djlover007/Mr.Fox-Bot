package de.amazonmonitor

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sichtbares Fenster für Probelauf und Auto-Kauf: oben ein Statusbalken, darunter die Amazon-Seite,
 * auf der die App klickt. Nach dem Ende bleibt die Seite offen, damit du das Ergebnis siehst.
 */
class WatchActivity : AppCompatActivity() {
    lateinit var web: WebView
        private set
    private lateinit var banner: TextView

    /** Wird aufgerufen, wenn du das Fenster schließt, solange der Ablauf noch läuft. */
    var onClosed: (() -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Mr.Fox Bot – Auto-Kauf"
        val pad = (12 * resources.displayMetrics.density).toInt()
        banner = TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            text = "Starte …"
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(banner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        val p = pending
        pending = null
        if (p == null || !p.complete(this)) {
            // Niemand wartet (z. B. nach Drehen des Bildschirms neu erstellt) – nichts zu tun.
            banner.text = "Kein Auto-Kauf aktiv."
        }
    }

    fun setStatus(s: String) {
        if (!isDestroyed) banner.text = s
    }

    fun finished(s: String) = setStatus("Fertig: $s")

    override fun onDestroy() {
        onClosed?.invoke()
        onClosed = null
        CookieManager.getInstance().flush()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var pending: CompletableDeferred<WatchActivity>? = null

        /**
         * Öffnet das Fenster und wartet, bis es bereit ist. Gibt null zurück, wenn Android es nicht
         * öffnet (App im Hintergrund ohne Erlaubnis „Über anderen Apps einblenden“).
         * Nur auf dem Main-Thread aufrufen.
         */
        suspend fun open(ctx: Context): WatchActivity? {
            val d = CompletableDeferred<WatchActivity>()
            pending = d
            try {
                ctx.startActivity(
                    Intent(ctx, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                pending = null
                return null
            }
            val w = withTimeoutOrNull(8_000L) { d.await() }
            if (w == null) {
                pending = null
                d.cancel()
            }
            return w
        }
    }
}

package de.amazonmonitor

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.Toast
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * In-App-Browser für Amazon. Ist ein Zugang gespeichert, füllt die App die Anmeldeseite aus
 * (je Öffnen nur ein Versuch pro Schritt, damit ein falsches Passwort kein Konto sperrt).
 */
class WebActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val login = AutoLogin.Session()
    private var lastHint: String? = null
    private var checkoutTried = false
    private val wantCheckout: Boolean
        get() = intent.getBooleanExtra("checkout", false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Amazon"

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Müller-Seiten: Browser-Kennung wie Chrome, sonst hängt die Sicherheitsprüfung.
        if (intent.getStringExtra("url")?.contains("mueller.de") == true) Mueller.setup(this, web)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view != null) {
                    tryLogin(view)
                    tryCheckout(view, url)
                }
            }
        }
        web.webChromeClient = WebChromeClient()

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState == null) {
            val url = intent.getStringExtra("url") ?: "https://www.${Store.domain(this)}"
            web.loadUrl(url)
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    private fun tryLogin(view: WebView) {
        AutoLogin.step(this, view, login) { st ->
            if (isDestroyed) return@step
            val hint = when (st) {
                AutoLogin.State.OTP -> "Bitte den Bestätigungscode eingeben – danach bleibt die App angemeldet."
                AutoLogin.State.VERIFY -> "Bitte die Anmeldung bestätigen (Amazon-App, SMS oder E-Mail)."
                AutoLogin.State.CAPTCHA -> "Bitte das Captcha lösen."
                AutoLogin.State.GAVE_UP -> "Automatische Anmeldung hat nicht geklappt – Passwort prüfen."
                else -> null
            }
            if (hint != null && hint != lastHint) {
                lastHint = hint
                Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Auf der Warenkorbseite wird einmal "Zur Kasse gehen" gedrückt. Das endgültige "Jetzt kaufen"
     * drückt die App nie, das bleibt dir überlassen.
     */
    private fun tryCheckout(view: WebView, url: String?) {
        if (!wantCheckout || checkoutTried || url == null || !url.contains("/cart/view")) return
        checkoutTried = true
        val js = "(function(){" +
            "var sel=['input[name=\"proceedToRetailCheckout\"]','#sc-buy-box-ptc-button input','#sc-buy-box-ptc-button'];" +
            "for(var i=0;i<sel.length;i++){var b=document.querySelector(sel[i]);" +
            "if(b){b.click();return 'clicked';}}" +
            "return 'none';})()"
        view.evaluateJavascript(js, null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}

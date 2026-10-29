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
        // Echte Browser-Kennung + Cookies für Bot-Prüfungen (Cloudflare); App-Links fängt der Client unten ab.
        WebShops.setup(this, web)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                WebShops.handleAppLink(view, request.url?.toString())

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view != null) {
                    tryLogin(view)
                    tryCheckout(view, url)
                    tryFill(view, url)
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

    private val filled = HashSet<String>()

    /**
     * PayPal/Payback: gespeicherte E-Mail und Passwort (Konten & Einstellungen) auf der Anmeldeseite nur
     * EINTRAGEN – „Einloggen“ und die Bestätigung (2FA) bleiben bei dir. Je Seite einmal, mehrfach nachgeschaut,
     * weil die Felder oft erst später erscheinen.
     */
    private fun tryFill(view: WebView, url: String?) {
        val host = try { android.net.Uri.parse(url ?: return).host ?: return } catch (e: Exception) { return }
        val key = when {
            host == "paypal.com" || host.endsWith(".paypal.com") -> Store.ACC_PAYPAL
            host == "payback.de" || host.endsWith(".payback.de") -> Store.ACC_PAYBACK
            else -> return
        }
        val mail = Store.accEmail(this, key)
        val pw = Store.accPassword(this, key)
        if (mail.isEmpty() && pw == null) return
        if (!filled.add(url ?: return)) return
        val js = "(function(){function setv(el,v){if(!el||!v||el.value)return false;" +
            "var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(el,v);" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return true;}" +
            "function vis(e){return e&&e.offsetParent!==null;}" +
            "var n=0;var m=document.querySelector('#email,input[name=login_email],input[type=email],input[autocomplete=username],input[name*=mail i],input[id*=mail i],input[name*=user i]');" +
            "if(vis(m)&&setv(m," + org.json.JSONObject.quote(mail) + "))n++;" +
            "var p=document.querySelector('#password,input[name=login_password],input[type=password]');" +
            "if(vis(p)&&setv(p," + org.json.JSONObject.quote(pw ?: "") + "))n++;return n;})()"
        // mehrmals versuchen (Felder erscheinen verzögert, PayPal fragt E-Mail und Passwort nacheinander ab)
        for (i in 0 until 30) {
            view.postDelayed({ if (!isDestroyed && view.url == url) view.evaluateJavascript(js, null) }, 800L + i * 2000L)
        }
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

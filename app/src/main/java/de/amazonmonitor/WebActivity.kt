package de.amazonmonitor

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * In-App-Browser für Amazon. Ist ein Zugang gespeichert, füllt die App die Anmeldeseite aus
 * (je Öffnen nur ein Versuch pro Schritt, damit ein falsches Passwort kein Konto sperrt).
 */
class WebActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var emailTries = 0
    private var pwTries = 0
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
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view != null) {
                    tryLogin(view, url)
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

    private fun tryLogin(view: WebView, url: String?) {
        val uri = Uri.parse(url ?: return)
        val host = uri.host?.lowercase() ?: return
        val domain = Store.domain(this).lowercase()
        // Nur exakt die Amazon-Domain oder echte Subdomains davon (z. B. www.amazon.de),
        // nicht etwa "evilamazon.de".
        val isAmazon = host == domain || host.endsWith(".$domain")
        if (uri.scheme != "https" || !isAmazon ||
            !(uri.path ?: "").contains("/ap/signin")
        ) return

        val email = Store.loginEmail(this)
        val pw = Store.loginPassword(this)
        if (email.isEmpty() || pw == null) return

        val detect = "(function(){" +
            "if(document.getElementById('auth-captcha-guess'))return 'captcha';" +
            "if(document.getElementById('ap_password'))return 'pw';" +
            "if(document.getElementById('ap_email'))return 'email';" +
            "return 'none';})()"

        view.evaluateJavascript(detect) { result ->
            when (result?.trim('"')) {
                "email" -> {
                    if (emailTries < 1) {
                        emailTries++
                        view.evaluateJavascript(fillScript(email, null), null)
                    }
                }
                "pw" -> {
                    if (pwTries < 1) {
                        pwTries++
                        view.evaluateJavascript(fillScript(email, pw), null)
                    }
                }
                else -> {}
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

    private fun fillScript(email: String, pw: String?): String {
        val sb = StringBuilder()
        sb.append("(function(){")
        sb.append("function set(el,v){el.focus();el.value=v;")
        sb.append("el.dispatchEvent(new Event('input',{bubbles:true}));")
        sb.append("el.dispatchEvent(new Event('change',{bubbles:true}));}")
        sb.append("var r=document.querySelector('input[name=rememberMe]');if(r&&!r.checked){r.click();}")
        sb.append("var m=document.getElementById('ap_email');")
        sb.append("if(m&&m.type!=='hidden'&&!m.value){set(m,").append(JSONObject.quote(email)).append(");}")
        if (pw == null) {
            sb.append("var c=document.getElementById('continue');if(c){c.click();}")
        } else {
            sb.append("var p=document.getElementById('ap_password');if(p){set(p,")
                .append(JSONObject.quote(pw)).append(");}")
            sb.append("var s=document.getElementById('signInSubmit');if(s){s.click();}")
        }
        sb.append("})();")
        return sb.toString()
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

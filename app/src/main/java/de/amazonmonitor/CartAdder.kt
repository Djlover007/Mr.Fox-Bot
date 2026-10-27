package de.amazonmonitor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Legt einen Artikel in den Warenkorb: Produktseite laden, "In den Einkaufswagen" drücken, Bestätigung abwarten.
 * Nutzt die angemeldete Sitzung der App. Es wird nie "Zur Kasse" oder "Jetzt kaufen" gedrückt.
 */
object CartAdder {
    enum class Result { OK, NOT_LOGGED_IN, FAILED }

    /** Grund des letzten Ergebnisses, für Meldungen und Fehlersuche. */
    @Volatile
    var lastDetail: String = ""

    private const val CAPTCHA_DETAIL =
        "Amazon zeigt ein Captcha – öffne die Produktseite einmal in der App, löse es und versuche es später erneut"

    private const val CLICK_JS =
        "(function(){" +
            "if(document.getElementById('captchacharacters')||" +
            "document.querySelector('form[action*=\"validateCaptcha\"]'))return 'captcha';" +
            "var sel=['#add-to-cart-button','input#add-to-cart-button','input[name=\"submit.add-to-cart\"]'," +
            "'#add-to-cart-button-ubb'];" +
            "for(var i=0;i<sel.length;i++){var b=document.querySelector(sel[i]);" +
            "if(b){b.click();return 'clicked';}}" +
            "return 'none';})()"

    private const val CHECK_JS =
        "(function(){" +
            "var ids=['NATC_SMART_WAGON_CONF_MSG_SUCCESS','sw-atc-confirmation','huc-v2-order-row-confirm-text'," +
            "'attachDisplayAddBaseAlert','sw-atc-details-single-container','attach-accessory-pane'];" +
            "for(var i=0;i<ids.length;i++){if(document.getElementById(ids[i]))return 'ok';}" +
            "return 'no';})()"

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun add(ctx: Context, domain: String, asin: String): Result =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(90_000L) {
                suspendCancellableCoroutine<Result> { cont ->
                    val web = WebView(ctx.applicationContext)
                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    val handler = Handler(Looper.getMainLooper())
                    var stage = 0
                    var polls = 0

                    fun done(r: Result, detail: String) {
                        if (cont.isActive) {
                            lastDetail = detail
                            handler.removeCallbacksAndMessages(null)
                            cont.resume(r)
                            web.stopLoading()
                            web.destroy()
                        }
                    }

                    cont.invokeOnCancellation {
                        handler.post {
                            handler.removeCallbacksAndMessages(null)
                            web.stopLoading()
                            web.destroy()
                        }
                    }

                    val productUrl = "https://www.$domain/dp/$asin"
                    val login = AutoLogin.Session()
                    var loginRunning = false
                    var reloadedAfterLogin = false

                    // Automatisch anmelden, danach (falls nötig) die Produktseite neu laden.
                    fun loginLoop() {
                        if (!cont.isActive) return
                        AutoLogin.step(ctx, web, login) { st ->
                            if (!cont.isActive) return@step
                            when (st) {
                                AutoLogin.State.FILLED, AutoLogin.State.WAIT ->
                                    handler.postDelayed({ loginLoop() }, 2000)
                                AutoLogin.State.NOT_LOGIN_PAGE -> {
                                    loginRunning = false
                                    if (web.url?.contains("/dp/") != true && !reloadedAfterLogin) {
                                        reloadedAfterLogin = true
                                        stage = 0
                                        web.loadUrl(productUrl)
                                    }
                                }
                                else -> done(Result.NOT_LOGGED_IN, AutoLogin.explain(st))
                            }
                        }
                    }

                    val poll = object : Runnable {
                        override fun run() {
                            if (!cont.isActive) return
                            web.evaluateJavascript(CHECK_JS) { res ->
                                if (!cont.isActive) return@evaluateJavascript
                                if (res != null && res.contains("ok")) {
                                    done(Result.OK, "Bestätigung von Amazon erhalten")
                                } else {
                                    polls++
                                    if (polls >= 12) {
                                        done(Result.FAILED, "Keine Bestätigung von Amazon erhalten")
                                    } else {
                                        handler.postDelayed(this, 1500)
                                    }
                                }
                            }
                        }
                    }

                    web.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (view == null || url == null || !cont.isActive) return
                            if (url.contains("validateCaptcha") || url.contains("/errors/")) {
                                done(Result.FAILED, CAPTCHA_DETAIL)
                            } else if (url.contains("/ap/")) {
                                if (!loginRunning) {
                                    loginRunning = true
                                    loginLoop()
                                }
                            } else if (stage == 0 && url.contains("/dp/")) {
                                stage = 1
                                view.evaluateJavascript(CLICK_JS) { res ->
                                    if (!cont.isActive) return@evaluateJavascript
                                    if (res != null && res.contains("clicked")) {
                                        handler.postDelayed(poll, 2000)
                                    } else if (res != null && res.contains("captcha")) {
                                        done(Result.FAILED, CAPTCHA_DETAIL)
                                    } else {
                                        done(
                                            Result.FAILED,
                                            "Kein „In den Einkaufswagen“-Knopf gefunden " +
                                                "(Artikel nicht verfügbar oder Variante nötig)"
                                        )
                                    }
                                }
                            } else if (stage == 1 && (url.contains("/huc/") || url.contains("/cart"))) {
                                done(Result.OK, "Weiterleitung zum Warenkorb")
                            }
                        }
                    }

                    web.loadUrl(productUrl)
                }
            } ?: run {
                lastDetail = "Zeitüberschreitung"
                Result.FAILED
            }
        }
}

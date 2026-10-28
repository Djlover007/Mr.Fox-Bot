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

    /** Bestätigungsseite von /gp/aws/cart/add.html: „Weiter/In den Einkaufswagen“ drücken. */
    private const val AWS_CONFIRM_JS =
        "(function(){" +
            "if(document.getElementById('captchacharacters')||" +
            "document.querySelector('form[action*=\"validateCaptcha\"]'))return 'captcha';" +
            "var sel=['input[name=\"add\"]','form[action*=\"cart/add\"] input[type=\"submit\"]'," +
            "'form[action*=\"cart\"] input[type=\"image\"]'];" +
            "for(var i=0;i<sel.length;i++){var b=document.querySelector(sel[i]);" +
            "if(b){b.click();return 'clicked';}}" +
            "return 'none';})()"

    /**
     * @param offerId OfferListingId: wenn bekannt, wird genau dieses Angebot direkt über den
     * Warenkorb-Link hinzugefügt (schnell, ohne Produktseite). Klappt das nicht, normaler Weg über die Produktseite.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun add(ctx: Context, domain: String, asin: String, offerId: String? = null): Result =
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
                    // Schneller Weg über OfferListingId: stage 10 = Link geladen, 11 = bestätigt.
                    val fastUrl = offerId?.let { Checker.cartUrl(domain, asin, it) }
                    var fastFailed = fastUrl == null

                    /** Startet (neu): schneller Weg, solange er nicht gescheitert ist, sonst Produktseite. */
                    fun start() {
                        if (!fastFailed && fastUrl != null) {
                            stage = 10
                            web.loadUrl(fastUrl)
                        } else {
                            stage = 0
                            web.loadUrl(productUrl)
                        }
                    }

                    fun fallback() {
                        fastFailed = true
                        start()
                    }

                    val fastTimeout = Runnable { if (stage == 11 && cont.isActive) fallback() }
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
                                    val u = web.url ?: ""
                                    if (!u.contains("/dp/") && !u.contains("/aws/cart/add") && !reloadedAfterLogin) {
                                        reloadedAfterLogin = true
                                        start()
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
                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                            WebShops.handleAppLink(view, request.url?.toString())

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (view == null || url == null || !cont.isActive) return
                            if (url.contains("validateCaptcha") || url.contains("/errors/")) {
                                done(Result.FAILED, CAPTCHA_DETAIL)
                            } else if (url.contains("/ap/")) {
                                if (!loginRunning) {
                                    loginRunning = true
                                    loginLoop()
                                }
                            } else if (stage == 10 && url.contains("/aws/cart/add")) {
                                stage = 11
                                view.evaluateJavascript(AWS_CONFIRM_JS) { res ->
                                    if (!cont.isActive) return@evaluateJavascript
                                    when {
                                        res != null && res.contains("clicked") ->
                                            handler.postDelayed(fastTimeout, 15_000)
                                        res != null && res.contains("captcha") ->
                                            done(Result.FAILED, CAPTCHA_DETAIL)
                                        else -> fallback()
                                    }
                                }
                            } else if (stage == 11 && !url.contains("/aws/cart/add") &&
                                (url.contains("/huc/") || url.contains("/cart"))
                            ) {
                                done(Result.OK, "Angebot per OfferListingId in den Warenkorb gelegt")
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
                            } else if (stage == 1 && !url.contains("/aws/cart/add") &&
                                (url.contains("/huc/") || url.contains("/cart"))
                            ) {
                                done(Result.OK, "Weiterleitung zum Warenkorb")
                            }
                        }
                    }

                    start()
                }
            } ?: run {
                lastDetail = "Zeitüberschreitung"
                Result.FAILED
            }
        }
}

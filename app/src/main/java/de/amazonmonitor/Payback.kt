package de.amazonmonitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * PAYBACK: Einkauf über die Payback-Shopseite starten, damit es °Punkte gibt.
 *
 * Ablauf: payback.de/shop/<partner> laden → alle Coupons „aktivieren“ → „Jetzt shoppen“ →
 * Weiterleitung zum Shop abwarten → Cookies des Shops akzeptieren (sonst kann Payback den Kauf
 * nicht zuordnen). Danach kauft die App im selben App-Browser (gemeinsame Cookies) ein.
 *
 * Voraussetzung: einmal im App-Fenster bei Payback angemeldet sein („Angemeldet bleiben“).
 * Payback schreibt nur Käufe gut, die zeitnah (innerhalb von 24 Std.) nach der Weiterleitung erfolgen.
 */
object Payback {
    const val LOGIN_URL = "https://www.payback.de/login"
    private const val ORIGIN = "https://*.payback.de"

    /** Payback-Partner: Shop-Schlüssel → Payback-Shopseite und Ziel-Domain. */
    private val PARTNERS = mapOf(
        Product.SHOP_AMAZON to Pair("https://www.payback.de/shop/amazon", "amazon.de"),
        Product.SHOP_MEDIAMARKT to Pair("https://www.payback.de/shop/mediamarkt", "mediamarkt.de"),
    )

    fun supports(shopKey: String): Boolean = shopKey in PARTNERS

    fun shopPage(shopKey: String): String? = PARTNERS[shopKey]?.first

    fun label(shopKey: String): String = WebShops.byKey(shopKey)?.label ?: "Amazon"

    data class Result(val ok: Boolean, val detail: String, val coupons: Int = 0)

    private val mutex = Mutex()

    /** Alle sichtbaren Coupons aktivieren; gibt die Anzahl der Klicks zurück. */
    private val COUPONS_JS = "(function(){" + WebShops.COMMON_JS +
        "var n=0;var els=document.querySelectorAll('button,a,[role=button]');" +
        "for(var i=0;i<els.length;i++){var e=els[i];var t=txt(e);" +
        "if(/^(jetzt aktivieren|aktivieren|coupon aktivieren)${'$'}/i.test(t)&&vis(e)){e.click();n++;}}" +
        "return String(n);})()"

    /** Zustand der Payback-Seite: Anmeldung nötig? Shop-Knopf da? */
    private val STATE_JS = "(function(){" + WebShops.COMMON_JS +
        "if(security())return 'security';" +
        "var pw=document.querySelector('input[type=password]');if(pw&&vis(pw))return 'login';" +
        "if(location.href.indexOf('/login')>=0)return 'login';" +
        "if(btn(/^(zur payback app|payback app download)${'$'}/i))return 'app';" +
        "if(btn(/^(jetzt shoppen|zum shop|jetzt einkaufen|shoppen)${'$'}/i))return 'shop';return 'wait';})()"

    /** „Jetzt shoppen“ drücken; Pop-up-Fenster werden in dasselbe Fenster umgeleitet. */
    private val SHOP_JS = "(function(){" + WebShops.COMMON_JS +
        "window.open=function(u){if(u)location.href=u;return window;};" +
        "var b=btn(/^(jetzt shoppen|zum shop|jetzt einkaufen|shoppen)${'$'}/i);if(!b)return 'none';" +
        "var a=b.closest('a');if(a&&a.href&&a.href.indexOf('javascript')!==0){location.href=a.href;return 'nav';}" +
        "b.removeAttribute('target');b.click();return 'clicked';})()"

    /** Cookie-Hinweis des Shops akzeptieren (Amazon: #sp-cc-accept; sonst per Beschriftung). */
    private val ACCEPT_COOKIES_JS = "(function(){" + WebShops.COMMON_JS +
        "var a=document.getElementById('sp-cc-accept');if(a){a.click();return 'ok';}" +
        "var b=btn(/^(alle akzeptieren|alle zulassen|akzeptieren|alle cookies akzeptieren|zustimmen)${'$'}/i);" +
        "if(b){b.click();return 'ok';}return 'none';})()"

    /**
     * Weiterleitung über Payback zum Shop [shopKey]. Mit [visible] im sichtbaren Fenster (zum Zuschauen
     * oder um sich anzumelden). Klappt die Weiterleitung nicht, sollte der Kauf trotzdem weiterlaufen –
     * es gibt dann nur keine Punkte.
     */
    suspend fun redirect(ctx: Context, shopKey: String, visible: Boolean = false): Result {
        val (page, domain) = PARTNERS[shopKey] ?: return Result(false, "kein Payback-Partner")
        return mutex.withLock {
            withContext(Dispatchers.Main) {
                val watch = if (visible) WatchActivity.open(ctx) else null
                watch?.let { WebShops.setupDesktop(ctx, it.web, ORIGIN) }
                val r = withTimeoutOrNull(60_000L) { execute(ctx, watch, page, domain) }
                    ?: Result(false, "Zeitüberschreitung bei der Payback-Weiterleitung")
                watch?.finished(if (r.ok) "✅ ${r.detail}" else "⚠️ ${r.detail}")
                r
            }
        }
    }

    private suspend fun execute(ctx: Context, watch: WatchActivity?, page: String, domain: String): Result =
        suspendCancellableCoroutine { cont ->
            val owned = watch == null
            // Umleitungen in die Payback-App (intent://…) fängt WebShops.safeClient ab.
            val web = watch?.web ?: WebShops.hiddenWebView(ctx).also { WebShops.setupDesktop(ctx, it, ORIGIN) }
            val handler = Handler(Looper.getMainLooper())
            fun status(s: String) = watch?.setStatus("Payback: $s")
            // 0 = Payback-Seite, 1 = „Jetzt shoppen“ gedrückt, warte auf Shop, 2 = Cookies beim Shop
            var stage = 0
            var polls = 0
            var coupons = 0
            var couponRounds = 0
            var last = ""

            fun release() {
                handler.removeCallbacksAndMessages(null)
                watch?.onClosed = null
                if (owned) {
                    web.stopLoading()
                    web.destroy()
                }
            }

            fun done(r: Result) {
                if (cont.isActive) {
                    cont.resume(r)
                    release()
                }
            }
            cont.invokeOnCancellation { handler.post { release() } }
            watch?.onClosed = { done(Result(false, "Fenster geschlossen", coupons)) }

            val tick = object : Runnable {
                fun again() = handler.postDelayed(this, 1500)
                override fun run() {
                    if (!cont.isActive) return
                    polls++
                    val host = android.net.Uri.parse(web.url ?: "").host ?: ""
                    when (stage) {
                        0 -> web.evaluateJavascript(STATE_JS) { raw ->
                            if (!cont.isActive) return@evaluateJavascript
                            last = raw?.trim('"') ?: ""
                            when {
                                last == "login" -> {
                                    done(Result(false, "nicht bei Payback angemeldet – einmal „Bei Payback anmelden (App)“", coupons))
                                    return@evaluateJavascript
                                }
                                last == "app" && polls >= 6 -> {
                                    done(Result(false, "Payback zeigt nur „App herunterladen“ statt „Jetzt shoppen“ (Handy-Ansicht)", coupons))
                                    return@evaluateJavascript
                                }
                                last == "shop" && couponRounds < 2 -> {
                                    // Coupons aktivieren (zweimal, falls nach dem ersten Klick neue nachladen)
                                    couponRounds++
                                    status("Aktiviere Coupons …")
                                    web.evaluateJavascript(COUPONS_JS) { n -> coupons += n?.trim('"')?.toIntOrNull() ?: 0 }
                                }
                                last == "shop" -> {
                                    status("„Jetzt shoppen“ …")
                                    web.evaluateJavascript(SHOP_JS, null)
                                    stage = 1
                                    polls = 0
                                }
                                polls >= 20 -> {
                                    done(Result(false, if (last == "security") "Payback-Sicherheitsprüfung nicht bestanden" else "„Jetzt shoppen“ nicht gefunden", coupons))
                                    return@evaluateJavascript
                                }
                            }
                            again()
                        }

                        1 -> {
                            if (host.endsWith(domain)) {
                                status("Weitergeleitet zu $domain, akzeptiere Cookies …")
                                stage = 2
                                polls = 0
                            } else if (polls >= 20) {
                                done(Result(false, "keine Weiterleitung zum Shop (Payback-Anmeldung abgelaufen?)", coupons))
                                return
                            }
                            again()
                        }

                        2 -> web.evaluateJavascript(ACCEPT_COOKIES_JS) { raw ->
                            if (!cont.isActive) return@evaluateJavascript
                            if (raw?.contains("ok") == true || polls >= 4) {
                                android.webkit.CookieManager.getInstance().flush()
                                val c = if (coupons > 0) ", $coupons Coupon(s) aktiviert" else ""
                                done(Result(true, "über Payback weitergeleitet$c", coupons))
                                return@evaluateJavascript
                            }
                            again()
                        }
                    }
                }
            }
            status("Lade Payback-Shopseite …")
            web.webViewClient = WebShops.safeClient()
            web.loadUrl(page)
            handler.postDelayed(tick, 2500)
        }

    /** Kurzer Hinweis für Meldungen. */
    fun describe(r: Result): String = if (r.ok) "💳 Payback: ${r.detail}" else "⚠️ Payback: ${r.detail} – Kauf ohne Punkte"
}

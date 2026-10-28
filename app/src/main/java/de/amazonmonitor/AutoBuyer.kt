package de.amazonmonitor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Automatischer Kauf eines einzelnen Artikels über „Jetzt kaufen“ (nicht über den Warenkorb,
 * damit nie andere Artikel aus dem Warenkorb mitbestellt werden).
 *
 * Ablauf: Produktseite → „Jetzt kaufen“ → Bestellseite (oder Schnellkauf-Fenster) →
 * Gesamtbetrag lesen (nur zur Info) → „Jetzt kaufen“/„Bestellung aufgeben“ drücken →
 * auf die Bestätigungsseite warten.
 *
 * Bei [dryRun] = true wird alles bis zur Bestellseite durchlaufen und der Gesamtbetrag gemeldet,
 * aber NICHT bestellt.
 *
 * Es wird mit Standard-Adresse und Standard-Zahlungsart aus dem Amazon-Konto bestellt.
 */
object AutoBuyer {
    enum class Outcome { ORDERED, DRY_RUN_OK, NOT_LOGGED_IN, BLOCKED, ABORTED, FAILED, UNCLEAR }

    data class Result(val outcome: Outcome, val detail: String, val total: Double? = null)

    /** Nie zwei Kaufvorgänge gleichzeitig (z. B. Hintergrundprüfung + „Jetzt prüfen“). */
    private val mutex = Mutex()

    private val BUY_NOW_SELECTORS = listOf(
        "#buy-now-button",
        "input#buy-now-button",
        "input[name='submit.buy-now']",
    )

    private val PLACE_ORDER_SELECTORS = listOf(
        "#turbo-checkout-pyo-button",
        "input[name='placeYourOrder1']",
        "#placeYourOrder input",
        "#submitOrderButtonId input",
        "#bottomSubmitOrderButtonId input",
    )

    private val TOTAL_SELECTORS = listOf(
        "#subtotals-marketplace-table .grand-total-price",
        ".grand-total-price",
        "#turbo-checkout-panel-container .grand-total-price",
        "#turbo-checkout-panel-container .a-price .a-offscreen",
        "#subtotals-marketplace-table .a-color-price",
    )

    private const val CAPTCHA_TEST =
        "document.getElementById('captchacharacters')||document.querySelector('form[action*=\"validateCaptcha\"]')"

    private fun buyNowJs() = "(function(){" +
        "if($CAPTCHA_TEST)return 'captcha';" +
        "var sel=${JSONArray(BUY_NOW_SELECTORS)};" +
        "for(var i=0;i<sel.length;i++){var b=document.querySelector(sel[i]);if(b){b.click();return 'clicked';}}" +
        "return 'none';})()"

    /** Sucht Bestell-Knopf und Gesamtbetrag in der Seite und im Schnellkauf-Fenster (iframe). */
    private fun docsJs() =
        "function q(d,s){for(var i=0;i<s.length;i++){try{var e=d.querySelector(s[i]);if(e)return e;}catch(x){}}return null;}" +
            "var docs=[];var f=document.querySelector('#turbo-checkout-iframe,iframe[id*=\"turbo\"]');" +
            "if(f){try{if(f.contentDocument)docs.push(f.contentDocument);}catch(x){}}" +
            "docs.push(document);" +
            "var BTN=${JSONArray(PLACE_ORDER_SELECTORS)};var TOT=${JSONArray(TOTAL_SELECTORS)};"

    /**
     * Sucht den Gesamtbetrag über die Beschriftung („Gesamtsumme“, „Gesamtbetrag“ …) und nimmt den
     * letzten €-Betrag in derselben Zeile. hintOf() sammelt €-Beträge der Seite für die Fehlersuche.
     */
    const val FIND_TOTAL_JS = """
function findTotal(d){
 var labels=[/^(gesamtsumme|gesamtbetrag|bestellsumme|zu zahlender betrag|order total)/i,/^(gesamt|summe|total)\b/i];
 var pr=/(\d{1,3}(?:[.\s]\d{3})*,\d{2}\s*€|€\s*\d{1,3}(?:[.,\s]\d{3})*[.,]\d{2}|\d+[.,]\d{2}\s*€)/g;
 var els=d.querySelectorAll('span,td,th,div,label,dt,dd,strong,b,h2,h3,h4,h5,p');
 for(var L=0;L<labels.length;L++){
  for(var i=0;i<els.length;i++){
   var e=els[i];if(e.children.length>3)continue;
   var t=(e.textContent||'').replace(/\s+/g,' ').trim();
   if(t.length>60||!labels[L].test(t))continue;
   var m=t.match(pr);if(m)return m[m.length-1];
   var row=e.parentElement;
   for(var up=0;up<4&&row;up++){
    var rt=(row.textContent||'').replace(/\s+/g,' ');
    if(rt.length>300)break;
    m=rt.match(pr);if(m)return m[m.length-1];
    row=row.parentElement;
   }
  }
 }
 return null;
}
function hintOf(d){
 var out=[];var els=d.querySelectorAll('span,td,div,dd,strong,b');
 for(var i=0;i<els.length&&out.length<8;i++){
  var e=els[i];if(e.children.length)continue;
  var t=(e.textContent||'').trim();
  if(t.length>30||t.indexOf('€')<0)continue;
  var p=e.parentElement&&e.parentElement.parentElement;
  var s=((p&&p.textContent)||t).replace(/\s+/g,' ').trim().slice(0,70);
  if(out.indexOf(s)<0)out.push(s);
 }
 return out.join(' | ');
}
"""

    private fun pollJs() = "(function(){" + docsJs() + FIND_TOTAL_JS +
        "var h=location.href;" +
        "if(h.indexOf('thankyou')>=0||document.getElementById('widget-purchaseConfirmationStatus'))" +
        "return JSON.stringify({s:'done'});" +
        "if($CAPTCHA_TEST)return JSON.stringify({s:'captcha'});" +
        "if(h.indexOf('/ap/')>=0)return JSON.stringify({s:'login'});" +
        "for(var k=0;k<docs.length;k++){var d=docs[k];var b=q(d,BTN);if(!b)continue;var t=q(d,TOT);" +
        "var tt=t?(t.textContent||'').trim():'';if(!/\\d/.test(tt))tt=findTotal(d)||'';" +
        "if(tt)return JSON.stringify({s:'ready',total:tt});" +
        "return JSON.stringify({s:'nototal',hint:hintOf(d)});}" +
        "return JSON.stringify({s:'wait'});})()"

    private fun placeOrderJs() = "(function(){" + docsJs() +
        "for(var k=0;k<docs.length;k++){var b=q(docs[k],BTN);if(b){b.click();return 'clicked';}}" +
        "return 'none';})()"

    private fun parse(raw: String?): JSONObject = try {
        val s = JSONTokener(raw ?: "null").nextValue() as? String
        if (s == null) JSONObject() else JSONObject(s)
    } catch (e: Exception) {
        JSONObject()
    }

    /**
     * @param visible true = Ablauf in einem sichtbaren Fenster ([WatchActivity]) zeigen. Lässt Android
     * das Fenster nicht zu (App im Hintergrund ohne „Über anderen Apps einblenden“), läuft es unsichtbar.
     */
    suspend fun buy(
        ctx: Context, domain: String, asin: String, dryRun: Boolean, visible: Boolean = false
    ): Result =
        mutex.withLock {
            withContext(Dispatchers.Main) {
                val watch = if (visible) WatchActivity.open(ctx) else null
                var placed = false
                val r = withTimeoutOrNull(120_000L) {
                    execute(ctx, watch, domain, asin, dryRun) { placed = true }
                } ?: if (placed) {
                    Result(Outcome.UNCLEAR, "Zeitüberschreitung nach dem Klick auf „Bestellen“")
                } else {
                    Result(Outcome.FAILED, "Zeitüberschreitung")
                }
                watch?.finished(describe(r))
                r
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun execute(
        ctx: Context, watch: WatchActivity?, domain: String, asin: String, dryRun: Boolean,
        onPlaced: () -> Unit
    ): Result = suspendCancellableCoroutine { cont ->
        // Sichtbares Fenster: dessen WebView benutzen (bleibt nach dem Ende offen). Sonst eine unsichtbare.
        val owned = watch == null
        val web = watch?.web ?: WebView(ctx.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
        val handler = Handler(Looper.getMainLooper())
        fun status(s: String) = watch?.setStatus((if (dryRun) "Probelauf: " else "Auto-Kauf: ") + s)

        // 0 = Produktseite lädt, 1 = „Jetzt kaufen“ gedrückt, 2 = Bestellung abgeschickt
        var stage = 0
        var polls = 0
        var noTotal = 0
        var total: Double? = null
        val productUrl = "https://www.$domain/dp/$asin"
        val login = AutoLogin.Session()
        var loginRunning = false
        var reloadedAfterLogin = false

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

        // Schließt du das Fenster mittendrin, wird abgebrochen (nach dem Bestell-Klick: „unklar“).
        watch?.onClosed = {
            if (stage == 2) done(Result(Outcome.UNCLEAR, "Fenster nach dem Bestell-Klick geschlossen", total))
            else done(Result(Outcome.FAILED, "Fenster geschlossen"))
        }
        status("Lade Produktseite …")

        // Anmeldung vor dem Kauf (Produktseite leitet auf die Anmeldung um).
        fun loginLoop() {
            if (!cont.isActive) return
            AutoLogin.step(ctx, web, login) { st ->
                if (!cont.isActive) return@step
                when (st) {
                    AutoLogin.State.FILLED, AutoLogin.State.WAIT -> {
                        status("Melde bei Amazon an …")
                        handler.postDelayed({ loginLoop() }, 2000)
                    }
                    AutoLogin.State.NOT_LOGIN_PAGE -> {
                        loginRunning = false
                        // Nach der Anmeldung nicht auf der Produktseite gelandet? Einmal neu laden.
                        if (stage == 0 && web.url?.contains("/dp/") != true && !reloadedAfterLogin) {
                            reloadedAfterLogin = true
                            status("Angemeldet, lade Produktseite …")
                            web.loadUrl(productUrl)
                        }
                    }
                    else -> done(Result(Outcome.NOT_LOGGED_IN, AutoLogin.explain(st)))
                }
            }
        }

        val poll = object : Runnable {
            fun next() {
                polls++
                when {
                    stage == 1 && polls >= 30 ->
                        done(Result(Outcome.FAILED, "Bestellseite nicht erreicht oder nicht erkannt"))
                    stage == 2 && polls >= 20 ->
                        done(Result(Outcome.UNCLEAR, "Keine Bestätigungsseite erkannt", total))
                    else -> handler.postDelayed(this, 1500)
                }
            }

            override fun run() {
                if (!cont.isActive) return
                web.evaluateJavascript(pollJs()) { raw ->
                    if (!cont.isActive) return@evaluateJavascript
                    val o = parse(raw)
                    when (o.optString("s")) {
                        "done" ->
                            if (stage == 2) done(Result(Outcome.ORDERED, "Bestellung bestätigt", total))
                            else done(Result(Outcome.UNCLEAR, "Bestätigungsseite erschienen, ohne dass die App bestellt hat"))
                        "captcha" ->
                            if (stage == 2) done(Result(Outcome.UNCLEAR, "Captcha nach dem Bestellen", total))
                            else done(Result(Outcome.BLOCKED, "Amazon zeigt ein Captcha"))
                        "login" ->
                            if (stage == 2) {
                                done(Result(Outcome.UNCLEAR, "Anmeldung nach dem Bestellen verlangt", total))
                            } else {
                                // Amazon will vor der Kasse oft noch einmal das Passwort.
                                AutoLogin.step(ctx, web, login) { st ->
                                    if (!cont.isActive) return@step
                                    when (st) {
                                        AutoLogin.State.FILLED, AutoLogin.State.WAIT,
                                        AutoLogin.State.NOT_LOGIN_PAGE -> {
                                            status("Melde bei Amazon an …")
                                            next()
                                        }
                                        else -> done(Result(Outcome.NOT_LOGGED_IN, AutoLogin.explain(st)))
                                    }
                                }
                            }
                        "ready", "nototal" -> {
                            if (stage == 1) {
                                val t = if (o.optString("s") == "ready") Scraper.parsePrice(o.optString("total")) else null
                                if (t == null) noTotal++
                                // Der Gesamtbetrag ist nur noch zur Info (kein Höchstbetrag mehr).
                                // Echter Kauf: sofort bestellen. Probelauf: kurz warten, ob er lesbar wird.
                                val proceed = t != null || !dryRun || noTotal >= 4
                                when {
                                    !proceed -> {}
                                    dryRun -> {
                                        val hint = o.optString("hint")
                                        val info = if (t != null) "Gesamtbetrag ${money(t)}"
                                        else "Gesamtbetrag nicht lesbar, würde trotzdem bestellen" +
                                            if (hint.isNotEmpty()) ". Gefundene Beträge: $hint" else ""
                                        done(Result(Outcome.DRY_RUN_OK, "Bestellknopf gefunden, $info – Probelauf, nichts bestellt", t))
                                        return@evaluateJavascript
                                    }
                                    else -> {
                                        total = t
                                        stage = 2
                                        polls = 0
                                        onPlaced()
                                        status("Bestellung wird abgeschickt …")
                                        web.evaluateJavascript(placeOrderJs(), null)
                                    }
                                }
                            }
                            next()
                        }
                        else -> next()
                    }
                }
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view == null || url == null || !cont.isActive || stage != 0) return
                when {
                    url.contains("validateCaptcha") -> done(Result(Outcome.BLOCKED, "Amazon zeigt ein Captcha"))
                    url.contains("/ap/") -> if (!loginRunning) {
                        loginRunning = true
                        loginLoop()
                    }
                    url.contains("/dp/") -> {
                        stage = 1
                        view.evaluateJavascript(buyNowJs()) { res ->
                            if (!cont.isActive) return@evaluateJavascript
                            when {
                                res != null && res.contains("clicked") -> {
                                    status("„Jetzt kaufen“ gedrückt, suche Bestellseite …")
                                    handler.postDelayed(poll, 2500)
                                }
                                res != null && res.contains("captcha") ->
                                    done(Result(Outcome.BLOCKED, "Amazon zeigt ein Captcha"))
                                else -> done(
                                    Result(
                                        Outcome.FAILED,
                                        "Kein „Jetzt kaufen“-Knopf (nicht verfügbar, Variante nötig oder 1-Click aus)"
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        web.loadUrl(productUrl)
    }

    /** Kurzer Text für Benachrichtigung, Discord und Produktliste. */
    fun describe(r: Result): String = when (r.outcome) {
        Outcome.ORDERED -> "🛒 Automatisch bestellt" + (r.total?.let { " (Gesamt ${money(it)})" } ?: "") +
            " – bitte in deinen Bestellungen prüfen"
        Outcome.DRY_RUN_OK -> "✅ ${r.detail}"
        Outcome.UNCLEAR -> "⚠️ Auto-Kauf unklar (${r.detail}) – bitte SOFORT deine Bestellungen prüfen"
        Outcome.NOT_LOGGED_IN -> "⚠️ Auto-Kauf nicht ausgeführt: nicht bei Amazon angemeldet"
        Outcome.BLOCKED -> "⚠️ Auto-Kauf nicht ausgeführt: ${r.detail}"
        Outcome.ABORTED -> "⛔ Auto-Kauf abgebrochen: ${r.detail}"
        Outcome.FAILED -> "⚠️ Auto-Kauf nicht ausgeführt: ${r.detail}"
    }
}

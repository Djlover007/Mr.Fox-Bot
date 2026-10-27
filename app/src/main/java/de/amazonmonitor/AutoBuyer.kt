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
 * Gesamtbetrag lesen (falls lesbar und > [maxTotal]: abbrechen; nicht lesbar: trotzdem weiter) →
 * „Jetzt kaufen“/„Bestellung aufgeben“ drücken → auf die Bestätigungsseite warten.
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
    private const val FIND_TOTAL_JS = """
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

    suspend fun buy(ctx: Context, domain: String, asin: String, maxTotal: Double, dryRun: Boolean): Result =
        mutex.withLock {
            withContext(Dispatchers.Main) {
                var placed = false
                withTimeoutOrNull(120_000L) {
                    execute(ctx, domain, asin, maxTotal, dryRun) { placed = true }
                } ?: if (placed) {
                    Result(Outcome.UNCLEAR, "Zeitüberschreitung nach dem Klick auf „Bestellen“")
                } else {
                    Result(Outcome.FAILED, "Zeitüberschreitung")
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun execute(
        ctx: Context, domain: String, asin: String, maxTotal: Double, dryRun: Boolean, onPlaced: () -> Unit
    ): Result = suspendCancellableCoroutine { cont ->
        val web = WebView(ctx.applicationContext)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        val handler = Handler(Looper.getMainLooper())

        // 0 = Produktseite lädt, 1 = „Jetzt kaufen“ gedrückt, 2 = Bestellung abgeschickt
        var stage = 0
        var polls = 0
        var noTotal = 0
        var total: Double? = null

        fun done(r: Result) {
            if (cont.isActive) {
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
                            if (stage == 2) done(Result(Outcome.UNCLEAR, "Anmeldung nach dem Bestellen verlangt", total))
                            else done(Result(Outcome.NOT_LOGGED_IN, "Amazon verlangt eine Anmeldung"))
                        "ready", "nototal" -> {
                            if (stage == 1) {
                                val t = if (o.optString("s") == "ready") Scraper.parsePrice(o.optString("total")) else null
                                if (t == null) noTotal++
                                // Ohne lesbaren Gesamtbetrag ein paar Sekunden warten (Seite lädt evtl. noch),
                                // dann trotzdem bestellen. Das Limit wurde vorher schon am Produktpreis geprüft.
                                val proceed = t != null || noTotal >= 4
                                when {
                                    !proceed -> {}
                                    t != null && t > maxTotal -> {
                                        done(Result(Outcome.ABORTED, "Gesamtbetrag ${money(t)} über Limit ${money(maxTotal)} – nicht bestellt", t))
                                        return@evaluateJavascript
                                    }
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
                    url.contains("/ap/") -> done(Result(Outcome.NOT_LOGGED_IN, "Amazon verlangt eine Anmeldung"))
                    url.contains("/dp/") -> {
                        stage = 1
                        view.evaluateJavascript(buyNowJs()) { res ->
                            if (!cont.isActive) return@evaluateJavascript
                            when {
                                res != null && res.contains("clicked") -> handler.postDelayed(poll, 2500)
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

        web.loadUrl("https://www.$domain/dp/$asin")
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

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
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Web-Shops (Müller, MediaMarkt …): in den Warenkorb legen und automatisch bestellen.
 *
 * Ablauf: Produktseite → „In den Warenkorb“ → Warenkorb → „Zur Kasse“ → ggf. Anmeldung mit dem
 * gespeicherten Zugang des Shops → „Weiter“-Schritte mit den voreingestellten Angaben →
 * „Zahlungspflichtig bestellen“/„Jetzt kaufen“ → Bestätigung abwarten.
 *
 * Achtung: Hier wird – anders als bei Amazons „Jetzt kaufen“ – der GANZE Warenkorb des Shops bestellt.
 * Die Knöpfe werden über ihre Beschriftung gefunden, weil die Shops keine festen Kennungen anbieten.
 */
object ShopBuyer {
    private enum class Mode { CART, BUY, DRY }

    private val mutex = Mutex()

    private val C = WebShops.COMMON_JS

    private fun addJs(shop: WebShop) = "(function(){" + C +
        "if(security())return 'security';if(!document.querySelector('h1'))return 'wait';" +
        "var b=btn(new RegExp(${JSONObject.quote(shop.addButtonRe)},'i'));if(b){b.click();return 'clicked';}return 'none';})()"

    private val ADDED_JS = "(function(){" + C +
        "var b=(document.body&&document.body.innerText)||'';" +
        "if(/in den warenkorb gelegt|zum warenkorb hinzugefügt|wurde hinzugefügt|artikel hinzugefügt/i.test(b))return 'ok';" +
        "if(btn(/^(zum warenkorb|warenkorb anzeigen)${'$'}/i))return 'ok';return 'no';})()"

    private fun cartHasJs(article: String) = "(function(){" + C +
        "if(security())return 'security';if(document.readyState!=='complete')return 'wait';" +
        "return document.documentElement.innerHTML.indexOf(${JSONObject.quote(article)})>=0?'yes':'no';})()"

    /**
     * Zusatzangebote ablehnen (Geräteschutz, Garantie, Versicherung): wählt „Ich möchte mein Gerät nicht
     * schützen“ o. Ä. Es wird nur der Auswahlknopf angeklickt, der zur Ablehn-Option selbst gehört
     * (kleinster Bereich mit genau einem Auswahlknopf) – nie der eines kostenpflichtigen Angebots.
     * Ergebnis: 'none' (kein Angebot), 'already' (Ablehnung ist ausgewählt), 'clicked' (Auswahl),
     * 'btn' („Nein, danke“-Knopf gedrückt).
     */
    private val DECLINE_JS = "(function(){" + C + """
var re=/ich möchte (mein gerät|meine geräte|das gerät|den artikel) nicht (schützen|absichern|versichern)|ohne (geräte)?schutz|keinen? (geräte)?schutz|keine (garantie|versicherung|absicherung|garantieverlängerung)|ohne (garantie|versicherung|absicherung)|nicht absichern|nein,? danke/i;
function radios(el){return el.querySelectorAll('input[type=radio],[role=radio]');}
var els=document.querySelectorAll('label,button,[role=radio],[role=button],span,div,p');
for(var i=0;i<els.length;i++){var e=els[i];if(e.children.length>4)continue;var t=txt(e);
 if(!t||t.length>90||!re.test(t))continue;
 // nur das kleinste passende Element (nicht den Bereich, der auch das Bezahl-Angebot enthält)
 var deeper=false;for(var c=0;c<e.children.length;c++){if(re.test(txt(e.children[c]))){deeper=true;break;}}
 if(deeper)continue;
 if(e.tagName==='BUTTON'||e.getAttribute('role')==='button'){if(!vis(e))continue;e.click();return 'btn';}
 var r=null;var box=e;for(var up=0;up<6&&box;up++){
  if(box.matches&&box.matches('input[type=radio],[role=radio]')){r=box;break;}
  var rs=radios(box);if(rs.length===1){r=rs[0];break;}if(rs.length>1)break;box=box.parentElement;}
 if(r&&(r.checked||r.getAttribute('aria-checked')==='true'))return 'already';
 if(!vis(e)&&!(r&&vis(r)))continue;
 (r||e).click();return 'clicked';}
return 'none';})()"""

    /**
     * Knopfsuche mit Vorrang für offene Fenster (Dialog/Overlay): Liegt z. B. ein Geräteschutz-Fenster über
     * dem Warenkorb, wird „Weiter“ im Fenster gefunden – nicht „Zur Kasse gehen“ dahinter.
     * EX = Beschriftungen, die schon mehrfach ohne Wirkung geklickt wurden (werden übersprungen).
     */
    private const val PICK_JS = """
function dlg(){var ds=document.querySelectorAll('[role=dialog],[aria-modal=true],dialog[open],[class*="modal" i],[class*="drawer" i],[class*="sheet" i],[class*="overlay" i],[class*="layer" i]');
 var best=null;for(var i=0;i<ds.length;i++){var d=ds[i];if(!vis(d))continue;
  if(d.querySelector('button,a,[role=button],input[type=submit]'))best=d;}return best;}
function pickIn(root,re,ex){var els=root.querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]');
 for(var i=0;i<els.length;i++){var e=els[i];var t=txt(e);if(!t||t.length>60||EX.indexOf(t)>=0)continue;
  if(re.test(t)&&!(ex&&ex.test(t))&&vis(e))return e;}return null;}
function roots(){var d=dlg();return d?[d,document]:[document];}
"""

    private val FINAL_RE = "/zahlungspflichtig bestellen|kostenpflichtig bestellen|zahlungspflichtig kaufen|jetzt kaufen|^kaufen${'$'}|kaufen und bezahlen|bestellung absenden|jetzt bestellen/i"
    private val NEXT_RE = "/^(weiter|weiter zu.*|weiter zur .*|zur zahlung|zur bestellübersicht|zur übersicht|bestellung prüfen|übernehmen|speichern und weiter|auswählen und weiter|bestätigen|fortfahren)${'$'}/i"

    private fun stepJs(exhausted: Collection<String>) = "(function(){" + C + AutoBuyer.FIND_TOTAL_JS + PICK_JS +
        "var EX=${org.json.JSONArray(exhausted)};" +
        "if(security())return JSON.stringify({s:'security'});" +
        "var body=(document.body&&document.body.innerText)||'';var h=location.href.toLowerCase();" +
        "var pw=document.querySelector('input[type=password]');if(pw&&vis(pw))return JSON.stringify({s:'login'});" +
        "var rs=roots();" +
        "for(var q=0;q<rs.length;q++){var R=rs[q];" +
        " var f=pickIn(R,$FINAL_RE,/einkaufen/i);" +
        " if(f)return JSON.stringify({s:'final',total:findTotal(document)||'',label:txt(f),hint:hintOf(document)});" +
        " if(q===0&&R!==document){var n0=pickIn(R,$NEXT_RE,/einkaufen|shoppen/i);if(n0)return JSON.stringify({s:'click',label:txt(n0)});}" +
        "}" +
        "if(/vielen dank für (ihre|deine) bestellung|(ihre|deine) bestellnummer|bestellung ist eingegangen/i.test(body)" +
        "||/success|danke|thank/.test(h))return JSON.stringify({s:'done'});" +
        "for(var q=0;q<rs.length;q++){var R=rs[q];" +
        " var k=pickIn(R,/^zur kasse/i);if(k)return JSON.stringify({s:'click',label:txt(k)});" +
        " var n=pickIn(R,$NEXT_RE,/einkaufen|shoppen/i);if(n)return JSON.stringify({s:'click',label:txt(n)});}" +
        "var a=pickIn(document,/^(anmelden|einloggen)${'$'}/i);if(a)return JSON.stringify({s:'needlogin',label:txt(a)});" +
        "return JSON.stringify({s:'wait',title:document.title});})()"

    /** Klickt den Knopf mit genau dieser Beschriftung – zuerst in einem offenen Fenster. */
    private fun clickTextJs(label: String) = "(function(){" + C + PICK_JS + "var EX=[];" +
        "var t=${JSONObject.quote(label)};var rs=roots();" +
        "for(var q=0;q<rs.length;q++){var els=rs[q].querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]');" +
        "for(var i=0;i<els.length;i++){var e=els[i];if(txt(e)===t&&vis(e)){e.click();return 'clicked';}}}return 'none';})()"

    private fun loginJs(email: String, pw: String) = "(function(){" + C +
        "function setv(el,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');" +
        "d.set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}" +
        "var m=document.querySelector('input[type=email],input[autocomplete=username],input[name*=mail i],input[id*=mail i]');" +
        "var p=document.querySelector('input[type=password]');" +
        "if(m&&!m.value)setv(m,${JSONObject.quote(email)});if(p)setv(p,${JSONObject.quote(pw)});" +
        "var b=btn(/^(anmelden|einloggen|login|weiter)${'$'}/i);" +
        "if(!b&&p&&p.form){b=p.form.querySelector('button[type=submit],input[type=submit]');}" +
        "if(b){b.click();return 'clicked';}return 'nobutton';})()"

    /** Legt das Produkt in den Warenkorb des Shops. */
    suspend fun addToCart(ctx: Context, shop: WebShop, url: String, productId: String): CartAdder.Result {
        val r = run(ctx, shop, url, productId, Mode.CART, visible = false)
        CartAdder.lastDetail = r.detail
        return when (r.outcome) {
            AutoBuyer.Outcome.DRY_RUN_OK -> CartAdder.Result.OK
            AutoBuyer.Outcome.NOT_LOGGED_IN -> CartAdder.Result.NOT_LOGGED_IN
            else -> CartAdder.Result.FAILED
        }
    }

    /** Auto-Kauf bzw. Probelauf ([dryRun]: bis zum Bestellknopf, ohne zu bestellen). */
    suspend fun buy(ctx: Context, shop: WebShop, url: String, productId: String, dryRun: Boolean, visible: Boolean): AutoBuyer.Result =
        run(ctx, shop, url, productId, if (dryRun) Mode.DRY else Mode.BUY, visible)

    private suspend fun run(ctx: Context, shop: WebShop, url: String, productId: String, mode: Mode, visible: Boolean): AutoBuyer.Result =
        mutex.withLock {
            withContext(Dispatchers.Main) {
                val watch = if (visible) WatchActivity.open(ctx) else null
                watch?.let { WebShops.setup(ctx, it.web) }
                var placed = false
                val r = withTimeoutOrNull(180_000L) {
                    execute(ctx, shop, watch, url, productId, mode) { placed = true }
                } ?: if (placed) {
                    AutoBuyer.Result(AutoBuyer.Outcome.UNCLEAR, "Zeitüberschreitung nach dem Klick auf „Bestellen“")
                } else {
                    AutoBuyer.Result(AutoBuyer.Outcome.FAILED, "Zeitüberschreitung")
                }
                watch?.finished(AutoBuyer.describe(r))
                r
            }
        }

    private suspend fun execute(
        ctx: Context, shop: WebShop, watch: WatchActivity?, url: String, productId: String, mode: Mode, onPlaced: () -> Unit
    ): AutoBuyer.Result = suspendCancellableCoroutine { cont ->
        val owned = watch == null
        val web = watch?.web ?: WebShops.hiddenWebView(ctx)
        val handler = Handler(Looper.getMainLooper())
        val article = WebShops.articleNumber(productId)
        val prefix = when (mode) {
            Mode.DRY -> "${shop.label}-Probelauf: "
            Mode.BUY -> "${shop.label}-Auto-Kauf: "
            Mode.CART -> "${shop.label}-Warenkorb: "
        }
        fun status(s: String) = watch?.setStatus(prefix + s)

        // 0 = Produktseite, 1 = Hinzufügen bestätigen, 2 = Warenkorb prüfen, 3 = Kasse, 4 = bestellt, warte auf Bestätigung
        var stage = 0
        var polls = 0
        var last = ""
        var cooldown = 0
        var loginTries = 0
        var stepClicks = 0
        val clicks = HashMap<String, Int>()
        var total: Double? = null
        var declineClicks = 0
        val exhausted = mutableSetOf<String>()
        var declineButtons = 0

        fun release() {
            handler.removeCallbacksAndMessages(null)
            watch?.onClosed = null
            if (owned) {
                web.stopLoading()
                web.destroy()
            }
        }

        fun done(r: AutoBuyer.Result) {
            if (cont.isActive) {
                cont.resume(r)
                release()
            }
        }

        cont.invokeOnCancellation { handler.post { release() } }
        watch?.onClosed = {
            if (stage == 4) done(AutoBuyer.Result(AutoBuyer.Outcome.UNCLEAR, "Fenster nach dem Bestell-Klick geschlossen", total))
            else done(AutoBuyer.Result(AutoBuyer.Outcome.FAILED, "Fenster geschlossen"))
        }

        fun nextStage(s: Int) {
            stage = s
            polls = 0
        }

        val tick = object : Runnable {
            fun again() = handler.postDelayed(this, 1500)

            override fun run() {
                if (!cont.isActive) return
                polls++
                when (stage) {
                    0 -> web.evaluateJavascript(addJs(shop)) { raw ->
                        if (!cont.isActive) return@evaluateJavascript
                        last = raw?.trim('"') ?: ""
                        when {
                            last == "clicked" -> {
                                status("„In den Warenkorb“ gedrückt …")
                                nextStage(1)
                            }
                            last == "none" && polls >= 6 -> {
                                done(AutoBuyer.Result(AutoBuyer.Outcome.FAILED, "Kein „In den Warenkorb“-Knopf (ausverkauft?)"))
                                return@evaluateJavascript
                            }
                            polls >= 25 -> {
                                done(
                                    if (last == "security") AutoBuyer.Result(AutoBuyer.Outcome.BLOCKED, "${shop.label}: Bot-/Sicherheitsprüfung nicht bestanden")
                                    else AutoBuyer.Result(AutoBuyer.Outcome.FAILED, "${shop.label}-Produktseite nicht erkannt")
                                )
                                return@evaluateJavascript
                            }
                        }
                        again()
                    }

                    1 -> {
                        // Manche Shops fragen direkt nach „In den Warenkorb“ nach Geräteschutz → ablehnen.
                        web.evaluateJavascript(DECLINE_JS) { d ->
                            if (d?.contains("clicked") == true) status("Wähle „Gerät nicht schützen“ …")
                        }
                        web.evaluateJavascript(ADDED_JS) { raw ->
                        if (!cont.isActive) return@evaluateJavascript
                        val ok = raw?.contains("ok") == true
                        if (ok && mode == Mode.CART) {
                            done(AutoBuyer.Result(AutoBuyer.Outcome.DRY_RUN_OK, "In den ${shop.label}-Warenkorb gelegt"))
                            return@evaluateJavascript
                        }
                        if (ok || polls >= 6) {
                            status("Öffne Warenkorb …")
                            web.loadUrl(shop.cartUrl)
                            nextStage(2)
                        }
                        again()
                        }
                    }

                    2 -> web.evaluateJavascript(cartHasJs(article)) { raw ->
                        if (!cont.isActive) return@evaluateJavascript
                        val yes = raw?.contains("yes") == true
                        when {
                            yes && mode == Mode.CART -> {
                                done(AutoBuyer.Result(AutoBuyer.Outcome.DRY_RUN_OK, "Im ${shop.label}-Warenkorb"))
                                return@evaluateJavascript
                            }
                            !yes && polls >= 8 && mode == Mode.CART -> {
                                done(AutoBuyer.Result(AutoBuyer.Outcome.FAILED, "Artikel nicht im ${shop.label}-Warenkorb gefunden"))
                                return@evaluateJavascript
                            }
                            // Kauf: auch ohne sichere Erkennung weiter – die Kasse zeigt, was im Warenkorb liegt.
                            yes || polls >= 8 -> {
                                status("Gehe zur Kasse …")
                                nextStage(3)
                            }
                        }
                        again()
                    }

                    3, 4 -> web.evaluateJavascript(if (stage == 3) DECLINE_JS else "'none'") { d ->
                        if (!cont.isActive) return@evaluateJavascript
                        // Erst Zusatzangebote (Geräteschutz) ablehnen und abwarten, bis die Ablehnung
                        // sicher ausgewählt ist – erst dann wird „Weiter“ gedrückt.
                        // „Nein, danke“-Knopf: höchstens zweimal drücken, dann normal weiter.
                        if (d?.contains("btn") == true && declineButtons < 2) {
                            declineButtons++
                            status("Lehne Zusatzangebot ab („Nein, danke“) …")
                            again()
                            return@evaluateJavascript
                        }
                        if (d?.contains("clicked") == true) {
                            declineClicks++
                            if (declineClicks > 4) {
                                done(AutoBuyer.Result(AutoBuyer.Outcome.FAILED, "Geräteschutz-Angebot ließ sich nicht abwählen – aus Sicherheit abgebrochen"))
                                return@evaluateJavascript
                            }
                            status("Wähle „Ich möchte mein Gerät nicht schützen“ …")
                            again()
                            return@evaluateJavascript
                        }
                        stepPoll()
                    }
                }
            }

            /** Kasse: nächsten Schritt erkennen und ausführen. */
            fun stepPoll() {
                web.evaluateJavascript(stepJs(exhausted)) { raw ->
                        if (!cont.isActive) return@evaluateJavascript
                        val o = WebShops.parseJs(raw)
                        val s = o.optString("s")
                        if (stage == 4) {
                            when {
                                s == "done" -> {
                                    done(AutoBuyer.Result(AutoBuyer.Outcome.ORDERED, "Bestellung bei ${shop.label} bestätigt", total))
                                    return@evaluateJavascript
                                }
                                polls >= 25 -> {
                                    done(AutoBuyer.Result(AutoBuyer.Outcome.UNCLEAR, "Keine Bestätigungsseite erkannt", total))
                                    return@evaluateJavascript
                                }
                            }
                            again()
                            return@evaluateJavascript
                        }
                        if (cooldown > 0) {
                            cooldown--
                            again()
                            return@evaluateJavascript
                        }
                        when (s) {
                            "final" -> {
                                val t = Scraper.parsePrice(o.optString("total"))
                                val label = o.optString("label")
                                if (mode == Mode.DRY) {
                                    val info = if (t != null) "Gesamtbetrag ${money(t)}" else
                                        "Gesamtbetrag nicht lesbar" + o.optString("hint").let { if (it.isNotEmpty()) " (gefunden: $it)" else "" }
                                    done(AutoBuyer.Result(AutoBuyer.Outcome.DRY_RUN_OK, "Bestellknopf „$label“ gefunden, $info – Probelauf, nichts bestellt", t))
                                    return@evaluateJavascript
                                }
                                total = t
                                onPlaced()
                                status("Bestellung wird abgeschickt …")
                                web.evaluateJavascript(clickTextJs(label), null)
                                nextStage(4)
                            }
                            "done" -> {
                                done(AutoBuyer.Result(AutoBuyer.Outcome.UNCLEAR, "Bestellbestätigung erschienen, ohne dass die App bestellt hat"))
                                return@evaluateJavascript
                            }
                            "login" -> {
                                val mail = Store.shopEmail(ctx, shop)
                                val pw = Store.shopPassword(ctx, shop)
                                when {
                                    mail.isEmpty() || pw == null -> {
                                        done(AutoBuyer.Result(AutoBuyer.Outcome.NOT_LOGGED_IN, "${shop.label} verlangt eine Anmeldung – ${shop.label}-Zugang in der App speichern"))
                                        return@evaluateJavascript
                                    }
                                    loginTries >= 2 -> {
                                        done(AutoBuyer.Result(AutoBuyer.Outcome.NOT_LOGGED_IN, "Anmeldung bei ${shop.label} hat nicht geklappt (Passwort prüfen)"))
                                        return@evaluateJavascript
                                    }
                                    else -> {
                                        loginTries++
                                        status("Melde bei ${shop.label} an …")
                                        web.evaluateJavascript(loginJs(mail, pw), null)
                                        cooldown = 3
                                    }
                                }
                            }
                            "click", "needlogin" -> {
                                val label = o.optString("label")
                                val n = clicks[label] ?: 0
                                val canLogin = Store.hasShopLogin(ctx, shop)
                                if (n >= 3) exhausted += label // wirkt nicht → künftig überspringen
                                if (n < 3 && stepClicks < 15 && (s == "click" || canLogin)) {
                                    clicks[label] = n + 1
                                    stepClicks++
                                    status("Klicke „$label“ …")
                                    web.evaluateJavascript(clickTextJs(label), null)
                                    cooldown = 2
                                }
                            }
                        }
                        if (s == "wait" || s == "security") last = o.optString("title", s)
                        if (polls >= 60) {
                            done(AutoBuyer.Result(AutoBuyer.Outcome.FAILED, "${shop.label}-Kasse nicht durchlaufen (hängt bei: ${last.ifEmpty { s }})"))
                            return@evaluateJavascript
                        }
                        again()
                    }
                }
            }

        status("Lade Produktseite …")
        web.webViewClient = WebShops.safeClient()
        web.loadUrl(url)
        handler.postDelayed(tick, 2500)
    }
}

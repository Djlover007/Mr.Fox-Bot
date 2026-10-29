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
 * Web-Shops (Müller, MediaMarkt, BAUR …): in den Warenkorb legen und automatisch bestellen.
 *
 * Ablauf: Produktseite → „In den Warenkorb“ → Warenkorb → „Zur Kasse“ → ggf. Anmeldung mit dem
 * gespeicherten Zugang des Shops → „Weiter“-Schritte mit den voreingestellten Angaben →
 * „Zahlungspflichtig bestellen“/„Jetzt kaufen“ → Bestätigung abwarten.
 *
 * Achtung: Hier wird – anders als bei Amazons „Jetzt kaufen“ – der GANZE Warenkorb des Shops bestellt.
 * Die Knöpfe werden über ihre Beschriftung gefunden, weil die Shops keine festen Kennungen anbieten.
 */
object ShopBuyer {
    /** Ablaufprotokoll des letzten Laufs (für den Seitenbericht im Fenster). */
    private val logLines = ArrayList<String>()
    private val logFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY)

    fun log(s: String) = synchronized(logLines) {
        logLines.add(logFmt.format(java.util.Date()) + " " + s.take(160))
        if (logLines.size > 60) logLines.removeAt(0)
    }

    /** Seitenberichte, die die App selbst unterwegs aufnimmt (z. B. Zahlungsseite ohne PayPal). */
    private val snapshots = ArrayList<String>()

    fun logText(): String = synchronized(logLines) {
        logLines.joinToString("\n") + snapshots.joinToString("") { "\n\n=== Automatisch aufgenommene Seite ===\n$it" }
    }

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
    /**
     * Zahlungsart PayPal auswählen, wenn angeboten. Nur Auswahlfelder (Radio) – nie einen
     * „PayPal-Express“-Knopf. Ergebnis: 'none' (kein PayPal), 'already' (ist gewählt), 'clicked'.
     */
    private val PAYPAL_SELECT_JS = "(function(){" + C + """
function lab(e){return e.tagName==='IMG'?(e.alt||''):txt(e);}
function radioText(r){var t='';if(r.id){var l=document.querySelector('label[for="'+r.id+'"]');if(l)t=txt(l)+' '+(l.querySelector('img[alt]')?l.querySelector('img[alt]').alt:'');}
 var L=r.closest('label');if(L)t+=' '+txt(L)+' '+(L.querySelector('img[alt]')?L.querySelector('img[alt]').alt:'');
 if(!t.trim()){var b=r.parentElement;for(var up=0;up<4&&b;up++){if(b.querySelectorAll('input[type=radio],[role=radio]').length===1){t=(b.innerText||'')+' '+[].map.call(b.querySelectorAll('img[alt]'),function(i){return i.alt;}).join(' ');}b=b.parentElement;}}
 if(r.getAttribute('aria-label'))t+=' '+r.getAttribute('aria-label');
 if(r.getAttribute('value'))t+=' '+r.getAttribute('value');
 return t.replace(/\s+/g,' ').trim();}
function on(r){return r.checked||r.getAttribute('aria-checked')==='true'||r.getAttribute('aria-selected')==='true';}
var rs=document.querySelectorAll('input[type=radio],[role=radio]');
var pp=null,list=[];
for(var i=0;i<rs.length;i++){var r=rs[i];var t=radioText(r);list.push((on(r)?'[x] ':'[ ] ')+t.slice(0,25));
 if(!pp&&/paypal/i.test(t)&&!/express/i.test(t))pp=r;}
if(!pp){
 // Kein Auswahlfeld: PayPal als anklickbare Kachel (z. B. BAUR). Nie Links in Kopf-/Fußzeile, nie „Express“.
 var cand=document.querySelectorAll('button,[role=button],[role=option],[role=tab],li,label,div,span');var best=null,bl=1e9;
 for(var k=0;k<cand.length;k++){var c=cand[k];if(c.closest('footer,header,nav'))continue;var a=c.closest('a[href]');
  if(a&&!/^(#|javascript:)/i.test(a.getAttribute('href')||''))continue;
  var ct=(txt(c)+' '+[].map.call(c.querySelectorAll('img[alt]'),function(i){return i.alt;}).join(' ')).replace(/\s+/g,' ').trim();
  if(!/paypal/i.test(ct)||/express|ratenzahlung|später bezahlen|pay later/i.test(ct)||ct.length>80||!vis(c))continue;
  var clk=c.tagName==='BUTTON'||c.tagName==='LABEL'||/^(button|option|tab)$/.test(c.getAttribute('role')||'')||getComputedStyle(c).cursor==='pointer';
  if(clk&&ct.length<bl){best=c;bl=ct.length;}}
 if(best){var sel=function(e){for(var u=0;u<4&&e;u++){if(e.getAttribute('aria-checked')==='true'||e.getAttribute('aria-selected')==='true'||e.getAttribute('aria-pressed')==='true'||/(^|[-_ ])(selected|active|checked)/i.test(e.getAttribute('class')||''))return true;e=e.parentElement;}return false;};
  if(sel(best))return 'already';best.click();return 'clicked-tile:'+txt(best).slice(0,30);}
 // PayPal versteckt (zugeklappter Bereich)? → den zugehörigen Aufklapp-Knopf drücken
 var hid=[].filter.call(document.querySelectorAll('body *:not(script):not(style)'),function(e){return e.children.length<3&&/paypal/i.test(e.textContent+' '+(e.alt||''))&&!/express/i.test(e.textContent)&&!e.closest('footer,header,nav,script')&&!vis(e);});
 if(hid.length){var tg=document.querySelectorAll('[aria-expanded=false],summary');
  for(var g=0;g<tg.length;g++){var x=tg[g];if(!vis(x))continue;var ctl=x.getAttribute('aria-controls');var reg=ctl?document.getElementById(ctl):(x.tagName==='SUMMARY'?x.parentElement:x.parentElement);
   if(reg&&/paypal/i.test(reg.textContent)){x.click();return 'expand:'+txt(x).slice(0,30);}}}
 var more=btn(/weitere zahlungsarten|andere zahlungsart|alle zahlungsarten|mehr zahlungsarten|zahlungsart wechseln|sofort bezahlen|direkt bezahlen|online bezahlen/i);
 if(more&&!/paypal express/i.test(txt(more))&&(!more.getAttribute('aria-expanded')||more.getAttribute('aria-expanded')==='false'))
  {more.click();return 'expand:'+txt(more).slice(0,30);}
 return 'none|'+list.join(' · ')+(hid.length?' (PayPal versteckt: '+hid.length+')':'');}
if(on(pp))return 'already';
// wie ein Finger: zuerst die Beschriftung antippen, sonst das Feld selbst
var l=(pp.id&&document.querySelector('label[for="'+pp.id+'"]'))||pp.closest('label');
if(l&&vis(l)){l.click();}else{pp.click();}
if(!on(pp))pp.click();
if(!on(pp)){pp.checked=true;pp.dispatchEvent(new Event('input',{bubbles:true}));pp.dispatchEvent(new Event('change',{bubbles:true}));}
return on(pp)?'clicked':'clicked-unconfirmed';})()"""

    /**
     * Übersichtsseite mit hinterlegter Zahlungsart („Zahlungsart: Rechnung [Ändern]“): ist es nicht PayPal,
     * im Zahlungs-Abschnitt auf „Ändern“ tippen, damit die Auswahl erscheint.
     * Ergebnis: 'paypal' (ist schon PayPal), 'clicked', 'none:<Text des Abschnitts>' oder 'none'.
     */
    private val PAYMENT_CHANGE_JS = "(function(){" + C + """
var head=/^(zahlungsart|zahlungsmethode|bezahlart|bezahlmethode|zahlung|zahlungsweise|deine zahlungsart|ihre zahlungsart)\s*:?$/i;
var chg=/^(ändern|bearbeiten|auswählen|zahlungsart ändern|zahlungsart auswählen|andere zahlungsart( wählen)?)$/i;
var other=/lieferadresse|lieferservice|rechnungsadresse|rechnungsanschrift/i;
function isEdit(b){var t=txt(b);if(t&&chg.test(t))return true;
 var al=((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('title')||'')+' '+(b.getAttribute('class')||'')+' '+(b.getAttribute('data-testid')||''));
 if(/bearbeit|ändern|edit|pencil|stift/i.test(al))return true;
 if(!t&&(b.tagName==='BUTTON'||b.tagName==='A'||b.getAttribute('role')==='button')&&b.querySelector('svg,img'))return true;
 // anklickbare Grafik ohne Text (z. B. Stift-Symbol als <div>/<span>/<svg> mit Mauszeiger „Hand“)
 if(!t&&(b.tagName==='svg'||b.tagName==='SVG'||b.tagName==='IMG'||b.querySelector('svg,img'))&&getComputedStyle(b).cursor==='pointer')return true;
 return false;}
function fire(b){var c=b.closest('button,a,[role=button]')||b;
 ['pointerdown','mousedown','pointerup','mouseup'].forEach(function(n){try{c.dispatchEvent(new (n.indexOf('pointer')===0&&window.PointerEvent?PointerEvent:MouseEvent)(n,{bubbles:true,cancelable:true}));}catch(x){}});
 if(typeof c.click==='function')c.click();else c.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));}
var eds=document.querySelectorAll('[data-testid=card-action-edit],[data-testid*=edit i]');
for(var q=0;q<eds.length;q++){var ed=eds[q];var cb=ed.parentElement;
 for(var w=0;w<6&&cb;w++){var ct=(cb.innerText||'').replace(/\s+/g,' ').trim();
  if(ct){if(other.test(ct)&&!/^zahlungsart/i.test(ct))break;
   if(/^(zahlungsart|zahlungsmethode|bezahlart)\b/i.test(ct)){if(/paypal/i.test(ct)&&!/paypal (express|checkout)/i.test(ct))return 'paypal';fire(ed);return 'clicked';}}
  cb=cb.parentElement;}}
var els=document.querySelectorAll('h1,h2,h3,h4,h5,legend,dt,span,div,p,strong,b,label');
for(var i=0;i<els.length;i++){var e=els[i];var t=txt(e);if(!t||t.length>30||!head.test(t))continue;
 var box=e.parentElement;
 for(var up=0;up<6&&box;up++){var bt=(box.innerText||'').replace(/\s+/g,' ');
  if(bt.length>600)break;
  if(/paypal/i.test(bt)&&!/paypal (express|checkout)/i.test(bt))return 'paypal';
  if(other.test(bt))break; // nicht in den Abschnitt Lieferadresse o. Ä. geraten
  var bs=box.querySelectorAll('button,a,[role=button],[aria-label],[title],svg,img,[class*="edit" i],[class*="pencil" i],div,span');
  for(var j=0;j<bs.length;j++){if(isEdit(bs[j])&&vis(bs[j])){var tg=bs[j];var ic=tg.querySelector&&tg.querySelector('svg,img');if(ic&&!txt(tg))tg=ic;fire(tg);return 'clicked';}}
  box=box.parentElement;}
 var pt=(e.parentElement&&e.parentElement.parentElement&&e.parentElement.parentElement.innerText||'').replace(/\s+/g,' ').trim().slice(0,80);
 return 'none:'+pt;}
return 'none';})()"""

    /** Auf der PayPal-Seite: Anmeldung nötig? Sonst „Jetzt zahlen“ / „Zustimmen und weiter“ drücken. */
    private val PAYPAL_PAY_JS = "(function(){" + C + """
var pw=document.querySelector('input[type=password]');
var em=document.querySelector('#email,input[name=login_email],input[type=email]');
var b=btn(/^(jetzt zahlen|zahlung abschließen|zustimmen und weiter|zustimmen und zahlen|weiter zur überprüfung|weiter|jetzt kaufen|kaufen|pay now|continue|complete purchase|agree (&|and) continue)$/i);
if((pw&&vis(pw))||(em&&vis(em)&&!em.value))return 'login';
if(b){b.click();return 'clicked:'+txt(b);}
return 'wait';})()"""

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

    private val FINAL_RE = "/zum genannten preis bestellen|jetzt zahlungspflichtig bestellen|bestellung abschicken|zahlungspflichtig bestellen|kostenpflichtig bestellen|zahlungspflichtig kaufen|jetzt kaufen|^kaufen${'$'}|kaufen und bezahlen|bestellung absenden|jetzt bestellen/i"
    private val NEXT_RE = "/^(weiter|weiter zu.*|weiter zur .*|zur zahlung|zur bestellübersicht|zur übersicht|bestellung prüfen|übernehmen|speichern und weiter|auswählen und weiter|bestätigen|fortfahren|speichern|auswahl speichern|zahlungsart übernehmen|zahlungsart speichern)${'$'}/i"

    /** Text des Zahlungs-Abschnitts (z. B. „Zahlungsart Ratenzahlung in 48 Monatsraten …“). */
    private const val PAY_SECTION_JS = """
function maxQty(){var b=(document.body&&document.body.innerText)||'';var re=/(anzahl|menge|stückzahl)\s*:?\s*(\d+)/gi;var m,mx=0;
 while((m=re.exec(b))!==null){var n=parseInt(m[2],10);if(n>mx&&n<100)mx=n;}
 var sels=document.querySelectorAll('select[name*=qty i],select[name*=quant i],select[name*=menge i],input[name*=qty i],input[name*=quant i]');
 for(var i=0;i<sels.length;i++){var n2=parseInt(sels[i].value,10);if(n2>mx&&n2<100)mx=n2;}
 return mx;}
function paySec(){var head=/^(zahlungsart|zahlungsmethode|bezahlart|bezahlmethode|zahlung|zahlungsweise)\s*:?$/i;
 var els=document.querySelectorAll('h1,h2,h3,h4,h5,legend,dt,span,div,p,strong,b,label');
 for(var i=0;i<els.length;i++){var e=els[i];var t=txt(e);if(!t||t.length>30||!head.test(t))continue;
  var box=e.parentElement;for(var up=0;up<5&&box;up++){var bt=(box.innerText||'').replace(/\s+/g,' ').trim();
   if(bt.length>t.length+8)return bt.slice(0,160);box=box.parentElement;}}
 return '';}
"""

    private fun stepJs(exhausted: Collection<String>) = "(function(){" + C + AutoBuyer.FIND_TOTAL_JS + PICK_JS + PAY_SECTION_JS +
        "var EX=${org.json.JSONArray(exhausted)};" +
        "if(security())return JSON.stringify({s:'security'});" +
        "var body=(document.body&&document.body.innerText)||'';var h=location.href.toLowerCase();" +
        "var pw=document.querySelector('input[type=password]');if(pw&&vis(pw))return JSON.stringify({s:'login'});" +
        "var rs=roots();" +
        "for(var q=0;q<rs.length;q++){var R=rs[q];" +
        " var f=pickIn(R,$FINAL_RE,/einkaufen/i);" +
        " if(!f&&R===document){var os=document.querySelector('[data-testid=order-submit],[data-testid*=order-submit i],button[name=placeOrder]');if(os&&vis(os))f=os;}" +
        " if(f)return JSON.stringify({s:'final',total:findTotal(document)||'',label:txt(f),hint:hintOf(document),pay:paySec(),qty:maxQty()});" +
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
        synchronized(logLines) { logLines.clear(); snapshots.clear() }
        val snapped = HashSet<String>()
        // Seite ohne PayPal-Auswahl einmal komplett beschreiben (hilft bei der Fehlersuche)
        fun snapshot() {
            val key = (web.title ?: "") + "|" + (web.url ?: "").substringBefore('?')
            if (snapped.size >= 3 || !snapped.add(key)) return
            web.evaluateJavascript(WatchActivity.REPORT_JS) { raw ->
                val t = try { org.json.JSONTokener(raw ?: "null").nextValue() as? String } catch (e: Exception) { null }
                if (t != null) synchronized(logLines) { snapshots.add(t) }
            }
        }
        log(prefix + url)
        var lastStatus = ""
        fun status(s: String) {
            if (s != lastStatus) { lastStatus = s; log(s) }
            watch?.setStatus(prefix + s)
        }

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
        var paypalClicks = 0
        var paypalState = "" // "", "gewählt", "nicht angeboten"
        var paypalPays = 0
        var paymentChanges = 0
        var paymentInfo = ""
        var paymentOptions = ""
        var lastStep = ""
        var expands = 0
        var payWait = 0 // nach Klick auf den Stift: so viele Runden nur auf die PayPal-Auswahl warten

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

                    // 9 = vorab im Warenkorb nachsehen: Liegt der Artikel schon drin, nicht noch einmal hinzufügen
                    9 -> web.evaluateJavascript(cartHasJs(article)) { raw ->
                        if (!cont.isActive) return@evaluateJavascript
                        val v = raw?.trim('"') ?: ""
                        when {
                            v == "yes" -> {
                                if (mode == Mode.CART) {
                                    done(AutoBuyer.Result(AutoBuyer.Outcome.DRY_RUN_OK, "Liegt schon im ${shop.label}-Warenkorb"))
                                    return@evaluateJavascript
                                }
                                status("Artikel liegt schon im Warenkorb – nicht noch einmal hinzufügen …")
                                nextStage(2)
                            }
                            (v == "no" && polls >= 3) || polls >= 10 -> {
                                status("Lade Produktseite …")
                                web.loadUrl(url)
                                nextStage(0)
                            }
                        }
                        again()
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
                        // Zahlungsart PayPal bevorzugen (wenn eingeschaltet und angeboten)
                        if (stage == 3 && Store.preferPaypal(ctx) && payWait > 0) {
                            web.evaluateJavascript(PAYPAL_SELECT_JS) { pp ->
                                if (!cont.isActive) return@evaluateJavascript
                                log("Warte auf Zahlungsauswahl (${payWait}): " + (pp ?: ""))
                                if (pp?.startsWith("\"none") == true && payWait <= 6) snapshot()
                                when {
                                    pp?.contains("expand") == true && expands < 3 -> {
                                        expands++
                                        status("Öffne weitere Zahlungsarten …")
                                        again()
                                    }
                                    pp?.contains("clicked") == true -> {
                                        payWait = 0
                                        paypalClicks++
                                        status("Wähle Zahlungsart PayPal …")
                                        again()
                                    }
                                    pp?.contains("already") == true -> {
                                        payWait = 0
                                        paypalState = "gewählt"
                                        stepPoll()
                                    }
                                    else -> {
                                        payWait--
                                        if (payWait == 0) {
                                            paymentOptions = pp?.trim('"')?.removePrefix("none|") ?: ""
                                            stepPoll()
                                        } else again()
                                    }
                                }
                            }
                            return@evaluateJavascript
                        }
                        if (stage == 3 && Store.preferPaypal(ctx) && paypalClicks < 3) {
                            web.evaluateJavascript(PAYPAL_SELECT_JS) { pp ->
                                if (!cont.isActive) return@evaluateJavascript
                                log("PayPal-Suche: " + (pp ?: ""))
                                if (pp?.startsWith("\"none") == true && (web.title ?: "").contains("zahl", true)) snapshot()
                                when {
                                    pp?.contains("expand") == true && expands < 3 -> {
                                        expands++
                                        status("Öffne weitere Zahlungsarten …")
                                        again()
                                    }
                                    pp?.contains("clicked") == true -> {
                                        paypalClicks++
                                        status("Wähle Zahlungsart PayPal …")
                                        again()
                                    }
                                    pp?.startsWith("\"none|") == true && paymentOptions.isEmpty() -> {
                                        paymentOptions = pp.trim('"').removePrefix("none|")
                                        // keine PayPal-Option sichtbar → evtl. erst „Ändern“/Stift nötig
                                        if (paymentChanges < 2) {
                                            web.evaluateJavascript(PAYMENT_CHANGE_JS) { pc ->
                                                if (!cont.isActive) return@evaluateJavascript
                                                val v = pc?.trim('"') ?: ""
                                                log("Stift Zahlungsart: $v")
                                                if (v == "clicked") {
                                                    paymentChanges++
                                                    payWait = 8
                                                    paymentOptions = ""
                                                    status("Zahlungsart ändern → PayPal …")
                                                    again()
                                                } else {
                                                    if (v == "paypal") paypalState = "gewählt"
                                                    if (v.startsWith("none:")) paymentInfo = v.removePrefix("none:")
                                                    stepPoll()
                                                }
                                            }
                                        } else stepPoll()
                                    }
                                    pp?.contains("already") == true -> {
                                        paypalState = "gewählt"
                                        stepPoll()
                                    }
                                    paymentChanges < 2 -> web.evaluateJavascript(PAYMENT_CHANGE_JS) { pc ->
                                        if (!cont.isActive) return@evaluateJavascript
                                        val v = pc?.trim('"') ?: ""
                                        log("Stift Zahlungsart: $v")
                                        when {
                                            v == "paypal" -> {
                                                paypalState = "gewählt"
                                                stepPoll()
                                            }
                                            v == "clicked" -> {
                                                paymentChanges++
                                                payWait = 8
                                                status("Zahlungsart ändern → PayPal …")
                                                again()
                                            }
                                            else -> {
                                                if (v.startsWith("none:")) paymentInfo = v.removePrefix("none:")
                                                stepPoll()
                                            }
                                        }
                                    }
                                    else -> stepPoll()
                                }
                            }
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
                        val stepText = o.toString().take(150)
                        if (stepText != lastStep) { lastStep = stepText; log("Schritt: $stepText") }
                        if (stage == 4) {
                            // Weiterleitung zu PayPal: „Jetzt zahlen“ drücken
                            val host = android.net.Uri.parse(web.url ?: "").host ?: ""
                            if (host.contains("paypal.")) {
                                web.evaluateJavascript(PAYPAL_PAY_JS) { r ->
                                    if (!cont.isActive) return@evaluateJavascript
                                    val v = r?.trim('"') ?: ""
                                    when {
                                        v == "login" -> {
                                            done(AutoBuyer.Result(AutoBuyer.Outcome.UNCLEAR, "PayPal verlangt eine Anmeldung – einmal „Bei PayPal anmelden (App)“; Bestellung evtl. offen, bitte prüfen", total))
                                            return@evaluateJavascript
                                        }
                                        v.startsWith("clicked") && paypalPays < 6 -> {
                                            paypalPays++
                                            polls = 0
                                            status("PayPal: „${v.removePrefix("clicked:")}“ …")
                                        }
                                    }
                                    again()
                                }
                                return@evaluateJavascript
                            }
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
                                // Sperre: nie mehr als 1 Stück bestellen (z. B. durch frühere Probeläufe im Warenkorb)
                                val qty = o.optInt("qty", 0)
                                if (qty > 1) {
                                    done(AutoBuyer.Result(AutoBuyer.Outcome.ABORTED,
                                        "Im ${shop.label}-Warenkorb liegen $qty Stück – aus Sicherheit nicht bestellt. " +
                                            "Bitte den Warenkorb auf 1 Stück reduzieren.", t))
                                    return@evaluateJavascript
                                }
                                // Sperre: nie mit Ratenzahlung/Finanzierung (Kredit) bestellen
                                val paySec = o.optString("pay")
                                if (Regex("(?i)ratenzahlung|monatsrate|finanzierung|ratenkauf|in \\d+ raten").containsMatchIn(paySec) &&
                                    !paySec.contains("paypal", ignoreCase = true)
                                ) {
                                    done(AutoBuyer.Result(AutoBuyer.Outcome.ABORTED,
                                        "Zahlungsart ist Ratenzahlung/Finanzierung („${paySec.take(70)}“) – aus Sicherheit nicht bestellt. " +
                                            "Bitte im ${shop.label}-Konto PayPal oder eine andere Standard-Zahlungsart einstellen." +
                                            (if (paymentOptions.isNotEmpty()) "\nGesehene Zahlungsarten: $paymentOptions" else "\nKeine Zahlungsarten-Auswahl gesehen") +
                                            " (Stift geklickt: ${paymentChanges}x, PayPal-Klicks: ${paypalClicks}x)", t))
                                    return@evaluateJavascript
                                }
                                if (mode == Mode.DRY) {
                                    val info = if (t != null) "Gesamtbetrag ${money(t)}" else
                                        "Gesamtbetrag nicht lesbar" + o.optString("hint").let { if (it.isNotEmpty()) " (gefunden: $it)" else "" }
                                    val pay = when {
                                        !Store.preferPaypal(ctx) -> ""
                                        paypalState == "gewählt" || paypalClicks > 0 -> ", Zahlung: PayPal ✅"
                                        else -> ", Zahlung: PayPal NICHT gewählt" +
                                            (if (paymentOptions.isNotEmpty()) " (Zahlungsarten: $paymentOptions)" else "") +
                                            (if (paymentInfo.isNotEmpty()) " (Zahlungs-Abschnitt: „$paymentInfo“)" else "")
                                    } + (if (paySec.isNotEmpty()) "\nÜbersicht: „${paySec.take(90)}“" else "")
                                    done(AutoBuyer.Result(AutoBuyer.Outcome.DRY_RUN_OK, "Bestellknopf „$label“ gefunden, $info$pay – Probelauf, nichts bestellt", t))
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
                            done(AutoBuyer.Result(AutoBuyer.Outcome.FAILED, "${shop.label}-Kasse nicht durchlaufen (hängt bei: ${last.ifEmpty { s }})" +
                                (if (paymentOptions.isNotEmpty()) "\nGesehene Zahlungsarten: $paymentOptions" else "") +
                                (if (exhausted.isNotEmpty()) "\nOhne Wirkung geklickt: ${exhausted.joinToString()}" else "")))
                            return@evaluateJavascript
                        }
                        again()
                    }
                }
            }

        status("Schaue in den Warenkorb …")
        web.webViewClient = WebShops.safeClient()
        stage = 9
        web.loadUrl(shop.cartUrl)
        handler.postDelayed(tick, 2500)
    }
}

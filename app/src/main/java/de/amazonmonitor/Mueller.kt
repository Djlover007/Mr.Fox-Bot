package de.amazonmonitor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Müller (mueller.de): Links erkennen und Seiten auslesen.
 *
 * Müller schaltet eine JavaScript-„Sicherheitsprüfung“ vor die Seiten. Deshalb werden die Seiten
 * nicht wie bei Amazon per einfachem Abruf, sondern in einem (unsichtbaren) Browserfenster geladen.
 */
object Mueller {
    const val BASE = "https://www.mueller.de"
    const val CART_URL = "$BASE/cart/"
    const val ACCOUNT_URL = "$BASE/my-account/"

    /** Produkt-Link: /p/<name>-<ID>/, die ID ist z. B. PPN3115411, IPN2958541 oder 2552002. */
    private val PRODUCT_RE = Regex("""mueller\.de/p/([^\s?#]*?-((?:[A-Za-z]{3})?\d{6,8}))/?""")
    private val CATEGORY_RE = Regex("""mueller\.de/(c/[^\s?#]+?)/?(?:[?#\s]|$)""")

    data class Link(val kind: String, val id: String, val url: String)

    /** Erkennt einen Müller-Produkt- oder Kategorie-Link. */
    fun parse(input: String): Link? {
        val s = input.trim()
        PRODUCT_RE.find(s)?.let {
            val id = it.groupValues[2].uppercase()
            return Link(Product.KIND_PRODUCT, "MUE-$id", "$BASE/p/${it.groupValues[1]}/")
        }
        CATEGORY_RE.find(s)?.let {
            val path = it.groupValues[1].trimEnd('/')
            return Link(Product.KIND_CATEGORY, "MUEC-$path", "$BASE/$path/")
        }
        return null
    }

    /** Müller-Artikelnummer aus einer Produkt-ID wie „MUE-PPN3115411“ → „3115411“ (für die Warenkorb-Prüfung). */
    fun articleNumber(productId: String): String = Regex("""\d{6,8}""").find(productId)?.value ?: productId

    /** Browser-Kennung ohne „wv“, damit die Seite uns wie Chrome behandelt. */
    fun chromeLikeUa(ctx: Context): String =
        WebSettings.getDefaultUserAgent(ctx).replace("; wv", "").replace(Regex("""Version/\S+ """), "")

    @SuppressLint("SetJavaScriptEnabled")
    fun setup(ctx: Context, web: WebView) {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = chromeLikeUa(ctx)
    }

    /** Unsichtbares Browserfenster mit Handy-Bildschirmgröße (sonst hat die Seite keine Fläche). */
    fun hiddenWebView(ctx: Context): WebView = WebView(ctx.applicationContext).apply {
        setup(ctx, this)
        layout(0, 0, 1080, 2400)
    }

    /** Gemeinsame JS-Hilfsfunktionen: Text, Sichtbarkeit, Knopf per Beschriftung, Sicherheitsprüfung. */
    const val COMMON_JS = """
function txt(e){return ((e.innerText||e.value||e.getAttribute('aria-label')||'')+'').replace(/\s+/g,' ').trim();}
function vis(e){if(e.disabled||e.getAttribute('aria-disabled')==='true')return false;var s=getComputedStyle(e);
 if(s.display==='none'||s.visibility==='hidden')return false;return e.offsetParent!==null||s.position==='fixed';}
function btn(re,ex){var els=document.querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]');
 for(var i=0;i<els.length;i++){var e=els[i];var t=txt(e);if(!t||t.length>60)continue;
  if(re.test(t)&&!(ex&&ex.test(t))&&vis(e))return e;}return null;}
function security(){var b=(document.body&&document.body.innerText)||'';
 return b.indexOf('Sicherheitsprüfung')>=0&&b.length<4000;}
"""

    private const val PRODUCT_JS = "(function(){" + COMMON_JS + """
if(security())return JSON.stringify({s:'security'});
var h1=document.querySelector('h1');if(!h1)return JSON.stringify({s:'wait'});
var r={s:'ok',n:txt(h1)};
var lds=document.querySelectorAll('script[type="application/ld+json"]');
for(var i=0;i<lds.length;i++){try{var j=JSON.parse(lds[i].textContent);var arr=Array.isArray(j)?j:(j['@graph']||[j]);
 for(var k=0;k<arr.length;k++){var o=arr[k];if(o&&(o['@type']==='Product'||(Array.isArray(o['@type'])&&o['@type'].indexOf('Product')>=0))){
  if(o.name)r.n=o.name;var of=o.offers;if(Array.isArray(of))of=of[0];
  if(of){if(of.price!=null)r.p=String(of.price);else if(of.lowPrice!=null)r.p=String(of.lowPrice);
   if(of.availability)r.av=String(of.availability);}}}}catch(e){}}
if(!r.p){var re=/(\d{1,3}(?:\.\d{3})*,\d{2})\s*€/;var c=h1;
 for(var up=0;up<7&&c;up++){var m=(c.innerText||'').match(re);if(m){r.p=m[1];break;}c=c.parentElement;}}
r.cart=!!btn(/^in den warenkorb$/i);
return JSON.stringify(r);})()"""

    private const val CATEGORY_JS = "(function(){" + COMMON_JS + """
if(security())return JSON.stringify({s:'security'});
var out=[];var seen={};var as=document.querySelectorAll('a[href*="/p/"]');
for(var i=0;i<as.length;i++){var a=as[i];var href=a.href.split('?')[0].split('#')[0];if(seen[href])continue;
 var t=a,tile=null;for(var up=0;up<8&&t;up++){if(/\d+,\d{2}\s*€/.test(t.innerText||'')){tile=t;break;}t=t.parentElement;}
 if(!tile)continue;
 var hs={};var ls=tile.querySelectorAll('a[href*="/p/"]');for(var x=0;x<ls.length;x++)hs[ls[x].href.split('?')[0].split('#')[0]]=1;
 if(Object.keys(hs).length>1)continue;
 seen[href]=1;
 var tx=(tile.innerText||'').replace(/\s+/g,' ');var low=tx.toLowerCase();
 var m=tx.match(/(\d{1,3}(?:\.\d{3})*,\d{2})\s*€/);
 var name='';var lines=(a.innerText||'').split('\n');
 for(var l=0;l<lines.length;l++){var s=lines[l].trim();if(s&&s.indexOf('€')<0&&s.length>3){name=s;break;}}
 if(!name){var imgs=tile.querySelectorAll('img[alt]');for(var g=0;g<imgs.length;g++){var al=imgs[g].alt||'';
  if(al&&al.indexOf('Wunschliste')<0){name=al;break;}}}
 var sold=low.indexOf('ausverkauft')>=0;
 out.push({u:href,n:name.slice(0,100),p:m?m[1]:'',a:!sold&&(low.indexOf('online verfügbar')>=0||low.indexOf('lieferbar')>=0)});}
if(!out.length)return JSON.stringify({s:'wait'});
return JSON.stringify({s:'ok',items:out});})()"""

    data class CatItem(val url: String, val name: String, val price: Double?, val available: Boolean)

    /** Ergebnis von evaluateJavascript (JSON-String in Anführungszeichen) in ein JSONObject wandeln. */
    fun parseJs(raw: String?): JSONObject = try {
        val s = JSONTokener(raw ?: "null").nextValue() as? String
        if (s == null) JSONObject() else JSONObject(s)
    } catch (e: Exception) {
        JSONObject()
    }

    /**
     * Lädt [url] unsichtbar, wartet die Sicherheitsprüfung ab und führt [js] so lange aus,
     * bis es {s:'ok'} meldet. Wirft [Scraper.BlockedException], wenn die Prüfung nicht durchgeht.
     */
    private suspend fun extract(ctx: Context, url: String, js: String, timeoutMs: Long = 45_000L): JSONObject =
        withContext(Dispatchers.Main) {
            var lastState = ""
            val res = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<JSONObject> { cont ->
                    val web = hiddenWebView(ctx)
                    val handler = Handler(Looper.getMainLooper())
                    fun finish(o: JSONObject) {
                        if (!cont.isActive) return
                        handler.removeCallbacksAndMessages(null)
                        cont.resume(o)
                        web.stopLoading()
                        web.destroy()
                    }
                    cont.invokeOnCancellation {
                        handler.post {
                            handler.removeCallbacksAndMessages(null)
                            web.stopLoading()
                            web.destroy()
                        }
                    }
                    val poll = object : Runnable {
                        override fun run() {
                            if (!cont.isActive) return
                            web.evaluateJavascript(js) { raw ->
                                if (!cont.isActive) return@evaluateJavascript
                                val o = parseJs(raw)
                                lastState = o.optString("s")
                                if (lastState == "ok") finish(o) else handler.postDelayed(this, 1500)
                            }
                        }
                    }
                    web.webViewClient = object : WebViewClient() {}
                    web.loadUrl(url)
                    handler.postDelayed(poll, 2500)
                }
            }
            res ?: if (lastState == "security") {
                throw Scraper.BlockedException("Müller-Sicherheitsprüfung nicht bestanden")
            } else {
                throw IllegalStateException("Müller-Seite nicht erkannt (Aufbau geändert?)")
            }
        }

    /** Produktseite: Name, Preis, Verfügbarkeit (Knopf „In den Warenkorb“ bzw. Angabe im Seiten-Datenblock). */
    suspend fun fetchProduct(ctx: Context, url: String): Scraper.Result {
        val o = extract(ctx, url, PRODUCT_JS)
        val av = o.optString("av")
        val available = when {
            av.contains("InStock", true) || av.contains("LimitedAvailability", true) -> true
            av.contains("OutOfStock", true) || av.contains("SoldOut", true) -> false
            else -> o.optBoolean("cart")
        }
        val price = o.optString("p").takeIf { it.isNotEmpty() }?.let { p ->
            // Seiten-Datenblock liefert „469.00“, die Anzeige „469,00“.
            if (p.contains(',')) Scraper.parsePrice(p) else p.toDoubleOrNull() ?: Scraper.parsePrice(p)
        }
        return Scraper.Result(o.optString("n").ifEmpty { url }, price, available, null, "Müller")
    }

    /** Kategorieseite: alle Produktkacheln (erste Seite). */
    suspend fun fetchCategory(ctx: Context, url: String): List<CatItem> {
        val o = extract(ctx, url, CATEGORY_JS)
        val arr = o.optJSONArray("items") ?: JSONArray()
        return List(arr.length()) { i ->
            val it = arr.getJSONObject(i)
            CatItem(
                it.getString("u"),
                it.optString("n").ifEmpty { it.getString("u") },
                Scraper.parsePrice(it.optString("p")),
                it.optBoolean("a"),
            )
        }
    }
}

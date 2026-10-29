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
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Ein Web-Shop, der im (unsichtbaren) Browserfenster der App gelesen und bedient wird
 * (Müller, MediaMarkt, BAUR …). Amazon läuft separat (Scraper/AutoBuyer).
 *
 * Neue Shops lassen sich hier mit einer weiteren Konfiguration ergänzen.
 */
class WebShop(
    /** Interner Schlüssel, steht in Product.shop. */
    val key: String,
    val label: String,
    val emoji: String,
    val base: String,
    val cartUrl: String,
    val accountUrl: String,
    /** Präfix für den gespeicherten Zugang (Store). */
    val credPrefix: String,
    /** Präfix der Produkt-/Kategorie-IDs in der Liste. */
    val idPrefix: String,
    /** Produkt-Link: Gruppe 1 = Pfadteil, Gruppe 2 = Artikelnummer. */
    private val productRe: Regex,
    private val productUrl: (String) -> String,
    /** Kategorie-Link: Gruppe 1 = Pfad. */
    private val categoryRe: Regex,
    private val categoryUrl: (String) -> String,
    /** CSS-Selektor für Produkt-Links in Kategorie-Kacheln. */
    val tileLinkSelector: String,
    /** JS-RegExp (Quelltext) für den „In den Warenkorb“-Knopf. */
    val addButtonRe: String,
    /** Wörter (klein), die in einer Kachel „nicht verfügbar“ bedeuten. */
    val soldWords: List<String>,
    /** Wörter (klein), die in einer Kachel „verfügbar“ bedeuten. */
    val availWords: List<String>,
    /** URL-Parameter, die zum Produkt gehören und erhalten bleiben (z. B. BAUR „sku“ = Variante). */
    val keepParams: List<String> = emptyList(),
) {
    fun matches(input: String): Boolean = productRe.containsMatchIn(input) || categoryRe.containsMatchIn(input)

    fun parse(input: String): WebShops.Link? {
        val s = input.trim()
        productRe.find(s)?.let {
            // Varianten-Parameter (z. B. sku) übernehmen: gehören zur Kennung und zum Link
            val kept = keepParams.mapNotNull { k ->
                Regex("[?&]" + Regex.escape(k) + "=([^&#\\s]+)").find(s)?.let { m -> k to m.groupValues[1] }
            }
            val id = "$idPrefix-${it.groupValues[2].uppercase()}" + kept.joinToString("") { (_, v) -> "-$v" }
            val url = productUrl(it.groupValues[1]) +
                if (kept.isEmpty()) "" else "?" + kept.joinToString("&") { (k, v) -> "$k=$v" }
            return WebShops.Link(Product.KIND_PRODUCT, id, url)
        }
        categoryRe.find(s)?.let {
            val path = it.groupValues[1].trimEnd('/')
            return WebShops.Link(Product.KIND_CATEGORY, "${idPrefix}C-$path", categoryUrl(path))
        }
        return null
    }
}

object WebShops {
    val MUELLER = WebShop(
        key = Product.SHOP_MUELLER, label = "Müller", emoji = "🟠",
        base = "https://www.mueller.de",
        cartUrl = "https://www.mueller.de/cart/",
        accountUrl = "https://www.mueller.de/my-account/",
        credPrefix = "mue", idPrefix = "MUE",
        // /p/<name>-<ID>/, ID z. B. PPN3115411, IPN2958541 oder 2552002
        productRe = Regex("""mueller\.de/p/([^\s?#]*?-((?:[A-Za-z]{3})?\d{6,8}))/?"""),
        productUrl = { "https://www.mueller.de/p/$it/" },
        categoryRe = Regex("""mueller\.de/(c/[^\s?#]+?)/?(?:[?#\s]|$)"""),
        categoryUrl = { "https://www.mueller.de/$it/" },
        tileLinkSelector = "a[href*=\"/p/\"]",
        addButtonRe = "^in den warenkorb$",
        soldWords = listOf("ausverkauft", "nicht verfügbar"),
        availWords = listOf("online verfügbar", "lieferbar"),
    )

    val MEDIAMARKT = WebShop(
        key = Product.SHOP_MEDIAMARKT, label = "MediaMarkt", emoji = "🔵",
        base = "https://www.mediamarkt.de",
        cartUrl = "https://www.mediamarkt.de/checkout",
        accountUrl = "https://www.mediamarkt.de/de/myaccount",
        credPrefix = "mm", idPrefix = "MM",
        // /de/product/_<name>-<Artikelnummer>.html
        productRe = Regex("""mediamarkt\.de/de/product/([^\s?#]*?-(\d{6,9}))\.html"""),
        productUrl = { "https://www.mediamarkt.de/de/product/$it.html" },
        categoryRe = Regex("""mediamarkt\.de/de/category/([^\s?#]+?)\.html"""),
        categoryUrl = { "https://www.mediamarkt.de/de/category/$it.html" },
        tileLinkSelector = "a[href*=\"/product/\"]",
        addButtonRe = "^(in den warenkorb|in den warenkorb legen|zum warenkorb hinzufügen)$",
        soldWords = listOf("nicht verfügbar", "nicht lieferbar", "ausverkauft", "nicht möglich", "online nicht"),
        availWords = listOf("lieferung", "lieferbar", "online verfügbar", "versand", "abholung"),
    )

    val BAUR = WebShop(
        key = Product.SHOP_BAUR, label = "BAUR", emoji = "🟢",
        base = "https://www.baur.de",
        cartUrl = "https://www.baur.de/warenkorb",
        accountUrl = "https://www.baur.de/mein-konto/uebersicht",
        credPrefix = "baur", idPrefix = "BAUR",
        // /p/AKLBB2161998740?sku=6345532920-0-2161998740 (oder /p/<name>-<Nummer>/)
        productRe = Regex("""baur\.de/p/((?:[^\s?#/]*?-)?([A-Za-z]{0,8}\d{6,}))/?(?:[?#\s]|$)"""),
        productUrl = { "https://www.baur.de/p/$it" },
        keepParams = listOf("sku"),
        // Kategorien: /bereich/unterbereich/ (alles außer Produkt-, Konto- und Warenkorbseiten)
        categoryRe = Regex("""baur\.de/((?!p/|warenkorb|mein-konto|merkzettel|service-hilfe)[a-z0-9-]+(?:/[a-z0-9-]+)*)/?(?:[?#\s]|$)"""),
        categoryUrl = { "https://www.baur.de/$it/" },
        tileLinkSelector = "a[href*=\"/p/\"]",
        addButtonRe = "^(in den warenkorb|in den warenkorb legen)$",
        soldWords = listOf("ausverkauft", "nicht lieferbar", "nicht verfügbar", "derzeit nicht", "bald wieder"),
        availWords = listOf("lieferbar", "sofort", "werktag", "auf lager", "verfügbar"),
    )

    val all = listOf(MUELLER, MEDIAMARKT, BAUR)

    fun byKey(key: String): WebShop? = all.firstOrNull { it.key == key }

    /** Welcher Web-Shop passt zu diesem Link? */
    fun detect(input: String): WebShop? = all.firstOrNull { it.matches(input) }

    data class Link(val kind: String, val id: String, val url: String)

    data class CatItem(val url: String, val name: String, val price: Double?, val available: Boolean)

    /** Artikelnummer aus einer Produkt-ID wie „MUE-PPN3115411“ → „3115411“ (für die Warenkorb-Prüfung). */
    fun articleNumber(productId: String): String = Regex("""\d{6,9}""").find(productId)?.value ?: productId

    /**
     * Browser einrichten. Die Browser-Kennung bleibt bewusst die echte des Android-Browsers:
     * Eine verstellte Kennung (z. B. „Windows-PC“) passt nicht zu den übrigen Angaben des Handys,
     * und Bot-Prüfungen wie Cloudflare lehnen dann ab („Überprüfung fehlgeschlagen“).
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun setup(@Suppress("UNUSED_PARAMETER") ctx: Context, web: WebView) {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Pop-ups (z. B. PayPal) im selben Fenster öffnen
        web.settings.javaScriptCanOpenWindowsAutomatically = true
        web.settings.setSupportMultipleWindows(false)
        web.settings.userAgentString = null // = Standard-Kennung des Android-Browsers
        // Bot-Prüfungen (Cloudflare Turnstile) laufen in einem eingebetteten Fenster und brauchen Cookies.
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
    }

    /**
     * App-Links (intent://, market://, payback:// …) kann das App-Fenster nicht öffnen.
     * Gibt true zurück, wenn [url] abgefangen wurde: mit Web-Ersatzadresse wird diese geladen,
     * sonst bleibt die Seite einfach stehen (statt „Webseite nicht verfügbar“).
     */
    fun handleAppLink(view: WebView, url: String?): Boolean {
        if (url == null) return false
        val scheme = url.substringBefore(':').lowercase()
        if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob") return false
        if (scheme == "intent") {
            val fallback = try {
                android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME)
                    .getStringExtra("browser_fallback_url")
            } catch (e: Exception) {
                null
            }
            if (fallback != null && fallback.startsWith("http")) view.post { view.loadUrl(fallback) }
        }
        return true
    }

    /** WebViewClient, der App-Links abfängt. */
    fun safeClient(onFinished: ((WebView, String) -> Unit)? = null) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
            handleAppLink(view, request.url?.toString())

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            onFinished?.invoke(view, url)
        }
    }

    /** Unsichtbares Browserfenster mit Handy-Bildschirmgröße (sonst hat die Seite keine Fläche). */
    fun hiddenWebView(ctx: Context): WebView = WebView(ctx.applicationContext).apply {
        setup(ctx, this)
        layout(0, 0, 1080, 2400)
    }

    /** Gemeinsame JS-Hilfsfunktionen: Text, Sichtbarkeit, Knopf per Beschriftung, Bot-/Sicherheitsprüfung. */
    const val COMMON_JS = """
function txt(e){return ((e.innerText||e.value||e.getAttribute('aria-label')||'')+'').replace(/\s+/g,' ').trim();}
function vis(e){if(e.disabled||e.getAttribute('aria-disabled')==='true')return false;var s=getComputedStyle(e);
 if(s.display==='none'||s.visibility==='hidden')return false;return e.offsetParent!==null||s.position==='fixed';}
function btn(re,ex){var els=document.querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]');
 for(var i=0;i<els.length;i++){var e=els[i];var t=txt(e);if(!t||t.length>60)continue;
  if(re.test(t)&&!(ex&&ex.test(t))&&vis(e))return e;}return null;}
function security(){var b=(document.body&&document.body.innerText)||'';if(b.length>=4000)return false;
 var w=['Sicherheitsprüfung','Just a moment','Einen Moment','Checking your browser','Verify you are human',
  'Bestätigen Sie, dass Sie ein Mensch sind','Überprüfung, ob','Access Denied','Zugriff verweigert'];
 for(var i=0;i<w.length;i++)if(b.indexOf(w[i])>=0)return true;return false;}
var PRICE_RE=/(\d{1,3}(?:\.\d{3})*,(?:\d{2}|[–-]+))/;
"""

    private fun productJs(shop: WebShop) = "(function(){" + COMMON_JS +
        "var ADD=new RegExp(${JSONObject.quote(shop.addButtonRe)},'i');" + """
if(security())return JSON.stringify({s:'security'});
var h1=document.querySelector('h1');if(!h1)return JSON.stringify({s:'wait'});
var r={s:'ok',n:txt(h1)};
var lds=document.querySelectorAll('script[type="application/ld+json"]');
for(var i=0;i<lds.length;i++){try{var j=JSON.parse(lds[i].textContent);var arr=Array.isArray(j)?j:(j['@graph']||[j]);
 for(var k=0;k<arr.length;k++){var o=arr[k];if(o&&(o['@type']==='Product'||(Array.isArray(o['@type'])&&o['@type'].indexOf('Product')>=0))){
  if(o.name)r.n=o.name;var of=o.offers;if(Array.isArray(of))of=of[0];
  if(of){if(of.price!=null)r.p=String(of.price);else if(of.lowPrice!=null)r.p=String(of.lowPrice);
   if(of.availability)r.av=String(of.availability);}}}}catch(e){}}
if(!r.p){var c=h1;for(var up=0;up<7&&c;up++){var t=c.innerText||'';if(t.indexOf('€')>=0){var m=t.match(PRICE_RE);if(m){r.p=m[1];break;}}c=c.parentElement;}}
r.cart=!!btn(ADD);
return JSON.stringify(r);})()"""

    private fun categoryJs(shop: WebShop) = "(function(){" + COMMON_JS +
        "var LINK=${JSONObject.quote(shop.tileLinkSelector)};" +
        "var SOLD=${JSONArray(shop.soldWords)};var AV=${JSONArray(shop.availWords)};" + """
if(security())return JSON.stringify({s:'security'});
function has(low,ws){for(var i=0;i<ws.length;i++)if(low.indexOf(ws[i])>=0)return true;return false;}
function clean(h){return h.split('?')[0].split('#')[0];}
var out=[];var seen={};var as=document.querySelectorAll(LINK);
for(var i=0;i<as.length;i++){var a=as[i];var href=clean(a.href);if(seen[href])continue;
 var t=a,tile=null;for(var up=0;up<8&&t;up++){var it=t.innerText||'';if(it.indexOf('€')>=0&&PRICE_RE.test(it)){tile=t;break;}t=t.parentElement;}
 if(!tile)continue;
 var hs={};var ls=tile.querySelectorAll(LINK);for(var x=0;x<ls.length;x++)hs[clean(ls[x].href)]=1;
 if(Object.keys(hs).length>1)continue;
 seen[href]=1;
 var tx=(tile.innerText||'').replace(/\s+/g,' ');var low=tx.toLowerCase();var m=tx.match(PRICE_RE);
 var name='';var lines=(a.innerText||'').split('\n');
 for(var l=0;l<lines.length;l++){var s=lines[l].trim();if(s&&s.indexOf('€')<0&&s.length>3){name=s;break;}}
 if(!name){var imgs=tile.querySelectorAll('img[alt]');for(var g=0;g<imgs.length;g++){var al=imgs[g].alt||'';
  if(al&&al.indexOf('Wunschliste')<0&&al.length>3){name=al;break;}}}
 if(!name)name=a.getAttribute('title')||a.getAttribute('aria-label')||'';
 var sold=has(low,SOLD);
 out.push({u:href,n:name.slice(0,100),p:m?m[1]:'',a:!sold&&has(low,AV)});}
if(!out.length)return JSON.stringify({s:'wait'});
return JSON.stringify({s:'ok',items:out});})()"""

    /** Ergebnis von evaluateJavascript (JSON-String in Anführungszeichen) in ein JSONObject wandeln. */
    fun parseJs(raw: String?): JSONObject = try {
        val s = JSONTokener(raw ?: "null").nextValue() as? String
        if (s == null) JSONObject() else JSONObject(s)
    } catch (e: Exception) {
        JSONObject()
    }

    /**
     * Lädt [url] unsichtbar, wartet eine Bot-/Sicherheitsprüfung ab und führt [js] so lange aus,
     * bis es {s:'ok'} meldet. Wirft [Scraper.BlockedException], wenn die Prüfung nicht durchgeht.
     */
    private suspend fun extract(ctx: Context, shop: WebShop, url: String, js: String, timeoutMs: Long = 45_000L): JSONObject =
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
                    web.webViewClient = safeClient()
                    web.loadUrl(url)
                    handler.postDelayed(poll, 2500)
                }
            }
            res ?: if (lastState == "security") {
                throw Scraper.BlockedException("${shop.label}: Bot-/Sicherheitsprüfung nicht bestanden")
            } else {
                throw IllegalStateException("${shop.label}-Seite nicht erkannt (Aufbau geändert?)")
            }
        }

    /** Produktseite: Name, Preis, Verfügbarkeit (Seiten-Datenblock bzw. „In den Warenkorb“-Knopf). */
    suspend fun fetchProduct(ctx: Context, shop: WebShop, url: String): Scraper.Result {
        val o = extract(ctx, shop, url, productJs(shop))
        val av = o.optString("av")
        val available = when {
            av.contains("InStock", true) || av.contains("LimitedAvailability", true) -> true
            av.contains("OutOfStock", true) || av.contains("SoldOut", true) || av.contains("Discontinued", true) -> false
            else -> o.optBoolean("cart")
        }
        val price = o.optString("p").takeIf { it.isNotEmpty() }?.let { p ->
            // Seiten-Datenblock liefert „469.00“, die Anzeige „469,00“ bzw. „499,–“.
            if (p.contains(',')) Scraper.parsePrice(p) else p.toDoubleOrNull() ?: Scraper.parsePrice(p)
        }
        return Scraper.Result(o.optString("n").ifEmpty { url }, price, available, null, shop.label)
    }

    /** Kategorieseite: alle Produktkacheln (erste Seite). */
    suspend fun fetchCategory(ctx: Context, shop: WebShop, url: String): List<CatItem> {
        val o = extract(ctx, shop, url, categoryJs(shop))
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

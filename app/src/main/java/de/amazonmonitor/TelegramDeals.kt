package de.amazonmonitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Telegram-Deals: liest öffentliche Telegram-Kanäle über die Webansicht (https://t.me/s/<kanal>, ohne
 * Anmeldung), filtert neue Beiträge nach Stichwörtern/Höchstpreis, löst den Deal-Link (z. B.
 * s.pirat.deals/…, amzn.to/…) zur Shopseite auf und reagiert je nach Einstellung:
 * „alert“ = nur Alarm, „watch“ = zusätzlich auf die Liste, „cart“ = zusätzlich in den Warenkorb.
 * Gruppen (Chats) und private Kanäle haben keine Webansicht und gehen so nicht.
 * Gleiche Regeln wie im Windows-Programm (telegram.js).
 */
object TelegramDeals {
    /** Beitrag; preview* = Link-Vorschau (Titel/Beschreibung/Seite), dort steht oft der Produktname. */
    data class Post(
        val id: Long, val post: String, val text: String, val links: List<String>,
        val preview: String = "", val previewDesc: String = "", val site: String = "",
    ) {
        /** Alles, was zum Filtern zählt: Text + Link-Vorschau. */
        val fullText: String get() = listOf(text, preview, previewDesc).filter { it.isNotBlank() }.joinToString("\n")
    }
    data class Target(val shop: String, val id: String, val url: String)

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Links in jedem Beitrag, die nicht zum Deal gehören (Werbung des Kanals, Telegram, WhatsApp …). */
    private val FOOTER_HOSTS = Regex(
        "(^|\\.)(t\\.me|telegram\\.me|telegram\\.org|whatsapp\\.com|wa\\.me|piratedeals\\.de|pirate-deals\\.com|" +
            "instagram\\.com|facebook\\.com|tiktok\\.com|youtube\\.com|x\\.com|twitter\\.com)$",
        RegexOption.IGNORE_CASE
    )

    private val LINK_IN_BODY = Regex(
        "https?://(?:www\\.)?(?:amazon\\.de/[^\"'\\s<>]*?(?:dp|gp/product)/[A-Z0-9]{10}[^\"'\\s<>]*|amzn\\.(?:to|eu)/[^\"'\\s<>]+|" +
            "mediamarkt\\.de/de/product/[^\"'\\s<>]+|baur\\.de/p/[^\"'\\s<>]+|mueller\\.de/p/[^\"'\\s<>]+)",
        RegexOption.IGNORE_CASE
    )

    private val PRICE = Regex("(\\d{1,3}(?:\\.\\d{3})*(?:,\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?)\\s?€|€\\s?(\\d{1,3}(?:\\.\\d{3})*(?:,\\d{1,2})?)")

    fun channelName(s: String): String =
        s.trim().removePrefix("@")
            .replace(Regex("^https?://(www\\.)?t\\.me/(s/)?", RegexOption.IGNORE_CASE), "")
            .split('/', '?', '#').first()

    fun channels(ctx: Context): List<String> =
        Store.tgChannels(ctx).split(Regex("[\\s,;]+")).map { channelName(it) }.filter { it.isNotEmpty() }

    /** Blockierend – nur im Hintergrund aufrufen. */
    fun fetchChannel(name: String): List<Post> {
        val doc = Jsoup.connect("https://t.me/s/$name")
            .userAgent(UA)
            .header("Accept-Language", "de-DE,de;q=0.9")
            .timeout(20_000)
            .get()
        val out = mutableListOf<Post>()
        for (m in doc.select(".tgme_widget_message[data-post]")) {
            val post = m.attr("data-post")
            val id = post.substringAfterLast('/').toLongOrNull() ?: continue
            val te = m.selectFirst(".tgme_widget_message_text")
            var text = ""
            val links = mutableListOf<String>()
            if (te != null) {
                te.select("a[href]").forEach { a -> a.attr("href").takeIf { it.startsWith("http") }?.let { links += it } }
                te.select("br").forEach { it.after(TextNode("\n")) }
                text = te.wholeText().replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
            }
            m.select(".tgme_widget_message_inline_button[href],a.tgme_widget_message_link_preview[href]")
                .forEach { a -> a.attr("href").takeIf { it.startsWith("http") }?.let { links += it } }
            val lp = m.selectFirst(".tgme_widget_message_link_preview")
            out += Post(
                id, post, text, links,
                lp?.selectFirst(".link_preview_title")?.text()?.trim().orEmpty(),
                lp?.selectFirst(".link_preview_description")?.text()?.trim().orEmpty(),
                lp?.selectFirst(".link_preview_site_name")?.text()?.trim().orEmpty(),
            )
        }
        if (out.isEmpty() && doc.selectFirst(".tgme_channel_info") == null) {
            throw IllegalStateException("@$name ist kein öffentlicher Kanal mit Webansicht (Gruppe oder privat?)")
        }
        return out.sortedBy { it.id }
    }

    /** Erster €-Betrag im Text (bei Deal-Posts meist der Angebotspreis). */
    fun dealPrice(text: String): Double? {
        PRICE.find(text)?.let { m -> return Scraper.parsePrice(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
        // Preis ohne €-Zeichen in einer eigenen Zeile (z. B. „30,99“)
        val line = text.split('\n').map { it.trim() }.firstOrNull { Regex("^\\d{1,5}[.,]\\d{2}$").matches(it) } ?: return null
        return Scraper.parsePrice(line)
    }

    private val JUNK = Regex(
        "https?://|www\\.|\\.(de|com|deals|net|org)\\b|schnäppchen|preisfehler|angebote:|anzeige|werbung|partnerlink|affiliate|" +
            "gutschein|coupon|code:|rabattcode|versand|prime|statt\\s|werbeaktion|blitzangebot|zeitlich begrenzt|aktivieren|kostenlos",
        RegexOption.IGNORE_CASE
    )

    /** Produktname: erste Zeile mit genug Buchstaben, ohne Links, Werbe-Kopfzeile, Preis- oder Gutscheinzeile. */
    fun title(text: String): String {
        val keep = Regex("[^\\p{L}\\p{N}\\s.,:;!?()&%€+\\-–/»«\"'|]")
        val lines = text.split('\n').map { it.replace(keep, "").trim() }.filter { it.length > 3 }
        val good = lines.firstOrNull { l -> !JUNK.containsMatchIn(l) && l.count { it.isLetter() } >= 6 }
        return (good ?: lines.firstOrNull() ?: "Telegram-Deal").take(100)
    }

    /** Brauchbare Textzeile als Name oder null. */
    private fun goodLine(text: String): String? {
        val keep = Regex("[^\\p{L}\\p{N}\\s.,:;!?()&%€+\\-–/»«\"'|]")
        return text.split('\n').map { it.replace(keep, "").trim() }
            .firstOrNull { l -> l.length > 3 && !JUNK.containsMatchIn(l) && l.count { it.isLetter() } >= 6 }?.take(100)
    }

    private val SITE_PREFIX = Regex("^[\\w.-]+\\.(de|com|eu|net)\\s*[:|–-]\\s*", RegexOption.IGNORE_CASE)

    /** Produktname aus Link-Vorschau oder Text; null, wenn der Beitrag keinen hat. */
    fun name(p: Post): String? {
        val pt = p.preview.replace(SITE_PREFIX, "").trim()
        if (pt.count { it.isLetter() } >= 6) return pt.take(100)
        return goodLine(p.text)
    }

    fun host(url: String?): String? = try { URL(url).host.removePrefix("www.") } catch (e: Exception) { null }

    fun displayName(p: Post, resolved: String?): String = name(p) ?: host(resolved)?.let { "Deal bei $it" } ?: "Telegram-Deal"

    /** Kurzer Textauszug für den Test (Zeilen mit „ / “), plus Link-Vorschau. */
    fun snippet(p: Post): String =
        (p.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ").take(160)) +
            if (p.preview.isNotBlank() || p.site.isNotBlank()) " / Vorschau: ${p.site}: ${p.preview}".take(120) else ""

    private fun words(s: String) = s.lowercase().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun dealLink(p: Post): String? = p.links.firstOrNull { l ->
        val host = try { URL(l).host } catch (e: Exception) { null }
        host != null && !FOOTER_HOSTS.containsMatchIn(host)
    }

    /** Passt der Beitrag zum Filter? (ok, Grund) */
    fun matches(ctx: Context, p: Post): Pair<Boolean, String> {
        val low = p.fullText.lowercase()
        words(Store.tgExclude(ctx)).firstOrNull { low.contains(it) }?.let { return false to "Ausschlusswort „$it“" }
        val kws = words(Store.tgKeywords(ctx))
        if (kws.isNotEmpty() && kws.none { k -> k.split(Regex("\\s+")).all { low.contains(it) } }) return false to "kein Stichwort"
        val price = dealPrice(p.text) ?: dealPrice(p.previewDesc)
        val max = Store.tgMaxPrice(ctx)
        if (max != null && price != null && price > max) return false to "Preis über Höchstpreis"
        if (dealLink(p) == null) return false to "kein Deal-Link"
        return true to if (kws.isNotEmpty()) "Stichwort passt" else "alle Deals"
    }

    /** Produktseite, mit der der Bot etwas anfangen kann? */
    fun target(url: String?): Target? {
        if (url == null) return null
        if (Regex("amazon\\.[a-z.]+/", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
            Scraper.extractAsin(url)?.let { return Target(Product.SHOP_AMAZON, it, url) }
        }
        val ws = WebShops.detect(url) ?: return null
        val link = ws.parse(url) ?: return null
        return if (link.kind == Product.KIND_PRODUCT) Target(ws.key, link.id, link.url) else null
    }

    /** Partner-/Tracking-Parameter, die aus Links entfernt werden. */
    private val TRACK = Regex(
        "^(tag|ascsubtag|linkcode|linkid|camp|creative|creativeasin|ref|ref_|th|psc|utm_.*|awc|affid|aff_?id|affiliate|aff|" +
            "partner|partnerid|subid|sub_?id|clickref|irclickid|gclid|fbclid|wt_mc|wt\\.mc_id|emid|cjevent|ranmid|ransiteid|siteid|" +
            "sc_?cid|cid|pirate.*|source|campaign|medium)$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Saubere Produktadresse ohne Partner-Tag: Amazon → https://www.amazon.de/dp/ASIN,
     * Müller/MediaMarkt/BAUR → erkannte Produktadresse, sonst ohne Tracking-Parameter.
     */
    fun cleanLink(url: String, t: Target?): String {
        if (t != null && t.shop == Product.SHOP_AMAZON) {
            val h = try { URL(url).host } catch (e: Exception) { null }
            val host = if (h != null && h.contains("amazon.", true)) h else "www.amazon.de"
            return "https://$host/dp/${t.id}"
        }
        if (t != null) return t.url
        return try {
            val u = android.net.Uri.parse(url)
            val b = u.buildUpon().clearQuery().fragment(null)
            for (k in u.queryParameterNames) {
                if (TRACK.matches(k)) continue
                for (v in u.getQueryParameters(k)) b.appendQueryParameter(k, v)
            }
            b.build().toString()
        } catch (e: Exception) {
            url
        }
    }

    /** Deal-Link (Weiterleitung) bis zur Shopseite auflösen. */
    suspend fun resolve(ctx: Context, url: String): String {
        var u = url
        withContext(Dispatchers.IO) {
            try {
                for (i in 0 until 8) {
                    if (target(u) != null) return@withContext
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 15_000
                    c.readTimeout = 15_000
                    c.setRequestProperty("User-Agent", UA)
                    c.setRequestProperty("Accept-Language", "de-DE")
                    val code = c.responseCode
                    if (code in 300..399) {
                        val loc = c.getHeaderField("Location")
                        c.disconnect()
                        if (loc == null) break
                        u = URL(URL(u), loc).toString()
                        continue
                    }
                    val body = try {
                        c.inputStream.bufferedReader().use { it.readText().take(500_000) }
                    } catch (e: Exception) {
                        ""
                    }
                    c.disconnect()
                    val m = LINK_IN_BODY.find(body.replace("&amp;", "&")) ?: break
                    u = m.value
                }
            } catch (e: Exception) {
                // weiter mit Browser
            }
        }
        if (target(u) != null) return u
        // Weiterleitung per JavaScript: im unsichtbaren Browser öffnen und warten
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(20_000L) {
                suspendCancellableCoroutine<String> { cont ->
                    val web = WebShops.hiddenWebView(ctx)
                    val handler = Handler(Looper.getMainLooper())
                    var n = 0
                    fun finish(r: String) {
                        if (!cont.isActive) return
                        handler.removeCallbacksAndMessages(null)
                        cont.resume(r)
                        web.stopLoading()
                        web.destroy()
                    }
                    cont.invokeOnCancellation { handler.post { handler.removeCallbacksAndMessages(null); web.stopLoading(); web.destroy() } }
                    val poll = object : Runnable {
                        override fun run() {
                            val cur = web.url ?: ""
                            n++
                            if (target(cur) != null || n >= 15) finish(cur.ifEmpty { u }) else handler.postDelayed(this, 1000)
                        }
                    }
                    web.webViewClient = WebShops.safeClient()
                    web.loadUrl(u)
                    handler.postDelayed(poll, 1000)
                }
            } ?: u
        }
    }

    /**
     * Neue Beiträge aller Kanäle prüfen. Beim ersten Mal wird nur der Stand gemerkt (keine Alarmflut).
     * Ergebnis: (Anzahl Treffer, Fehler).
     */
    suspend fun poll(ctx: Context): Pair<Int, List<String>> {
        var found = 0
        val errors = mutableListOf<String>()
        for (ch in channels(ctx)) {
            try {
                val posts = withContext(Dispatchers.IO) { fetchChannel(ch) }
                val last = Store.tgLast(ctx, ch)
                val maxId = posts.lastOrNull()?.id ?: last
                if (last == 0L) {
                    Store.setTgLast(ctx, ch, maxId)
                    continue
                }
                val fresh = posts.filter { it.id > last }.takeLast(20)
                Store.setTgLast(ctx, ch, maxOf(last, maxId))
                for (p in fresh) {
                    if (!matches(ctx, p).first) continue
                    val link = dealLink(p) ?: continue
                    // Derselbe Deal in mehreren Kanälen (z. B. weitergeleitet): nur einmal melden
                    if (Store.tgSeen(ctx, "l:$link")) continue
                    val resolved = resolve(ctx, link)
                    val tkey = target(resolved)?.let { "t:${it.id}" }
                    Store.tgRemember(ctx, "l:$link")
                    if (tkey != null) {
                        if (Store.tgSeen(ctx, tkey)) continue
                        Store.tgRemember(ctx, tkey)
                    }
                    found++
                    handleDeal(ctx, ch, p, link, resolved)
                }
            } catch (e: Exception) {
                errors += (e.message ?: "Fehler bei @$ch")
            }
        }
        return found to errors
    }

    private suspend fun handleDeal(ctx: Context, channel: String, p: Post, link: String, resolved: String) {
        val t = target(resolved)
        val name = displayName(p, resolved)
        val price = dealPrice(p.text) ?: dealPrice(p.previewDesc)
        val action = Store.tgAction(ctx)
        val lines = mutableListOf<String>()
        var addedId: String? = null
        if (action != "alert") {
            if (t == null) {
                lines += "ℹ️ Link führt nicht zu Amazon/Müller/MediaMarkt/BAUR – nur Alarm"
            } else {
                if (Store.load(ctx).none { it.asin == t.id }) {
                    val target = Store.tgMaxPrice(ctx) ?: price
                    Store.add(
                        ctx,
                        // ohne Namen im Beitrag: ID als Name → die erste Prüfung übernimmt den Produkttitel des Shops
                        if (t.shop == Product.SHOP_AMAZON) Product(t.id, name(p)?.take(80) ?: t.id, target, lastAlertPrice = price)
                        else Product(t.id, name(p)?.take(80) ?: t.id, target, lastAlertPrice = price, shop = t.shop, url = t.url)
                    )
                    addedId = t.id
                    lines += "👀 Auf die Überwachungsliste gesetzt" + (target?.let { " (Zielpreis ${money(it)})" } ?: "")
                } else {
                    lines += "👀 Steht schon auf der Überwachungsliste"
                }
                if (action == "cart") {
                    val ws = WebShops.byKey(t.shop)
                    val res = try {
                        if (ws != null) ShopBuyer.addToCart(ctx, ws, t.url, t.id)
                        else CartAdder.add(ctx, Store.domain(ctx), t.id, null)
                    } catch (e: Exception) {
                        CartAdder.lastDetail = e.message ?: "Fehler"
                        CartAdder.Result.FAILED
                    }
                    if (res == CartAdder.Result.OK) Store.update(ctx, t.id) { it.lastCartAdd = System.currentTimeMillis() }
                    lines += when (res) {
                        CartAdder.Result.OK -> "✅ Automatisch in den Warenkorb gelegt"
                        CartAdder.Result.NOT_LOGGED_IN -> "⚠️ Nicht angemeldet: ${CartAdder.lastDetail}"
                        CartAdder.Result.FAILED -> "⚠️ Warenkorb hat nicht geklappt: ${CartAdder.lastDetail}"
                    }
                }
            }
        }
        // saubere Produktadresse ohne Partner-Tag; Payback-Partner (Amazon, MediaMarkt, BAUR) über Payback öffnen
        val url = cleanLink(resolved, t)
        val pb = if (Store.tgPayback(ctx) && t != null && Payback.supports(t.shop)) t.shop else null
        if (pb != null) lines += "💳 Antippen öffnet über Payback (°Punkte)"
        // Fußzeilen des Kanals (nur Links/Domains) weglassen
        val domainRe = Regex("https?://\\S+|[\\wäöüß.-]+\\.(deals|de|com|me|org|net|to|eu|ly)\\b(/\\S*)?", RegexOption.IGNORE_CASE)
        val body = p.text.split('\n')
            .filter { it.replace(domainRe, "").replace(Regex("[|•·\\-–\\s]"), "").length >= 3 }
            .take(8).joinToString("\n").take(500)
        val msg = "📣 Telegram @$channel" + (price?.let { " · ${money(it)}" } ?: "") + "\n$body" +
            if (lines.isNotEmpty()) "\n" + lines.joinToString("\n") else ""
        val cart = if (t != null && t.shop == Product.SHOP_AMAZON) Checker.cartUrl(Store.domain(ctx), t.id) else url
        val checkout = WebShops.byKey(t?.shop ?: "")?.cartUrl ?: Checker.checkoutUrl(Store.domain(ctx))
        Checker.notifyUser(
            ctx, "📣 $name", msg, url, cart, checkout,
            payback = pb?.let { it to name }, paybackUrl = url, tapViaPayback = pb != null,
        )
        if (Store.openOnAlert(ctx)) {
            withContext(Dispatchers.Main) {
                if (pb != null) {
                    if (Links.canOpenFromBackground(ctx)) Payback.open(ctx, pb, name, url)
                } else {
                    Links.openFromBackground(ctx, url)
                }
            }
        }
        withContext(Dispatchers.IO) {
            Checker.sendDiscord(
                Store.webhook(ctx),
                "**$name**\n$msg\n<$url>" + (pb?.let { "\n💳 Payback: <${Payback.shopPage(it)}>" } ?: "")
            )
        }
        addedId?.let { Checker.checkOne(ctx, it) }
    }

    /** Test: letzte Beiträge mit Filter-Ergebnis (löst die Links der passenden auf). */
    suspend fun test(ctx: Context, n: Int = 5): List<String> {
        val out = mutableListOf<String>()
        for (ch in channels(ctx)) {
            val posts = withContext(Dispatchers.IO) { fetchChannel(ch) }.takeLast(n)
            for (p in posts) {
                val (ok, why) = matches(ctx, p)
                var tgt = ""
                var r: String? = null
                if (ok) {
                    r = resolve(ctx, dealLink(p) ?: continue)
                    val t = target(r)
                    tgt = if (t != null) " → ${if (t.shop == Product.SHOP_AMAZON) "Amazon ${t.id}" else "${t.shop} ${t.id}"}"
                    else " → anderer Shop (${host(r) ?: "?"})"
                    tgt += "\n   Link: " + cleanLink(r, t)
                }
                val price = (dealPrice(p.text) ?: dealPrice(p.previewDesc))?.let { " – ${money(it)}" } ?: ""
                val nm = if (ok) displayName(p, r) else name(p) ?: "(kein Name im Beitrag)"
                out += (if (ok) "✅ " else "⏭️ ") + "@$ch #${p.id}: $nm$price\n   ($why$tgt)\n   Text: ${snippet(p)}"
            }
        }
        return out
    }
}

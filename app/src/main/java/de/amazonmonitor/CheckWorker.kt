package de.amazonmonitor

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Checker.checkAll(applicationContext)
        Result.success()
    }
}

object Checker {
    private const val CHANNEL = "price_alerts"

    /**
     * Prüft alle Produkte nacheinander (Amazon und Web-Shops). Blockiert ein Shop (Captcha bzw.
     * Sicherheitsprüfung), werden nur dessen restliche Produkte für diese Runde übersprungen.
     * Gibt true zurück, wenn ein Shop blockiert hat.
     */
    suspend fun checkAll(ctx: Context): Boolean {
        val domain = Store.domain(ctx)
        val dropPct = Store.dropPercent(ctx)
        val products = Store.load(ctx)
        val problems = mutableListOf<String>()
        val blockedShops = mutableSetOf<String>()

        for ((i, p) in products.withIndex()) {
            if (p.shop in blockedShops) continue
            try {
                checkProduct(ctx, domain, dropPct, p)
            } catch (e: Scraper.BlockedException) {
                blockedShops += p.shop
                problems += p.webShop?.let { "${it.label}: Bot-/Sicherheitsprüfung nicht bestanden" } ?: "Amazon: blockiert (Captcha)"
            } catch (e: Exception) {
                problems += "Fehler bei ${p.name}: ${e.message}"
            }
            if (i < products.lastIndex) delay((3000L..8000L).random())
        }

        Store.setStatus(ctx, if (problems.isEmpty()) "OK" else problems.joinToString(" · "))
        return blockedShops.isNotEmpty()
    }

    /** Ein Produkt bzw. eine Kategorie je nach Shop abrufen und auswerten. */
    private suspend fun checkProduct(ctx: Context, domain: String, dropPct: Int, p: Product) {
        val ws = p.webShop
        when {
            ws != null && p.isCategory -> handleCategory(ctx, ws, p, WebShops.fetchCategory(ctx, ws, p.url))
            ws != null -> handle(ctx, domain, dropPct, p.asin, WebShops.fetchProduct(ctx, ws, p.url))
            else -> handle(ctx, domain, dropPct, p.asin, Scraper.fetch(domain, p.asin))
        }
    }

    /**
     * Prüft ein einzelnes Produkt sofort (z. B. direkt nach dem Hinzufügen).
     * Gibt null bei Erfolg zurück, sonst eine Fehlermeldung. Blockierend – nur im Hintergrund aufrufen.
     */
    suspend fun checkOne(ctx: Context, asin: String): String? = try {
        val p = Store.load(ctx).firstOrNull { it.asin == asin } ?: throw IllegalStateException("Produkt fehlt")
        checkProduct(ctx, Store.domain(ctx), Store.dropPercent(ctx), p)
        null
    } catch (e: Scraper.BlockedException) {
        "Blockiert (${e.message}) – später „Jetzt prüfen“"
    } catch (e: Exception) {
        e.message ?: "Fehler"
    }

    /** Text mit OfferListingId & Co. für Discord (ID in `…`, damit sie sich leicht kopieren lässt). */
    fun offerInfo(domain: String, p: Product): String {
        val ws = p.webShop
        if (ws != null) {
            val sb = StringBuilder("Shop: ${ws.label}")
            p.lastPrice?.let { sb.append("\nPreis: ").append(money(it)) }
            p.target?.let { sb.append("\nZielpreis: ").append(money(it)) }
            sb.append("\nVerfügbar: ").append(if (p.available) "ja" else "nein")
            sb.append("\nProdukt: <").append(p.url).append(">")
            sb.append("\nWarenkorb: <").append(ws.cartUrl).append(">")
            return sb.toString()
        }
        val sb = StringBuilder()
        sb.append("ASIN: ").append(p.asin)
        val offer = p.cartOffer
        sb.append("\nOfferListingId: ").append(if (offer != null) "`$offer`" else "nicht gefunden")
        if (p.pinnedOffer != null) sb.append(" 📌 festgehalten")
        if (p.seller.isNotEmpty()) sb.append("\nVerkauf: ").append(p.seller)
        p.lastPrice?.let { sb.append("\nPreis: ").append(money(it)) }
        p.target?.let { sb.append("\nZielpreis: ").append(money(it)) }
        sb.append("\nProdukt: <https://www.$domain/dp/${p.asin}>")
        sb.append("\nIn den Warenkorb: <").append(cartUrl(domain, p.asin, offer)).append(">")
        return sb.toString()
    }

    /** Schickt die OfferListingIds aller Produkte an Discord (in mehreren Nachrichten, max. 2000 Zeichen). */
    fun sendAllOffers(ctx: Context): Boolean {
        val hook = Store.webhook(ctx)
        val domain = Store.domain(ctx)
        val items = Store.load(ctx)
        if (hook.isBlank() || items.isEmpty()) return false
        var ok = true
        var buf = StringBuilder("🦊 **Mr.Fox Bot – OfferListingIds aller Produkte**\n")
        for (p in items) {
            val block = "\n**${p.name}**\n${offerInfo(domain, p)}\n"
            if (buf.length + block.length > 1900) {
                ok = sendDiscord(hook, buf.toString()) && ok
                buf = StringBuilder()
            }
            buf.append(block)
        }
        if (buf.isNotBlank()) ok = sendDiscord(hook, buf.toString()) && ok
        return ok
    }

    private suspend fun handle(ctx: Context, domain: String, dropPct: Int, asin: String, r: Scraper.Result) {
        val autoBuyOn = Store.autoBuyEnabled(ctx)
        var offerEvent: String? = null
        val upd = Store.update(ctx, asin) { p ->
            val prevOffer = p.offerId
            offerEvent = when {
                r.offerId == null -> null
                prevOffer == null -> "🆕 OfferListingId gefunden"
                r.offerId != prevOffer -> "🔄 Neues Angebot in der Kaufbox"
                else -> null
            }
            val prevPrice = p.lastPrice
            val prevAvail = p.available
            val first = p.lastCheck == 0L
            p.lastCheck = System.currentTimeMillis()
            // Unklarer Zustand (null) überschreibt den bekannten nicht.
            val nowAvail = r.available
            if (nowAvail != null) p.available = nowAvail
            // Kaufbox-Angebot merken (nur überschreiben, wenn etwas gefunden wurde).
            r.offerId?.let { p.offerId = it }
            r.seller?.let { p.seller = it }
            // Ohne eigenen Namen den Produkttitel von Amazon übernehmen.
            if (p.name == p.asin && r.title != p.asin) p.name = r.title.take(80)

            var text: String? = null
            val price = r.price
            if (price != null) {
                p.lastPrice = price
                p.minPrice = minOf(p.minPrice ?: price, price)
                val target = p.target
                text = when {
                    target != null && price <= target && p.lastAlertPrice != price ->
                        "Zielpreis erreicht: ${money(price)} (Ziel ${money(target)})"
                    dropPct > 0 && prevPrice != null &&
                        price <= prevPrice * (1 - dropPct / 100.0) && p.lastAlertPrice != price ->
                        "Preis um mind. $dropPct % gefallen: ${money(prevPrice)} → ${money(price)}"
                    !first && !prevAvail && nowAvail == true ->
                        "Wieder verfügbar: ${money(price)}"
                    else -> null
                }
                if (text != null) p.lastAlertPrice = price
            }

            // Auto-Kauf: nur wenn Hauptschalter an, Produkt scharf, verfügbar und Preis ≤ Zielpreis.
            // Wird hier (atomar) sofort entschärft, damit höchstens EIN Kaufversuch passiert.
            val t = p.target
            val priceOk = autoBuyOn && p.autoBuy && nowAvail == true &&
                price != null && t != null && price <= t
            // Festgehaltenes Angebot: nur kaufen, wenn genau dieses in der Kaufbox liegt.
            // Sonst bleibt Auto-Kauf scharf und wartet auf die nächste Prüfung.
            val pin = p.pinnedOffer
            val offerMismatch = priceOk && pin != null && r.offerId != pin
            val buyNow = priceOk && !offerMismatch
            if (buyNow) {
                p.autoBuy = false
                p.lastBuyAttempt = System.currentTimeMillis()
                p.lastBuyResult = "läuft …"
                if (text == null) {
                    text = "Zielpreis erreicht: ${money(price!!)} (Ziel ${money(t!!)})"
                    p.lastAlertPrice = price
                }
            }
            Triple(text, buyNow, offerMismatch)
        } ?: return
        val (alert, buyNow, offerMismatch) = upd

        // OfferListingId bei Discord hinterlegen: wenn sie zum ersten Mal gefunden wird oder sich ändert.
        // (Bei einem Alarm steht sie ohnehin in der Alarm-Nachricht.)
        val ev = offerEvent
        if (ev != null && alert == null) {
            Store.load(ctx).firstOrNull { it.asin == asin }?.let { p ->
                sendDiscord(Store.webhook(ctx), "$ev\n**${p.name}**\n${offerInfo(domain, p)}")
            }
        }

        if (alert != null) {
            val product = Store.load(ctx).firstOrNull { it.asin == asin }
            val name = product?.name ?: r.title
            val offer = product?.cartOffer
            val ws = product?.webShop
            val url = product?.pageUrl(domain) ?: "https://www.$domain/dp/$asin"
            // Web-Shops haben keinen Direkt-Link „in den Warenkorb“ – dort öffnet der Knopf die Produktseite.
            val cart = if (ws != null) url else cartUrl(domain, asin, offer)
            var message: String = alert
            if (offerMismatch) {
                message += "\n⏸ Auto-Kauf wartet: in der Kaufbox liegt nicht das festgehaltene Angebot" +
                    (r.seller?.let { " (gerade: $it)" } ?: "")
            }
            // Welche Seite bei „Bei Alarm automatisch öffnen“ gezeigt wird (null = nichts öffnen).
            var openUrl: String? = if (Store.openOnAlert(ctx)) url else null

            // PAYBACK: vor automatischem Kauf/Warenkorb über die Payback-Shopseite weiterleiten (°Punkte).
            val shopKey = product?.shop ?: Product.SHOP_AMAZON
            if ((buyNow || Store.autoCart(ctx)) && Store.payback(ctx, shopKey) && Payback.supports(shopKey)) {
                val pb = try {
                    Payback.redirect(ctx, shopKey)
                } catch (e: Exception) {
                    Payback.Result(false, e.message ?: "Fehler")
                }
                message += "\n" + Payback.describe(pb)
            }

            if (buyNow) {
                val visible = Store.watchBuy(ctx) && Links.canOpenFromBackground(ctx)
                val res = try {
                    if (ws != null) ShopBuyer.buy(ctx, ws, url, asin, dryRun = false, visible = visible)
                    else AutoBuyer.buy(ctx, domain, asin, dryRun = false, visible = visible)
                } catch (e: Exception) {
                    AutoBuyer.Result(AutoBuyer.Outcome.FAILED, e.message ?: "Fehler")
                }
                val line = AutoBuyer.describe(res)
                Store.update(ctx, asin) { it.lastBuyResult = line }
                message += "\n$line"
                if (openUrl != null) {
                    openUrl = when {
                        visible -> null // Das Auto-Kauf-Fenster ist schon offen.
                        res.outcome == AutoBuyer.Outcome.ORDERED || res.outcome == AutoBuyer.Outcome.UNCLEAR ->
                            ws?.accountUrl ?: ordersUrl(domain)
                        else -> url
                    }
                }
            } else if (Store.autoCart(ctx)) {
                val res = try {
                    if (ws != null) ShopBuyer.addToCart(ctx, ws, url, asin)
                    else CartAdder.add(ctx, domain, asin, offer)
                } catch (e: Exception) {
                    CartAdder.Result.FAILED
                }
                if (res == CartAdder.Result.OK) {
                    Store.update(ctx, asin) { it.lastCartAdd = System.currentTimeMillis() }
                }
                message += when (res) {
                    CartAdder.Result.OK -> "\n✅ Automatisch in den Warenkorb gelegt"
                    CartAdder.Result.NOT_LOGGED_IN -> "\n⚠️ Nicht angemeldet: ${CartAdder.lastDetail}"
                    CartAdder.Result.FAILED ->
                        "\n⚠️ Automatisch in den Warenkorb legen hat nicht geklappt (${CartAdder.lastDetail}) – nutze den Link"
                }
            }

            val checkout = ws?.cartUrl ?: checkoutUrl(domain)
            // Mit Erlaubnis „Über anderen Apps einblenden“ direkt öffnen, sonst als Vollbild-Benachrichtigung
            // (öffnet sich bei gesperrtem/ausgeschaltetem Bildschirm, sonst erscheint sie oben).
            val fullScreen = openUrl?.let { u ->
                if (Links.openFromBackground(ctx, u)) null else u
            }
            notifyUser(ctx, name, message, url, cart, checkout, fullScreen)
            sendDiscord(
                Store.webhook(ctx),
                "**$name**\n$message\n" +
                    (product?.let { offerInfo(domain, it) } ?: "Produkt: $url\nIn den Warenkorb: $cart") +
                    "\nZur Kasse: <$checkout>"
            )
        }
    }

    /**
     * Kategorie (Web-Shop): meldet neue Produkte, Produkte die wieder online verfügbar sind,
     * und – falls ein Zielpreis gesetzt ist – verfügbare Produkte unter diesem Preis.
     * Die erste Prüfung merkt sich nur den Stand (keine Alarmflut).
     */
    private fun handleCategory(ctx: Context, shop: WebShop, cat: Product, items: List<WebShops.CatItem>) {
        if (items.isEmpty()) throw IllegalStateException("Keine Produkte in der Kategorie gefunden")
        val lines = mutableListOf<String>()
        Store.update(ctx, cat.asin) { p ->
            val first = p.lastCheck == 0L
            val old = try { JSONObject(p.catState) } catch (e: Exception) { JSONObject() }
            val now = JSONObject()
            val target = p.target
            for (it in items) {
                val prev = old.optJSONObject(it.url)
                val prevAvail = prev?.optBoolean("a") ?: false
                val prevPrice = prev?.let { o -> if (o.isNull("p")) null else o.optDouble("p") }
                val priceTxt = it.price?.let { pr -> money(pr) } ?: "Preis ?"
                if (!first) {
                    when {
                        prev == null && it.available -> lines += "🆕 Neu & online verfügbar: ${it.name} – $priceTxt\n<${it.url}>"
                        prev == null -> lines += "🆕 Neu (noch ausverkauft): ${it.name} – $priceTxt"
                        !prevAvail && it.available -> lines += "✅ Wieder online verfügbar: ${it.name} – $priceTxt\n<${it.url}>"
                        target != null && it.available && it.price != null && it.price <= target &&
                            (prevPrice == null || prevPrice > target) ->
                            lines += "💶 Unter ${money(target)}: ${it.name} – $priceTxt\n<${it.url}>"
                    }
                }
                now.put(it.url, JSONObject().put("n", it.name).put("p", it.price ?: JSONObject.NULL).put("a", it.available))
            }
            p.catState = now.toString()
            p.lastCheck = System.currentTimeMillis()
            p.available = items.any { it.available }
        }
        if (lines.isEmpty()) return

        val text = lines.joinToString("\n")
        val fullScreen = if (Store.openOnAlert(ctx)) {
            if (Links.openFromBackground(ctx, cat.url)) null else cat.url
        } else null
        notifyUser(ctx, cat.name, text, cat.url, cat.url, shop.cartUrl, fullScreen)
        // Discord: max. 2000 Zeichen pro Nachricht
        val hook = Store.webhook(ctx)
        var buf = StringBuilder("${shop.emoji} **${cat.name}** (${shop.label}-Kategorie)\n")
        for (l in lines) {
            if (buf.length + l.length > 1900) {
                sendDiscord(hook, buf.toString())
                buf = StringBuilder()
            }
            buf.append(l).append('\n')
        }
        sendDiscord(hook, buf.toString())
    }

    /**
     * Link, der den Artikel in den Warenkorb legt (du bestellst danach selbst).
     * Mit [offerId] (OfferListingId) genau dieses Angebot/diesen Verkäufer, sonst das Kaufbox-Angebot.
     */
    fun cartUrl(domain: String, asin: String, offerId: String? = null): String =
        if (offerId != null) {
            "https://www.$domain/gp/aws/cart/add.html?OfferListingId.1=${encodeOffer(offerId)}&Quantity.1=1"
        } else {
            "https://www.$domain/gp/aws/cart/add.html?ASIN.1=$asin&Quantity.1=1"
        }

    /** IDs werden dekodiert gespeichert (siehe [Scraper.normOffer]); für den Link einmal kodieren. */
    fun encodeOffer(id: String): String =
        java.net.URLEncoder.encode(Scraper.normOffer(id) ?: id, "UTF-8")

    /** Warenkorbseite; von dort geht es mit "Zur Kasse gehen" weiter. */
    fun checkoutUrl(domain: String): String = "https://www.$domain/gp/cart/view.html"

    /** Bestellübersicht. */
    fun ordersUrl(domain: String): String = "https://www.$domain/gp/your-account/order-history"

    /** Sendet eine Nachricht an einen Discord-Webhook. Blockierend – nur aus einem Hintergrund-Thread aufrufen. */
    fun sendDiscord(webhook: String, text: String): Boolean {
        if (webhook.isBlank()) return false
        return try {
            val conn = URL(webhook.trim()).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "AmazonMonitor/1.0")
            conn.outputStream.use { it.write(JSONObject().put("content", text).toString().toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyUser(
        ctx: Context, title: String, text: String, url: String, cartUrl: String, checkoutUrl: String,
        fullScreenUrl: String? = null
    ) {
        val nm = NotificationManagerCompat.from(ctx)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Preisalarme")
                .build()
        )
        val pi = Links.pending(ctx, url, false, url.hashCode())
        val cartPi = Links.pending(ctx, cartUrl, false, cartUrl.hashCode())
        val checkoutPi = Links.pending(ctx, checkoutUrl, true, checkoutUrl.hashCode())
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_add, "In den Warenkorb", cartPi)
            .addAction(android.R.drawable.ic_menu_send, "Zur Kasse", checkoutPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (fullScreenUrl != null) {
            b.setCategory(NotificationCompat.CATEGORY_ALARM)
            b.setFullScreenIntent(Links.pending(ctx, fullScreenUrl, false, fullScreenUrl.hashCode() + 1), true)
        }
        val n = b.build()
        try {
            nm.notify(url.hashCode(), n)
        } catch (e: SecurityException) {
            // Benachrichtigungs-Erlaubnis fehlt
        }
    }
}

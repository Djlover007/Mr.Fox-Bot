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

    /** Prüft alle Produkte nacheinander. Gibt true zurück, wenn Amazon blockiert hat. */
    suspend fun checkAll(ctx: Context): Boolean {
        val domain = Store.domain(ctx)
        val dropPct = Store.dropPercent(ctx)
        val products = Store.load(ctx)
        var status = "OK"
        var blocked = false

        for ((i, p) in products.withIndex()) {
            try {
                val r = Scraper.fetch(domain, p.asin)
                handle(ctx, domain, dropPct, p.asin, r)
            } catch (e: Scraper.BlockedException) {
                status = "Von Amazon blockiert (Captcha) – später erneut"
                blocked = true
                break
            } catch (e: Exception) {
                status = "Fehler bei ${p.name}: ${e.message}"
            }
            if (i < products.lastIndex) delay((3000L..8000L).random())
        }

        Store.setStatus(ctx, status)
        return blocked
    }

    private suspend fun handle(ctx: Context, domain: String, dropPct: Int, asin: String, r: Scraper.Result) {
        val autoBuyOn = Store.autoBuyEnabled(ctx)
        val cap = Store.autoBuyCap(ctx)
        val upd = Store.update(ctx, asin) { p ->
            val prevPrice = p.lastPrice
            val prevAvail = p.available
            val first = p.lastCheck == 0L
            p.lastCheck = System.currentTimeMillis()
            // Unklarer Zustand (null) überschreibt den bekannten nicht.
            val nowAvail = r.available
            if (nowAvail != null) p.available = nowAvail

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

            // Auto-Kauf: nur wenn Hauptschalter an, Produkt scharf, verfügbar und Preis ≤ Ziel und ≤ Limit.
            // Wird hier (atomar) sofort entschärft, damit höchstens EIN Kaufversuch passiert.
            val t = p.target
            val buyNow = autoBuyOn && p.autoBuy && nowAvail == true &&
                price != null && t != null && price <= t && price <= cap
            if (buyNow) {
                p.autoBuy = false
                p.lastBuyAttempt = System.currentTimeMillis()
                p.lastBuyResult = "läuft …"
                if (text == null) {
                    text = "Zielpreis erreicht: ${money(price!!)} (Ziel ${money(t!!)})"
                    p.lastAlertPrice = price
                }
            }
            Pair(text, buyNow)
        } ?: return
        val (alert, buyNow) = upd

        if (alert != null) {
            val name = Store.load(ctx).firstOrNull { it.asin == asin }?.name ?: r.title
            val url = "https://www.$domain/dp/$asin"
            val cart = cartUrl(domain, asin)
            var message: String = alert

            if (buyNow) {
                val res = try {
                    AutoBuyer.buy(ctx, domain, asin, cap, dryRun = false)
                } catch (e: Exception) {
                    AutoBuyer.Result(AutoBuyer.Outcome.FAILED, e.message ?: "Fehler")
                }
                val line = AutoBuyer.describe(res)
                Store.update(ctx, asin) { it.lastBuyResult = line }
                message += "\n$line"
            } else if (Store.autoCart(ctx)) {
                val res = try {
                    CartAdder.add(ctx, domain, asin)
                } catch (e: Exception) {
                    CartAdder.Result.FAILED
                }
                if (res == CartAdder.Result.OK) {
                    Store.update(ctx, asin) { it.lastCartAdd = System.currentTimeMillis() }
                }
                message += when (res) {
                    CartAdder.Result.OK -> "\n✅ Automatisch in den Warenkorb gelegt"
                    CartAdder.Result.NOT_LOGGED_IN -> "\n⚠️ Nicht bei Amazon angemeldet – bitte in der App anmelden"
                    CartAdder.Result.FAILED ->
                        "\n⚠️ Automatisch in den Warenkorb legen hat nicht geklappt (${CartAdder.lastDetail}) – nutze den Link"
                }
            }

            val checkout = checkoutUrl(domain)
            notifyUser(ctx, name, message, url, cart, checkout)
            sendDiscord(
                Store.webhook(ctx),
                "**$name**\n$message\nProdukt: $url\nIn den Warenkorb: $cart\nZur Kasse: $checkout"
            )
        }
    }

    /** Link, der den Artikel in den Warenkorb legt (du bestellst danach selbst). */
    fun cartUrl(domain: String, asin: String): String =
        "https://www.$domain/gp/aws/cart/add.html?ASIN.1=$asin&Quantity.1=1"

    /** Warenkorbseite; von dort geht es mit "Zur Kasse gehen" weiter. */
    fun checkoutUrl(domain: String): String = "https://www.$domain/gp/cart/view.html"

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
        ctx: Context, title: String, text: String, url: String, cartUrl: String, checkoutUrl: String
    ) {
        val nm = NotificationManagerCompat.from(ctx)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Preisalarme")
                .build()
        )
        val intent = Intent(ctx, WebActivity::class.java).putExtra("url", url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, url.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cartIntent = Intent(ctx, WebActivity::class.java).putExtra("url", cartUrl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val cartPi = PendingIntent.getActivity(
            ctx, cartUrl.hashCode(), cartIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val checkoutIntent = Intent(ctx, WebActivity::class.java)
            .putExtra("url", checkoutUrl)
            .putExtra("checkout", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val checkoutPi = PendingIntent.getActivity(
            ctx, checkoutUrl.hashCode(), checkoutIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_add, "In den Warenkorb", cartPi)
            .addAction(android.R.drawable.ic_menu_send, "Zur Kasse", checkoutPi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(url.hashCode(), n)
        } catch (e: SecurityException) {
            // Benachrichtigungs-Erlaubnis fehlt
        }
    }
}

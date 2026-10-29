package de.amazonmonitor

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * PAYBACK, halbautomatisch: Auf dem Handy lässt Payback das Online-Shoppen („Jetzt shoppen“) nur in
 * der eigenen Payback-App zu – eine automatische Weiterleitung im App-Browser blockt Payback.
 *
 * Deshalb öffnet „Über Payback kaufen“ die Payback-App auf der Shopseite (Amazon/MediaMarkt) und
 * kopiert den Produktnamen in die Zwischenablage. Dort „Jetzt shoppen“ tippen, im Shop (bzw. in dessen
 * App) den Namen in die Suche einfügen und kaufen. Laut Payback zählt dabei auch die Amazon-App.
 * Der Auto-Kauf läuft unabhängig davon ohne Payback (also ohne °Punkte).
 */
object Payback {
    private const val APP_PACKAGE = "de.payback.client.android"

    /** Payback-Partner: Shop-Schlüssel → Payback-Shopseite. */
    private val PARTNERS = mapOf(
        Product.SHOP_AMAZON to "https://www.payback.de/shop/amazon",
        Product.SHOP_MEDIAMARKT to "https://www.payback.de/shop/mediamarkt",
        Product.SHOP_BAUR to "https://www.payback.de/shop/baur",
    )

    fun supports(shopKey: String): Boolean = shopKey in PARTNERS

    /** Alle Shops mit Payback (für die Einstellungen). */
    val partners: List<String> get() = PARTNERS.keys.toList()

    fun shopPage(shopKey: String): String? = PARTNERS[shopKey]

    fun label(shopKey: String): String = WebShops.byKey(shopKey)?.label ?: "Amazon"

    /** Payback-App direkt, sonst der Standard-Browser (der leitet dann selbst in die Payback-App weiter). */
    private fun viewIntent(ctx: Context, url: String): Intent {
        val inApp = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(APP_PACKAGE)
        return if (inApp.resolveActivity(ctx.packageManager) != null) inApp
        else Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    /**
     * Öffnet Payback für diesen Shop. Mit [productUrl] (saubere Produktadresse, z. B. aus Telegram) öffnet die
     * Payback-Automatik nach der Weiterleitung genau dieses Produkt; sonst wird [productName] für die Suche kopiert.
     */
    fun open(ctx: Context, shopKey: String, productName: String?, productUrl: String? = null) {
        val url = shopPage(shopKey) ?: return
        val auto = PaybackClicker.isEnabled(ctx)
        val clip = productUrl ?: productName
        if (!clip.isNullOrBlank()) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Produkt", clip))
            Toast.makeText(
                ctx,
                when {
                    productUrl != null && auto -> "Payback öffnet – nach der Weiterleitung öffnet die Automatik das Produkt (Link ist auch kopiert)."
                    productUrl != null -> "Produkt-Link kopiert. In Payback „Jetzt shoppen“/„Zur App“ tippen, dann den Link öffnen."
                    auto -> "Produktname kopiert. Payback leitet automatisch zum Shop weiter – dort in die Suche einfügen."
                    else -> "Produktname kopiert. In Payback „Zur Amazon-App“ tippen, dann im Shop in die Suche einfügen."
                },
                Toast.LENGTH_LONG
            ).show()
        }
        // Payback-Automatik (Bedienungshilfe) kurz scharf machen: drückt dann „Zur Amazon-App“ (und öffnet ggf. das Produkt).
        if (auto) PaybackClicker.arm(ctx, productUrl)
        try {
            ctx.startActivity(viewIntent(ctx, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Payback konnte nicht geöffnet werden.", Toast.LENGTH_LONG).show()
        }
    }

    /** Payback-App für den Auto-Kauf öffnen (die Bedienungshilfe ist schon im Kauf-Modus scharf). */
    fun openForBuy(ctx: Context, shopKey: String) {
        val url = shopPage(shopKey) ?: return
        try {
            ctx.startActivity(viewIntent(ctx, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Wächter in PaybackClicker greift (Zeitüberschreitung → normaler Auto-Kauf)
        }
    }

    /** Für den Knopf in der Benachrichtigung (über [LaunchActivity], damit der Name kopiert wird). */
    fun pending(ctx: Context, shopKey: String, productName: String, requestCode: Int, productUrl: String? = null): PendingIntent =
        PendingIntent.getActivity(
            ctx, requestCode,
            Intent(ctx, LaunchActivity::class.java)
                .putExtra("shop", shopKey)
                .putExtra("name", productName)
                .putExtra("url", productUrl)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** Unsichtbare Zwischenstation: Name kopieren, Payback öffnen, fertig. */
    class LaunchActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            open(this, intent.getStringExtra("shop") ?: Product.SHOP_AMAZON, intent.getStringExtra("name"), intent.getStringExtra("url"))
            finish()
        }
    }
}

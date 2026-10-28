package de.amazonmonitor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Product(
    val asin: String,
    var name: String,
    var target: Double? = null,
    var lastPrice: Double? = null,
    var minPrice: Double? = null,
    var available: Boolean = true,
    var lastCheck: Long = 0L,
    var lastAlertPrice: Double? = null,
    var lastCartAdd: Long = 0L,
    /** Auto-Kauf für dieses Produkt scharf geschaltet (schaltet sich nach einem Versuch selbst aus). */
    var autoBuy: Boolean = false,
    var lastBuyAttempt: Long = 0L,
    var lastBuyResult: String = "",
    /** OfferListingId des zuletzt gesehenen Kaufbox-Angebots. */
    var offerId: String? = null,
    /** Verkäufer des zuletzt gesehenen Kaufbox-Angebots. */
    var seller: String = "",
    /** Festgehaltenes Angebot: Auto-Kauf nur, wenn genau dieses in der Kaufbox liegt. */
    var pinnedOffer: String? = null,
    /** Verkäufer des festgehaltenen Angebots (nur zur Anzeige). */
    var pinnedSeller: String = "",
    /** Shop: [SHOP_AMAZON] oder [SHOP_MUELLER]. */
    val shop: String = SHOP_AMAZON,
    /** [KIND_PRODUCT] oder [KIND_CATEGORY] (Kategorie-Überwachung, nur Web-Shops). */
    val kind: String = KIND_PRODUCT,
    /** Seiten-Link (bei Web-Shops nötig; bei Amazon leer, dort wird er aus Domain + ASIN gebaut). */
    val url: String = "",
    /** Kategorie: zuletzt gesehene Produkte als JSON {link: {n: Name, p: Preis, a: verfügbar}}. */
    var catState: String = "{}",
) {
    /** Web-Shop (Müller, MediaMarkt …) bzw. null für Amazon. */
    val webShop: WebShop? get() = WebShops.byKey(shop)
    val isWebShop: Boolean get() = shop != SHOP_AMAZON
    val isCategory: Boolean get() = kind == KIND_CATEGORY

    /** Angebot für den Warenkorb: festgehaltenes, sonst das zuletzt gesehene (nur Amazon). */
    val cartOffer: String? get() = if (isWebShop) null else pinnedOffer ?: offerId

    /** Produkt- bzw. Kategorieseite. */
    fun pageUrl(amazonDomain: String): String = if (isWebShop) url else "https://www.$amazonDomain/dp/$asin"

    fun toJson(): JSONObject = JSONObject()
        .put("shop", shop)
        .put("kind", kind)
        .put("url", url)
        .put("catState", catState)
        .put("offerId", offerId ?: JSONObject.NULL)
        .put("seller", seller)
        .put("pinnedOffer", pinnedOffer ?: JSONObject.NULL)
        .put("pinnedSeller", pinnedSeller)
        .put("autoBuy", autoBuy)
        .put("lastBuyAttempt", lastBuyAttempt)
        .put("lastBuyResult", lastBuyResult)
        .put("asin", asin)
        .put("name", name)
        .put("target", target ?: JSONObject.NULL)
        .put("lastPrice", lastPrice ?: JSONObject.NULL)
        .put("minPrice", minPrice ?: JSONObject.NULL)
        .put("available", available)
        .put("lastCheck", lastCheck)
        .put("lastAlertPrice", lastAlertPrice ?: JSONObject.NULL)
        .put("lastCartAdd", lastCartAdd)

    fun describe(): String {
        val sb = StringBuilder((webShop?.emoji ?: "🟡") + " ").append(name).append("\n")
        if (isCategory) {
            if (lastCheck == 0L) return sb.append("Kategorie – noch nicht geprüft").toString()
            val st = try { JSONObject(catState) } catch (e: Exception) { JSONObject() }
            var avail = 0
            for (k in st.keys()) if (st.getJSONObject(k).optBoolean("a")) avail++
            sb.append("Kategorie: ${st.length()} Produkte, davon $avail online verfügbar")
            target?.let { sb.append("\nAlarm unter: ${money(it)}") }
            sb.append("\nGeprüft: ${SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(lastCheck))}")
            return sb.toString()
        }
        val lp = lastPrice
        if (lastCheck == 0L) {
            sb.append("Noch nicht geprüft")
        } else {
            sb.append(if (lp != null) "Preis: ${money(lp)}" else "Preis: nicht gefunden")
            sb.append(if (available) "" else "  (nicht verfügbar)")
            val t = target
            if (t != null) sb.append("\nZielpreis: ${money(t)}")
            val mp = minPrice
            if (mp != null) sb.append("\nTiefstpreis: ${money(mp)}")
            sb.append("\nGeprüft: ${SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(lastCheck))}")
        }
        if (seller.isNotEmpty()) sb.append("\nVerkauf: ").append(seller)
        val pin = pinnedOffer
        if (pin != null) {
            sb.append("\n📌 Angebot festgehalten")
            if (pinnedSeller.isNotEmpty()) sb.append(" (").append(pinnedSeller).append(")")
            val cur = offerId
            if (cur != null && cur != pin) sb.append(" – gerade liegt ein ANDERES Angebot in der Kaufbox")
        }
        if (autoBuy) sb.append("\n🛒 Auto-Kauf scharf")
        if (lastBuyResult.isNotEmpty()) {
            sb.append("\nLetzter Auto-Kauf (")
                .append(SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(lastBuyAttempt)))
                .append("): ").append(lastBuyResult)
        }
        return sb.toString()
    }

    companion object {
        fun fromJson(o: JSONObject) = Product(
            asin = o.getString("asin"),
            name = o.optString("name", o.getString("asin")),
            target = o.optD("target"),
            lastPrice = o.optD("lastPrice"),
            minPrice = o.optD("minPrice"),
            available = o.optBoolean("available", true),
            lastCheck = o.optLong("lastCheck", 0L),
            lastAlertPrice = o.optD("lastAlertPrice"),
            lastCartAdd = o.optLong("lastCartAdd", 0L),
            autoBuy = o.optBoolean("autoBuy", false),
            lastBuyAttempt = o.optLong("lastBuyAttempt", 0L),
            lastBuyResult = o.optString("lastBuyResult", ""),
            offerId = o.optS("offerId"),
            seller = o.optString("seller", ""),
            pinnedOffer = o.optS("pinnedOffer"),
            pinnedSeller = o.optString("pinnedSeller", ""),
            shop = o.optString("shop", SHOP_AMAZON),
            kind = o.optString("kind", KIND_PRODUCT),
            url = o.optString("url", ""),
            catState = o.optString("catState", "{}"),
        )

        const val SHOP_AMAZON = "amazon"
        const val SHOP_MUELLER = "mueller"
        const val SHOP_MEDIAMARKT = "mediamarkt"
        const val KIND_PRODUCT = "product"
        const val KIND_CATEGORY = "category"

        private fun JSONObject.optS(k: String): String? =
            if (!has(k) || isNull(k)) null else getString(k).takeIf { it.isNotEmpty() }

        private fun JSONObject.optD(k: String): Double? = if (isNull(k)) null else getDouble(k)
    }
}

fun money(d: Double): String = String.format(Locale.GERMANY, "%.2f €", d)

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("monitor", Context.MODE_PRIVATE)

    @Synchronized
    fun load(c: Context): MutableList<Product> {
        val arr = JSONArray(prefs(c).getString("products", "[]"))
        return MutableList(arr.length()) { i -> Product.fromJson(arr.getJSONObject(i)) }
    }

    private fun write(c: Context, list: List<Product>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString("products", arr.toString()).apply()
    }

    @Synchronized
    fun add(c: Context, p: Product) {
        val list = load(c)
        list.removeAll { it.asin == p.asin }
        list.add(p)
        write(c, list)
    }

    @Synchronized
    fun remove(c: Context, asin: String) {
        val list = load(c)
        list.removeAll { it.asin == asin }
        write(c, list)
    }

    /** Ändert ein Produkt atomar; gibt das Ergebnis des Blocks zurück (null, wenn das Produkt fehlt). */
    @Synchronized
    fun <T> update(c: Context, asin: String, block: (Product) -> T): T? {
        val list = load(c)
        val p = list.firstOrNull { it.asin == asin } ?: return null
        val result = block(p)
        write(c, list)
        return result
    }

    fun domain(c: Context): String = prefs(c).getString("domain", "amazon.de") ?: "amazon.de"
    fun interval(c: Context): Int = prefs(c).getInt("interval", 60)
    fun dropPercent(c: Context): Int = prefs(c).getInt("drop", 10)

    fun webhook(c: Context): String = prefs(c).getString("webhook", "") ?: ""

    fun autoCart(c: Context): Boolean = prefs(c).getBoolean("autocart", false)

    fun saveSettings(c: Context, domain: String, interval: Int, drop: Int, webhook: String, autoCart: Boolean) {
        prefs(c).edit()
            .putString("domain", domain)
            .putInt("interval", interval)
            .putInt("drop", drop)
            .putString("webhook", webhook)
            .putBoolean("autocart", autoCart)
            .apply()
    }

    /** Hauptschalter für Auto-Kauf. Ohne ihn bestellt die App nie selbst, auch wenn Produkte scharf sind. */
    fun autoBuyEnabled(c: Context): Boolean = prefs(c).getBoolean("autobuy", false)

    /** Bei Alarm Amazon-Seite automatisch öffnen. */
    fun openOnAlert(c: Context): Boolean = prefs(c).getBoolean("open_on_alert", false)

    /** Probelauf/Auto-Kauf in einem sichtbaren Fenster zeigen. */
    fun watchBuy(c: Context): Boolean = prefs(c).getBoolean("watch_buy", false)

    /** Links in Chrome statt im App-Fenster öffnen. */
    fun externalBrowser(c: Context): Boolean = prefs(c).getBoolean("external_browser", false)

    fun saveWindows(c: Context, openOnAlert: Boolean, watchBuy: Boolean, externalBrowser: Boolean) {
        prefs(c).edit()
            .putBoolean("open_on_alert", openOnAlert)
            .putBoolean("watch_buy", watchBuy)
            .putBoolean("external_browser", externalBrowser)
            .apply()
    }

    /** Einkäufe bei diesem Shop über PAYBACK starten (°Punkte). */
    fun payback(c: Context, shopKey: String): Boolean = prefs(c).getBoolean("payback_$shopKey", false)

    fun savePayback(c: Context, shopKey: String, enabled: Boolean) {
        prefs(c).edit().putBoolean("payback_$shopKey", enabled).apply()
    }

    fun saveAutoBuy(c: Context, enabled: Boolean) {
        prefs(c).edit()
            .putBoolean("autobuy", enabled)
            .remove("autobuy_cap")
            .apply()
    }

    fun loginEmail(c: Context): String = prefs(c).getString("amz_mail", "") ?: ""

    fun hasLogin(c: Context): Boolean = prefs(c).getString("amz_pw", null) != null

    fun loginPassword(c: Context): String? = prefs(c).getString("amz_pw", null)?.let { Vault.decrypt(it) }

    /** Speichert die E-Mail; das Passwort nur, wenn eines übergeben wird (verschlüsselt). */
    fun saveLogin(c: Context, email: String, password: String?) {
        val e = prefs(c).edit().putString("amz_mail", email)
        if (password != null) e.putString("amz_pw", Vault.encrypt(password))
        e.apply()
    }

    fun clearLogin(c: Context) {
        prefs(c).edit().remove("amz_mail").remove("amz_pw").apply()
    }

    // --- Zugang für Web-Shops (Müller, MediaMarkt …); Passwort verschlüsselt im Android-Tresor
    fun shopEmail(c: Context, shop: WebShop): String = prefs(c).getString("${shop.credPrefix}_mail", "") ?: ""

    fun hasShopLogin(c: Context, shop: WebShop): Boolean = prefs(c).getString("${shop.credPrefix}_pw", null) != null

    fun shopPassword(c: Context, shop: WebShop): String? =
        prefs(c).getString("${shop.credPrefix}_pw", null)?.let { Vault.decrypt(it) }

    fun saveShopLogin(c: Context, shop: WebShop, email: String, password: String?) {
        val e = prefs(c).edit().putString("${shop.credPrefix}_mail", email)
        if (password != null) e.putString("${shop.credPrefix}_pw", Vault.encrypt(password))
        e.apply()
    }

    fun clearShopLogin(c: Context, shop: WebShop) {
        prefs(c).edit().remove("${shop.credPrefix}_mail").remove("${shop.credPrefix}_pw").apply()
    }

    fun status(c: Context): String = prefs(c).getString("status", "–") ?: "–"

    fun setStatus(c: Context, text: String) {
        val time = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date())
        prefs(c).edit().putString("status", "$text ($time)").apply()
    }
}

/** Verschlüsselt Texte mit einem Schlüssel aus dem Android-Keystore (AES-GCM). Der Schlüssel verlässt das Gerät nie. */
object Vault {
    private const val ALIAS = "amazon_monitor_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = try {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}

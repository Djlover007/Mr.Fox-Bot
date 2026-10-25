package de.amazonmonitor

import org.jsoup.Jsoup

object Scraper {
    class BlockedException(msg: String) : Exception(msg)

    data class Result(val title: String, val price: Double?, val available: Boolean)

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val PRICE_SELECTORS = listOf(
        "#corePrice_feature_div span.a-offscreen",
        "#corePriceDisplay_desktop_feature_div span.a-offscreen",
        "span.a-price span.a-offscreen",
        "#priceblock_ourprice",
        "#priceblock_dealprice",
        "#price_inside_buybox",
    )

    private val ASIN_URL = Regex("/(?:dp|gp/product)/([A-Za-z0-9]{10})")
    private val ASIN_PLAIN = Regex("^[A-Za-z0-9]{10}$")

    fun extractAsin(input: String): String? {
        val s = input.trim()
        ASIN_URL.find(s)?.let { return it.groupValues[1].uppercase() }
        if (ASIN_PLAIN.matches(s)) return s.uppercase()
        return null
    }

    /** "1.299,99 €" -> 1299.99, "$1,299.99" -> 1299.99 */
    fun parsePrice(text: String): Double? {
        val cleaned = text.replace(" ", "").replace(" ", "")
        val m = Regex("\\d[\\d.,]*").find(cleaned) ?: return null
        var s = m.value.trimEnd('.', ',')
        val hasComma = s.contains(',')
        val hasDot = s.contains('.')
        if (hasComma && hasDot) {
            s = if (s.lastIndexOf(',') > s.lastIndexOf('.')) {
                s.replace(".", "").replace(",", ".")
            } else {
                s.replace(",", "")
            }
        } else if (hasComma) {
            val tail = s.substringAfterLast(',')
            s = if (tail.length in 1..2) {
                s.substringBeforeLast(',').replace(",", "") + "." + tail
            } else {
                s.replace(",", "")
            }
        } else if (hasDot) {
            val tail = s.substringAfterLast('.')
            if (tail.length == 3) s = s.replace(".", "")
        }
        return s.toDoubleOrNull()
    }

    /** Blockierend – nur aus einem Hintergrund-Thread aufrufen. */
    fun fetch(domain: String, asin: String): Result {
        val url = "https://www.$domain/dp/$asin"
        val resp = Jsoup.connect(url)
            .userAgent(UA)
            .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.7")
            .timeout(25_000)
            .maxBodySize(0)
            .ignoreHttpErrors(true)
            .execute()
        val code = resp.statusCode()
        val html = resp.body()

        if (code == 429 || code == 503 ||
            html.contains("api-services-support@amazon.com") ||
            html.contains("validateCaptcha")
        ) {
            throw BlockedException("Blockiert (HTTP $code)")
        }
        if (code == 404) throw IllegalStateException("Produkt nicht gefunden (404)")
        if (code !in 200..299) throw IllegalStateException("HTTP $code")

        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("#productTitle")?.text()?.trim().orEmpty().ifEmpty { asin }

        var price: Double? = null
        for (sel in PRICE_SELECTORS) {
            val t = doc.selectFirst(sel)?.text()?.trim().orEmpty()
            if (t.isNotEmpty()) {
                price = parsePrice(t)
                if (price != null) break
            }
        }

        val avail = doc.selectFirst("#availability")?.text()?.lowercase().orEmpty()
        val unavailable = listOf("nicht verfügbar", "currently unavailable", "nicht auf lager")
            .any { avail.contains(it) }

        return Result(title, price, price != null && !unavailable)
    }
}

package de.amazonmonitor

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bedienungshilfe „Mr.Fox Bot – Payback-Automatik“.
 *
 * Tut nur etwas, wenn Mr.Fox Bot sie kurz vorher „scharf“ gemacht hat. Zwei Betriebsarten:
 *
 * OPEN (Knopf „Über Payback kaufen“, 90 Sek.): in der Payback-App Coupons aktivieren, „Zur Amazon-App“,
 * „Verstanden“, im Shop den Cookie-Hinweis akzeptieren – dann aus. Kaufen tust du selbst.
 *
 * BUY (Auto-Kauf über Payback, nur Amazon, max. 3 Min.): wie OPEN, danach in der Amazon-App die ASIN suchen,
 * Produkt öffnen, „Jetzt kaufen“, Schutzangebote ablehnen, Bestellung abschicken, Bestätigung abwarten.
 * Bleibt es vor dem Bestellen hängen, kauft Mr.Fox Bot auf dem normalen Weg (ohne °Punkte).
 * Mit „Probelauf“ wird vor dem Bestellen angehalten.
 */
class PaybackClicker : AccessibilityService() {
    private var couponClicks = 0
    private var lastAction = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        process(pkg)
    }

    /**
     * Regelmäßig selbst nachsehen, solange ein Vorgang läuft – ruhige Seiten lösen sonst keine
     * Ereignisse aus und die Automatik würde warten.
     */
    private val tick = object : Runnable {
        override fun run() {
            if (!isArmed(this@PaybackClicker)) return
            rootInActiveWindow?.packageName?.toString()?.let { process(it) }
            handler.postDelayed(this, 1_500)
        }
    }

    fun startTicking() {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1_500)
    }

    private fun process(pkg: String) {
        if (pkg == packageName) return // nie in Mr.Fox Bot selbst
        if (!isArmed(this)) {
            couponClicks = 0
            return
        }
        // Nicht schneller als alle 0,7 Sek. handeln (die Apps brauchen Zeit zum Umschalten)
        val now = System.currentTimeMillis()
        if (now - lastAction < 700) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        prefs(this).edit().putString(LAST_PKG, pkg).apply()
        val st = stage(this)
        val buy = mode(this) == MODE_BUY

        // --- Payback-App
        if (pkg == PAYBACK_PACKAGE) {
            find(root, UNDERSTOOD_RE).firstOrNull()?.let { if (act(it)) return }
            if (st >= STAGE_REDIRECTED) {
                // Shop evtl. in einem Browserfenster innerhalb der Payback-App
                find(root, COOKIE_RE).firstOrNull()?.let { if (act(it)) afterCookies() }
                return
            }
            if (couponClicks < 10) {
                for (n in find(root, COUPON_RE)) if (act(n)) couponClicks++
            }
            find(root, SHOP_RE).firstOrNull()?.let { if (act(it)) setStage(this, STAGE_REDIRECTED) }
            return
        }

        // --- Nach der Weiterleitung: Payback-Hinweis „Ohne Cookies keine °Punkte – Verstanden“ (erscheint oft
        //     in einem Browserfenster, also nicht in der Payback-App selbst), dann der Cookie-Hinweis des Shops
        if (st == STAGE_REDIRECTED) {
            find(root, UNDERSTOOD_RE).firstOrNull()?.let { if (act(it)) return }
            find(root, COOKIE_RE).firstOrNull()?.let {
                if (act(it)) afterCookies()
                return
            }
            // Kein Cookie-Hinweis in der Amazon-App? Nach 5 Sek. einfach weiter.
            if (buy && pkg == AMAZON_PACKAGE && now - stageTime(this) > 5_000) setStage(this, STAGE_SEARCH)
            return
        }
        if (!buy || pkg != AMAZON_PACKAGE) return

        // --- Amazon-App: Auto-Kauf
        when (st) {
            STAGE_SEARCH -> {
                val field = findEditText(root)
                if (field != null) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, asin(this@PaybackClicker))
                    }
                    field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    lastAction = now
                    handler.postDelayed({ submitSearch() }, 600)
                    setStage(this, STAGE_RESULTS)
                } else {
                    find(root, SEARCH_RE).firstOrNull()?.let { act(it) }
                        ?: findById(root, "search")?.let { act(it) }
                }
            }
            STAGE_RESULTS -> {
                if (find(root, BUY_NOW_RE).isNotEmpty()) {
                    setStage(this, STAGE_PRODUCT)
                    return
                }
                val key = nameKey(name(this))
                val hit = findContains(root, asin(this)) ?: if (key.isNotEmpty()) findContains(root, key) else null
                hit?.let { act(it) }
            }
            STAGE_PRODUCT -> {
                declineOffers(root)
                find(root, BUY_NOW_RE).firstOrNull()?.let { if (act(it)) setStage(this, STAGE_CHECKOUT) }
            }
            STAGE_CHECKOUT -> {
                if (declineOffers(root)) return
                val text = allText(root)
                if (!CHECKOUT_CONTEXT_RE.containsMatchIn(text)) return
                // Letzter Treffer: liegt die Bestellübersicht als Fenster über der Produktseite, ist das ihr Knopf
                val final = find(root, FINAL_RE).lastOrNull() ?: return
                if (dry(this)) {
                    finish(AutoBuyer.Outcome.DRY_RUN_OK, "Payback-Weg bis zum Bestellknopf „${label(final)}“ geschafft – Probelauf, nichts bestellt")
                    return
                }
                if (act(final)) setStage(this, STAGE_PLACED)
            }
            STAGE_PLACED -> {
                if (CONFIRM_RE.containsMatchIn(allText(root))) {
                    finish(AutoBuyer.Outcome.ORDERED, "Über Payback bestellt (Amazon-App) – °Punkte werden in einigen Tagen gutgeschrieben")
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun afterCookies() {
        if (mode(this) == MODE_BUY) setStage(this, STAGE_SEARCH) else disarm(this)
    }

    private fun submitSearch() {
        val field = rootInActiveWindow?.let { findEditText(it) } ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else {
            rootInActiveWindow?.let { r -> find(r, Regex("(?i)suchen|los")).firstOrNull()?.let { act(it) } }
        }
    }

    /** Geräteschutz/Garantie ablehnen. Gibt true zurück, wenn etwas geklickt wurde. */
    private fun declineOffers(root: AccessibilityNodeInfo): Boolean {
        val n = find(root, DECLINE_RE).firstOrNull() ?: return false
        return act(n)
    }

    private fun act(node: AccessibilityNodeInfo): Boolean {
        val ok = click(node)
        if (ok) lastAction = System.currentTimeMillis()
        return ok
    }

    private fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()?.trim() ?: ""

    /** Alle Elemente, deren Text oder Beschreibung genau zu [re] passt. */
    private fun find(root: AccessibilityNodeInfo, re: Regex): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { n ->
            val t = label(n)
            if (t.isNotEmpty() && t.length <= 40 && re.matches(t) && n.isVisibleToUser) out += n
        }
        return out
    }

    /** Erstes sichtbare Element, dessen Text [part] enthält (Suchergebnis). */
    private fun findContains(root: AccessibilityNodeInfo, part: String): AccessibilityNodeInfo? {
        var hit: AccessibilityNodeInfo? = null
        val p = part.lowercase()
        walk(root) { n ->
            if (hit == null && n.isVisibleToUser && n.className?.toString()?.contains("EditText") != true &&
                label(n).lowercase().contains(p)
            ) hit = n
        }
        return hit
    }

    private fun findEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var hit: AccessibilityNodeInfo? = null
        walk(root) { n -> if (hit == null && n.isEditable && n.isVisibleToUser) hit = n }
        return hit
    }

    private fun findById(root: AccessibilityNodeInfo, part: String): AccessibilityNodeInfo? {
        var hit: AccessibilityNodeInfo? = null
        walk(root) { n -> if (hit == null && n.isVisibleToUser && n.viewIdResourceName?.contains(part, true) == true) hit = n }
        return hit
    }

    private fun allText(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        walk(root) { n -> label(n).takeIf { it.isNotEmpty() }?.let { sb.append(it).append('\n') } }
        return sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo?, depth: Int = 0, f: (AccessibilityNodeInfo) -> Unit) {
        if (n == null || depth > 45) return
        f(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, f)
    }

    /** Klickt das Element oder das nächste anklickbare Eltern-Element. */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
            depth++
        }
        return false
    }

    /** Auto-Kauf über Payback beendet: Ergebnis speichern und melden. */
    private fun finish(outcome: AutoBuyer.Outcome, detail: String) {
        val ctx = applicationContext
        val asin = asin(ctx)
        disarm(ctx)
        report(ctx, asin, AutoBuyer.Result(outcome, detail))
    }

    companion object {
        const val PAYBACK_PACKAGE = "de.payback.client.android"
        const val AMAZON_PACKAGE = "com.amazon.mShop.android.shopping"

        private const val MODE_OPEN = "open"
        private const val MODE_BUY = "buy"

        private const val STAGE_START = 0
        private const val STAGE_REDIRECTED = 1
        private const val STAGE_SEARCH = 2
        private const val STAGE_RESULTS = 3
        private const val STAGE_PRODUCT = 4
        private const val STAGE_CHECKOUT = 5
        private const val STAGE_PLACED = 6

        private val COUPON_RE = Regex("(?i)(jetzt )?aktivieren|coupon aktivieren")
        private val UNDERSTOOD_RE = Regex("(?i)verstanden|ok,? verstanden|alles klar|weiter zum shop")
        private val COOKIE_RE = Regex("(?i)alle akzeptieren|alle cookies akzeptieren|cookies akzeptieren|akzeptieren|alle zulassen|zustimmen|zustimmen und weiter|accept( all)?( cookies)?")
        private val SHOP_RE = Regex("(?i)zur (amazon|mediamarkt|media markt)[- ]app|zum (online[- ])?shop|jetzt shoppen|zu (amazon|mediamarkt)|weiter zu (amazon|mediamarkt)")
        private val SEARCH_RE = Regex("(?i)suche|suchen|suchen auf amazon.*|amazon durchsuchen|search")
        private val BUY_NOW_RE = Regex("(?i)jetzt kaufen|sofort kaufen")
        private val FINAL_RE = Regex("(?i)bestellung aufgeben|jetzt kaufen|kaufen|zahlungspflichtig bestellen|jetzt bestellen|bestellen")
        private val CHECKOUT_CONTEXT_RE = Regex("(?i)bestellsumme|gesamtsumme|gesamtbetrag|bestellung überprüfen|zahlungsmethode|zahlungsart|bezahlen mit")
        private val CONFIRM_RE = Regex("(?i)bestellung (wurde )?aufgegeben|vielen dank|danke,? (ihre|deine) bestellung|bestellung bestätigt|bestellnummer")
        private val DECLINE_RE = Regex("(?i)nein,? danke|ohne (geräte)?schutz( fortfahren)?|ohne garantie( fortfahren)?|ich möchte (mein gerät|meine geräte) nicht schützen|keinen schutz hinzufügen|überspringen")

        private const val PREFS = "monitor"
        private const val KEY = "pb_armed_until"
        private const val STAGE = "pb_stage"
        private const val STAGE_AT = "pb_stage_at"
        private const val MODE = "pb_mode"
        private const val ASIN = "pb_asin"
        private const val NAME = "pb_name"
        private const val DRY = "pb_dry"
        private const val LAST_PKG = "pb_last_pkg"

        @Volatile
        private var instance: PaybackClicker? = null
        private val watchdog = Handler(Looper.getMainLooper())

        private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** OPEN: für 90 Sekunden scharf machen (vor dem Öffnen der Payback-App). */
        fun arm(ctx: Context) {
            prefs(ctx).edit()
                .putLong(KEY, System.currentTimeMillis() + 90_000L)
                .putInt(STAGE, STAGE_START).putLong(STAGE_AT, System.currentTimeMillis())
                .putString(MODE, MODE_OPEN).putString(LAST_PKG, "").apply()
            instance?.startTicking()
        }

        fun setStage(ctx: Context, s: Int) {
            val e = prefs(ctx).edit().putInt(STAGE, s).putLong(STAGE_AT, System.currentTimeMillis())
            // OPEN: nach der Weiterleitung noch 60 Sek. für „Verstanden“ und Cookies
            if (s == STAGE_REDIRECTED && mode(ctx) == MODE_OPEN) e.putLong(KEY, System.currentTimeMillis() + 60_000L)
            e.apply()
        }

        fun stage(ctx: Context): Int = prefs(ctx).getInt(STAGE, 0)
        private fun stageTime(ctx: Context): Long = prefs(ctx).getLong(STAGE_AT, 0L)
        private fun mode(ctx: Context): String = prefs(ctx).getString(MODE, MODE_OPEN) ?: MODE_OPEN
        private fun asin(ctx: Context): String = prefs(ctx).getString(ASIN, "") ?: ""
        private fun name(ctx: Context): String = prefs(ctx).getString(NAME, "") ?: ""
        private fun dry(ctx: Context): Boolean = prefs(ctx).getBoolean(DRY, false)

        /** Die ersten drei Wörter des Produktnamens (zum Wiederfinden in den Suchergebnissen). */
        private fun nameKey(name: String): String = name.split(' ').filter { it.isNotBlank() }.take(3).joinToString(" ")

        fun disarm(ctx: Context) {
            prefs(ctx).edit().putLong(KEY, 0L).putInt(STAGE, 0).apply()
        }

        fun isArmed(ctx: Context): Boolean = System.currentTimeMillis() < prefs(ctx).getLong(KEY, 0L)

        /** Ist die Bedienungshilfe in den Android-Einstellungen eingeschaltet? */
        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, PaybackClicker::class.java)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        private fun installed(ctx: Context, pkg: String): Boolean = try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }

        /**
         * Kann der Auto-Kauf über Payback gerade laufen? Gibt null zurück, wenn ja – sonst den Grund.
         * (Bedienungshilfe an, Payback- und Amazon-App installiert, Fenster aus dem Hintergrund erlaubt,
         * Bildschirm an und entsperrt.)
         */
        fun whyNotBuy(ctx: Context): String? {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            return when {
                !isEnabled(ctx) -> "Payback-Automatik (Bedienungshilfe) ist aus"
                !installed(ctx, PAYBACK_PACKAGE) -> "Payback-App nicht installiert"
                !installed(ctx, AMAZON_PACKAGE) -> "Amazon-App nicht installiert"
                !Links.canOpenFromBackground(ctx) -> "Erlaubnis „Über anderen Apps einblenden“ fehlt"
                !pm.isInteractive || km.isKeyguardLocked -> "Handy ist gesperrt"
                isArmed(ctx) -> "es läuft schon ein Payback-Vorgang"
                else -> null
            }
        }

        /** Startet den Auto-Kauf bzw. Probelauf über Payback → Amazon-App. */
        fun startBuy(ctx: Context, asin: String, name: String, dryRun: Boolean) {
            prefs(ctx).edit()
                .putLong(KEY, System.currentTimeMillis() + 180_000L)
                .putInt(STAGE, STAGE_START).putLong(STAGE_AT, System.currentTimeMillis())
                .putString(MODE, MODE_BUY).putString(ASIN, asin).putString(NAME, name).putBoolean(DRY, dryRun)
                .apply()
            prefs(ctx).edit().putString(LAST_PKG, "").apply()
            val app = ctx.applicationContext
            watchdog.post {
                Payback.openForBuy(app, Product.SHOP_AMAZON)
                instance?.startTicking()
            }
            scheduleWatchdog(app, asin)
        }

        /** Prüft alle 5 Sek., ob der Vorgang hängt; nach 3 Min. Abbruch (und ggf. normaler Auto-Kauf). */
        private fun scheduleWatchdog(ctx: Context, asin: String) {
            watchdog.postDelayed(object : Runnable {
                override fun run() {
                    if (mode(ctx) != MODE_BUY || asin(ctx) != asin) return
                    if (isArmed(ctx)) {
                        watchdog.postDelayed(this, 5_000)
                        return
                    }
                    if (prefs(ctx).getLong(KEY, 0L) == 0L) return // regulär beendet (disarm)
                    // Zeit abgelaufen
                    val st = stage(ctx)
                    val dry = dry(ctx)
                    disarm(ctx)
                    when {
                        st == STAGE_PLACED -> report(ctx, asin, AutoBuyer.Result(AutoBuyer.Outcome.UNCLEAR, "Payback-Weg: Bestellung abgeschickt, aber keine Bestätigung erkannt"))
                        dry -> report(ctx, asin, AutoBuyer.Result(AutoBuyer.Outcome.FAILED, "Payback-Probelauf hängt bei Schritt ${stepName(st)}${appName(ctx)}"))
                        else -> fallback(ctx, asin, "Payback-Weg hängt bei Schritt ${stepName(st)}${appName(ctx)}")
                    }
                }
            }, 5_000)
        }

        /** In welcher App der Vorgang zuletzt war (für die Fehlermeldung). */
        private fun appName(ctx: Context): String = when (val p = prefs(ctx).getString(LAST_PKG, "") ?: "") {
            "" -> ""
            PAYBACK_PACKAGE -> " (in der Payback-App)"
            AMAZON_PACKAGE -> " (in der Amazon-App)"
            "com.android.chrome" -> " (in Chrome – Amazon wurde im Browser statt in der Amazon-App geöffnet)"
            else -> " (in App: $p)"
        }

        private fun stepName(st: Int) = when (st) {
            STAGE_START -> "„Zur Amazon-App“"
            STAGE_REDIRECTED -> "Cookie-Hinweis"
            STAGE_SEARCH -> "Suche in der Amazon-App"
            STAGE_RESULTS -> "Suchergebnis"
            STAGE_PRODUCT -> "„Jetzt kaufen“"
            STAGE_CHECKOUT -> "Bestellseite"
            else -> "Bestätigung"
        }

        /** Payback-Weg gescheitert, bevor bestellt wurde: normal kaufen (ohne °Punkte). */
        private fun fallback(ctx: Context, asin: String, why: String) {
            CoroutineScope(Dispatchers.Main).launch {
                val res = try {
                    AutoBuyer.buy(ctx, Store.domain(ctx), asin, dryRun = false)
                } catch (e: Exception) {
                    AutoBuyer.Result(AutoBuyer.Outcome.FAILED, e.message ?: "Fehler")
                }
                report(ctx, asin, res.copy(detail = res.detail + " ($why – normaler Auto-Kauf ohne °Punkte)"))
            }
        }

        private fun report(ctx: Context, asin: String, res: AutoBuyer.Result) {
            val line = "💳 " + AutoBuyer.describe(res)
            CoroutineScope(Dispatchers.IO).launch {
                Store.update(ctx, asin) {
                    it.lastBuyResult = line
                    it.lastBuyAttempt = System.currentTimeMillis()
                }
                val p = Store.load(ctx).firstOrNull { it.asin == asin }
                val title = p?.name ?: asin
                val url = p?.pageUrl(Store.domain(ctx)) ?: "https://www.${Store.domain(ctx)}/dp/$asin"
                withContext(Dispatchers.Main) { Checker.notifyResult(ctx, title, line, url) }
                Checker.sendDiscord(Store.webhook(ctx), "**$title**\n$line\n<$url>")
            }
        }
    }
}

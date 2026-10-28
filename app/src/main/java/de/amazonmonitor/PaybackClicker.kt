package de.amazonmonitor

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Bedienungshilfe „Mr.Fox Bot – Payback-Automatik“.
 *
 * Wirkt ausschließlich in der Payback-App und nur, wenn Mr.Fox Bot sie kurz vorher „scharf“ gemacht hat
 * (Knopf „Über Payback kaufen“, 90 Sekunden). Dann:
 *  1. sichtbare, noch nicht aktivierte Coupons aktivieren,
 *  2. einmal „Zur Amazon-App“ / „Zur MediaMarkt-App“ / „Jetzt shoppen“ drücken – danach wieder aus.
 * Sonst tut sie nichts.
 */
class PaybackClicker : AccessibilityService() {
    private var couponClicks = 0

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != PAYBACK_PACKAGE) return
        if (!isArmed(this)) {
            couponClicks = 0
            return
        }
        val root = rootInActiveWindow ?: return

        // 1. Coupons aktivieren (nur sichtbare, höchstens 10)
        if (couponClicks < 10) {
            for (n in find(root, COUPON_RE)) {
                if (click(n)) couponClicks++
            }
        }

        // 2. Weiter zum Shop
        val target = find(root, SHOP_RE).firstOrNull() ?: return
        if (click(target)) disarm(this)
    }

    override fun onInterrupt() {}

    /** Alle Elemente, deren Text oder Beschreibung zu [re] passt. */
    private fun find(root: AccessibilityNodeInfo, re: Regex): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (t != null && t.length <= 40 && re.matches(t) && n.isVisibleToUser) out += n
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
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

    companion object {
        const val PAYBACK_PACKAGE = "de.payback.client.android"
        private val COUPON_RE = Regex("(?i)(jetzt )?aktivieren|coupon aktivieren")
        private val SHOP_RE = Regex("(?i)zur (amazon|mediamarkt|media markt)[- ]app|zum (online[- ])?shop|jetzt shoppen|zu (amazon|mediamarkt)|weiter zu (amazon|mediamarkt)")
        private const val PREFS = "monitor"
        private const val KEY = "pb_armed_until"

        /** Für 90 Sekunden scharf machen (vor dem Öffnen der Payback-App). */
        fun arm(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY, System.currentTimeMillis() + 90_000L).apply()
        }

        fun disarm(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, 0L).apply()
        }

        fun isArmed(ctx: Context): Boolean =
            System.currentTimeMillis() < ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, 0L)

        /** Ist die Bedienungshilfe in den Android-Einstellungen eingeschaltet? */
        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, PaybackClicker::class.java)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

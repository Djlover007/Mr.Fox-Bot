package de.amazonmonitor

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/**
 * Eigene Seite „Konten & Einstellungen“: E-Mail und Passwort für Amazon, Müller, MediaMarkt, BAUR, PayPal
 * und Payback verwalten (Passwörter verschlüsselt im Android-Schlüsselspeicher) und Dark Mode an/aus.
 *
 * Amazon und die Shops: der Bot meldet sich damit bei Auto-Kauf/Warenkorb selbst an.
 * PayPal und Payback: E-Mail/Passwort werden auf deren Anmeldeseite im App-Fenster nur EINGETRAGEN –
 * „Einloggen“ und die Bestätigung (2FA) machst du selbst.
 */
class AccountsActivity : AppCompatActivity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** Wartet auf die Entsperrung (PIN/Fingerabdruck), bevor ein Passwort angezeigt wird. */
    private var afterUnlock: (() -> Unit)? = null
    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) afterUnlock?.invoke() else toast("Nicht entsperrt – Passwort bleibt verborgen.")
        afterUnlock = null
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 19f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        alpha = 0.8f
    }

    private fun small(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        textSize = 12f
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Konten & Einstellungen"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        // --- Darstellung
        root.addView(label("🌙 Darstellung"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val modes = listOf(
            Store.THEME_LIGHT to "☀️ Hell",
            Store.THEME_DARK to "🌙 Dunkel (Dark Mode)",
            Store.THEME_SYSTEM to "📱 Wie das Handy (System-Einstellung)",
        ).map { (key, t) ->
            key to RadioButton(this).apply { id = View.generateViewId(); text = t; textSize = 16f }.also { group.addView(it) }
        }
        modes.firstOrNull { it.first == Store.themeMode(this) }?.second?.isChecked = true
        group.setOnCheckedChangeListener { _, id ->
            val mode = modes.firstOrNull { it.second.id == id }?.first ?: return@setOnCheckedChangeListener
            if (mode == Store.themeMode(this)) return@setOnCheckedChangeListener
            Store.saveThemeMode(this, mode)
            AppCompatDelegate.setDefaultNightMode(Store.nightMode(this)) // baut die Seiten in der neuen Farbe neu auf
        }
        root.addView(group)

        // --- Konten
        root.addView(label("🔑 Konten & Passwörter"))
        root.addView(hint(
            "Passwörter werden verschlüsselt im Android-Schlüsselspeicher abgelegt, verlassen das Handy nie und werden " +
                "auch nicht exportiert. Zum Anzeigen musst du das Handy entsperren (PIN/Fingerabdruck)."
        ))
        val amazonLogin = "https://www.${Store.domain(this)}/gp/sign-in.html"
        root.addView(card(
            Product.SHOP_AMAZON, "🟡 Amazon",
            "Auto-Kauf und automatischer Warenkorb melden sich damit bei Bedarf selbst an. Bei Code/Captcha einmal „In der App anmelden“.",
            amazonLogin, chromeUrl = amazonLogin,
        ))
        for (ws in WebShops.all) {
            root.addView(card(ws.key, "${ws.emoji} ${ws.label}", "Für Auto-Kauf und Warenkorb bei ${ws.label}.", ws.accountUrl, chromeUrl = ws.accountUrl))
        }
        root.addView(card(
            Store.ACC_PAYPAL, "🅿️ PayPal",
            "Wird auf der PayPal-Anmeldeseite im App-Fenster eingetragen – „Einloggen“ und die Bestätigung machst du selbst " +
                "(„Eingeloggt bleiben“ anhaken). Danach kann der Bot nach dem Bestellen „Jetzt zahlen“ drücken.",
            "https://www.paypal.com/signin",
        ))
        root.addView(card(
            Store.ACC_PAYBACK, "💳 Payback",
            "Zum Nachschlagen und Eintragen auf der Payback-Anmeldeseite im App-Fenster (2FA bestätigst du selbst). " +
                "Die Payback-App hat ihre eigene Anmeldung.",
            "https://www.payback.de/login",
        ))

        root.addView(TextView(this).apply {
            text = "🦊 Design by Mr.Fox"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD_ITALIC)
            alpha = 0.7f
            setPadding(0, dp(28), 0, dp(8))
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** Karte für ein Konto: E-Mail, Passwort (anzeigen/verbergen), Speichern, Löschen, Anmelden. */
    private fun card(key: String, title: String, info: String, loginUrl: String, chromeUrl: String? = null): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0x55888888)
                setColor(0x10888888)
            }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
        box.layoutParams = lp
        box.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(hint(info))
        val mail = EditText(this).apply {
            hint = "E-Mail"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(Store.accEmail(this@AccountsActivity, key))
        }
        val pwHidden = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val pwShown = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val pw = EditText(this).apply { inputType = pwHidden }
        val status = hint("")
        fun refresh() {
            val has = Store.hasAccPassword(this, key)
            pw.hint = if (has) "Passwort (gespeichert – leer lassen zum Behalten)" else "Passwort"
            status.text = when {
                has -> "✅ Passwort gespeichert (verschlüsselt)"
                Store.accEmail(this, key).isNotEmpty() -> "Nur E-Mail gespeichert"
                else -> "Noch nichts gespeichert"
            }
        }
        refresh()
        box.addView(mail)
        box.addView(pw)
        box.addView(status)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val weight = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row1.addView(small("💾 Speichern") {
            val m = mail.text.toString().trim()
            val p = pw.text.toString()
            when {
                m.isEmpty() -> toast("Bitte E-Mail eintragen.")
                p.isEmpty() && !Store.hasAccPassword(this, key) && key != Store.ACC_PAYBACK && key != Store.ACC_PAYPAL ->
                    toast("Bitte Passwort eintragen.")
                else -> {
                    Store.saveAcc(this, key, m, p.ifEmpty { null })
                    pw.setText("")
                    pw.inputType = pwHidden
                    refresh()
                    toast("$title: gespeichert.")
                }
            }
        }, weight)
        row1.addView(small("👁 Anzeigen") {
            if (pw.inputType == pwShown) {
                pw.inputType = pwHidden
                pw.setSelection(pw.text.length)
                return@small
            }
            val show = {
                if (pw.text.isEmpty()) Store.accPassword(this, key)?.let { pw.setText(it) }
                pw.inputType = pwShown
                pw.setSelection(pw.text.length)
            }
            // Gespeichertes Passwort nur nach Entsperren zeigen
            if (pw.text.isEmpty() && Store.hasAccPassword(this, key)) requireUnlock(show) else show()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(small("🗑 Löschen") {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("$title löschen?")
                .setMessage("E-Mail und Passwort werden aus der App entfernt.")
                .setPositiveButton("Löschen") { _, _ ->
                    Store.clearAcc(this, key)
                    mail.setText("")
                    pw.setText("")
                    refresh()
                    toast("$title: gelöscht.")
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(small("🔐 In der App anmelden") {
            startActivity(Links.inApp(this, loginUrl))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (chromeUrl != null) {
            row2.addView(small("🌐 In Chrome anmelden") { Links.openInChrome(this, chromeUrl) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(row2)
        return box
    }

    /** Fragt PIN/Muster/Fingerabdruck ab (wenn das Handy gesichert ist) und führt dann [then] aus. */
    @Suppress("DEPRECATION")
    private fun requireUnlock(then: () -> Unit) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent = if (km.isDeviceSecure) km.createConfirmDeviceCredentialIntent("Passwort anzeigen", "Zum Anzeigen bitte entsperren") else null
        if (intent == null) {
            then()
        } else {
            afterUnlock = then
            unlock.launch(intent)
        }
    }
}

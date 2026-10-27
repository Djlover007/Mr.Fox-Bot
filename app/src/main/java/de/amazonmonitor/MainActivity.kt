package de.amazonmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var listBox: LinearLayout
    private lateinit var statusView: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun openWeb(url: String, checkout: Boolean = false) {
        startActivity(
            Intent(this, WebActivity::class.java)
                .putExtra("url", url)
                .putExtra("checkout", checkout)
        )
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun edit(h: String, type: Int = InputType.TYPE_CLASS_TEXT, value: String = "") =
        EditText(this).apply {
            hint = h
            inputType = type
            setText(value)
        }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Mr.Fox Bot"

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        // --- Produkt hinzufügen
        root.addView(label("Produkt hinzufügen"))
        val inUrl = edit("Amazon-Link oder ASIN", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val inName = edit("Name (optional)")
        val inTarget = edit(
            "Zielpreis in € (optional)",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        root.addView(inUrl)
        root.addView(inName)
        root.addView(inTarget)
        root.addView(button("Hinzufügen") {
            val asin = Scraper.extractAsin(inUrl.text.toString())
            if (asin == null) {
                toast("Keine ASIN erkannt. Nutze den vollen Link (…/dp/B0XXXXXXXX) oder die ASIN. Kurzlinks wie amzn.to gehen nicht.")
            } else {
                val target = inTarget.text.toString().replace(',', '.').toDoubleOrNull()
                val name = inName.text.toString().trim().ifEmpty { asin }
                Store.add(this, Product(asin, name, target))
                inUrl.setText("")
                inName.setText("")
                inTarget.setText("")
                schedule(Store.interval(this), update = false)
                refresh()
            }
        })

        // --- Einstellungen
        root.addView(label("Einstellungen"))
        val inDomain = edit("Amazon-Domain (z. B. amazon.de)", InputType.TYPE_CLASS_TEXT, Store.domain(this))
        val inInterval = edit(
            "Prüfintervall in Minuten (min. 15)",
            InputType.TYPE_CLASS_NUMBER,
            Store.interval(this).toString()
        )
        val inDrop = edit(
            "Alarm bei Preisfall in % (0 = aus)",
            InputType.TYPE_CLASS_NUMBER,
            Store.dropPercent(this).toString()
        )
        val inWebhook = edit(
            "Discord-Webhook-URL (optional)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            Store.webhook(this)
        )
        root.addView(inDomain)
        root.addView(inInterval)
        root.addView(inDrop)
        root.addView(inWebhook)
        val cbCart = CheckBox(this).apply {
            text = "Bei Alarm automatisch in den Warenkorb legen (Anmeldung in der App nötig)"
            isChecked = Store.autoCart(this@MainActivity)
        }
        root.addView(cbCart)
        val cbBuy = CheckBox(this).apply {
            text = "Auto-Kauf erlauben: scharf geschaltete Produkte werden OHNE Rückfrage bestellt " +
                "(Standard-Adresse und -Zahlungsart)"
            isChecked = Store.autoBuyEnabled(this@MainActivity)
        }
        val inCap = edit(
            "Auto-Kauf: max. Gesamtbetrag pro Bestellung in € (inkl. Versand)",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            Store.autoBuyCap(this).let { if (it > 0) it.toString() else "" }
        )
        root.addView(cbBuy)
        root.addView(inCap)
        root.addView(button("Speichern & Überwachung starten") {
            val domain = inDomain.text.toString().trim().removePrefix("www.").ifEmpty { "amazon.de" }
            val interval = (inInterval.text.toString().toIntOrNull() ?: 60).coerceAtLeast(15)
            val drop = inDrop.text.toString().toIntOrNull() ?: 10
            Store.saveSettings(this, domain, interval, drop, inWebhook.text.toString().trim(), cbCart.isChecked)
            val cap = inCap.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            if (cbBuy.isChecked && cap <= 0.0) {
                cbBuy.isChecked = false
                Store.saveAutoBuy(this, false, 0.0)
                toast("Auto-Kauf bleibt aus: bitte einen Höchstbetrag eintragen.")
            } else {
                Store.saveAutoBuy(this, cbBuy.isChecked, cap)
                toast(
                    "Überwachung läuft alle $interval Minuten." +
                        if (cbBuy.isChecked) " Auto-Kauf aktiv, Limit ${money(cap)}." else ""
                )
            }
            schedule(interval, update = true)
            refresh()
        })
        root.addView(button("Discord testen") {
            val hook = inWebhook.text.toString().trim()
            if (hook.isEmpty()) {
                toast("Bitte zuerst die Webhook-URL eintragen.")
            } else {
                Thread {
                    val ok = Checker.sendDiscord(hook, "✅ Mr.Fox Bot: Testnachricht")
                    runOnUiThread { toast(if (ok) "Testnachricht gesendet." else "Senden fehlgeschlagen. URL prüfen.") }
                }.start()
            }
        })

        // --- Amazon-Zugang (verschlüsselt gespeichert)
        root.addView(label("Amazon-Zugang (optional)"))
        val inMail = edit(
            "E-Mail",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            Store.loginEmail(this)
        )
        val inPw = edit(
            if (Store.hasLogin(this)) "Passwort (gespeichert – leer lassen zum Behalten)" else "Passwort",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        root.addView(inMail)
        root.addView(inPw)
        root.addView(button("Zugang speichern") {
            val mail = inMail.text.toString().trim()
            val pw = inPw.text.toString()
            if (mail.isEmpty()) {
                toast("Bitte E-Mail eintragen.")
            } else if (pw.isEmpty() && !Store.hasLogin(this)) {
                toast("Bitte Passwort eintragen.")
            } else {
                Store.saveLogin(this, mail, if (pw.isEmpty()) null else pw)
                inPw.setText("")
                toast("Zugang verschlüsselt gespeichert.")
            }
        })
        root.addView(button("Zugang löschen") {
            Store.clearLogin(this)
            inMail.setText("")
            inPw.setText("")
            toast("Zugang gelöscht.")
        })

        root.addView(button("Bei Amazon anmelden") {
            openWeb("https://www.${Store.domain(this)}/gp/sign-in.html")
        })

        root.addView(button("Jetzt prüfen") {
            val req = OneTimeWorkRequestBuilder<CheckWorker>().build()
            val wm = WorkManager.getInstance(this)
            wm.enqueue(req)
            toast("Prüfung läuft …")
            wm.getWorkInfoByIdLiveData(req.id).observe(this) { info ->
                if (info != null && info.state.isFinished) refresh()
            }
        })

        // --- Liste
        statusView = TextView(this).apply { setPadding(0, dp(12), 0, 0) }
        root.addView(statusView)
        root.addView(label("Überwachte Produkte"))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        root.addView(TextView(this).apply {
            text = "Tipp: Schalte für diese App die Akku-Optimierung aus (Einstellungen → Apps → Mr.Fox Bot → Akku → Uneingeschränkt), " +
                "sonst verschiebt Android die Prüfungen im Hintergrund oft stark."
            textSize = 12f
            setPadding(0, dp(24), 0, 0)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun schedule(intervalMin: Int, update: Boolean) {
        val req = PeriodicWorkRequestBuilder<CheckWorker>(intervalMin.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "amazon-check",
            if (update) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }

    private fun toggleAutoBuy(p: Product) {
        if (p.autoBuy) {
            Store.update(this, p.asin) { it.autoBuy = false }
            toast("Auto-Kauf für ${p.name} entschärft.")
            refresh()
            return
        }
        val target = p.target
        if (target == null) {
            toast("Auto-Kauf braucht einen Zielpreis. Produkt mit Zielpreis neu hinzufügen.")
            return
        }
        if (!Store.autoBuyEnabled(this)) {
            toast("Erst unter Einstellungen „Auto-Kauf erlauben“ ankreuzen, Höchstbetrag eintragen und speichern.")
            return
        }
        val cap = Store.autoBuyCap(this)
        AlertDialog.Builder(this)
            .setTitle("Auto-Kauf scharf schalten?")
            .setMessage(
                "${p.name} wird automatisch und ohne Rückfrage bestellt, sobald der Preis bei " +
                    "höchstens ${money(minOf(target, cap))} liegt. Zeigt die Bestellseite einen " +
                    "Gesamtbetrag über ${money(cap)}, wird abgebrochen; ist er nicht lesbar, wird " +
                    "trotzdem bestellt.\n\n" +
                    "• Menge 1, über „Jetzt kaufen“ (der übrige Warenkorb wird nicht mitbestellt)\n" +
                    "• Standard-Lieferadresse und -Zahlungsart deines Amazon-Kontos\n" +
                    "• Nur EIN Versuch, danach schaltet sich Auto-Kauf für dieses Produkt aus\n\n" +
                    "Teste vorher den Probelauf."
            )
            .setPositiveButton("Scharf schalten") { _, _ ->
                Store.update(this, p.asin) { it.autoBuy = true }
                toast("Auto-Kauf für ${p.name} ist scharf.")
                refresh()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun refresh() {
        statusView.text = "Status: " + Store.status(this)
        listBox.removeAllViews()
        val items = Store.load(this)
        if (items.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "Noch keine Produkte." })
            return
        }
        for (p in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }
            val info = TextView(this).apply {
                text = p.describe()
                textSize = 15f
                setOnClickListener {
                    openWeb("https://www.${Store.domain(this@MainActivity)}/dp/${p.asin}")
                }
            }
            val cart = Button(this).apply {
                text = "In den Warenkorb"
                setOnClickListener {
                    openWeb(Checker.cartUrl(Store.domain(this@MainActivity), p.asin))
                }
            }
            val cartTest = Button(this).apply {
                text = "Automatisch in Warenkorb (Test)"
                setOnClickListener {
                    isEnabled = false
                    toast("Test läuft, bitte etwa 20–60 Sekunden warten …")
                    CoroutineScope(Dispatchers.Main).launch {
                        val res = try {
                            CartAdder.add(this@MainActivity, Store.domain(this@MainActivity), p.asin)
                        } catch (e: Exception) {
                            CartAdder.lastDetail = e.message ?: "Fehler"
                            CartAdder.Result.FAILED
                        }
                        isEnabled = true
                        val text = when (res) {
                            CartAdder.Result.OK -> "✅ Im Warenkorb: ${CartAdder.lastDetail}"
                            CartAdder.Result.NOT_LOGGED_IN -> "⚠️ Nicht angemeldet. Erst „Bei Amazon anmelden“ nutzen."
                            CartAdder.Result.FAILED -> "⚠️ Nicht geklappt: ${CartAdder.lastDetail}"
                        }
                        toast(text)
                    }
                }
            }
            val checkout = Button(this).apply {
                text = "Zur Kasse"
                setOnClickListener {
                    openWeb("https://www.${Store.domain(this@MainActivity)}/gp/cart/view.html", true)
                }
            }
            val del = Button(this).apply {
                text = "Entfernen"
                setOnClickListener {
                    Store.remove(this@MainActivity, p.asin)
                    refresh()
                }
            }
            val arm = Button(this).apply {
                text = if (p.autoBuy) "Auto-Kauf entschärfen" else "Auto-Kauf scharf schalten"
                setOnClickListener { toggleAutoBuy(p) }
            }
            val dry = Button(this).apply {
                text = "Auto-Kauf Probelauf (bestellt nicht)"
                setOnClickListener {
                    val cap = Store.autoBuyCap(this@MainActivity)
                    if (cap <= 0.0) {
                        toast("Bitte zuerst unter Einstellungen einen Auto-Kauf-Höchstbetrag eintragen und speichern.")
                        return@setOnClickListener
                    }
                    isEnabled = false
                    toast("Probelauf läuft, bitte bis zu 2 Minuten warten … Es wird nichts bestellt.")
                    CoroutineScope(Dispatchers.Main).launch {
                        val res = try {
                            AutoBuyer.buy(this@MainActivity, Store.domain(this@MainActivity), p.asin, cap, dryRun = true)
                        } catch (e: Exception) {
                            AutoBuyer.Result(AutoBuyer.Outcome.FAILED, e.message ?: "Fehler")
                        }
                        isEnabled = true
                        if (isFinishing || isDestroyed) return@launch
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Probelauf: ${p.name}")
                            .setMessage(AutoBuyer.describe(res))
                            .setPositiveButton("OK", null)
                            .show()
                            .findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
                    }
                }
            }
            row.addView(info)
            row.addView(cart)
            row.addView(cartTest)
            row.addView(checkout)
            row.addView(arm)
            row.addView(dry)
            row.addView(del)
            listBox.addView(row)
        }
    }
}

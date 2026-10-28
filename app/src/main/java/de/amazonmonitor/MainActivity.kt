package de.amazonmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var listBox: LinearLayout
    private lateinit var statusView: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private var overlayHint: () -> Unit = {}

    private fun overlayText() =
        if (Links.canOpenFromBackground(this)) "✅ „Über anderen Apps einblenden“ erlaubt"
        else "Erlaubnis „Über anderen Apps einblenden“ geben (für Fenster bei Alarm)"

    /** Öffnet je nach Einstellung im App-Fenster oder in Chrome. */
    private fun openWeb(url: String, checkout: Boolean = false) = Links.open(this, url, checkout)

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
        val inOffer = edit("OfferListingId (optional – hält genau dieses Angebot fest)")
        root.addView(inUrl)
        root.addView(inName)
        root.addView(inTarget)
        root.addView(inOffer)
        root.addView(button("Hinzufügen") {
            val asin = Scraper.extractAsin(inUrl.text.toString())
            if (asin == null) {
                toast("Keine ASIN erkannt. Nutze den vollen Link (…/dp/B0XXXXXXXX) oder die ASIN. Kurzlinks wie amzn.to gehen nicht.")
            } else {
                val target = inTarget.text.toString().replace(',', '.').toDoubleOrNull()
                val name = inName.text.toString().trim().ifEmpty { asin }
                // OfferListingId aus eigenem Feld oder aus dem eingefügten Link (falls dort enthalten).
                val offer = Scraper.extractOfferId(inOffer.text.toString())
                    ?: Regex("(?i)offerlistingid").find(inUrl.text)?.let { Scraper.extractOfferId(inUrl.text.toString()) }
                Store.add(this, Product(asin, name, target, pinnedOffer = offer))
                inUrl.setText("")
                inName.setText("")
                inTarget.setText("")
                inOffer.setText("")
                schedule(Store.interval(this), update = false)
                refresh()
                toast("Produkt hinzugefügt – lese OfferListingId …")
                // Sofort prüfen: liest Preis, Verkäufer und OfferListingId und hinterlegt sie bei Discord.
                val app = applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    val err = Checker.checkOne(app, asin)
                    val p = Store.load(app).firstOrNull { it.asin == asin }
                    // Nur von Hand eingetragene ID (auf der Seite keine gefunden): trotzdem bei Discord hinterlegen.
                    if (err == null && p != null && p.offerId == null && p.pinnedOffer != null) {
                        Checker.sendDiscord(
                            Store.webhook(app),
                            "🆕 OfferListingId (von Hand)\n**${p.name}**\n${Checker.offerInfo(Store.domain(app), p)}"
                        )
                    }
                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        refresh()
                        val id = p?.cartOffer
                        val discord = if (Store.webhook(app).isNotBlank()) " – bei Discord hinterlegt" else ""
                        toast(
                            when {
                                err != null -> "Prüfung fehlgeschlagen: $err"
                                id != null -> "OfferListingId gefunden$discord:\n$id"
                                else -> "Keine OfferListingId gefunden (Artikel evtl. nicht verfügbar)."
                            }
                        )
                    }
                }
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
        root.addView(cbBuy)

        // --- Fenster
        root.addView(label("Fenster"))
        val cbOpen = CheckBox(this).apply {
            text = "Bei Alarm Amazon automatisch öffnen (Produkt; nach Auto-Kauf: Bestellungen)"
            isChecked = Store.openOnAlert(this@MainActivity)
        }
        val cbWatch = CheckBox(this).apply {
            text = "Probelauf und Auto-Kauf in sichtbarem Fenster zeigen"
            isChecked = Store.watchBuy(this@MainActivity)
        }
        val cbChrome = CheckBox(this).apply {
            text = "Links in Chrome statt im App-Fenster öffnen " +
                "(Chrome hat eine eigene Amazon-Anmeldung; „Zur Kasse“ klickt dort nicht automatisch)"
            isChecked = Store.externalBrowser(this@MainActivity)
        }
        root.addView(cbOpen)
        root.addView(cbWatch)
        root.addView(cbChrome)
        val overlayBtn = button("") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        overlayHint = { overlayBtn.text = overlayText() }
        overlayHint()
        root.addView(overlayBtn)

        root.addView(button("Speichern & Überwachung starten") {
            Store.saveWindows(this, cbOpen.isChecked, cbWatch.isChecked, cbChrome.isChecked)
            if ((cbOpen.isChecked || cbWatch.isChecked) && !Links.canOpenFromBackground(this)) {
                toast("Tipp: Erlaube „Über anderen Apps einblenden“, sonst öffnen sich Fenster im Hintergrund nur bei gesperrtem Bildschirm.")
            }
            val domain = inDomain.text.toString().trim().removePrefix("www.").ifEmpty { "amazon.de" }
            val interval = (inInterval.text.toString().toIntOrNull() ?: 60).coerceAtLeast(15)
            val drop = inDrop.text.toString().toIntOrNull() ?: 10
            Store.saveSettings(this, domain, interval, drop, inWebhook.text.toString().trim(), cbCart.isChecked)
            Store.saveAutoBuy(this, cbBuy.isChecked)
            toast(
                "Überwachung läuft alle $interval Minuten." +
                    if (cbBuy.isChecked) " Auto-Kauf erlaubt." else ""
            )
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
        root.addView(button("Alle OfferListingIds an Discord senden") {
            if (Store.webhook(this).isBlank()) {
                toast("Bitte zuerst die Webhook-URL eintragen und speichern.")
            } else if (Store.load(this).isEmpty()) {
                toast("Noch keine Produkte.")
            } else {
                Thread {
                    val ok = Checker.sendAllOffers(applicationContext)
                    runOnUiThread { toast(if (ok) "An Discord gesendet." else "Senden fehlgeschlagen. URL prüfen.") }
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
                toast("Zugang verschlüsselt gespeichert. Auto-Kauf und Warenkorb melden sich damit bei Bedarf selbst an.")
            }
        })
        root.addView(button("Zugang löschen") {
            Store.clearLogin(this)
            inMail.setText("")
            inPw.setText("")
            toast("Zugang gelöscht.")
        })

        root.addView(button("Bei Amazon anmelden (Chrome)") {
            // Anmeldung in Chrome – Chromes Passwortmanager kann die Daten ausfüllen.
            Links.openInChrome(this, "https://www.${Store.domain(this)}/gp/sign-in.html")
        })
        root.addView(button("In der App anmelden (nur nötig bei Code/Captcha für Auto-Kauf)") {
            // Auto-Kauf und automatischer Warenkorb nutzen die Anmeldung der App, nicht die von Chrome.
            startActivity(Links.inApp(this, "https://www.${Store.domain(this)}/gp/sign-in.html"))
        }.apply { textSize = 12f })

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
        overlayHint()
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

    /** Hält das zuletzt gesehene Kaufbox-Angebot (OfferListingId) fest bzw. gibt es wieder frei. */
    private fun togglePin(p: Product) {
        if (p.pinnedOffer != null) {
            Store.update(this, p.asin) { it.pinnedOffer = null; it.pinnedSeller = "" }
            toast("Angebot freigegeben – Auto-Kauf nimmt wieder das Angebot in der Kaufbox.")
            refresh()
            return
        }
        val offer = p.offerId
        if (offer == null) {
            toast("Noch keine OfferListingId bekannt. Erst „Jetzt prüfen“, oder die ID beim Hinzufügen eintragen.")
            return
        }
        val who = p.seller.ifEmpty { "unbekannter Verkäufer" }
        AlertDialog.Builder(this)
            .setTitle("Angebot festhalten?")
            .setMessage(
                "Aktuelles Kaufbox-Angebot: $who" +
                    (p.lastPrice?.let { ", ${money(it)}" } ?: "") + "\n\n" +
                    "Auto-Kauf kauft dann nur, wenn genau dieses Angebot in der Kaufbox liegt. " +
                    "„In den Warenkorb“ legt genau dieses Angebot hinein.\n\nOfferListingId:\n$offer"
            )
            .setPositiveButton("Festhalten") { _, _ ->
                Store.update(this, p.asin) { it.pinnedOffer = offer; it.pinnedSeller = p.seller }
                toast("Angebot festgehalten.")
                refresh()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
            .findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
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
            toast("Erst unter Einstellungen „Auto-Kauf erlauben“ ankreuzen und speichern.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Auto-Kauf scharf schalten?")
            .setMessage(
                "${p.name} wird automatisch und ohne Rückfrage bestellt, sobald der Preis bei " +
                    "höchstens ${money(target)} liegt (Versandkosten kommen ggf. dazu).\n\n" +
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
                    openWeb(Checker.cartUrl(Store.domain(this@MainActivity), p.asin, p.cartOffer))
                }
            }
            val cartTest = Button(this).apply {
                text = "Automatisch in Warenkorb (Test)"
                setOnClickListener {
                    isEnabled = false
                    toast("Test läuft, bitte etwa 20–60 Sekunden warten …")
                    CoroutineScope(Dispatchers.Main).launch {
                        val res = try {
                            CartAdder.add(this@MainActivity, Store.domain(this@MainActivity), p.asin, p.cartOffer)
                        } catch (e: Exception) {
                            CartAdder.lastDetail = e.message ?: "Fehler"
                            CartAdder.Result.FAILED
                        }
                        isEnabled = true
                        val text = when (res) {
                            CartAdder.Result.OK -> "✅ Im Warenkorb: ${CartAdder.lastDetail}"
                            CartAdder.Result.NOT_LOGGED_IN -> "⚠️ Nicht angemeldet: ${CartAdder.lastDetail}"
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
                    isEnabled = false
                    toast("Probelauf läuft, bitte bis zu 2 Minuten warten … Es wird nichts bestellt.")
                    CoroutineScope(Dispatchers.Main).launch {
                        val res = try {
                            AutoBuyer.buy(
                                this@MainActivity, Store.domain(this@MainActivity), p.asin,
                                dryRun = true, visible = Store.watchBuy(this@MainActivity)
                            )
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
            row.addView(Button(this).apply {
                text = if (p.pinnedOffer != null) "Angebot freigeben (jedes Angebot)" else "Aktuelles Angebot festhalten"
                setOnClickListener { togglePin(p) }
            })
            row.addView(dry)
            row.addView(del)
            listBox.addView(row)
        }
    }
}

package de.amazonmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    // --- Export/Import der Produkte (Datei, gleiches Format wie das Windows-Programm)
    private var importWithSettings = false

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(Store.exportJson(this).toString(1).toByteArray(Charsets.UTF_8)) }
            toast("${Store.load(this).size} Produkte exportiert. Datei auf den PC holen und dort im Mr.Fox Bot importieren.")
        } catch (e: Exception) {
            toast("Export fehlgeschlagen: ${e.message}")
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val (n, disarmed) = Store.importJson(this, org.json.JSONObject(text), importWithSettings)
            refresh()
            toast("$n Produkte importiert." +
                if (disarmed > 0) " Auto-Kauf bei $disarmed Produkt(en) ausgeschaltet (sonst würden Handy und PC doppelt kaufen)." else "")
        } catch (e: Exception) {
            toast("Import fehlgeschlagen: ${e.message}")
        }
    }

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

        // --- Läuft der Bot? (ganz oben)
        botView = TextView(this).apply {
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(botView)
        botButton = button("") {
            if (BotService.alive) {
                BotService.stop(this)
                toast("Dauerbetrieb gestoppt.")
            } else {
                BotService.start(this)
                toast("Dauerbetrieb gestartet – siehe feste Benachrichtigung.")
            }
            handler.postDelayed({ updateBotView() }, 800)
        }
        root.addView(botButton)
        root.addView(button("⚙️ Konten, Passwörter & Dark Mode") {
            startActivity(Intent(this, AccountsActivity::class.java))
        })
        // Jeden Shop einzeln an/aus
        root.addView(TextView(this).apply {
            text = "Überwachung je Shop:"
            setPadding(0, dp(8), 0, 0)
        })
        for ((key, label) in listOf(Product.SHOP_AMAZON to "🟡 Amazon") + WebShops.all.map { it.key to "${it.emoji} ${it.label}" }) {
            root.addView(androidx.appcompat.widget.SwitchCompat(this).apply {
                text = label
                textSize = 16f
                isChecked = Store.shopEnabled(this@MainActivity, key)
                setOnCheckedChangeListener { _, on ->
                    Store.setShopEnabled(this@MainActivity, key, on)
                    toast("$label: Überwachung ${if (on) "an" else "aus"}")
                    updateBotView()
                    refresh()
                }
            })
        }
        root.addView(button("Akku-Optimierung für Mr.Fox Bot ausschalten (empfohlen)") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }.apply { textSize = 12f })

        // --- Produkt hinzufügen
        root.addView(label("Produkt hinzufügen"))
        // Shop-Auswahl: Amazon oder ein Web-Shop
        val shopGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbAmazon = RadioButton(this).apply { id = View.generateViewId(); text = "Amazon" }
        shopGroup.addView(rbAmazon)
        // Ein Knopf pro Web-Shop (Müller, MediaMarkt, BAUR …)
        val shopButtons = WebShops.all.associateWith { ws ->
            RadioButton(this).apply { id = View.generateViewId(); text = ws.label }.also { shopGroup.addView(it) }
        }
        rbAmazon.isChecked = true
        fun selectedShop(): WebShop? = shopButtons.entries.firstOrNull { it.value.isChecked }?.key
        val inUrl = edit("Amazon-Link oder ASIN", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val inName = edit("Name (optional)")
        val inTarget = edit(
            "Zielpreis in € (optional)",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val inOffer = edit("OfferListingId (optional – hält genau dieses Angebot fest)")
        fun applyShop() {
            val ws = selectedShop()
            inUrl.hint = if (ws != null) "${ws.label}-Link: Produkt oder Kategorie" else "Amazon-Link oder ASIN"
            inTarget.hint = if (ws != null) "Zielpreis in € (optional; bei Kategorie: Alarm unter diesem Preis)" else "Zielpreis in € (optional)"
            inOffer.visibility = if (ws != null) View.GONE else View.VISIBLE
        }
        shopGroup.setOnCheckedChangeListener { _, _ -> applyShop() }
        // Eingefügten Link erkennen und Shop automatisch umstellen.
        inUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val t = s?.toString() ?: return
                val ws = WebShops.detect(t)
                if (ws != null) shopButtons[ws]?.let { if (!it.isChecked) it.isChecked = true }
                else if (t.contains("amazon.") && !rbAmazon.isChecked) rbAmazon.isChecked = true
            }
        })
        root.addView(shopGroup)
        root.addView(inUrl)
        root.addView(inName)
        root.addView(inTarget)
        root.addView(inOffer)
        root.addView(button("Hinzufügen") {
            val target = inTarget.text.toString().replace(',', '.').toDoubleOrNull()
            val product: Product
            val ws = selectedShop()
            if (ws != null) {
                val link = ws.parse(inUrl.text.toString())
                if (link == null) {
                    toast("Kein ${ws.label}-Link erkannt. Nutze den Link einer Produkt- oder Kategorieseite von ${ws.base}.")
                    return@button
                }
                val name = inName.text.toString().trim().ifEmpty { link.id }
                product = Product(link.id, name, target, shop = ws.key, kind = link.kind, url = link.url)
            } else {
                val asin = Scraper.extractAsin(inUrl.text.toString())
                if (asin == null) {
                    toast("Keine ASIN erkannt. Nutze den vollen Link (…/dp/B0XXXXXXXX) oder die ASIN. Kurzlinks wie amzn.to gehen nicht.")
                    return@button
                }
                val name = inName.text.toString().trim().ifEmpty { asin }
                // OfferListingId aus eigenem Feld oder aus dem eingefügten Link (falls dort enthalten).
                val offer = Scraper.extractOfferId(inOffer.text.toString())
                    ?: Regex("(?i)offerlistingid").find(inUrl.text)?.let { Scraper.extractOfferId(inUrl.text.toString()) }
                product = Product(asin, name, target, pinnedOffer = offer)
            }
            val id = product.asin
            Store.add(this, product)
            inUrl.setText("")
            inName.setText("")
            inTarget.setText("")
            inOffer.setText("")
            schedule(Store.interval(this), update = false)
            refresh()
            toast(product.webShop?.let { "Hinzugefügt – prüfe ${it.label}-Seite …" } ?: "Produkt hinzugefügt – lese OfferListingId …")
            // Sofort prüfen (Amazon: Preis, Verkäufer, OfferListingId → Discord; Web-Shops: Preis/Verfügbarkeit bzw. Kategorie-Stand).
            val app = applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                val err = Checker.checkOne(app, id)
                val p = Store.load(app).firstOrNull { it.asin == id }
                // Nur von Hand eingetragene ID (auf der Seite keine gefunden): trotzdem bei Discord hinterlegen.
                if (err == null && p != null && !p.isWebShop && p.offerId == null && p.pinnedOffer != null) {
                    Checker.sendDiscord(
                        Store.webhook(app),
                        "🆕 OfferListingId (von Hand)\n**${p.name}**\n${Checker.offerInfo(Store.domain(app), p)}"
                    )
                }
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    refresh()
                    val discord = if (Store.webhook(app).isNotBlank()) " – bei Discord hinterlegt" else ""
                    toast(
                        when {
                            err != null -> "Prüfung fehlgeschlagen: $err"
                            p == null -> "Produkt fehlt."
                            p.isCategory -> "Kategorie gemerkt: " + p.describe().substringAfter("\n").substringBefore("\n")
                            p.isWebShop -> "${p.webShop?.label}: " + (p.lastPrice?.let { money(it) } ?: "Preis ?") +
                                if (p.available) ", online verfügbar" else ", ausverkauft"
                            p.cartOffer != null -> "OfferListingId gefunden$discord:\n${p.cartOffer}"
                            else -> "Keine OfferListingId gefunden (Artikel evtl. nicht verfügbar)."
                        }
                    )
                }
            }
        })

        // --- Einstellungen
        root.addView(label("Einstellungen"))
        val inDomain = edit("Amazon-Domain (z. B. amazon.de)", InputType.TYPE_CLASS_TEXT, Store.domain(this))
        val inAmazonInterval = edit(
            "Amazon-Prüfintervall in Minuten (Dauerbetrieb, min. 1)",
            InputType.TYPE_CLASS_NUMBER,
            Store.amazonInterval(this).toString()
        )
        val inInterval = edit(
            "Müller/MediaMarkt/BAUR-Prüfintervall in Minuten (min. 5; ohne Dauerbetrieb min. 15)",
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
        root.addView(inAmazonInterval)
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
        root.addView(CheckBox(this).apply {
            text = "Zahlung: immer PayPal wählen, wenn angeboten (Müller, MediaMarkt, BAUR – Amazon bietet kein PayPal)"
            isChecked = Store.preferPaypal(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Store.savePreferPaypal(this@MainActivity, on) }
        })
        root.addView(button("Bei PayPal anmelden (App, „Eingeloggt bleiben“ anhaken)") {
            startActivity(Links.inApp(this, "https://www.paypal.com/signin"))
        }.apply { textSize = 12f })

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
            val interval = (inInterval.text.toString().toIntOrNull() ?: 60).coerceAtLeast(5)
            Store.saveAmazonInterval(this, inAmazonInterval.text.toString().toIntOrNull() ?: 2)
            val drop = inDrop.text.toString().toIntOrNull() ?: 10
            Store.saveSettings(this, domain, interval, drop, inWebhook.text.toString().trim(), cbCart.isChecked)
            Store.saveAutoBuy(this, cbBuy.isChecked)
            toast(
                "Gespeichert. Amazon alle ${Store.amazonInterval(this)} Min., Müller/MediaMarkt/BAUR alle $interval Min." +
                    (if (cbBuy.isChecked) " Auto-Kauf erlaubt." else "") +
                    (if (!BotService.alive) " Für Prüfungen unter 15 Min. oben den Dauerbetrieb starten." else "")
            )
            schedule(interval, update = true)
            // Dauerbetrieb starten, falls er laufen soll (übernimmt die neuen Intervalle sofort)
            if (Store.botWanted(this) && !BotService.alive) BotService.start(this)
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

        // --- Konten & Passwörter (eigene Seite)
        root.addView(label("🔑 Konten & Passwörter"))
        root.addView(TextView(this).apply {
            text = "E-Mail und Passwort für Amazon, Müller, MediaMarkt, BAUR, PayPal und Payback verwaltest du jetzt auf einer " +
                "eigenen Seite – dort auch Anmelden und der Dark Mode."
            textSize = 12f
        })
        root.addView(button("🔑 Konten & Passwörter öffnen") { startActivity(Intent(this, AccountsActivity::class.java)) })


        // --- PAYBACK
        root.addView(label("💳 PAYBACK (°Punkte bei Amazon, MediaMarkt und BAUR)"))
        root.addView(TextView(this).apply {
            text = "Payback erlaubt Online-Shopping auf dem Handy nur in der Payback-App. Mit Haken bekommen " +
                "Alarme und Produkte einen Knopf „💳 Über Payback kaufen“: Er öffnet die Payback-App beim Shop und " +
                "kopiert den Produktnamen. Dort „Jetzt shoppen“ tippen und im Shop den Namen in die Suche einfügen. " +
                "Der Auto-Kauf läuft ohne Payback (ohne °Punkte)."
            textSize = 12f
        })
        // Payback-Automatik (Bedienungshilfe): drückt in der Payback-App „Zur Amazon-App“
        paybackAutoBtn = button("") {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("„Mr.Fox Bot – Payback-Automatik“ antippen und einschalten. Falls gesperrt: App-Info → ⋮ → „Eingeschränkte Einstellungen zulassen“.")
            } catch (e: Exception) {
                toast("Bitte unter Einstellungen → Bedienungshilfen einschalten.")
            }
        }
        root.addView(paybackAutoBtn)
        for (key in Payback.partners) {
            root.addView(CheckBox(this).apply {
                text = "${Payback.label(key)}: Payback-Knopf anzeigen"
                isChecked = Store.payback(this@MainActivity, key)
                setOnCheckedChangeListener { _, on ->
                    Store.savePayback(this@MainActivity, key, on)
                    refresh()
                }
            })
            root.addView(button("Payback öffnen: ${Payback.label(key)}") {
                Payback.open(this, key, null)
            }.apply { textSize = 12f })
        }
        root.addView(CheckBox(this).apply {
            text = "Auto-Kauf bei Amazon über Payback erledigen (Payback → Amazon-App, nur bei entsperrtem " +
                "Handy und eingeschalteter Payback-Automatik; sonst normaler Auto-Kauf ohne °Punkte)"
            isChecked = Store.paybackAutoBuy(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Store.savePaybackAutoBuy(this@MainActivity, on)
                if (on) PaybackClicker.whyNotBuy(this@MainActivity)?.let { toast("Hinweis: $it") }
                refresh()
            }
        })

        // --- Telegram-Deals
        root.addView(label("📣 Telegram-Deals"))
        root.addView(TextView(this).apply {
            text = "Liest öffentliche Telegram-KANÄLE über deren Webansicht (ohne Anmeldung) und meldet neue Deals, die zu " +
                "deinem Filter passen. Den Deal-Link (z. B. s.pirat.deals/…) löst der Bot bis zur Shopseite auf. Gruppen/Chats " +
                "und private Kanäle gehen so nicht – für „Pirate Deals Chat“ den Kanal PirateDeals nehmen. Läuft im Dauerbetrieb."
            textSize = 12f
        })
        val cbTg = CheckBox(this).apply { text = "Telegram-Deals lesen"; isChecked = Store.tgEnabled(this@MainActivity) }
        val inTgCh = edit("Kanäle (Name oder t.me-Link, mehrere mit Komma)", InputType.TYPE_CLASS_TEXT, Store.tgChannels(this))
        val inTgKw = edit("Stichwörter (Komma = oder; Leerzeichen = alle Wörter; leer = ALLE Deals)", InputType.TYPE_CLASS_TEXT, Store.tgKeywords(this))
        val inTgEx = edit("Ausschlusswörter (optional)", InputType.TYPE_CLASS_TEXT, Store.tgExclude(this))
        val inTgMax = edit(
            "Höchstpreis in € (optional)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            Store.tgMaxPrice(this)?.toString()?.replace('.', ',') ?: ""
        )
        val inTgInt = edit("Prüfen alle … Minuten (min. 1)", InputType.TYPE_CLASS_NUMBER, Store.tgInterval(this).toString())
        val tgGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val tgActions = listOf(
            "alert" to "Bei Treffer: nur Alarm (Benachrichtigung + Discord)",
            "watch" to "Bei Treffer: Alarm + auf die Überwachungsliste",
            "cart" to "Bei Treffer: Alarm + Liste + automatisch in den Warenkorb",
        ).map { (key, t) ->
            key to RadioButton(this).apply { id = View.generateViewId(); text = t; textSize = 13f }.also { tgGroup.addView(it) }
        }
        tgActions.firstOrNull { it.first == Store.tgAction(this) }?.second?.isChecked = true
        val cbTgPb = CheckBox(this).apply {
            text = "Treffer bei Amazon, MediaMarkt und BAUR über Payback öffnen (Payback-App → Shop → Produkt, für °Punkte; " +
                "mit Payback-Automatik öffnet sich das Produkt danach von selbst). Links immer ohne Partner-Tag."
            textSize = 13f
            isChecked = Store.tgPayback(this@MainActivity)
        }
        for (v in listOf(cbTg, inTgCh, inTgKw, inTgEx, inTgMax, inTgInt, tgGroup, cbTgPb)) root.addView(v)
        fun saveTg() {
            val action = tgActions.firstOrNull { it.second.isChecked }?.first ?: "alert"
            val chans = inTgCh.text.toString().split(Regex("[\\s,;]+")).map { TelegramDeals.channelName(it) }.filter { it.isNotEmpty() }
            Store.saveTelegram(
                this, cbTg.isChecked, chans.joinToString(", "), inTgKw.text.toString().trim(), inTgEx.text.toString().trim(),
                inTgMax.text.toString().trim(), action, inTgInt.text.toString().toIntOrNull() ?: 1
            )
            Store.saveTgPayback(this, cbTgPb.isChecked)
            toast(
                "Telegram gespeichert." + when {
                    cbTg.isChecked && !BotService.alive -> " Gelesen wird nur im Dauerbetrieb – oben starten."
                    cbTg.isChecked -> " Neue Beiträge ab jetzt werden geprüft."
                    else -> ""
                }
            )
        }
        root.addView(button("Telegram speichern") {
            if (cbTg.isChecked && inTgKw.text.isBlank()) {
                AlertDialog.Builder(this)
                    .setTitle("Ohne Stichwörter?")
                    .setMessage("Ohne Stichwörter meldet der Bot JEDEN Deal des Kanals – bei großen Kanälen hunderte am Tag. Trotzdem speichern?")
                    .setPositiveButton("Trotzdem speichern") { _, _ -> saveTg() }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            } else {
                saveTg()
            }
        })
        root.addView(button("Telegram testen: letzte 5 Beiträge mit Filter") {
            toast("Lese Telegram … (gespeicherte Filter)")
            val app = applicationContext
            CoroutineScope(Dispatchers.Main).launch {
                val lines = try {
                    TelegramDeals.test(app, 5)
                } catch (e: Exception) {
                    listOf("⚠️ " + (e.message ?: "Fehler"))
                }
                if (isFinishing || isDestroyed) return@launch
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Telegram-Test")
                    .setMessage(lines.joinToString("\n\n").ifEmpty { "Kein Kanal eingetragen." })
                    .setPositiveButton("OK", null)
                    .show()
                    .findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
            }
        }.apply { textSize = 12f })

        // --- Produkte übertragen (Handy ↔ PC)
        root.addView(label("📦 Produkte übertragen (Handy ↔ PC)"))
        root.addView(TextView(this).apply {
            text = "Exportiert alle Produkte in eine Datei (ohne Passwörter). Die Datei z. B. per Mail oder Google Drive " +
                "auf den PC holen und im Windows-Programm „Mr.Fox Bot“ importieren – oder andersherum. " +
                "Beim Import wird Auto-Kauf ausgeschaltet, damit nicht doppelt bestellt wird."
            textSize = 12f
        })
        root.addView(button("📤 Produkte exportieren") { exportLauncher.launch("mrfox-produkte.json") })
        val cbImportSettings = CheckBox(this).apply { text = "Beim Import auch Einstellungen übernehmen (Intervalle, Webhook …)" }
        root.addView(button("📥 Produkte importieren") {
            importWithSettings = cbImportSettings.isChecked
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        })
        root.addView(cbImportSettings)

        root.addView(button("Jetzt prüfen") {
            val req = OneTimeWorkRequestBuilder<CheckWorker>().addTag(CheckWorker.TAG_NOW).build()
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

        // --- Signatur
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        root.addView(TextView(this).apply {
            text = "🦊 Design by Mr.Fox" + if (version.isNotEmpty()) "\nVersion $version" else ""
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD_ITALIC)
            alpha = 0.7f
            setPadding(0, dp(32), 0, dp(8))
        })
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 1, 0, "⚙️ Konten & Einstellungen").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == 1) {
            startActivity(Intent(this, AccountsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // --- Anzeige „Bot läuft“
    private lateinit var botView: TextView
    private lateinit var botButton: Button
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateBotView()
            handler.postDelayed(this, 5_000)
        }
    }

    private lateinit var paybackAutoBtn: Button

    private fun updatePaybackAuto() {
        paybackAutoBtn.text = if (PaybackClicker.isEnabled(this))
            "✅ Payback-Automatik an (drückt „Zur Amazon-App“) – Einstellungen öffnen"
        else "Payback-Automatik einschalten (Bedienungshilfe)"
    }

    private fun updateBotView() {
        val running = BotService.alive
        val wanted = Store.botWanted(this)
        botView.text = when {
            running -> "🟢 Bot läuft (Dauerbetrieb)\n" + BotService.summary(this) + "\nStatus: " + Store.status(this)
            wanted -> "🟠 Bot soll laufen, wurde aber von Android beendet – „Dauerbetrieb starten“ tippen " +
                "und die Akku-Optimierung ausschalten."
            else -> "🔴 Dauerbetrieb aus – nur Prüfung alle ${Store.interval(this).coerceAtLeast(15)} Min. im Hintergrund " +
                "(ungenau). Für Amazon alle ${Store.amazonInterval(this)} Min. Dauerbetrieb starten."
        }
        botView.setBackgroundColor(
            when {
                running -> 0x2233AA33
                wanted -> 0x33FF9900
                else -> 0x22CC3333
            }
        )
        botButton.text = if (running) "Dauerbetrieb stoppen" else "Dauerbetrieb starten"
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        overlayHint()
        updatePaybackAuto()
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
        val ws = p.webShop
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
                    (if (ws != null) {
                        "• ${ws.label}: Artikel kommt in den Warenkorb, dann wird der GANZE Warenkorb bestellt " +
                            "– leere ihn vorher, wenn noch anderes drin liegt\n" +
                            "• Voreinstellungen aus deinem ${ws.label}-Konto/Checkout (Lieferart, Adresse, Zahlung)\n" +
                            (if (!Store.hasShopLogin(this, ws)) "• ⚠️ Kein ${ws.label}-Zugang gespeichert – Anmeldung klappt nur, wenn die App schon angemeldet ist\n" else "")
                    } else {
                        "• Menge 1, über „Jetzt kaufen“ (der übrige Warenkorb wird nicht mitbestellt)\n" +
                            "• Standard-Lieferadresse und -Zahlungsart deines Amazon-Kontos\n"
                    }) +
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

    /** Welche Sparte die Liste zeigt: null = alle, sonst Product.SHOP_AMAZON / SHOP_MUELLER. */
    private var listFilter: String? = null

    private fun refresh() {
        statusView.text = "Status: " + Store.status(this)
        listBox.removeAllViews()
        val all = Store.load(this)

        // Sparten-Auswahl
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val barScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        fun tab(label: String, f: String?) = Button(this).apply {
            text = (if (listFilter == f) "● " else "") + label
            setOnClickListener { listFilter = f; refresh() }
        }
        bar.addView(tab("Alle (${all.size})", null))
        bar.addView(tab("Amazon (${all.count { !it.isWebShop }})", Product.SHOP_AMAZON))
        for (ws in WebShops.all) bar.addView(tab("${ws.label} (${all.count { it.shop == ws.key }})", ws.key))
        listBox.addView(barScroll)

        val items = all.filter { listFilter == null || it.shop == listFilter }
        if (items.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "Noch keine Produkte." })
            return
        }
        for (shop in listOf(Product.SHOP_AMAZON) + WebShops.all.map { it.key }) {
            val group = items.filter { it.shop == shop }
            if (group.isEmpty()) continue
            listBox.addView(label(WebShops.byKey(shop)?.let { "${it.emoji} ${it.label}" } ?: "🟡 Amazon"))
            for (p in group) listBox.addView(buildRow(p))
        }
    }

    private fun smallButton(t: String, onClick: () -> Unit) = button(t, onClick)

    private fun buildRow(p: Product): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val domain = Store.domain(this)
        val active = Store.isActive(this, p)
        row.addView(TextView(this).apply {
            text = p.describe() + if (!p.paused && !active) "\n⏹ Shop ist ausgeschaltet" else ""
            textSize = 15f
            alpha = if (active) 1f else 0.5f
            setOnClickListener { openWeb(p.pageUrl(domain)) }
        })
        // Dieses Produkt einzeln an/aus
        row.addView(smallButton(if (p.paused) "▶ Überwachung fortsetzen" else "⏸ Überwachung pausieren") {
            Store.update(this, p.asin) { it.paused = !it.paused }
            refresh()
        })
        val del = smallButton("Entfernen") {
            Store.remove(this, p.asin)
            refresh()
        }

        if (p.isCategory) {
            row.addView(smallButton("Produkte der Kategorie anzeigen") { showCategory(p) })
            row.addView(smallButton("Kategorie öffnen") { openWeb(p.url) })
            row.addView(del)
            return row
        }

        val ws = p.webShop
        if (ws != null) {
            row.addView(smallButton("Zur Produktseite") { openWeb(p.url) })
        } else {
            row.addView(smallButton("In den Warenkorb") { openWeb(Checker.cartUrl(domain, p.asin, p.cartOffer)) })
        }
        // Von Hand über Payback kaufen: Payback-App öffnen, Produktname liegt in der Zwischenablage.
        if (Payback.supports(p.shop) && Store.payback(this, p.shop)) {
            row.addView(smallButton("💳 Über Payback kaufen") { Payback.open(this, p.shop, p.name) })
            if (!p.isWebShop && Store.paybackAutoBuy(this)) {
                row.addView(smallButton("💳 Probelauf über Payback (bestellt nicht)") {
                    val why = PaybackClicker.whyNotBuy(this)
                    if (why != null) {
                        toast("Geht gerade nicht: $why")
                        return@smallButton
                    }
                    toast("Probelauf über Payback startet – Handy nicht anfassen, Ergebnis kommt als Meldung.")
                    PaybackClicker.startBuy(this, p.asin, p.name, dryRun = true)
                })
            }
        }
        row.addView(Button(this).apply {
            text = "Automatisch in Warenkorb (Test)"
            setOnClickListener {
                isEnabled = false
                toast("Test läuft, bitte etwa 20–90 Sekunden warten …")
                CoroutineScope(Dispatchers.Main).launch {
                    val res = try {
                        if (ws != null) ShopBuyer.addToCart(this@MainActivity, ws, p.url, p.asin)
                        else CartAdder.add(this@MainActivity, domain, p.asin, p.cartOffer)
                    } catch (e: Exception) {
                        CartAdder.lastDetail = e.message ?: "Fehler"
                        CartAdder.Result.FAILED
                    }
                    isEnabled = true
                    toast(
                        when (res) {
                            CartAdder.Result.OK -> "✅ Im Warenkorb: ${CartAdder.lastDetail}"
                            CartAdder.Result.NOT_LOGGED_IN -> "⚠️ Nicht angemeldet: ${CartAdder.lastDetail}"
                            CartAdder.Result.FAILED -> "⚠️ Nicht geklappt: ${CartAdder.lastDetail}"
                        }
                    )
                }
            }
        })
        row.addView(smallButton("Zur Kasse") {
            if (ws != null) openWeb(ws.cartUrl) else openWeb("https://www.$domain/gp/cart/view.html", true)
        })
        row.addView(smallButton(if (p.autoBuy) "Auto-Kauf entschärfen" else "Auto-Kauf scharf schalten") { toggleAutoBuy(p) })
        if (ws == null) {
            row.addView(smallButton(
                if (p.pinnedOffer != null) "Angebot freigeben (jedes Angebot)" else "Aktuelles Angebot festhalten"
            ) { togglePin(p) })
        }
        row.addView(Button(this).apply {
            text = "Auto-Kauf Probelauf (bestellt nicht)"
            setOnClickListener {
                isEnabled = false
                toast("Probelauf läuft, bitte bis zu 3 Minuten warten … Es wird nichts bestellt.")
                CoroutineScope(Dispatchers.Main).launch {
                    val visible = Store.watchBuy(this@MainActivity)
                    val res = try {
                        if (ws != null) ShopBuyer.buy(this@MainActivity, ws, p.url, p.asin, dryRun = true, visible = visible)
                        else AutoBuyer.buy(this@MainActivity, domain, p.asin, dryRun = true, visible = visible)
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
        })
        row.addView(del)
        return row
    }

    /** Zeigt die zuletzt gesehenen Produkte einer Shop-Kategorie; Antippen übernimmt eins als einzelnes Produkt. */
    private fun showCategory(cat: Product) {
        val st = try { org.json.JSONObject(cat.catState) } catch (e: Exception) { org.json.JSONObject() }
        val urls = st.keys().asSequence().toList()
        if (urls.isEmpty()) {
            toast("Noch keine Produkte gesehen – erst „Jetzt prüfen“.")
            return
        }
        val labels = urls.map { u ->
            val o = st.getJSONObject(u)
            val price = if (o.isNull("p")) "?" else money(o.getDouble("p"))
            (if (o.optBoolean("a")) "✅ " else "❌ ") + o.optString("n") + " – " + price
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${cat.name} (✅ online verfügbar)")
            .setItems(labels) { _, i -> cat.webShop?.let { ws -> addFromCategory(ws, urls[i], st.getJSONObject(urls[i]).optString("n")) } }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun addFromCategory(shop: WebShop, url: String, name: String) {
        val link = shop.parse(url) ?: return toast("Link nicht erkannt.")
        val inTarget = edit("Zielpreis in € (für Alarm / Auto-Kauf, optional)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        AlertDialog.Builder(this)
            .setTitle("Einzeln überwachen?")
            .setMessage(name)
            .setView(inTarget)
            .setPositiveButton("Hinzufügen") { _, _ ->
                val target = inTarget.text.toString().replace(',', '.').toDoubleOrNull()
                Store.add(this, Product(link.id, name.ifEmpty { link.id }, target, shop = shop.key, url = link.url))
                refresh()
                toast("Hinzugefügt – prüfe …")
                val app = applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    val err = Checker.checkOne(app, link.id)
                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        refresh()
                        if (err != null) toast("Prüfung fehlgeschlagen: $err")
                    }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}

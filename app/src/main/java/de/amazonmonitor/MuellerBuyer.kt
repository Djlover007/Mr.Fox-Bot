package de.amazonmonitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Müller: in den Warenkorb legen und automatisch bestellen.
 *
 * Ablauf: Produktseite → „In den Warenkorb“ → Warenkorb (/cart/) → „Zur Kasse“ → ggf. Anmeldung
 * mit dem gespeicherten Müller-Zugang → „Weiter“-Schritte mit den voreingestellten Angaben →
 * „Zahlungspflichtig bestellen“ → Bestätigung abwarten.
 *
 * Achtung: Bei Müller wird – anders als bei Amazons „Jetzt kaufen“ – der GANZE Warenkorb bestellt.
 * Die Knöpfe werden über ihre Beschriftung gefunden, weil Müller keine festen Kennungen anbietet.
 */
object MuellerBuyer {
    private enum class Mode { CART, BUY, DRY }

    private val mutex = Mutex()

    private val C = Mueller.COMMON_JS

    private val ADD_JS = "(function(){" + C +
        "if(security())return 'security';if(!document.querySelector('h1'))return 'wait';" +
        "var b=btn(/^in den warenkorb${'$'}/i);if(b){b.click();return 'clicked';}return 'none';})()"

    private val ADDED_JS = "(function(){" + C +
        "var b=(document.body&&document.body.innerText)||'';" +
        "if(/in den warenkorb gelegt|zum warenkorb hinzugefügt|wurde hinzugefügt|artikel hinzugefügt/i.test(b))return 'ok';" +
        "if(btn(/^(zum warenkorb|warenkorb anzeigen)${'$'}/i))return 'ok';return 'no';})()"

    private fun cartHasJs(article: String) = "(function(){" + C +
        "if(security())return 'security';if(document.readyState!=='complete')return 'wait';" +
        "return document.documentElement.innerHTML.indexOf(${JSONObject.quote(article)})>=0?'yes':'no';})()"

    private val STEP_JS = "(function(){" + C + AutoBuyer.FIND_TOTAL_JS +
        "if(security())return JSON.stringify({s:'security'});" +
        "var body=(document.body&&document.body.innerText)||'';var h=location.href.toLowerCase();" +
        "var pw=document.querySelector('input[type=password]');if(pw&&vis(pw))return JSON.stringify({s:'login'});" +
        "var f=btn(/zahlungspflichtig bestellen|kostenpflichtig bestellen|jetzt kaufen|bestellung absenden|jetzt bestellen/i,/einkaufen/i);" +
        "if(f)return JSON.stringify({s:'final',total:findTotal(document)||'',label:txt(f),hint:hintOf(document)});" +
        "if(/vielen dank für (ihre|deine) bestellung|(ihre|deine) bestellnummer|bestellung ist eingegangen/i.test(body)" +
        "||/success|danke|thank/.test(h))return JSON.stringify({s:'done'});" +
        "var k=btn(/^zur kasse/i);if(k)return JSON.stringify({s:'click',label:txt(k)});" +
        "var n=btn(/^(weiter|weiter zu.*|weiter zur .*|zur zahlung|zur bestellübersicht|zur übersicht|bestellung prüfen|übernehmen|speichern und weiter|auswählen und weiter)${'$'}/i,/einkaufen|shoppen/i);" +
        "if(n)return JSON.stringify({s:'click',label:txt(n)});" +
        "var a=btn(/^(anmelden|einloggen)${'$'}/i);if(a)return JSON.stringify({s:'needlogin',label:txt(a)});" +
        "return JSON.stringify({s:'wait',title:document.title});})()"

    private fun clickTextJs(label: String) = "(function(){" + C +
        "var t=${JSONObject.quote(label)};" +
        "var els=document.querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]');" +
        "for(var i=0;i<els.length;i++){var e=els[i];if(txt(e)===t&&vis(e)){e.click();return 'clicked';}}return 'none';})()"

    private fun loginJs(email: String, pw: String) = "(function(){" + C +
        "function setv(el,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');" +
        "d.set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}" +
        "var m=document.querySelector('input[type=email],input[autocomplete=username],input[name*=mail i],input[id*=mail i]');" +
        "var p=document.querySelector('input[type=password]');" +
        "if(m&&!m.value)setv(m,${JSONObject.quote(email)});if(p)setv(p,${JSONObject.quote(pw)});" +
        "var b=btn(/^(anmelden|einloggen|login|weiter)${'$'}/i);" +
        "if(!b&&p&&p.form){b=p.form.querySelector('button[type=submit],input[type=submit]');}" +
        "if(b){b.click();return 'clicked';}return 'nobutton';})()"

    /** Legt das Produkt in den Müller-Warenkorb. */
    suspend fun addToCart(ctx: Context, url: String, productId: String): CartAdder.Result {
        val r = run(ctx, url, productId, Mode.CART, visible = false)
        CartAdder.lastDetail = r.detail
        return when (r.outcome) {
            AutoBuyer.Outcome.DRY_RUN_OK -> CartAdder.Result.OK
            AutoBuyer.Outcome.NOT_LOGGED_IN -> CartAdder.Result.NOT_LOGGED_IN
            else -> CartAdder.Result.FAILED
        }
    }

    /** Auto-Kauf bzw. Probelauf ([dryRun]: bis zum Bestellknopf, ohne zu bestellen). */
    suspend fun buy(ctx: Context, url: String, productId: String, dryRun: Boolean, visible: Boolean): AutoBuyer.Result =
        run(ctx, url, productId, if (dryRun) Mode.DRY else Mode.BUY, visible)

    private suspend fun run(ctx: Context, url: String, productId: String, mode: Mode, visible: Boolean): AutoBuyer.Result =
        mutex.withLock {
            withContext(Dispatchers.Main) {
                val watch = if (visible) WatchActivity.open(ctx) else null
                watch?.let { Mueller.setup(ctx, it.web) }
                var placed = false
                val r = withTimeoutOrNull(180_000L) {
                    execute(ctx, watch, url, productId, mode) { placed = true }
                } ?: if (placed) {
                    AutoBuyer.Result(AutoBuyer.Outcome.UNCLEAR, "Zeitüberschreitung nach dem Klick auf „Bestellen“")
                } else {
                    AutoBuyer.Result(AutoBuyer.Outcome.FAILED, "Zeitüberschreitung")
                }
                watch?.finished(AutoBuyer.describe(r))
                r
            }
        }

    private suspend fun execute(
        ctx: Context, watch: WatchActivity?, url: String, productId: String, mode: Mode, onPlaced: () -> Unit
    ): AutoBuyer.Result = suspendCancellableCoroutine { cont ->
        val owned = watch == null
        val web = watch?.web ?: Mueller.hiddenWebView(ctx)
        val handler = Handler(Looper.getMainLooper())
        val article = Mueller.articleNumber(productId)
        val prefix = when (mode) {
            Mode.DRY -> "Müller-Probelauf: "
            Mode.BUY -> "Müller-Auto-Kauf: "
            Mode.CART -> "Müller-Warenkorb: "
        }
        fun status(s: String) = watch?.setStatus(prefix + s)

        // 0 = Produktseite, 1 = Hinzufügen bestätigen, 2 = Warenkorb prüfen, 3 = Kasse, 4 = bestellt, warte auf Bestätigung
        var stage = 0
        var polls = 0
        var last = ""
        var cooldown = 0
        var loginTries = 0
        var stepClicks = 0
        val clicks = HashMap<String, Int>()
        var total: Double? = null

        fun release() {
            handler.removeCallbacksAndMessages(null)
            watch?.onClosed = null
            if (owned) {
                web.stopLoading()
                web.destroy()
            }
        }

        fun done(r: AutoBuyer.Result) {
            if (cont.isActive) {
                cont.resume(r)
                release()
            }
        }

        cont.invokeOnCancellation { handler.post { release() } }
        watch?.onClosed = {
            if (stage == 4) done(AutoBuyer.Result(AutoBuyer.Outcome.UNCLEAR, "Fenster nach dem Bestell-Klick geschlossen", total))
            else done(AutoBuyer.Result(AutoBuyer.Outcome.FAILED, "Fenster geschlossen"))
        }

        fun nextStage(s: Int) {
            stage = s
            polls = 0
        }

        val tick = object : Runnable {
            fun again() = handler.postDelayed(this, 1500)

            override fun run() {
                if (!cont.isActive) return
                polls++
                when (stage) {
                    0 -> web.evaluateJavascript(ADD_JS) { raw ->
                        if (!cont.isActive) return@evaluateJavascript
                        last = raw?.trim('"') ?: ""
                        when {
                            last == "clicked" -> {
                                status("„In den Warenkorb“ gedrückt …")
                                nextStage(1)
                            }
                            last == "none" && polls >= 6 -> {
                                done(AutoBuyer.Result(AutoBuyer.Outcome.FAILED, "Kein „In den Warenkorb“-Knopf (ausverkauft?)"))
                                return@evaluateJavascript
                            }
                            polls >= 25 -> {
                                done(
                                    if (last == "security") AutoBuyer.Result(AutoBuyer.Outcome.BLOCKED, "Müller-Sicherheitsprüfung nicht bestanden")
                                    else AutoBuyer.Result(AutoBuyer.Outcome.FAILED, "Müller-Produktseite nicht erkannt")
                                )
                                return@evaluateJavascript
                            }
                        }
                        again()
                    }

                    1 -> web.evaluateJavascript(ADDED_JS) { raw ->
                        if (!cont.isActive) return@evaluateJavascript
                        val ok = raw?.contains("ok") == true
                        if (ok && mode == Mode.CART) {
                            done(AutoBuyer.Result(AutoBuyer.Outcome.DRY_RUN_OK, "In den Müller-Warenkorb gelegt"))
                            return@evaluateJavascript
                        }
                        if (ok || polls >= 6) {
                            status("Öffne Warenkorb …")
                            web.loadUrl(Mueller.CART_URL)
                            nextStage(2)
                        }
                        again()
                    }

                    2 -> web.evaluateJavascript(cartHasJs(article)) { raw ->
                        if (!cont.isActive) return@evaluateJavascript
                        val yes = raw?.contains("yes") == true
                        when {
                            yes && mode == Mode.CART -> {
                                done(AutoBuyer.Result(AutoBuyer.Outcome.DRY_RUN_OK, "Im Müller-Warenkorb"))
                                return@evaluateJavascript
                            }
                            !yes && polls >= 8 && mode == Mode.CART -> {
                                done(AutoBuyer.Result(AutoBuyer.Outcome.FAILED, "Artikel nicht im Müller-Warenkorb gefunden"))
                                return@evaluateJavascript
                            }
                            // Kauf: auch ohne sichere Erkennung weiter – die Kasse zeigt, was im Warenkorb liegt.
                            yes || polls >= 8 -> {
                                status("Gehe zur Kasse …")
                                nextStage(3)
                            }
                        }
                        again()
                    }

                    3, 4 -> web.evaluateJavascript(STEP_JS) { raw ->
                        if (!cont.isActive) return@evaluateJavascript
                        val o = Mueller.parseJs(raw)
                        val s = o.optString("s")
                        if (stage == 4) {
                            when {
                                s == "done" -> {
                                    done(AutoBuyer.Result(AutoBuyer.Outcome.ORDERED, "Bestellung bei Müller bestätigt", total))
                                    return@evaluateJavascript
                                }
                                polls >= 25 -> {
                                    done(AutoBuyer.Result(AutoBuyer.Outcome.UNCLEAR, "Keine Bestätigungsseite erkannt", total))
                                    return@evaluateJavascript
                                }
                            }
                            again()
                            return@evaluateJavascript
                        }
                        if (cooldown > 0) {
                            cooldown--
                            again()
                            return@evaluateJavascript
                        }
                        when (s) {
                            "final" -> {
                                val t = Scraper.parsePrice(o.optString("total"))
                                val label = o.optString("label")
                                if (mode == Mode.DRY) {
                                    val info = if (t != null) "Gesamtbetrag ${money(t)}" else
                                        "Gesamtbetrag nicht lesbar" + o.optString("hint").let { if (it.isNotEmpty()) " (gefunden: $it)" else "" }
                                    done(AutoBuyer.Result(AutoBuyer.Outcome.DRY_RUN_OK, "Bestellknopf „$label“ gefunden, $info – Probelauf, nichts bestellt", t))
                                    return@evaluateJavascript
                                }
                                total = t
                                onPlaced()
                                status("Bestellung wird abgeschickt …")
                                web.evaluateJavascript(clickTextJs(label), null)
                                nextStage(4)
                            }
                            "done" -> {
                                done(AutoBuyer.Result(AutoBuyer.Outcome.UNCLEAR, "Bestellbestätigung erschienen, ohne dass die App bestellt hat"))
                                return@evaluateJavascript
                            }
                            "login" -> {
                                val mail = Store.muellerEmail(ctx)
                                val pw = Store.muellerPassword(ctx)
                                when {
                                    mail.isEmpty() || pw == null -> {
                                        done(AutoBuyer.Result(AutoBuyer.Outcome.NOT_LOGGED_IN, "Müller verlangt eine Anmeldung – Müller-Zugang in der App speichern"))
                                        return@evaluateJavascript
                                    }
                                    loginTries >= 2 -> {
                                        done(AutoBuyer.Result(AutoBuyer.Outcome.NOT_LOGGED_IN, "Anmeldung bei Müller hat nicht geklappt (Passwort prüfen)"))
                                        return@evaluateJavascript
                                    }
                                    else -> {
                                        loginTries++
                                        status("Melde bei Müller an …")
                                        web.evaluateJavascript(loginJs(mail, pw), null)
                                        cooldown = 3
                                    }
                                }
                            }
                            "click", "needlogin" -> {
                                val label = o.optString("label")
                                val n = clicks[label] ?: 0
                                val canLogin = Store.hasMuellerLogin(ctx)
                                if (n < 3 && stepClicks < 15 && (s == "click" || canLogin)) {
                                    clicks[label] = n + 1
                                    stepClicks++
                                    status("Klicke „$label“ …")
                                    web.evaluateJavascript(clickTextJs(label), null)
                                    cooldown = 2
                                }
                            }
                        }
                        if (s == "wait" || s == "security") last = o.optString("title", s)
                        if (polls >= 60) {
                            done(AutoBuyer.Result(AutoBuyer.Outcome.FAILED, "Müller-Kasse nicht durchlaufen (hängt bei: ${last.ifEmpty { s }})"))
                            return@evaluateJavascript
                        }
                        again()
                    }
                }
            }
        }

        status("Lade Produktseite …")
        web.webViewClient = object : android.webkit.WebViewClient() {}
        web.loadUrl(url)
        handler.postDelayed(tick, 2500)
    }
}

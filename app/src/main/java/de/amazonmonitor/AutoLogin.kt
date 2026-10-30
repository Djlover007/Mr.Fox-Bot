package de.amazonmonitor

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import org.json.JSONObject

/**
 * Automatische Anmeldung bei Amazon mit dem in der App gespeicherten Zugang.
 * Wird vom Amazon-Fenster, vom Auto-Kauf und vom automatischen Warenkorb benutzt.
 *
 * Schutz vor Kontosperre: pro Ablauf höchstens ein Versuch für die E-Mail und einer fürs Passwort.
 * Zugangsdaten werden nur auf der echten Amazon-Domain (https) eingetragen.
 */
object AutoLogin {
    enum class State {
        /** Daten eingetragen und abgeschickt – auf die nächste Seite warten. */
        FILLED,
        /** Gerade erst abgeschickt, Seite lädt noch – nichts tun. */
        WAIT,
        NO_CREDENTIALS,
        CAPTCHA,
        /** Bestätigungscode (Zwei-Schritt-Verifizierung) verlangt. */
        OTP,
        /** Amazon will die Anmeldung per App/SMS/E-Mail bestätigt haben. */
        VERIFY,
        /** Versuche aufgebraucht (z. B. falsches Passwort). */
        GAVE_UP,
        NOT_LOGIN_PAGE,
    }

    /** Merkt sich die Versuche eines Ablaufs (pro Fenster bzw. pro Auto-Kauf neu anlegen). */
    class Session {
        internal var emailTries = 0
        internal var pwTries = 0
        internal var lastFill = 0L
        internal var lastKind = ""
        var attempted = false
            internal set
    }

    fun isLoginUrl(url: String?): Boolean = url != null && (url.contains("/ap/") || url.contains("/ax/"))

    private fun isAmazon(ctx: Context, url: String?): Boolean {
        val uri = Uri.parse(url ?: return false)
        val host = uri.host?.lowercase() ?: return false
        val domain = Store.domain(ctx).lowercase()
        return uri.scheme == "https" && (host == domain || host.endsWith(".$domain"))
    }

    private const val DETECT_JS = "(function(){" +
        "var h=location.href;" +
        "if(document.getElementById('auth-captcha-guess')||document.getElementById('captchacharacters'))return 'captcha';" +
        "if(document.getElementById('auth-mfa-otpcode')||h.indexOf('/ap/mfa')>=0)return 'otp';" +
        "if(h.indexOf('/ap/cvf')>=0||document.getElementById('channelDetailsForOtp')||" +
        "document.querySelector('[name=\"transactionApprovalStatus\"]'))return 'verify';" +
        "var p=document.getElementById('ap_password');if(p&&p.type!=='hidden')return 'pw';" +
        "if(document.querySelector('#ap_email,#ap_email_login,input[name=\"email\"]'))return 'email';" +
        "return 'none';})()"

    private fun fillScript(email: String, pw: String?): String {
        val sb = StringBuilder()
        sb.append("(function(){")
        sb.append("function set(el,v){el.focus();el.value=v;")
        sb.append("el.dispatchEvent(new Event('input',{bubbles:true}));")
        sb.append("el.dispatchEvent(new Event('change',{bubbles:true}));}")
        // Selektoren der Reihe nach probieren (der innere Knopf zuerst, nicht der umgebende Rahmen).
        sb.append("function first(s){for(var i=0;i<s.length;i++){var e=document.querySelector(s[i]);if(e)return e;}return null;}")
        sb.append("var r=document.querySelector('input[name=rememberMe]');if(r&&!r.checked){r.click();}")
        sb.append("var m=document.querySelector('#ap_email,#ap_email_login,input[name=\"email\"]');")
        sb.append("if(m&&m.type!=='hidden'&&!m.value){set(m,").append(JSONObject.quote(email)).append(");}")
        if (pw == null) {
            sb.append("var c=first(['#continue input','input#continue','#continue']);if(c){c.click();}")
        } else {
            sb.append("var p=document.getElementById('ap_password');if(p){set(p,")
                .append(JSONObject.quote(pw)).append(");}")
            sb.append("var s=first(['input#signInSubmit','#signInSubmit input','#signInSubmit']);if(s){s.click();}")
        }
        sb.append("})();")
        return sb.toString()
    }

    /**
     * Prüft die aktuelle Seite und meldet sich wenn nötig an. Ergebnis kommt über [cb] (Main-Thread).
     */
    fun step(ctx: Context, view: WebView, session: Session, cb: (State) -> Unit) {
        val url = view.url
        if (!isLoginUrl(url) || !isAmazon(ctx, url)) {
            cb(State.NOT_LOGIN_PAGE)
            return
        }
        val email = Store.loginEmail(ctx)
        val pw = Store.loginPassword(ctx)
        view.evaluateJavascript(DETECT_JS) { raw ->
            val kind = raw?.trim('"') ?: ""
            // Gleicher Schritt kurz nach dem Abschicken: Seite lädt noch – abwarten statt erneut ausfüllen.
            if (kind == session.lastKind && System.currentTimeMillis() - session.lastFill < 8_000L) {
                cb(State.WAIT)
                return@evaluateJavascript
            }
            if (kind == "email" || kind == "pw") session.lastKind = kind
            when (kind) {
                "captcha" -> cb(State.CAPTCHA)
                "otp" -> cb(State.OTP)
                "verify" -> cb(State.VERIFY)
                "email" -> when {
                    email.isEmpty() || pw == null -> cb(State.NO_CREDENTIALS)
                    session.emailTries >= 1 -> cb(State.GAVE_UP)
                    else -> {
                        session.emailTries++
                        session.attempted = true
                        session.lastFill = System.currentTimeMillis()
                        view.evaluateJavascript(fillScript(email, null), null)
                        cb(State.FILLED)
                    }
                }
                "pw" -> when {
                    email.isEmpty() || pw == null -> cb(State.NO_CREDENTIALS)
                    session.pwTries >= 1 -> cb(State.GAVE_UP)
                    else -> {
                        session.pwTries++
                        session.attempted = true
                        session.lastFill = System.currentTimeMillis()
                        view.evaluateJavascript(fillScript(email, pw), null)
                        cb(State.FILLED)
                    }
                }
                else -> cb(State.WAIT)
            }
        }
    }

    /** Erklärung für Meldungen, wenn die Anmeldung nicht automatisch geht. */
    fun explain(s: State): String = when (s) {
        State.NO_CREDENTIALS -> "Amazon verlangt eine Anmeldung, aber in der App ist kein Zugang (E-Mail/Passwort) gespeichert"
        State.CAPTCHA -> "Amazon zeigt bei der Anmeldung ein Captcha – einmal über „In der App anmelden“ lösen"
        State.OTP -> "Amazon verlangt einen Bestätigungscode (Zwei-Schritt-Verifizierung) – einmal über „In der App anmelden“ eingeben"
        State.VERIFY -> "Amazon will die Anmeldung bestätigt haben (App/SMS/E-Mail) – einmal über „In der App anmelden“ bestätigen"
        State.GAVE_UP -> "Automatische Anmeldung hat nicht geklappt (Passwort prüfen)"
        else -> "Amazon verlangt eine Anmeldung"
    }
}

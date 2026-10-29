package de.amazonmonitor

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sichtbares Fenster für Probelauf und Auto-Kauf: oben ein Statusbalken, darunter die Amazon-Seite,
 * auf der die App klickt. Nach dem Ende bleibt die Seite offen, damit du das Ergebnis siehst.
 */
class WatchActivity : AppCompatActivity() {
    lateinit var web: WebView
        private set
    private lateinit var banner: TextView

    /** Wird aufgerufen, wenn du das Fenster schließt, solange der Ablauf noch läuft. */
    var onClosed: (() -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Mr.Fox Bot – Auto-Kauf"
        val pad = (12 * resources.displayMetrics.density).toInt()
        banner = TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            text = "Starte …"
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(banner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(android.widget.Button(this@WatchActivity).apply {
                text = "📋 Seitenbericht kopieren (für die Fehlersuche)"
                textSize = 12f
                setOnClickListener { copyReport() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        val p = pending
        pending = null
        if (p == null || !p.complete(this)) {
            // Niemand wartet (z. B. nach Drehen des Bildschirms neu erstellt) – nichts zu tun.
            banner.text = "Kein Auto-Kauf aktiv."
        }
    }

    /** Beschreibung der angezeigten Seite (Knöpfe, Auswahlfelder, Beschriftungen) in die Zwischenablage. */
    private fun copyReport() {
        web.evaluateJavascript(REPORT_JS) { raw ->
            val text = try {
                org.json.JSONTokener(raw ?: "null").nextValue() as? String ?: "leer"
            } catch (e: Exception) {
                raw ?: "leer"
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Seitenbericht", "Mr.Fox Seitenbericht (" + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }) + ")\n" + text + "\n--- Ablauf der App\n" + ShopBuyer.logText()))
            android.widget.Toast.makeText(this, "Seitenbericht kopiert – im Chat einfügen.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun setStatus(s: String) {
        if (!isDestroyed) banner.text = s
    }

    fun finished(s: String) = setStatus("Fertig: $s")

    override fun onDestroy() {
        onClosed?.invoke()
        onClosed = null
        CookieManager.getInstance().flush()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        /** Sammelt sichtbare Überschriften, Knöpfe, Auswahlfelder und Beschriftungen (gekürzt). */
        private const val REPORT_JS = """(function(){
function t(e){return ((e.innerText||e.value||'')+'').replace(/\s+/g,' ').trim().slice(0,60);}
function a(e,n){var v=e.getAttribute(n);return v?(' '+n+'="'+v.slice(0,40)+'"'):'';}
function seen(e){var s=getComputedStyle(e);return s.display!=='none'&&s.visibility!=='hidden';}
var out=['URL: '+location.href,'Titel: '+document.title];
var hs=document.querySelectorAll('h1,h2,h3,h4,legend');out.push('--- Überschriften');
for(var i=0;i<hs.length&&i<30;i++)if(seen(hs[i]))out.push(hs[i].tagName+': '+t(hs[i]));
var rs=document.querySelectorAll('input[type=radio],input[type=checkbox],[role=radio],[role=option],[aria-checked],[aria-selected]');out.push('--- Auswahlfelder');
for(var j=0;j<rs.length&&j<40;j++){var r=rs[j];var lab='';if(r.id){var l=document.querySelector('label[for="'+r.id+'"]');if(l)lab=t(l);}
 var L=r.closest('label');if(!lab&&L)lab=t(L);
 var box=r.parentElement;var bt=box?t(box):'';
 out.push(r.tagName+(r.type?'['+r.type+']':'')+a(r,'id')+a(r,'name')+a(r,'value')+a(r,'role')+' checked='+(r.checked||r.getAttribute('aria-checked')||r.getAttribute('aria-selected'))+(seen(r)?'':' (unsichtbar)')+' | Label: '+lab+' | Umgebung: '+bt);}
var bs=document.querySelectorAll('button,a,[role=button],input[type=submit]');out.push('--- Knöpfe');
var n=0;for(var k=0;k<bs.length&&n<60;k++){var b=bs[k];if(!seen(b))continue;var tx=t(b);var al=a(b,'aria-label')+a(b,'title')+a(b,'data-testid');
 if(!tx&&!al)al=' class="'+((b.getAttribute('class')||'').slice(0,40))+'"';out.push(b.tagName+': '+tx+al);n++;}
out.push('--- Klickbare Symbole (ohne Text)');
var cs=document.querySelectorAll('div,span,svg,img,i');var nc=0;
for(var c=0;c<cs.length&&nc<25;c++){var el=cs[c];if(t(el)||!seen(el))continue;if(getComputedStyle(el).cursor!=='pointer')continue;
 if(el.parentElement&&getComputedStyle(el.parentElement).cursor==='pointer'&&!t(el.parentElement))continue;
 var near='';var up2=el.parentElement;for(var z=0;z<4&&up2&&!near;z++){near=t(up2);up2=up2.parentElement;}
 out.push(el.tagName+a(el,'class').slice(0,50)+a(el,'data-testid')+' | in der Nähe: '+near.slice(0,40));nc++;}
out.push('--- Abschnitte (Text)');
var hd=document.querySelectorAll('h2,h3,h4,div,span,p');var ns=0;
for(var d=0;d<hd.length&&ns<8;d++){var hx=hd[d];var ht=t(hx);if(!/^(zahlungsart|lieferservice|lieferadresse|rechnungsadresse)$/i.test(ht))continue;
 var bx=hx.parentElement;for(var y=0;y<4&&bx;y++){var bxt=(bx.innerText||'').replace(/\s+/g,' ').trim();if(bxt.length>ht.length+8){out.push(ht+': '+bxt.slice(0,140));break;}bx=bx.parentElement;}ns++;}
var nd=document.getElementById('__NEXT_DATA__');if(nd){var js=nd.textContent;var re2=/.{0,80}paypal.{0,80}/gi;var mm;var nn=0;out.push('--- Seitendaten mit „paypal“');
 while((mm=re2.exec(js))!==null&&nn<6){out.push(mm[0].replace(/"(firstName|lastName|street|email|phone|city|zip)[^,]*,/gi,''));nn++;}}
var pp=[].filter.call(document.querySelectorAll('body *:not(script)'),function(e){return e.children.length===0&&/paypal/i.test((e.innerText||e.alt||e.getAttribute('aria-label')||''));});
out.push('--- Elemente mit „PayPal“ ('+pp.length+')');
for(var q=0;q<pp.length&&q<10;q++){var e=pp[q];var chain=[];var x=e;for(var u=0;u<4&&x;u++){chain.push(x.tagName+(x.getAttribute('role')?'[role='+x.getAttribute('role')+']':'')+(x.getAttribute('class')?'.'+x.getAttribute('class').split(' ')[0].slice(0,25):''));x=x.parentElement;}
 out.push(chain.join(' < ')+' | '+t(e));}
return out.join('\n').slice(0,7000);})()"""

        @Volatile
        private var pending: CompletableDeferred<WatchActivity>? = null

        /**
         * Öffnet das Fenster und wartet, bis es bereit ist. Gibt null zurück, wenn Android es nicht
         * öffnet (App im Hintergrund ohne Erlaubnis „Über anderen Apps einblenden“).
         * Nur auf dem Main-Thread aufrufen.
         */
        suspend fun open(ctx: Context): WatchActivity? {
            val d = CompletableDeferred<WatchActivity>()
            pending = d
            try {
                ctx.startActivity(
                    Intent(ctx, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                pending = null
                return null
            }
            val w = withTimeoutOrNull(8_000L) { d.await() }
            if (w == null) {
                pending = null
                d.cancel()
            }
            return w
        }
    }
}

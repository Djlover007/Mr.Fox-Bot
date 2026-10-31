'use strict';
/**
 * PAYBACK am PC: Die Payback-Webseite läuft im Bot-Browser (gleiche Sitzung wie die Käufe).
 * Einmal „Bei Payback anmelden“ (mit 2FA von Hand), danach kann der Bot vor einem Auto-Kauf
 * die Payback-Shopseite öffnen und zum Shop weiterleiten lassen – so werden °Punkte gutgeschrieben.
 * Klappt die Weiterleitung nicht, läuft der Kauf normal weiter (ohne Punkte) und das steht im Ergebnis.
 */
const B = require('./browser');
const Log = require('./log');

const PARTNERS = {
  amazon: { page: 'https://www.payback.de/shop/amazon', host: /(^|\.)amazon\.de$/i, label: 'Amazon' },
  mediamarkt: { page: 'https://www.payback.de/shop/mediamarkt', host: /(^|\.)mediamarkt\.de$/i, label: 'MediaMarkt' },
  baur: { page: 'https://www.payback.de/shop/baur', host: /(^|\.)baur\.de$/i, label: 'BAUR' },
};

function supports(key) { return Object.prototype.hasOwnProperty.call(PARTNERS, key); }

const LOGIN_URL = 'https://www.payback.de/login';

const STEP_JS = String.raw`(function(){
function txt(e){return ((e.innerText||e.value||e.getAttribute('aria-label')||'')+'').replace(/\s+/g,' ').trim();}
function vis(e){var s=getComputedStyle(e);if(s.display==='none'||s.visibility==='hidden')return false;return e.offsetParent!==null||s.position==='fixed';}
function find(re){var els=document.querySelectorAll('button,a,[role=button],input[type=submit]');
 for(var i=0;i<els.length;i++){var e=els[i];var t=txt(e);if(t&&t.length<70&&re.test(t)&&vis(e))return e;}return null;}
// Cookie-Hinweis bestätigen (auch im Shadow-DOM des Usercentrics-Banners)
var c=find(/^(alle akzeptieren|alles akzeptieren|akzeptieren|alle cookies akzeptieren|zustimmen|einverstanden)$/i);
if(!c){var hosts=document.querySelectorAll('#usercentrics-root,#usercentrics-cmp-ui,[id*=consent i]');
 for(var h=0;h<hosts.length&&!c;h++){var sr=hosts[h].shadowRoot;if(!sr)continue;var bs=sr.querySelectorAll('button');
  for(var j=0;j<bs.length;j++){if(/akzeptieren|zustimmen|accept/i.test(txt(bs[j]))){c=bs[j];break;}}}}
if(c){c.click();return 'cookie';}
var pw=document.querySelector('input[type=password]');if(pw&&vis(pw))return 'login';
if(/\/login/i.test(location.pathname))return 'login';
var go=find(/^(jetzt shoppen|jetzt einkaufen|zum shop|zum online-?shop|weiter zum shop|zum partner|°?punkte sammeln|jetzt °?punkte sammeln|shoppen & °?punkte sammeln|zu amazon|zu mediamarkt|zu baur)/i);
if(go){
 // Payback öffnet den Shop oft in einem NEUEN Tab – im selben Tab öffnen, damit der Bot ihn sieht
 window.open=function(u){if(u){location.href=u;}return window;};
 var a=go.closest('a[href]')||go;
 if(a.getAttribute&&a.getAttribute('target'))a.removeAttribute('target');
 var f=go.closest('form');if(f&&f.getAttribute('target'))f.removeAttribute('target');
 var h=a.getAttribute&&a.getAttribute('href');
 if(h&&/^https?:|^\//i.test(h)&&!/^#/.test(h)){location.href=a.href;}else{go.click();}
 return 'go:'+txt(go).slice(0,30)+(h?' → '+h.slice(0,60):'');}
if(/anmelden|einloggen/i.test((document.body&&document.body.innerText||'').slice(0,3000))&&find(/^(anmelden|einloggen|login)$/i))return 'login';
return 'wait';})()`;

/**
 * Payback-Shopseite öffnen und zum Shop weiterleiten lassen (im gegebenen Fenster).
 * Gibt {ok, detail} zurück.
 */
async function activate(wc, key, status) {
  const p = PARTNERS[key];
  if (!p) return { ok: false, detail: 'kein Payback-Partner' };
  status && status('Öffne Payback-Shopseite …');
  Log.add('Payback: ' + p.page);
  B.load(wc, p.page);
  await B.sleep(3000);
  let clicked = 0;
  let lastStep = '';
  let lastHost = '';
  const end = Date.now() + 45000;
  while (Date.now() < end) {
    let host = '';
    try { host = new URL(wc.getURL()).hostname; } catch (e) { /* leer */ }
    if (p.host.test(host)) {
      Log.add('Payback: beim Shop angekommen (' + host + ')');
      await B.sleep(2000);
      return { ok: true, detail: '💳 Über Payback gestartet' };
    }
    lastHost = host;
    if (!wc.isLoading() && /payback\./i.test(host)) {
      const r = (await B.js(wc, STEP_JS)) || '';
      lastStep = r;
      Log.add('Payback: ' + r);
      if (r === 'login') return { ok: false, detail: 'Payback nicht angemeldet – einmal „Bei Payback anmelden“' };
      if (r.startsWith('go:')) {
        clicked++;
        status && status('Payback: Weiterleitung zum Shop …');
        if (clicked > 3) return { ok: false, detail: `Payback-Weiterleitung hat nicht geklappt (Knopf gedrückt: ${r.slice(3)}, Seite bleibt ${host})` };
        await B.sleep(4000);
        continue;
      }
    }
    await B.sleep(1500);
  }
  return { ok: false, detail: `Payback-Weiterleitung: Zeitüberschreitung (zuletzt auf ${lastHost || '?'}: ${lastStep === 'wait' ? 'kein „Jetzt shoppen“-Knopf gefunden' : lastStep || '–'})` };
}

module.exports = { PARTNERS, supports, activate, LOGIN_URL };

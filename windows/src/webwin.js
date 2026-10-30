'use strict';
/**
 * Bot-Browser zum Ansehen und Anmelden (wie WebActivity der Android-App): gleiche Sitzung wie die
 * Abläufe des Bots. Auf Amazon-Anmeldeseiten füllt er den gespeicherten Zugang aus; mit
 * checkout=true wird im Amazon-Warenkorb einmal „Zur Kasse“ gedrückt (nie „Jetzt kaufen“).
 */
const { shell, Menu, clipboard } = require('electron');
const B = require('./browser');
const Store = require('./store');

/**
 * PayPal/Payback: gespeicherte E-Mail und Passwort auf der Anmeldeseite nur EINTRAGEN (nicht abschicken).
 * Mehrfach nachschauen, weil die Felder oft erst später erscheinen (PayPal: erst E-Mail, dann Passwort).
 */
function autofill(wc, url) {
  let host = '';
  try { host = new URL(url).hostname; } catch (e) { return; }
  const key = /(^|\.)paypal\.com$/i.test(host) ? 'paypal' : /(^|\.)payback\.de$/i.test(host) ? 'payback' : null;
  if (!key) return;
  const mail = Store.loginEmail(key);
  const pw = Store.loginPassword(key);
  if (!mail && !pw) return;
  const js = "(function(){function setv(el,v){if(!el||!v||el.value)return false;var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');" +
    "d.set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return true;}" +
    'function vis(e){return e&&e.offsetParent!==null;}var n=0;' +
    "var m=document.querySelector('#email,input[name=login_email],input[type=email],input[autocomplete=username],input[name*=mail i],input[id*=mail i],input[name*=user i]');" +
    'if(vis(m)&&setv(m,' + JSON.stringify(mail || '') + '))n++;' +
    "var p=document.querySelector('#password,input[name=login_password],input[type=password]');" +
    'if(vis(p)&&setv(p,' + JSON.stringify(pw || '') + '))n++;return n;})()';
  for (let i = 0; i < 30; i++) {
    setTimeout(() => { if (!wc.isDestroyed() && wc.getURL() === url) B.js(wc, js); }, 800 + i * 2000);
  }
}

function open(url, { checkout = false, forceInApp = false } = {}) {
  if (!forceInApp && Store.setting('externalBrowser')) {
    shell.openExternal(url);
    return null;
  }
  const A = require('./amazon');
  const w = B.newWindow({ show: true, title: 'Mr.Fox Bot – Browser', width: 1280, height: 950 });
  const wc = w.webContents;
  w.removeAllListeners('page-title-updated'); // hier den Seitentitel zeigen
  const sess = A.newSession();
  let checkoutTried = false;
  wc.on('did-finish-load', async () => {
    const u = wc.getURL();
    // neue Anmeldeseite (/ax/): nicht automatisch absenden – sonst antwortet Amazon mit „Bitte aktiviere Cookies“
    if (A.isLoginUrl(u) && A.isAmazon(u) && !u.includes('/ax/')) {
      const st = await A.loginStep(wc, sess);
      const hint = {
        [A.State.OTP]: 'Bitte den Bestätigungscode eingeben – danach bleibt der Bot angemeldet.',
        [A.State.VERIFY]: 'Bitte die Anmeldung bestätigen (Amazon-App, SMS oder E-Mail).',
        [A.State.CAPTCHA]: 'Bitte das Captcha lösen.',
        [A.State.GAVE_UP]: 'Automatische Anmeldung hat nicht geklappt – Passwort prüfen.',
      }[st];
      if (hint && !w.isDestroyed()) w.setTitle('Mr.Fox Bot – ' + hint);
    }
    autofill(wc, u);
    if (checkout && !checkoutTried && u.includes('/cart/view')) {
      checkoutTried = true;
      await B.js(wc, "(function(){var sel=['input[name=\"proceedToRetailCheckout\"]','#sc-buy-box-ptc-button input','#sc-buy-box-ptc-button'];" +
        "for(var i=0;i<sel.length;i++){var b=document.querySelector(sel[i]);if(b){b.click();return 'clicked';}}return 'none';})()");
    }
  });
  w.setMenu(Menu.buildFromTemplate([
    { label: '← Zurück', click: () => { if (wc.canGoBack()) wc.goBack(); } },
    { label: 'Vor →', click: () => { if (wc.canGoForward()) wc.goForward(); } },
    { label: '⟳ Neu laden', click: () => wc.reload() },
    { label: '🔗 Link kopieren', click: () => clipboard.writeText(wc.getURL()) },
    { label: '🌐 Im Standard-Browser öffnen', click: () => shell.openExternal(wc.getURL()) },
  ]));
  B.load(wc, url);
  return w;
}

/**
 * Über Payback öffnen: zuerst die Payback-Shopseite im Bot-Browser („Jetzt shoppen“ drückt der Bot),
 * danach die (saubere) Produktseite im selben Fenster – so werden °Punkte gutgeschrieben.
 */
function openViaPayback(key, url) {
  const Payback = require('./payback');
  const w = B.newWindow({ show: true, title: 'Mr.Fox Bot – öffne über Payback …', width: 1280, height: 950 });
  const wc = w.webContents;
  wc.on('did-finish-load', () => autofill(wc, wc.getURL()));
  w.setMenu(Menu.buildFromTemplate([
    { label: '← Zurück', click: () => { if (wc.canGoBack()) wc.goBack(); } },
    { label: '⟳ Neu laden', click: () => wc.reload() },
    { label: '🛍️ Zum Produkt', click: () => B.load(wc, url) },
    { label: '🔗 Produkt-Link kopieren', click: () => clipboard.writeText(url) },
  ]));
  (async () => {
    const r = await Payback.activate(wc, key, (s) => { if (!w.isDestroyed()) w.setTitle('Mr.Fox Bot – ' + s); });
    if (w.isDestroyed()) return;
    if (!r.ok && /nicht angemeldet/.test(r.detail)) {
      // Payback-Anmeldung offen lassen; danach oben „🛍️ Zum Produkt“
      clipboard.writeText(url);
      w.setTitle('Mr.Fox Bot – Bitte bei Payback anmelden, dann „Jetzt shoppen“ und oben „🛍️ Zum Produkt“ (Link ist kopiert)');
      return;
    }
    B.load(wc, url);
    w.setTitle(r.ok ? 'Mr.Fox Bot – 💳 über Payback geöffnet (°Punkte)' : 'Mr.Fox Bot – ⚠️ ohne Payback: ' + r.detail);
  })();
  return w;
}

module.exports = { open, openViaPayback, autofill };

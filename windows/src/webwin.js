'use strict';
/**
 * Bot-Browser zum Ansehen und Anmelden (wie WebActivity der Android-App): gleiche Sitzung wie die
 * Abläufe des Bots. Auf Amazon-Anmeldeseiten füllt er den gespeicherten Zugang aus; mit
 * checkout=true wird im Amazon-Warenkorb einmal „Zur Kasse“ gedrückt (nie „Jetzt kaufen“).
 */
const { shell, Menu, clipboard } = require('electron');
const B = require('./browser');
const Store = require('./store');

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
    if (u.includes('/ap/') && A.isAmazon(u)) {
      const st = await A.loginStep(wc, sess);
      const hint = {
        [A.State.OTP]: 'Bitte den Bestätigungscode eingeben – danach bleibt der Bot angemeldet.',
        [A.State.VERIFY]: 'Bitte die Anmeldung bestätigen (Amazon-App, SMS oder E-Mail).',
        [A.State.CAPTCHA]: 'Bitte das Captcha lösen.',
        [A.State.GAVE_UP]: 'Automatische Anmeldung hat nicht geklappt – Passwort prüfen.',
      }[st];
      if (hint && !w.isDestroyed()) w.setTitle('Mr.Fox Bot – ' + hint);
    }
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

module.exports = { open };

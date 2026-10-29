'use strict';
/**
 * Amazon: automatische Anmeldung, Auto-Kauf über „Jetzt kaufen“ und automatischer Warenkorb.
 * Gleiche Abläufe wie AutoLogin.kt, AutoBuyer.kt und CartAdder.kt der Android-App.
 */
const B = require('./browser');
const Store = require('./store');
const { parsePrice, normOffer } = require('./scraper');
const { REPORT_JS } = require('./report');
const Log = require('./log');

const Outcome = {
  ORDERED: 'ORDERED', DRY_RUN_OK: 'DRY_RUN_OK', NOT_LOGGED_IN: 'NOT_LOGGED_IN',
  BLOCKED: 'BLOCKED', ABORTED: 'ABORTED', FAILED: 'FAILED', UNCLEAR: 'UNCLEAR',
};

function money(v) { return v == null ? '?' : v.toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' €'; }

function describe(r) {
  switch (r.outcome) {
    case Outcome.ORDERED: return '🛒 Automatisch bestellt' + (r.total != null ? ` (Gesamt ${money(r.total)})` : '') + ' – bitte in deinen Bestellungen prüfen';
    case Outcome.DRY_RUN_OK: return '✅ ' + r.detail;
    case Outcome.UNCLEAR: return `⚠️ Auto-Kauf unklar (${r.detail}) – bitte SOFORT deine Bestellungen prüfen`;
    case Outcome.NOT_LOGGED_IN: return '⚠️ Auto-Kauf nicht ausgeführt: ' + (r.detail || 'nicht angemeldet');
    case Outcome.BLOCKED: return '⚠️ Auto-Kauf nicht ausgeführt: ' + r.detail;
    case Outcome.ABORTED: return '⛔ Auto-Kauf abgebrochen: ' + r.detail;
    default: return '⚠️ Auto-Kauf nicht ausgeführt: ' + r.detail;
  }
}

// ---------------- Anmeldung ----------------
const State = {
  FILLED: 'FILLED', WAIT: 'WAIT', NO_CREDENTIALS: 'NO_CREDENTIALS', CAPTCHA: 'CAPTCHA',
  OTP: 'OTP', VERIFY: 'VERIFY', GAVE_UP: 'GAVE_UP', NOT_LOGIN_PAGE: 'NOT_LOGIN_PAGE',
};

function newSession() { return { emailTries: 0, pwTries: 0, lastFill: 0, lastKind: '' }; }

function isAmazon(url) {
  try {
    const u = new URL(url);
    const d = String(Store.setting('domain')).toLowerCase();
    return u.protocol === 'https:' && (u.hostname === d || u.hostname.endsWith('.' + d));
  } catch (e) { return false; }
}

const DETECT_JS = String.raw`(function(){var h=location.href;
if(document.getElementById('auth-captcha-guess')||document.getElementById('captchacharacters'))return 'captcha';
if(document.getElementById('auth-mfa-otpcode')||h.indexOf('/ap/mfa')>=0)return 'otp';
if(h.indexOf('/ap/cvf')>=0||document.getElementById('channelDetailsForOtp')||document.querySelector('[name="transactionApprovalStatus"]'))return 'verify';
var p=document.getElementById('ap_password');if(p&&p.type!=='hidden')return 'pw';
if(document.querySelector('#ap_email,#ap_email_login,input[name="email"]'))return 'email';
return 'none';})()`;

function fillScript(email, pw) {
  let s = "(function(){function set(el,v){el.focus();el.value=v;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}" +
    'function first(s){for(var i=0;i<s.length;i++){var e=document.querySelector(s[i]);if(e)return e;}return null;}' +
    "var r=document.querySelector('input[name=rememberMe]');if(r&&!r.checked){r.click();}" +
    "var m=document.querySelector('#ap_email,#ap_email_login,input[name=\"email\"]');" +
    "if(m&&m.type!=='hidden'&&!m.value){set(m," + JSON.stringify(email) + ');}';
  if (pw == null) {
    s += "var c=first(['#continue input','input#continue','#continue']);if(c){c.click();}";
  } else {
    s += "var p=document.getElementById('ap_password');if(p){set(p," + JSON.stringify(pw) + ');}' +
      "var b=first(['input#signInSubmit','#signInSubmit input','#signInSubmit']);if(b){b.click();}";
  }
  return s + '})();';
}

/** Ein Anmeldeschritt (höchstens ein Versuch je E-Mail und Passwort pro Ablauf). */
async function loginStep(wc, sess) {
  const url = wc.getURL();
  if (!url.includes('/ap/') || !isAmazon(url)) return State.NOT_LOGIN_PAGE;
  const email = Store.loginEmail('amazon');
  const pw = Store.loginPassword('amazon');
  const kind = (await B.js(wc, DETECT_JS)) || '';
  if (kind === sess.lastKind && Date.now() - sess.lastFill < 8000) return State.WAIT;
  if (kind === 'email' || kind === 'pw') sess.lastKind = kind;
  switch (kind) {
    case 'captcha': return State.CAPTCHA;
    case 'otp': return State.OTP;
    case 'verify': return State.VERIFY;
    case 'email':
      if (!email || pw == null) return State.NO_CREDENTIALS;
      if (sess.emailTries >= 1) return State.GAVE_UP;
      sess.emailTries++;
      sess.lastFill = Date.now();
      await B.js(wc, fillScript(email, null));
      return State.FILLED;
    case 'pw':
      if (!email || pw == null) return State.NO_CREDENTIALS;
      if (sess.pwTries >= 1) return State.GAVE_UP;
      sess.pwTries++;
      sess.lastFill = Date.now();
      await B.js(wc, fillScript(email, pw));
      return State.FILLED;
    default: return State.WAIT;
  }
}

function explain(st) {
  switch (st) {
    case State.NO_CREDENTIALS: return 'Amazon verlangt eine Anmeldung, aber im Bot ist kein Amazon-Zugang (E-Mail/Passwort) gespeichert';
    case State.CAPTCHA: return 'Amazon zeigt bei der Anmeldung ein Captcha – einmal über „Im Bot-Browser anmelden“ lösen';
    case State.OTP: return 'Amazon verlangt einen Bestätigungscode – einmal über „Im Bot-Browser anmelden“ eingeben';
    case State.VERIFY: return 'Amazon will die Anmeldung bestätigt haben (App/SMS/E-Mail) – einmal über „Im Bot-Browser anmelden“ bestätigen';
    case State.GAVE_UP: return 'Automatische Anmeldung hat nicht geklappt (Passwort prüfen)';
    default: return 'Amazon verlangt eine Anmeldung';
  }
}

// ---------------- Auto-Kauf ----------------
const BUY_NOW_SELECTORS = ['#buy-now-button', 'input#buy-now-button', "input[name='submit.buy-now']"];
const PLACE_ORDER_SELECTORS = ['#turbo-checkout-pyo-button', "input[name='placeYourOrder1']", '#placeYourOrder input', '#submitOrderButtonId input', '#bottomSubmitOrderButtonId input'];
const TOTAL_SELECTORS = ['#subtotals-marketplace-table .grand-total-price', '.grand-total-price', '#turbo-checkout-panel-container .grand-total-price', '#turbo-checkout-panel-container .a-price .a-offscreen', '#subtotals-marketplace-table .a-color-price'];
const CAPTCHA_TEST = "document.getElementById('captchacharacters')||document.querySelector('form[action*=\"validateCaptcha\"]')";

const FIND_TOTAL_JS = String.raw`
function findTotal(d){
 var labels=[/^(gesamtsumme|gesamtbetrag|bestellsumme|zu zahlender betrag|order total)/i,/^(gesamt|summe|total)\b/i];
 var pr=/(\d{1,3}(?:[.\s]\d{3})*,\d{2}\s*€|€\s*\d{1,3}(?:[.,\s]\d{3})*[.,]\d{2}|\d+[.,]\d{2}\s*€)/g;
 var els=d.querySelectorAll('span,td,th,div,label,dt,dd,strong,b,h2,h3,h4,h5,p');
 for(var L=0;L<labels.length;L++){
  for(var i=0;i<els.length;i++){
   var e=els[i];if(e.children.length>3)continue;
   var t=(e.textContent||'').replace(/\s+/g,' ').trim();
   if(t.length>60||!labels[L].test(t))continue;
   var m=t.match(pr);if(m)return m[m.length-1];
   var row=e.parentElement;
   for(var up=0;up<4&&row;up++){
    var rt=(row.textContent||'').replace(/\s+/g,' ');
    if(rt.length>300)break;
    m=rt.match(pr);if(m)return m[m.length-1];
    row=row.parentElement;
   }
  }
 }
 return null;
}
function hintOf(d){
 var out=[];var els=d.querySelectorAll('span,td,div,dd,strong,b');
 for(var i=0;i<els.length&&out.length<8;i++){
  var e=els[i];if(e.children.length)continue;
  var t=(e.textContent||'').trim();
  if(t.length>30||t.indexOf('€')<0)continue;
  var p=e.parentElement&&e.parentElement.parentElement;
  var s=((p&&p.textContent)||t).replace(/\s+/g,' ').trim().slice(0,70);
  if(out.indexOf(s)<0)out.push(s);
 }
 return out.join(' | ');
}
`;

function buyNowJs() {
  return '(function(){if(' + CAPTCHA_TEST + ")return 'captcha';var sel=" + JSON.stringify(BUY_NOW_SELECTORS) +
    ";for(var i=0;i<sel.length;i++){var b=document.querySelector(sel[i]);if(b){b.click();return 'clicked';}}return 'none';})()";
}

function docsJs() {
  return 'function q(d,s){for(var i=0;i<s.length;i++){try{var e=d.querySelector(s[i]);if(e)return e;}catch(x){}}return null;}' +
    "var docs=[];var f=document.querySelector('#turbo-checkout-iframe,iframe[id*=\"turbo\"]');" +
    'if(f){try{if(f.contentDocument)docs.push(f.contentDocument);}catch(x){}}docs.push(document);' +
    'var BTN=' + JSON.stringify(PLACE_ORDER_SELECTORS) + ';var TOT=' + JSON.stringify(TOTAL_SELECTORS) + ';';
}

function pollJs() {
  return '(function(){' + docsJs() + FIND_TOTAL_JS +
    "var h=location.href;if(h.indexOf('thankyou')>=0||document.getElementById('widget-purchaseConfirmationStatus'))return JSON.stringify({s:'done'});" +
    'if(' + CAPTCHA_TEST + ")return JSON.stringify({s:'captcha'});" +
    "if(h.indexOf('/ap/')>=0)return JSON.stringify({s:'login'});" +
    'for(var k=0;k<docs.length;k++){var d=docs[k];var b=q(d,BTN);if(!b)continue;var t=q(d,TOT);' +
    "var tt=t?(t.textContent||'').trim():'';if(!/\\d/.test(tt))tt=findTotal(d)||'';" +
    "if(tt)return JSON.stringify({s:'ready',total:tt});return JSON.stringify({s:'nototal',hint:hintOf(d)});}" +
    "return JSON.stringify({s:'wait'});})()";
}

function placeOrderJs() {
  return '(function(){' + docsJs() + "for(var k=0;k<docs.length;k++){var b=q(docs[k],BTN);if(b){b.click();return 'clicked';}}return 'none';})()";
}

let busy = Promise.resolve();
/** Nie zwei Kauf-/Warenkorb-Abläufe gleichzeitig (gemeinsam mit den Web-Shops). */
function exclusive(fn) {
  const run = busy.then(fn, fn);
  busy = run.catch(() => {});
  return run;
}

/**
 * Auto-Kauf bzw. Probelauf eines Amazon-Artikels über „Jetzt kaufen“.
 * opts: {dryRun, visible, payback: async (wc)=>{...}}
 */
function buy(asin, opts) {
  return exclusive(() => runBuy(asin, opts));
}

async function runBuy(asin, { dryRun, visible, beforeStart }) {
  const domain = Store.setting('domain');
  const productUrl = `https://www.${domain}/dp/${asin}`;
  const prefix = dryRun ? 'Amazon-Probelauf: ' : 'Amazon-Auto-Kauf: ';
  Log.start(prefix + productUrl);
  const w = visible ? B.watchWindow(prefix + 'startet …', reportOf) : B.newWindow({ show: false });
  const wc = w.webContents;
  let closed = false;
  let lastStatus = '';
  const status = (s) => {
    if (s !== lastStatus) { lastStatus = s; Log.add(s); }
    if (!w.isDestroyed()) w.setTitle(prefix + s);
  };
  w.on('closed', () => { closed = true; });

  let stage = 0; // 0 = Produktseite, 1 = „Jetzt kaufen“ gedrückt, 2 = bestellt
  let polls = 0;
  let noTotal = 0;
  let total = null;
  let wasLogin = false;
  let reloaded = false;
  const sess = newSession();
  const deadline = Date.now() + 120000;

  const finish = (r) => {
    Log.add(`Ergebnis: ${r.outcome} – ${r.detail}`);
    if (visible) { if (!w.isDestroyed()) w.setTitle('Fertig: ' + describe(r)); } else if (!w.isDestroyed()) w.destroy();
    return r;
  };

  try {
    if (beforeStart) await beforeStart(wc, status);
    status('Lade Produktseite …');
    B.load(wc, productUrl);
    await B.sleep(2500);
    while (true) {
      if (closed) return finish({ outcome: stage === 2 ? Outcome.UNCLEAR : Outcome.FAILED, detail: stage === 2 ? 'Fenster nach dem Bestell-Klick geschlossen' : 'Fenster geschlossen', total });
      if (Date.now() > deadline) return finish(stage === 2 ? { outcome: Outcome.UNCLEAR, detail: 'Zeitüberschreitung nach dem Klick auf „Bestellen“', total } : { outcome: Outcome.FAILED, detail: 'Zeitüberschreitung' });
      const url = wc.getURL();
      if (stage === 0) {
        polls++;
        if (wc.isLoading()) { await B.sleep(1000); continue; }
        if (url.includes('validateCaptcha')) return finish({ outcome: Outcome.BLOCKED, detail: 'Amazon zeigt ein Captcha' });
        if (url.includes('/ap/')) {
          wasLogin = true;
          const st = await loginStep(wc, sess);
          if (st === State.FILLED || st === State.WAIT) { status('Melde bei Amazon an …'); await B.sleep(2000); continue; }
          if (st !== State.NOT_LOGIN_PAGE) return finish({ outcome: Outcome.NOT_LOGGED_IN, detail: explain(st) });
        } else if (url.includes('/dp/')) {
          stage = 1;
          polls = 0;
          const res = await B.js(wc, buyNowJs());
          if (res === 'clicked') { status('„Jetzt kaufen“ gedrückt, suche Bestellseite …'); await B.sleep(2500); continue; }
          if (res === 'captcha') return finish({ outcome: Outcome.BLOCKED, detail: 'Amazon zeigt ein Captcha' });
          return finish({ outcome: Outcome.FAILED, detail: 'Kein „Jetzt kaufen“-Knopf (nicht verfügbar, Variante nötig oder 1-Click aus)' });
        } else if (wasLogin && !reloaded) {
          reloaded = true;
          status('Angemeldet, lade Produktseite …');
          B.load(wc, productUrl);
        } else if (polls >= 30) {
          return finish({ outcome: Outcome.FAILED, detail: 'Produktseite nicht geladen' });
        }
        await B.sleep(1500);
        continue;
      }
      const o = B.parse(await B.js(wc, pollJs()));
      polls++;
      switch (o.s) {
        case 'done':
          return finish(stage === 2 ? { outcome: Outcome.ORDERED, detail: 'Bestellung bestätigt', total } : { outcome: Outcome.UNCLEAR, detail: 'Bestätigungsseite erschienen, ohne dass der Bot bestellt hat' });
        case 'captcha':
          return finish(stage === 2 ? { outcome: Outcome.UNCLEAR, detail: 'Captcha nach dem Bestellen', total } : { outcome: Outcome.BLOCKED, detail: 'Amazon zeigt ein Captcha' });
        case 'login': {
          if (stage === 2) return finish({ outcome: Outcome.UNCLEAR, detail: 'Anmeldung nach dem Bestellen verlangt', total });
          const st = await loginStep(wc, sess);
          if (st === State.FILLED || st === State.WAIT || st === State.NOT_LOGIN_PAGE) status('Melde bei Amazon an …');
          else return finish({ outcome: Outcome.NOT_LOGGED_IN, detail: explain(st) });
          break;
        }
        case 'ready':
        case 'nototal':
          if (stage === 1) {
            const t = o.s === 'ready' ? parsePrice(o.total) : null;
            if (t == null) noTotal++;
            const proceed = t != null || !dryRun || noTotal >= 4;
            if (proceed && dryRun) {
              const info = t != null ? 'Gesamtbetrag ' + money(t) : 'Gesamtbetrag nicht lesbar, würde trotzdem bestellen' + (o.hint ? '. Gefundene Beträge: ' + o.hint : '');
              return finish({ outcome: Outcome.DRY_RUN_OK, detail: `Bestellknopf gefunden, ${info} – Probelauf, nichts bestellt`, total: t });
            }
            if (proceed) {
              total = t;
              stage = 2;
              polls = 0;
              status('Bestellung wird abgeschickt …');
              await B.js(wc, placeOrderJs());
            }
          }
          break;
        default:
          break;
      }
      if (stage === 1 && polls >= 30) return finish({ outcome: Outcome.FAILED, detail: 'Bestellseite nicht erreicht oder nicht erkannt' });
      if (stage === 2 && polls >= 20) return finish({ outcome: Outcome.UNCLEAR, detail: 'Keine Bestätigungsseite erkannt', total });
      await B.sleep(1500);
    }
  } catch (e) {
    return finish({ outcome: stage === 2 ? Outcome.UNCLEAR : Outcome.FAILED, detail: e.message || 'Fehler', total });
  }
}

// ---------------- Warenkorb ----------------
const CLICK_JS = "(function(){if(document.getElementById('captchacharacters')||document.querySelector('form[action*=\"validateCaptcha\"]'))return 'captcha';" +
  "var sel=['#add-to-cart-button','input#add-to-cart-button','input[name=\"submit.add-to-cart\"]','#add-to-cart-button-ubb'];" +
  "for(var i=0;i<sel.length;i++){var b=document.querySelector(sel[i]);if(b){b.click();return 'clicked';}}return 'none';})()";
const CHECK_JS = "(function(){var ids=['NATC_SMART_WAGON_CONF_MSG_SUCCESS','sw-atc-confirmation','huc-v2-order-row-confirm-text','attachDisplayAddBaseAlert','sw-atc-details-single-container','attach-accessory-pane'];" +
  "for(var i=0;i<ids.length;i++){if(document.getElementById(ids[i]))return 'ok';}return 'no';})()";
const AWS_CONFIRM_JS = "(function(){if(document.getElementById('captchacharacters')||document.querySelector('form[action*=\"validateCaptcha\"]'))return 'captcha';" +
  "var sel=['input[name=\"add\"]','form[action*=\"cart/add\"] input[type=\"submit\"]','form[action*=\"cart\"] input[type=\"image\"]'];" +
  "for(var i=0;i<sel.length;i++){var b=document.querySelector(sel[i]);if(b){b.click();return 'clicked';}}return 'none';})()";
const CAPTCHA_DETAIL = 'Amazon zeigt ein Captcha – öffne die Produktseite einmal im Bot-Browser, löse es und versuche es später erneut';

function encodeOffer(id) { return encodeURIComponent(normOffer(id) || id); }

function cartUrl(asin, offerId) {
  const d = Store.setting('domain');
  return offerId
    ? `https://www.${d}/gp/aws/cart/add.html?OfferListingId.1=${encodeOffer(offerId)}&Quantity.1=1`
    : `https://www.${d}/gp/aws/cart/add.html?ASIN.1=${asin}&Quantity.1=1`;
}

function checkoutUrl() { return `https://www.${Store.setting('domain')}/gp/cart/view.html`; }
function ordersUrl() { return `https://www.${Store.setting('domain')}/gp/your-account/order-history`; }

/** Legt den Artikel in den Warenkorb. Ergebnis {result: 'OK'|'NOT_LOGGED_IN'|'FAILED', detail}. */
function addToCart(asin, offerId) {
  return exclusive(() => runCart(asin, offerId));
}

async function runCart(asin, offerId) {
  const productUrl = `https://www.${Store.setting('domain')}/dp/${asin}`;
  const fastUrl = offerId ? cartUrl(asin, offerId) : null;
  let fastFailed = !fastUrl;
  const w = B.newWindow({ show: false });
  const wc = w.webContents;
  let stage = 0;
  let polls = 0;
  let t11 = 0;
  let wasLogin = false;
  let reloaded = false;
  const sess = newSession();
  const deadline = Date.now() + 90000;
  const done = (result, detail) => { if (!w.isDestroyed()) w.destroy(); return { result, detail }; };
  const start = () => {
    if (!fastFailed && fastUrl) { stage = 10; B.load(wc, fastUrl); } else { stage = 0; B.load(wc, productUrl); }
  };
  try {
    start();
    await B.sleep(2000);
    while (Date.now() < deadline) {
      if (wc.isLoading()) { await B.sleep(800); continue; }
      const url = wc.getURL();
      if (url.includes('validateCaptcha') || url.includes('/errors/')) return done('FAILED', CAPTCHA_DETAIL);
      if (url.includes('/ap/')) {
        wasLogin = true;
        const st = await loginStep(wc, sess);
        if (st === State.FILLED || st === State.WAIT) { await B.sleep(2000); continue; }
        if (st !== State.NOT_LOGIN_PAGE) return done('NOT_LOGGED_IN', explain(st));
      } else if (wasLogin && !reloaded && !url.includes('/dp/') && !url.includes('/aws/cart/add')) {
        reloaded = true;
        start();
        await B.sleep(2000);
        continue;
      }
      if (stage === 10 && url.includes('/aws/cart/add')) {
        stage = 11;
        const res = await B.js(wc, AWS_CONFIRM_JS);
        if (res === 'clicked') t11 = Date.now();
        else if (res === 'captcha') return done('FAILED', CAPTCHA_DETAIL);
        else { fastFailed = true; start(); }
      } else if (stage === 11) {
        if (!url.includes('/aws/cart/add') && (url.includes('/huc/') || url.includes('/cart'))) return done('OK', 'Angebot per OfferListingId in den Warenkorb gelegt');
        if (Date.now() - t11 > 15000) { fastFailed = true; start(); }
      } else if (stage === 0 && url.includes('/dp/')) {
        stage = 1;
        polls = 0;
        const res = await B.js(wc, CLICK_JS);
        if (res === 'captcha') return done('FAILED', CAPTCHA_DETAIL);
        if (res !== 'clicked') return done('FAILED', 'Kein „In den Einkaufswagen“-Knopf gefunden (Artikel nicht verfügbar oder Variante nötig)');
      } else if (stage === 1) {
        if (!url.includes('/aws/cart/add') && (url.includes('/huc/') || url.includes('/cart'))) return done('OK', 'Weiterleitung zum Warenkorb');
        if ((await B.js(wc, CHECK_JS)) === 'ok') return done('OK', 'Bestätigung von Amazon erhalten');
        polls++;
        if (polls >= 12) return done('FAILED', 'Keine Bestätigung von Amazon erhalten');
      }
      await B.sleep(1500);
    }
    return done('FAILED', 'Zeitüberschreitung');
  } catch (e) {
    return done('FAILED', e.message || 'Fehler');
  }
}

async function reportOf(wc) {
  const page = (await B.js(wc, REPORT_JS)) || 'Seite nicht lesbar';
  return `Mr.Fox Seitenbericht (Windows ${require('electron').app.getVersion()})\n${page}\n--- Ablauf\n${Log.text()}`;
}

module.exports = {
  Outcome, describe, money, State, newSession, loginStep, explain, isAmazon,
  buy, addToCart, exclusive, cartUrl, checkoutUrl, ordersUrl, encodeOffer, reportOf, FIND_TOTAL_JS,
};

'use strict';
/**
 * Web-Shops (Müller, MediaMarkt, BAUR): in den Warenkorb legen und automatisch bestellen.
 * Gleicher Ablauf wie ShopBuyer.kt der Android-App:
 * Warenkorb prüfen → Produktseite → „In den Warenkorb“ → Warenkorb → Kasse (Geräteschutz ablehnen,
 * PayPal wählen, „Weiter“) → Bestellknopf → Bestätigung (bzw. PayPal „Jetzt zahlen“).
 *
 * Achtung: Hier wird der GANZE Warenkorb des Shops bestellt.
 */
const B = require('./browser');
const Store = require('./store');
const Log = require('./log');
const { COMMON_JS: C, articleNumber } = require('./shops');
const { parsePrice } = require('./scraper');
const { REPORT_JS } = require('./report');
const A = require('./amazon');
const K = require('./checkout-js');

const { Outcome, money } = A;

const fn = (body) => '(function(){' + C + body;
const PAYPAL_SELECT_JS = fn(K.PAYPAL_SELECT_JS_BODY);
const PAYMENT_CHANGE_JS = fn(K.PAYMENT_CHANGE_JS_BODY);
const PAYPAL_PAY_JS = fn(K.PAYPAL_PAY_JS_BODY);
const DECLINE_JS = fn(K.DECLINE_JS_BODY);

function addJs(shop) {
  return '(function(){' + C + "if(security())return 'security';if(!document.querySelector('h1'))return 'wait';" +
    'var b=btn(new RegExp(' + JSON.stringify(shop.addButtonRe) + ",'i'));if(b){b.click();return 'clicked';}return 'none';})()";
}

const ADDED_JS = '(function(){' + C + "var b=(document.body&&document.body.innerText)||'';" +
  "if(/in den warenkorb gelegt|zum warenkorb hinzugefügt|wurde hinzugefügt|artikel hinzugefügt/i.test(b))return 'ok';" +
  "if(btn(/^(zum warenkorb|warenkorb anzeigen)$/i))return 'ok';return 'no';})()";

function cartHasJs(article) {
  return '(function(){' + C + "if(security())return 'security';if(document.readyState!=='complete')return 'wait';" +
    'return document.documentElement.innerHTML.indexOf(' + JSON.stringify(article) + ")>=0?'yes':'no';})()";
}

function stepJs(exhausted) {
  return '(function(){' + C + A.FIND_TOTAL_JS + K.PICK_JS + K.PAY_SECTION_JS +
    'var EX=' + JSON.stringify([...exhausted]) + ';' +
    "if(security())return JSON.stringify({s:'security'});" +
    "var body=(document.body&&document.body.innerText)||'';var h=location.href.toLowerCase();" +
    "var pw=document.querySelector('input[type=password]');if(pw&&vis(pw))return JSON.stringify({s:'login'});" +
    'var rs=roots();' +
    'for(var q=0;q<rs.length;q++){var R=rs[q];' +
    ' var f=pickIn(R,' + K.FINAL_RE + ',/einkaufen/i);' +
    " if(!f&&R===document){var os=document.querySelector('[data-testid=order-submit],[data-testid*=order-submit i],button[name=placeOrder]');if(os&&vis(os))f=os;}" +
    " if(f)return JSON.stringify({s:'final',total:findTotal(document)||'',label:txt(f),hint:hintOf(document),pay:paySec(),qty:maxQty()});" +
    ' if(q===0&&R!==document){var n0=pickIn(R,' + K.NEXT_RE + ",/einkaufen|shoppen/i);if(n0)return JSON.stringify({s:'click',label:txt(n0)});}" +
    '}' +
    'if(/vielen dank für (ihre|deine) bestellung|(ihre|deine) bestellnummer|bestellung ist eingegangen/i.test(body)' +
    "||/success|danke|thank/.test(h))return JSON.stringify({s:'done'});" +
    'for(var q=0;q<rs.length;q++){var R=rs[q];' +
    " var k=pickIn(R,/^zur kasse/i);if(k)return JSON.stringify({s:'click',label:txt(k)});" +
    ' var n=pickIn(R,' + K.NEXT_RE + ",/einkaufen|shoppen/i);if(n)return JSON.stringify({s:'click',label:txt(n)});}" +
    "var a=pickIn(document,/^(anmelden|einloggen)$/i);if(a)return JSON.stringify({s:'needlogin',label:txt(a)});" +
    "return JSON.stringify({s:'wait',title:document.title});})()";
}

function clickTextJs(label) {
  return '(function(){' + C + K.PICK_JS + 'var EX=[];var t=' + JSON.stringify(label) + ';var rs=roots();' +
    "for(var q=0;q<rs.length;q++){var els=rs[q].querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]');" +
    "for(var i=0;i<els.length;i++){var e=els[i];if(txt(e)===t&&vis(e)){e.click();return 'clicked';}}}return 'none';})()";
}

function loginJs(email, pw) {
  return '(function(){' + C +
    "function setv(el,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');" +
    "d.set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}" +
    "var m=document.querySelector('input[type=email],input[autocomplete=username],input[name*=mail i],input[id*=mail i]');" +
    "var p=document.querySelector('input[type=password]');" +
    'if(m&&!m.value)setv(m,' + JSON.stringify(email) + ');if(p)setv(p,' + JSON.stringify(pw) + ');' +
    'var b=btn(/^(anmelden|einloggen|login|weiter)$/i);' +
    "if(!b&&p&&p.form){b=p.form.querySelector('button[type=submit],input[type=submit]');}" +
    "if(b){b.click();return 'clicked';}return 'nobutton';})()";
}

const Mode = { CART: 'CART', BUY: 'BUY', DRY: 'DRY' };

/** In den Warenkorb legen. Ergebnis {result: 'OK'|'NOT_LOGGED_IN'|'FAILED', detail}. */
async function addToCart(shop, url, productId) {
  const r = await A.exclusive(() => run(shop, url, productId, Mode.CART, false));
  const result = r.outcome === Outcome.DRY_RUN_OK ? 'OK' : r.outcome === Outcome.NOT_LOGGED_IN ? 'NOT_LOGGED_IN' : 'FAILED';
  return { result, detail: r.detail };
}

/** Auto-Kauf bzw. Probelauf. opts: {dryRun, visible, beforeStart} */
function buy(shop, url, productId, { dryRun, visible, beforeStart }) {
  return A.exclusive(() => run(shop, url, productId, dryRun ? Mode.DRY : Mode.BUY, visible, beforeStart));
}

async function run(shop, url, productId, mode, visible, beforeStart) {
  const prefix = mode === Mode.DRY ? `${shop.label}-Probelauf: ` : mode === Mode.BUY ? `${shop.label}-Auto-Kauf: ` : `${shop.label}-Warenkorb: `;
  Log.start(prefix + url);
  const w = visible ? B.watchWindow(prefix + 'startet …', A.reportOf) : B.newWindow({ show: false });
  const wc = w.webContents;
  let closed = false;
  w.on('closed', () => { closed = true; });
  let lastStatus = '';
  const status = (s) => {
    if (s !== lastStatus) { lastStatus = s; Log.add(s); }
    if (!w.isDestroyed()) w.setTitle(prefix + s);
  };
  const snapped = new Set();
  const snapshot = async () => {
    const key = wc.getTitle() + '|' + wc.getURL().split('?')[0];
    if (snapped.size >= 3 || snapped.has(key)) return;
    snapped.add(key);
    const t = await B.js(wc, REPORT_JS);
    if (t) Log.snapshot(t);
  };

  const article = articleNumber(productId);
  const preferPaypal = !!Store.setting('preferPaypal');
  let stage = 9;
  let polls = 0;
  let last = '';
  let cooldown = 0;
  let loginTries = 0;
  let stepClicks = 0;
  const clicks = {};
  let total = null;
  let declineClicks = 0;
  const exhausted = new Set();
  let declineButtons = 0;
  let paypalClicks = 0;
  let paypalState = '';
  let paypalPays = 0;
  let paymentChanges = 0;
  let paymentInfo = '';
  let paymentOptions = '';
  let lastStep = '';
  let expands = 0;
  let payWait = 0;
  let placed = false;
  const deadline = Date.now() + 180000;

  const finish = (r) => {
    Log.add(`Ergebnis: ${r.outcome} – ${r.detail}`);
    if (visible) { if (!w.isDestroyed()) w.setTitle('Fertig: ' + A.describe(r)); } else if (!w.isDestroyed()) w.destroy();
    return r;
  };
  const next = (s) => { stage = s; polls = 0; };

  /** Kasse: nächsten Schritt erkennen und ausführen. Gibt ein Ergebnis zurück, wenn fertig. */
  async function stepPoll() {
    const o = B.parse(await B.js(wc, stepJs(exhausted)));
    const s = o.s || '';
    const stepText = JSON.stringify(o).slice(0, 150);
    if (stepText !== lastStep) { lastStep = stepText; Log.add('Schritt: ' + stepText); }
    if (stage === 4) {
      let host = '';
      try { host = new URL(wc.getURL()).hostname; } catch (e) { /* leer */ }
      if (host.includes('paypal.')) {
        const v = (await B.js(wc, PAYPAL_PAY_JS)) || '';
        if (v === 'login') return { outcome: Outcome.UNCLEAR, detail: 'PayPal verlangt eine Anmeldung – einmal „Bei PayPal anmelden“; Bestellung evtl. offen, bitte prüfen', total };
        if (v.startsWith('clicked') && paypalPays < 6) {
          paypalPays++;
          polls = 0;
          status(`PayPal: „${v.replace('clicked:', '')}“ …`);
        }
        return null;
      }
      if (s === 'done') return { outcome: Outcome.ORDERED, detail: `Bestellung bei ${shop.label} bestätigt`, total };
      if (polls >= 25) return { outcome: Outcome.UNCLEAR, detail: 'Keine Bestätigungsseite erkannt', total };
      return null;
    }
    if (cooldown > 0) { cooldown--; return null; }
    if (s === 'final') {
      const t = parsePrice(o.total || '');
      const label = o.label || '';
      const qty = Number(o.qty || 0);
      if (qty > 1) {
        return { outcome: Outcome.ABORTED, detail: `Im ${shop.label}-Warenkorb liegen ${qty} Stück – aus Sicherheit nicht bestellt. Bitte den Warenkorb auf 1 Stück reduzieren.`, total: t };
      }
      const paySec = o.pay || '';
      if (/ratenzahlung|monatsrate|finanzierung|ratenkauf|in \d+ raten/i.test(paySec) && !/paypal/i.test(paySec)) {
        return {
          outcome: Outcome.ABORTED,
          detail: `Zahlungsart ist Ratenzahlung/Finanzierung („${paySec.slice(0, 70)}“) – aus Sicherheit nicht bestellt. Bitte im ${shop.label}-Konto PayPal oder eine andere Standard-Zahlungsart einstellen.` +
            (paymentOptions ? '\nGesehene Zahlungsarten: ' + paymentOptions : '\nKeine Zahlungsarten-Auswahl gesehen') +
            ` (Stift geklickt: ${paymentChanges}x, PayPal-Klicks: ${paypalClicks}x)`,
          total: t,
        };
      }
      if (mode === Mode.DRY) {
        const info = t != null ? 'Gesamtbetrag ' + money(t) : 'Gesamtbetrag nicht lesbar' + (o.hint ? ` (gefunden: ${o.hint})` : '');
        let pay = '';
        if (preferPaypal) {
          if (paypalState === 'gewählt' || /paypal/i.test(paySec)) pay = ', Zahlung: PayPal ✅';
          else if (paypalClicks > 0) pay = ', Zahlung: PayPal angeklickt, aber nicht bestätigt ⚠️';
          else pay = ', Zahlung: PayPal NICHT gewählt' + (paymentOptions ? ` (Zahlungsarten: ${paymentOptions})` : '') + (paymentInfo ? ` (Zahlungs-Abschnitt: „${paymentInfo}“)` : '');
        }
        if (paySec) pay += `\nÜbersicht: „${paySec.slice(0, 90)}“`;
        return { outcome: Outcome.DRY_RUN_OK, detail: `Bestellknopf „${label}“ gefunden, ${info}${pay} – Probelauf, nichts bestellt`, total: t };
      }
      total = t;
      placed = true;
      status('Bestellung wird abgeschickt …');
      await B.js(wc, clickTextJs(label));
      next(4);
      return null;
    }
    if (s === 'done') return { outcome: Outcome.UNCLEAR, detail: 'Bestellbestätigung erschienen, ohne dass der Bot bestellt hat' };
    if (s === 'login') {
      const mail = Store.loginEmail(shop.key);
      const pw = Store.loginPassword(shop.key);
      if (!mail || pw == null) return { outcome: Outcome.NOT_LOGGED_IN, detail: `${shop.label} verlangt eine Anmeldung – ${shop.label}-Zugang im Bot speichern` };
      if (loginTries >= 2) return { outcome: Outcome.NOT_LOGGED_IN, detail: `Anmeldung bei ${shop.label} hat nicht geklappt (Passwort prüfen)` };
      loginTries++;
      status(`Melde bei ${shop.label} an …`);
      await B.js(wc, loginJs(mail, pw));
      cooldown = 3;
    } else if (s === 'click' || s === 'needlogin') {
      const label = o.label || '';
      const n = clicks[label] || 0;
      if (n >= 3) exhausted.add(label);
      if (n < 3 && stepClicks < 15 && (s === 'click' || Store.hasLogin(shop.key))) {
        clicks[label] = n + 1;
        stepClicks++;
        status(`Klicke „${label}“ …`);
        await B.js(wc, clickTextJs(label));
        cooldown = 2;
      }
    }
    if (s === 'wait' || s === 'security') last = o.title || s;
    if (polls >= 60) {
      return {
        outcome: Outcome.FAILED,
        detail: `${shop.label}-Kasse nicht durchlaufen (hängt bei: ${last || s})` +
          (paymentOptions ? '\nGesehene Zahlungsarten: ' + paymentOptions : '') +
          (exhausted.size ? '\nOhne Wirkung geklickt: ' + [...exhausted].join(', ') : ''),
      };
    }
    return null;
  }

  /** PayPal-Auswahl (Stufe 3). Gibt 'again' (nächste Runde) oder 'step' (stepPoll ausführen) zurück. */
  async function paypalPhase() {
    if (payWait > 0) {
      const pp = (await B.js(wc, PAYPAL_SELECT_JS)) || '';
      Log.add(`Warte auf Zahlungsauswahl (${payWait}): ${pp}`);
      if (pp.startsWith('none') && payWait <= 6) await snapshot();
      if (pp.includes('expand') && expands < 3) { expands++; status('Öffne weitere Zahlungsarten …'); return 'again'; }
      if (pp.includes('clicked')) { payWait = 0; paypalClicks++; status('Wähle Zahlungsart PayPal …'); return 'again'; }
      if (pp.includes('already')) { payWait = 0; paypalState = 'gewählt'; return 'step'; }
      payWait--;
      if (payWait === 0) { paymentOptions = pp.replace(/^none\|/, ''); return 'step'; }
      return 'again';
    }
    if (paypalClicks >= 3) return 'step';
    const pp = (await B.js(wc, PAYPAL_SELECT_JS)) || '';
    Log.add('PayPal-Suche: ' + pp);
    if (pp.startsWith('none') && /zahl/i.test(wc.getTitle())) await snapshot();
    if (pp.includes('expand') && expands < 3) { expands++; status('Öffne weitere Zahlungsarten …'); return 'again'; }
    if (pp.includes('clicked')) { paypalClicks++; status('Wähle Zahlungsart PayPal …'); return 'again'; }
    const change = async (resetOptions) => {
      const v = (await B.js(wc, PAYMENT_CHANGE_JS)) || '';
      Log.add('Stift Zahlungsart: ' + v);
      if (v === 'clicked') {
        paymentChanges++;
        payWait = 8;
        if (resetOptions) paymentOptions = '';
        status('Zahlungsart ändern → PayPal …');
        return 'again';
      }
      if (v === 'paypal') paypalState = 'gewählt';
      if (v.startsWith('none:')) paymentInfo = v.slice(5);
      return 'step';
    };
    if (pp.startsWith('none|') && !paymentOptions) {
      paymentOptions = pp.slice(5);
      return paymentChanges < 2 ? change(true) : 'step';
    }
    if (pp.includes('already')) { paypalState = 'gewählt'; return 'step'; }
    if (paymentChanges < 2) return change(false);
    return 'step';
  }

  try {
    if (beforeStart) await beforeStart(wc, status);
    status('Schaue in den Warenkorb …');
    B.load(wc, shop.cartUrl);
    await B.sleep(2500);
    while (true) {
      if (closed) return finish({ outcome: stage === 4 ? Outcome.UNCLEAR : Outcome.FAILED, detail: stage === 4 ? 'Fenster nach dem Bestell-Klick geschlossen' : 'Fenster geschlossen', total });
      if (Date.now() > deadline) return finish(placed ? { outcome: Outcome.UNCLEAR, detail: 'Zeitüberschreitung nach dem Klick auf „Bestellen“', total } : { outcome: Outcome.FAILED, detail: 'Zeitüberschreitung' });
      polls++;
      if (stage === 0) {
        last = (await B.js(wc, addJs(shop))) || '';
        if (last === 'clicked') { status('„In den Warenkorb“ gedrückt …'); next(1); }
        else if (last === 'none' && polls >= 6) return finish({ outcome: Outcome.FAILED, detail: 'Kein „In den Warenkorb“-Knopf (ausverkauft?)' });
        else if (polls >= 25) {
          return finish(last === 'security'
            ? { outcome: Outcome.BLOCKED, detail: `${shop.label}: Bot-/Sicherheitsprüfung nicht bestanden` }
            : { outcome: Outcome.FAILED, detail: `${shop.label}-Produktseite nicht erkannt` });
        }
      } else if (stage === 1) {
        const d = await B.js(wc, DECLINE_JS);
        if (d === 'clicked') status('Wähle „Gerät nicht schützen“ …');
        const ok = (await B.js(wc, ADDED_JS)) === 'ok';
        if (ok && mode === Mode.CART) return finish({ outcome: Outcome.DRY_RUN_OK, detail: `In den ${shop.label}-Warenkorb gelegt` });
        if (ok || polls >= 6) { status('Öffne Warenkorb …'); B.load(wc, shop.cartUrl); next(2); }
      } else if (stage === 9) {
        const v = (await B.js(wc, cartHasJs(article))) || '';
        if (v === 'yes') {
          if (mode === Mode.CART) return finish({ outcome: Outcome.DRY_RUN_OK, detail: `Liegt schon im ${shop.label}-Warenkorb` });
          status('Artikel liegt schon im Warenkorb – nicht noch einmal hinzufügen …');
          next(2);
        } else if ((v === 'no' && polls >= 3) || polls >= 10) {
          status('Lade Produktseite …');
          B.load(wc, url);
          next(0);
        }
      } else if (stage === 2) {
        const yes = (await B.js(wc, cartHasJs(article))) === 'yes';
        if (yes && mode === Mode.CART) return finish({ outcome: Outcome.DRY_RUN_OK, detail: `Im ${shop.label}-Warenkorb` });
        if (!yes && polls >= 8 && mode === Mode.CART) return finish({ outcome: Outcome.FAILED, detail: `Artikel nicht im ${shop.label}-Warenkorb gefunden` });
        if (yes || polls >= 8) { status('Gehe zur Kasse …'); next(3); }
      } else {
        // Stufe 3 (Kasse) und 4 (bestellt, warte auf Bestätigung)
        let r = null;
        let handled = false;
        if (stage === 3) {
          const d = (await B.js(wc, DECLINE_JS)) || '';
          if (d.includes('btn') && declineButtons < 2) {
            declineButtons++;
            status('Lehne Zusatzangebot ab („Nein, danke“) …');
            handled = true;
          } else if (d.includes('clicked')) {
            declineClicks++;
            if (declineClicks > 4) return finish({ outcome: Outcome.FAILED, detail: 'Geräteschutz-Angebot ließ sich nicht abwählen – aus Sicherheit abgebrochen' });
            status('Wähle „Ich möchte mein Gerät nicht schützen“ …');
            handled = true;
          } else if (preferPaypal && (payWait > 0 || paypalClicks < 3)) {
            const ph = await paypalPhase();
            if (ph === 'again') handled = true;
          }
        }
        if (!handled) r = await stepPoll();
        if (r) return finish(r);
      }
      await B.sleep(1500);
    }
  } catch (e) {
    return finish({ outcome: placed ? Outcome.UNCLEAR : Outcome.FAILED, detail: e.message || 'Fehler', total });
  }
}

module.exports = { addToCart, buy };

'use strict';
/**
 * Selbsttest (Start mit --selftest): spielt Prüfung, Probelauf, Auto-Kauf und Warenkorb gegen
 * nachgebaute Amazon- und Shop-Seiten durch (alles lokal, kein echter Shop wird aufgerufen).
 */
const { BrowserWindow } = require('electron');
const path = require('path');
const B = require('./browser');
const Store = require('./store');
const Scraper = require('./scraper');
const A = require('./amazon');
const ShopBuyer = require('./shopbuyer');
const Checker = require('./checker');
const Shops = require('./shops');

const results = [];
function check(name, ok, info) {
  results.push({ name, ok: !!ok, info: info || '' });
  console.log((ok ? 'OK   ' : 'FAIL ') + name + (info ? ' – ' + String(info).replace(/\n/g, ' | ') : ''));
}

// ---------------- nachgebaute Seiten ----------------
const st = { amzOrdered: false, cart: false, payment: 'Ratenzahlung in 6 Monatsraten', ordered: false, protect: null, mmCart: false };

function page(title, body, extra = '') {
  return `<!doctype html><html><head><meta charset="utf-8"><title>${title}</title>${extra}</head><body>${body}</body></html>`;
}

function amazon(u) {
  const p = u.pathname;
  if (p === '/dp/B000TEST01') {
    return page('Testprodukt', `<span id="productTitle">Mr.Fox Testkonsole</span>
      <div id="corePrice_feature_div"><span class="a-price"><span class="a-offscreen">1.299,99 €</span></span></div>
      <div id="availability">Auf Lager.</div><a id="sellerProfileTriggerId">Fuchs Handel</a>
      <form id="addToCart"><input type="hidden" name="offerListingID" value="ABC%2Bdef123"></form>
      <input id="add-to-cart-button" type="button" value="In den Einkaufswagen" onclick="location.href='/cart/huc/'">
      <input id="buy-now-button" type="button" value="Jetzt kaufen" onclick="location.href='/checkout/spc'">`);
  }
  if (p === '/checkout/spc') {
    return page('Bestellung', `<table id="subtotals-marketplace-table"><tr><td class="grand-total-price">1.305,98 €</td></tr></table>
      <span id="submitOrderButtonId"><input type="submit" value="Jetzt kaufen" onclick="location.href='/gp/buy/thankyou'"></span>`);
  }
  if (p === '/gp/buy/thankyou') { st.amzOrdered = true; return page('Danke', '<div id="widget-purchaseConfirmationStatus">Bestellung aufgegeben</div>'); }
  if (p === '/gp/aws/cart/add.html') return page('Hinzufügen', '<form action="/cart/view" method="get"><input type="submit" name="add" value="Weiter"></form>');
  if (p.startsWith('/cart')) return page('Warenkorb', '<div id="sw-atc-confirmation">Hinzugefügt</div>');
  return page('Amazon', 'Startseite');
}

/** BAUR-ähnlicher Shop: Warenkorb, Produktseite, Kasse mit Zahlungsart-Karte und Zahlungsseite mit Karten. */
function shop(u) {
  const p = u.pathname;
  if (p === '/api/add') { st.cart = true; return { redirect: '/p/AKL123456789?added=1' }; }
  if (p === '/api/pay') { st.payment = u.searchParams.get('m'); return { redirect: '/kasse/zahlung-aendern' }; }
  if (p === '/api/order') { st.ordered = true; return { redirect: '/danke' }; }
  if (p.startsWith('/p/')) {
    return page('Testshop Produkt', `<h1>Fuchs-Spiel</h1><div>39,99 €</div>
      <script type="application/ld+json">{"@type":"Product","name":"Fuchs-Spiel","offers":{"price":"39.99","availability":"https://schema.org/InStock"}}</script>
      <button onclick="location.href='/api/add'">In den Warenkorb</button>
      ${u.searchParams.get('added') ? '<div role="dialog">Artikel wurde in den Warenkorb gelegt <a href="/warenkorb">Zum Warenkorb</a></div>' : ''}`);
  }
  if (p === '/warenkorb') {
    return page('Warenkorb', st.cart
      ? '<h1>Warenkorb</h1><div>Fuchs-Spiel Art.-Nr. 123456789 – Anzahl: 1 – 39,99 €</div><button onclick="location.href=\'/kasse\'">Zur Kasse</button>'
      : '<h1>Warenkorb</h1><div>Dein Warenkorb ist leer</div>');
  }
  if (p === '/kasse') {
    return page('Testshop - Prüfen & Bestellen', `<h1>Prüfen und Bestellen</h1>
      <div class="card"><div><h3>Lieferadresse</h3><svg data-testid="card-action-edit" width="20" height="20" style="cursor:pointer" onclick="location.href='/falsch'"></svg></div><p>Wie Rechnungsanschrift</p></div>
      <div class="card"><div><h3>Zahlungsart</h3><svg data-testid="card-action-edit" width="20" height="20" style="cursor:pointer" onclick="location.href='/kasse/zahlung-aendern'"></svg></div><p>${st.payment}</p></div>
      <div>Anzahl: 1</div><div><span>Gesamtsumme:</span> <span>44,94 €</span></div>
      <label><input type="checkbox"> Ihr Einkaufsschutzbrief</label>
      <button data-testid="order-submit" onclick="location.href='/api/order'">Jetzt zum genannten Preis bestellen</button>`);
  }
  if (p === '/kasse/zahlung-aendern') {
    const card = (name) => `<div class="Card"><div class="CardHeader"><div class="Headline">${name}</div>
      <button data-testid="${st.payment.startsWith(name.split(' ')[0]) ? 'active-trigger' : 'inactive-trigger'}" onclick="location.href='/api/pay?m=${encodeURIComponent(name)}'">${st.payment.startsWith(name.split(' ')[0]) ? '' : 'wählen'}</button></div></div>`;
    return page('Testshop - Bestellvorgang - Zahlung', `<h1>Zahlung</h1><p>Zahlung Schritt 4 von 5</p>
      ${card('Ratenzahlung in 6 Monatsraten')}${card('Rechnung')}${card('PayPal')}
      <button onclick="history.back()">Abbrechen</button><button onclick="location.href='/kasse'">Speichern</button>`);
  }
  if (p === '/danke') return page('Danke', '<h1>Vielen Dank für deine Bestellung</h1><p>Deine Bestellnummer: 4711</p>');
  if (p === '/falsch') return page('Falsch', '<h1>FALSCHER STIFT</h1>');
  return page('Testshop', '<h1>Start</h1>');
}

/** MediaMarkt-ähnlich: Geräteschutz-Dialog nach „In den Warenkorb“. */
function mm(u) {
  const p = u.pathname;
  if (p === '/api/add') { st.mmCart = true; return { redirect: '/de/product/_fuchs-1234567.html?protect=1' }; }
  if (p.startsWith('/de/product/')) {
    const dlg = u.searchParams.get('protect') ? `<div role="dialog" aria-modal="true"><h2>Gerät schützen?</h2>
      <label><input type="radio" name="pr" value="2y"> 2 Jahre Schutz für 49,99 €</label>
      <label><input type="radio" name="pr" value="none" onchange="window.noProt=1"> Ich möchte mein Gerät nicht schützen</label>
      <button onclick="if(window.noProt){document.body.insertAdjacentHTML('beforeend','<p>In den Warenkorb gelegt</p>');this.parentNode.remove();}">Weiter</button></div>` : '';
    return page('MM Produkt', `<h1>Fuchs-Konsole</h1><div>499,– €</div><button onclick="location.href='/api/add'">In den Warenkorb</button>${dlg}`);
  }
  if (p === '/checkout') {
    return page('Warenkorb', st.mmCart
      ? `<h1>Warenkorb</h1><div>Fuchs-Konsole 1234567</div>
        <fieldset><legend>Zahlungsart</legend><label><input type="radio" name="pay" checked> Rechnung</label><label><input type="radio" name="pay" id="pp"> PayPal</label></fieldset>
        <div>Gesamtbetrag 499,00 €</div><button>Zahlungspflichtig bestellen</button>`
      : '<h1>Warenkorb</h1><div>leer</div>');
  }
  return page('MM', '<h1>MM</h1>');
}

/** Telegram-Webansicht (t.me/s/…) und Deal-Weiterleitungen. */
const tgPosts2 = [{ id: 500, html: 'Start<br>1€<br><a href="https://s.pirat.deals/aaa">x</a>' }];
const tgPosts = [{ id: 100, html: 'Alter Deal<br>9,99€<br><a href="https://s.pirat.deals/aaa">s.pirat.deals/aaa</a>' }];
function telegram(u) {
  if (u.pathname !== '/s/MockDeals' && u.pathname !== '/s/MockDeals2') return page('Telegram', 'Gruppe ohne Vorschau');
  const list = u.pathname === '/s/MockDeals2' ? tgPosts2 : tgPosts;
  const name = u.pathname.slice(3);
  const msgs = list.map((p) => `<div class="tgme_widget_message_wrap"><div class="tgme_widget_message" data-post="${name}/${p.id}">
    <div class="tgme_widget_message_text">${p.html}<br><br><a href="https://piratedeals.de/">piratedeals.de</a> | <a href="https://t.me/addlist/x">Ordner</a></div>${p.preview || ''}
    <time datetime="2026-10-29T17:00:00+00:00"></time></div></div>`).join('');
  return page('MockDeals – Telegram', `<div class="tgme_channel_info">Mock Deals</div>${msgs}`);
}
function pirat(u) {
  if (u.pathname === '/ccc') return { redirect: 'https://www.mock-amazon.test/dp/B000TEST05' };
  if (u.pathname === '/bbb2') return { redirect: 'https://www.mock-amazon.test/dp/B000TEST03?tag=other-21' };
  if (u.pathname === '/bbb') return { redirect: 'https://www.mock-amazon.test/dp/B000TEST03?tag=pirate-21' };
  if (u.pathname === '/js') return page('Weiterleitung', "<script>setTimeout(function(){location.href='https://www.mock-amazon.test/dp/B000TEST02?tag=x'},300)</script>Weiterleitung …");
  return page('Deal', 'Kein Shop');
}

function handler(req) {
  const u = new URL(req.url);
  let r;
  if (u.hostname === 'www.payback.de') {
    r = page('PAYBACK Amazon', `<div id="cookie"><button onclick="document.getElementById('cookie').remove()">Alle akzeptieren</button></div>
      <h1>Amazon</h1><a href="https://www.amazon.de/?tag=payback-21">Jetzt shoppen</a>`);
  } else if (u.hostname === 'www.amazon.de') r = page('Amazon.de', '<h1>Amazon über Payback</h1>');
  else if (u.hostname === 'www.paypal.com') r = page('PayPal', '<form><input id="email" type="email"><button type="button" onclick="document.getElementById(\'pwbox\').style.display=\'block\'">Weiter</button><div id="pwbox" style="display:none"><input id="password" type="password"></div></form>');
  else if (u.hostname === 't.me') r = telegram(u);
  else if (u.hostname === 's.pirat.deals') r = pirat(u);
  else if (u.hostname === 'www.mock-amazon.test') r = amazon(u);
  else if (u.hostname === 'www.mock-shop.test') r = shop(u);
  else if (u.hostname === 'www.mock-mm.test') r = mm(u);
  else return new Response('blocked', { status: 404 });
  if (r && r.redirect) return new Response('', { status: 302, headers: { Location: r.redirect } });
  return new Response(r, { status: 200, headers: { 'content-type': 'text/html; charset=utf-8' } });
}

const MOCK_SHOP = Object.assign({}, Shops.byKey('baur'), {
  key: 'baur', label: 'Testshop', base: 'https://www.mock-shop.test', cartUrl: 'https://www.mock-shop.test/warenkorb', accountUrl: 'https://www.mock-shop.test/konto',
});
const MOCK_MM = Object.assign({}, Shops.byKey('mediamarkt'), {
  label: 'Test-MM', base: 'https://www.mock-mm.test', cartUrl: 'https://www.mock-mm.test/checkout', accountUrl: 'https://www.mock-mm.test/konto',
});

async function run() {
  const ses = B.ses();
  ses.protocol.handle('https', handler);
  Store.setSettings({ domain: 'mock-amazon.test', webhook: '', autoCart: false, autoBuy: false, preferPaypal: true, watchBuy: false });
  try {
    // 1) Amazon auslesen
    const r = await Scraper.fetchAmazon(ses, 'mock-amazon.test', 'B000TEST01');
    check('Amazon: Preis gelesen', r.price === 1299.99, r.price);
    check('Amazon: OfferListingId gelesen', r.offerId === 'ABC+def123', r.offerId);
    check('Amazon: verfügbar + Verkäufer', r.available === true && r.seller === 'Fuchs Handel', r.seller);

    // 2) Prüfung mit Alarm
    Store.add({ asin: 'B000TEST01', name: 'B000TEST01', target: 1300 });
    await Checker.checkAll();
    const p = Store.get('B000TEST01');
    check('Prüfung: Name übernommen', p.name === 'Mr.Fox Testkonsole', p.name);
    check('Prüfung: Alarm Zielpreis', Store.alerts().some((a) => /Zielpreis erreicht/.test(a.text)), (Store.alerts()[0] || {}).text);

    // 3) Amazon-Probelauf
    let res = await A.buy('B000TEST01', { dryRun: true, visible: false });
    check('Amazon-Probelauf', res.outcome === 'DRY_RUN_OK' && res.total === 1305.98 && !st.amzOrdered, A.describe(res));

    // 4) Amazon-Auto-Kauf
    res = await A.buy('B000TEST01', { dryRun: false, visible: false });
    check('Amazon-Auto-Kauf', res.outcome === 'ORDERED' && st.amzOrdered, A.describe(res));

    // 5) Amazon-Warenkorb per OfferListingId
    const c = await A.addToCart('B000TEST01', 'ABC+def123');
    check('Amazon-Warenkorb (OfferListingId)', c.result === 'OK', c.detail);

    // 6) Shop (BAUR-Art): Warenkorb
    st.cart = false;
    let s = await ShopBuyer.addToCart(MOCK_SHOP, 'https://www.mock-shop.test/p/AKL123456789', 'BAUR-AKL123456789');
    check('Shop: in den Warenkorb', s.result === 'OK' && st.cart, s.detail);

    // 7) Shop-Probelauf: PayPal über Stift + Karte „wählen“
    res = await ShopBuyer.buy(MOCK_SHOP, 'https://www.mock-shop.test/p/AKL123456789', 'BAUR-AKL123456789', { dryRun: true, visible: false });
    check('Shop-Probelauf mit PayPal', res.outcome === 'DRY_RUN_OK' && /PayPal ✅/.test(res.detail) && st.payment === 'PayPal' && !st.ordered, A.describe(res) + ' | Zahlung: ' + st.payment);

    // 8) Ratenzahlungs-Sperre (PayPal-Wahl aus)
    st.payment = 'Ratenzahlung in 6 Monatsraten';
    Store.setSettings({ preferPaypal: false });
    res = await ShopBuyer.buy(MOCK_SHOP, 'https://www.mock-shop.test/p/AKL123456789', 'BAUR-AKL123456789', { dryRun: false, visible: false });
    check('Shop: Ratenzahlung wird nicht bestellt', res.outcome === 'ABORTED' && !st.ordered, A.describe(res));
    Store.setSettings({ preferPaypal: true });

    // 9) Shop-Auto-Kauf
    res = await ShopBuyer.buy(MOCK_SHOP, 'https://www.mock-shop.test/p/AKL123456789', 'BAUR-AKL123456789', { dryRun: false, visible: false });
    check('Shop-Auto-Kauf', res.outcome === 'ORDERED' && st.ordered && st.payment === 'PayPal', A.describe(res));

    // 10) MediaMarkt-Art: Geräteschutz ablehnen, PayPal-Radio
    res = await ShopBuyer.buy(MOCK_MM, 'https://www.mock-mm.test/de/product/_fuchs-1234567.html', 'MM-1234567', { dryRun: true, visible: false });
    check('MM-Probelauf (Geräteschutz + PayPal)', res.outcome === 'DRY_RUN_OK' && /PayPal ✅/.test(res.detail), A.describe(res));

    // 11) Shop-Produktprüfung
    const sp = await Shops.fetchProduct(MOCK_SHOP, 'https://www.mock-shop.test/p/AKL123456789');
    check('Shop: Produkt auslesen', sp.price === 39.99 && sp.available === true, JSON.stringify(sp));

    // 12) Links erkennen
    const l = Shops.parse(Shops.byKey('baur'), 'https://www.baur.de/p/AKLBB2161998740?sku=6345532920-0-2161998740');
    check('BAUR-Link erkannt', l && l.id === 'BAUR-AKLBB2161998740-6345532920-0-2161998740' && l.url.includes('?sku='), JSON.stringify(l));
    const l2 = Shops.parse(Shops.byKey('mediamarkt'), 'https://www.mediamarkt.de/de/product/_sony-ps5-1234567.html');
    check('MediaMarkt-Link erkannt', l2 && l2.id === 'MM-1234567', JSON.stringify(l2));

    // 13) Export/Import
    const ex = Store.exportJson();
    Store.update('B000TEST01', (x) => { x.autoBuy = true; });
    const ex2 = Store.exportJson();
    const im = Store.importJson(ex2, false);
    check('Export/Import (Auto-Kauf aus)', ex.products.length === 1 && im.count === 1 && im.disarmed === 1 && Store.get('B000TEST01').autoBuy === false, JSON.stringify(im));

    // 14) Telegram-Deals
    const Telegram = require('./telegram');
    Store.setSettings({ tgEnabled: true, tgChannels: 'MockDeals', tgKeywords: 'konsole', tgExclude: 'hülle', tgMaxPrice: null, tgAction: 'watch' });
    let tr = await Telegram.poll((d) => Checker.telegramDeal(d));
    check('Telegram: erster Abruf merkt nur den Stand', tr.found === 0 && Store.setting('tgLast_mockdeals') === 100 && !tr.errors.length, JSON.stringify(tr));
    tgPosts.push({ id: 101, html: 'Lego Technic Set<br>💰 19,99€<br><a href="https://s.pirat.deals/aaa">s.pirat.deals/aaa</a>' });
    tgPosts.push({ id: 102, html: '🔥 Mr.Fox Konsole Pro<br>💰 1.199,00€ statt 1.499€<br>Gutschein: FOX10<br><a href="https://s.pirat.deals/bbb">s.pirat.deals/bbb</a>' });
    tgPosts.push({ id: 103, html: 'Konsole Mini<br>49,99 €<br><a href="https://s.pirat.deals/js">s.pirat.deals/js</a>' });
    tgPosts.push({ id: 104, html: 'Konsole Hülle<br>5,99 €<br><a href="https://s.pirat.deals/aaa">s.pirat.deals/aaa</a>' });
    tgPosts.push({ id: 105, html: '24,99<br>Blitzangebot ⚡<br><a href="https://s.pirat.deals/ccc">https://s.pirat.deals/ccc</a>',
      preview: '<a class="tgme_widget_message_link_preview" href="https://s.pirat.deals/ccc"><div class="link_preview_site_name">Amazon.de</div><div class="link_preview_title">Amazon.de: Fuchs Gamepad Konsole Edition</div></a>' });
    tr = await Telegram.poll((d) => Checker.telegramDeal(d));
    const p3 = Store.get('B000TEST03');
    const p2 = Store.get('B000TEST02');
    check('Telegram: 3 Treffer (Stichwort, Ausschlusswort, Vorschau)', tr.found === 3, JSON.stringify(tr));
    const p5 = Store.get('B000TEST05');
    check('Telegram: Name aus Link-Vorschau, Preis ohne €', p5 && p5.name === 'Fuchs Gamepad Konsole Edition' && p5.target === 24.99, JSON.stringify(p5));
    check('Telegram: Weiterleitung (302) aufgelöst + auf Liste', p3 && p3.name === 'Mr.Fox Konsole Pro' && p3.target === 1199, JSON.stringify(p3));
    check('Telegram: JS-Weiterleitung aufgelöst + auf Liste', p2 && p2.target === 49.99, JSON.stringify(p2));
    const al3 = Store.alerts().find((a) => /Konsole Pro/.test(a.title));
    check('Telegram: Link ohne Partner-Tag + Payback', al3 && al3.links.page === 'https://www.mock-amazon.test/dp/B000TEST03' && al3.links.payback === 'amazon', JSON.stringify(al3 && al3.links));
    const cl = Telegram.cleanLink('https://www.otto.de/p/fuchs-123?utm_source=pirate&ref=deal&farbe=rot#x', null);
    check('Telegram: Tracking-Parameter entfernt', cl === 'https://www.otto.de/p/fuchs-123?farbe=rot', cl);
    const WebWin = require('./webwin');
    const pw = WebWin.openViaPayback('amazon', 'https://www.mock-amazon.test/dp/B000TEST01');
    let pu = '';
    for (let i = 0; i < 30 && !pu.includes('mock-amazon.test/dp/'); i++) { await B.sleep(1000); pu = pw.isDestroyed() ? '' : pw.webContents.getURL(); }
    check('Payback: erst Payback-Seite, dann Produkt', pu === 'https://www.mock-amazon.test/dp/B000TEST01', pu + ' | ' + (pw.isDestroyed() ? '' : pw.getTitle()));
    if (!pw.isDestroyed()) pw.destroy();
    check('Telegram: Alarm erzeugt', Store.alerts().some((a) => /📣 Telegram @MockDeals/.test(a.text) && /FOX10/.test(a.text)), (Store.alerts()[0] || {}).text);
    // gleicher Deal in einem zweiten Kanal (weitergeleitet) → kein zweiter Alarm
    Store.setSettings({ tgChannels: 'MockDeals, MockDeals2' });
    await Telegram.poll((d) => Checker.telegramDeal(d));
    const before = Store.alerts().length;
    tgPosts2.push({ id: 501, html: '🔥 Mr.Fox Konsole Pro<br>💰 1.199,00€<br><a href="https://s.pirat.deals/bbb">s.pirat.deals/bbb</a>' });
    tgPosts2.push({ id: 502, html: 'Konsole Max<br>59,99€<br><a href="https://s.pirat.deals/bbb2">s.pirat.deals/bbb2</a>' });
    tr = await Telegram.poll((d) => Checker.telegramDeal(d));
    check('Telegram: zweiter Kanal, doppelter Deal nur einmal', tr.found === 0 && Store.alerts().length === before, JSON.stringify(tr) + ' Alarme ' + before + '→' + Store.alerts().length);
    Store.setSettings({ tgChannels: 'MockDeals' });
    const tt = await Telegram.test(5);
    check('Telegram: Test-Ansicht', tt.length === 5 && tt.filter((x) => x.ok).length === 3, tt.map((x) => x.id + ':' + x.ok + ':' + x.why).join(', '));
    const tbad = await Telegram.fetchChannel('PirateDealsChat').then(() => 'kein Fehler', (e) => e.message);
    check('Telegram: Gruppe wird erkannt', /kein öffentlicher Kanal/.test(tbad), tbad);
    Store.setSettings({ tgEnabled: false });

    // 15) Konten: PayPal-Anmeldung wird nur ausgefüllt (E-Mail, nach „Weiter“ auch Passwort)
    Store.saveLogin('paypal', 'fuchs@example.org', 'Geheim123!');
    const ww = require('./webwin').open('https://www.paypal.com/signin', { forceInApp: true });
    await B.sleep(3500);
    const em = await B.js(ww.webContents, "document.getElementById('email').value");
    await B.js(ww.webContents, "document.querySelector('button').click()");
    await B.sleep(4500);
    const pv = await B.js(ww.webContents, "document.getElementById('password').value");
    check('Konten: PayPal-Anmeldung ausgefüllt', em === 'fuchs@example.org' && pv === 'Geheim123!', em + ' / ' + (pv ? '***' : 'leer'));
    ww.destroy();
    check('Konten: Passwort verschlüsselt gespeichert', !JSON.stringify(Store.load().secrets).includes('Geheim123!') && Store.loginPassword('paypal') === 'Geheim123!', '');

    // 16) Oberfläche lädt ohne Fehler
    const errors = [];
    const w = new BrowserWindow({ show: false, webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true } });
    w.webContents.on('console-message', (_e, level, msg) => { if (level >= 3) errors.push(msg); });
    await w.loadFile(path.join(__dirname, 'ui', 'index.html'));
    await B.sleep(1500);
    const n = await w.webContents.executeJavaScript("document.querySelectorAll('.prod').length+'|'+document.getElementById('sigver').textContent");
    if (process.env.MRFOX_SHOT) {
      w.setSize(1180, 900);
      Store.setSettings({ theme: 'light' });
      w.webContents.send('changed');
      await B.sleep(800);
      for (const [tab, file] of [['products', 'shot-products.png'], ['logins', 'shot-logins.png'], ['settings', 'shot-settings.png']]) {
        await w.webContents.executeJavaScript(`document.querySelector('nav button[data-tab="${tab}"]').click()`);
        await B.sleep(400);
        const img = await w.webContents.capturePage();
        require('fs').writeFileSync(path.join(process.env.MRFOX_SHOT, file), img.toPNG());
      }
    }
    check('Oberfläche lädt', !errors.length && /^[1-9]\d*\|/.test(n), errors.join(' ; ') || n);
    w.destroy();
  } catch (e) {
    check('Ausnahme', false, e.stack || e.message);
  }
  const failed = results.filter((r) => !r.ok);
  console.log(`\nSELFTEST: ${results.length - failed.length}/${results.length} OK`);
  if (process.env.GITHUB_ACTIONS) {
    for (const f of failed) console.log(`::error::Selbsttest: ${f.name} – ${f.info.replace(/\n/g, ' | ').slice(0, 400)}`);
    console.log(`::notice::Selbsttest: ${results.length - failed.length}/${results.length} OK`);
  }
  return failed.length ? 1 : 0;
}

module.exports = { run };

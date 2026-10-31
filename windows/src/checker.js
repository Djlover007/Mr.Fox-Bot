'use strict';
/**
 * Prüfungen und Alarme (wie Checker in CheckWorker.kt der Android-App): Preis/Verfügbarkeit,
 * Zielpreis, Preisfall, OfferListingId, Kategorien, Auto-Kauf, automatischer Warenkorb,
 * Windows-Benachrichtigung, Discord und Alarm-Verlauf.
 */
const { Notification, net } = require('electron');
const B = require('./browser');
const Store = require('./store');
const Shops = require('./shops');
const Scraper = require('./scraper');
const A = require('./amazon');
const ShopBuyer = require('./shopbuyer');
const Payback = require('./payback');
const WebWin = require('./webwin');

const { money } = A;

function webShop(p) { return Shops.byKey(p.shop); }

/** Prüft alle (passenden) Produkte nacheinander. Gibt true zurück, wenn ein Shop blockiert hat. */
async function checkAll(which = () => true) {
  const domain = Store.setting('domain');
  const dropPct = Number(Store.setting('drop')) || 0;
  const list = Store.products().filter((p) => which(p) && Store.isActive(p));
  if (!list.length) return false;
  const problems = [];
  const blocked = new Set();
  for (let i = 0; i < list.length; i++) {
    const p = list[i];
    if (blocked.has(p.shop)) continue;
    try {
      await checkProduct(domain, dropPct, p);
    } catch (e) {
      if (e instanceof Scraper.BlockedError) {
        blocked.add(p.shop);
        const ws = webShop(p);
        problems.push(ws ? `${ws.label}: Bot-/Sicherheitsprüfung nicht bestanden` : 'Amazon: blockiert (Captcha)');
      } else {
        problems.push(`Fehler bei ${p.name}: ${e.message}`);
      }
    }
    if (i < list.length - 1) await B.sleep(3000 + Math.random() * 5000);
  }
  Store.setStatus(problems.length ? problems.join(' · ') : 'OK');
  return blocked.size > 0;
}

async function checkProduct(domain, dropPct, p) {
  const ws = webShop(p);
  if (ws && Store.isCategory(p)) return handleCategory(ws, p, await Shops.fetchCategory(ws, p.url));
  if (ws) {
    await handle(domain, dropPct, p.asin, await Shops.fetchProduct(ws, p.url));
    if (ws.key === 'mueller' && p.storeCheck && Store.setting('muellerPlz')) await checkStores(p);
    return;
  }
  return handle(domain, dropPct, p.asin, await Scraper.fetchAmazon(B.ses(), domain, p.asin));
}

/**
 * Müller-Filialen: Verfügbarkeit per PLZ prüfen und melden, wenn eine Filiale den Artikel (wieder) hat.
 * Gibt die Filialliste zurück.
 */
async function checkStores(p, opts) {
  const plz = String(Store.setting('muellerPlz') || '').trim();
  if (!plz) throw new Error('Keine PLZ für Müller-Filialen eingetragen (Einstellungen)');
  const stores = await require('./stores').check(p.url, plz, opts);
  let prev = {};
  try { prev = JSON.parse(p.storeState || '{}'); } catch (e) { prev = {}; }
  const now = {};
  const fresh = [];
  for (const s of stores) {
    const k = s.name + '|' + s.addr;
    now[k] = { a: s.a, st: s.status };
    if (s.a && !(prev[k] && prev[k].a)) fresh.push(s);
  }
  Store.update(p.asin, (x) => { x.storeState = JSON.stringify(now); x.storeCheckAt = Date.now(); });
  if (fresh.length) {
    const text = `🏪 Jetzt in ${fresh.length === 1 ? 'einer Müller-Filiale' : fresh.length + ' Müller-Filialen'} verfügbar (PLZ ${plz}):\n` +
      fresh.map((s) => `✅ ${s.name}${s.addr ? ', ' + s.addr : ''} – ${s.status}`).join('\n');
    notifyUser('🏪 ' + p.name, text, { page: p.url, cart: p.url, checkout: Shops.byKey('mueller').cartUrl });
    if (Store.setting('openOnAlert')) WebWin.open(p.url);
    await sendDiscord(Store.setting('webhook'), `🟠 **${p.name}**\n${text}\nProdukt: <${p.url}>`);
  }
  return stores;
}

/** Einzelnes Produkt sofort prüfen. Gibt null bei Erfolg zurück, sonst eine Fehlermeldung. */
async function checkOne(id) {
  try {
    const p = Store.get(id);
    if (!p) throw new Error('Produkt fehlt');
    await checkProduct(Store.setting('domain'), Number(Store.setting('drop')) || 0, p);
    return null;
  } catch (e) {
    if (e instanceof Scraper.BlockedError) return `Blockiert (${e.message}) – später „Jetzt prüfen“`;
    return e.message || 'Fehler';
  }
}

function offerInfo(p) {
  const ws = webShop(p);
  if (ws) {
    let s = `Shop: ${ws.label}`;
    if (p.lastPrice != null) s += '\nPreis: ' + money(p.lastPrice);
    if (p.target != null) s += '\nZielpreis: ' + money(p.target);
    s += '\nVerfügbar: ' + (p.available ? 'ja' : 'nein');
    s += `\nProdukt: <${p.url}>\nWarenkorb: <${ws.cartUrl}>`;
    return s;
  }
  const offer = Store.cartOffer(p);
  let s = 'ASIN: ' + p.asin;
  s += '\nOfferListingId: ' + (offer ? '`' + offer + '`' : 'nicht gefunden');
  if (p.pinnedOffer) s += ' 📌 festgehalten';
  if (p.seller) s += '\nVerkauf: ' + p.seller;
  if (p.lastPrice != null) s += '\nPreis: ' + money(p.lastPrice);
  if (p.target != null) s += '\nZielpreis: ' + money(p.target);
  s += `\nProdukt: <https://www.${Store.setting('domain')}/dp/${p.asin}>`;
  s += `\nIn den Warenkorb: <${A.cartUrl(p.asin, offer)}>`;
  return s;
}

async function sendDiscord(hook, text) {
  if (!hook || !String(hook).trim()) return false;
  try {
    const r = await net.fetch(String(hook).trim(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'User-Agent': 'MrFoxBot/5' },
      body: JSON.stringify({ content: String(text).slice(0, 1990) }),
    });
    return r.status >= 200 && r.status < 300;
  } catch (e) {
    return false;
  }
}

async function sendAllOffers() {
  const hook = Store.setting('webhook');
  const items = Store.products();
  if (!hook || !items.length) return false;
  let ok = true;
  let buf = '🦊 **Mr.Fox Bot – OfferListingIds aller Produkte**\n';
  for (const p of items) {
    const block = `\n**${p.name}**\n${offerInfo(p)}\n`;
    if (buf.length + block.length > 1900) { ok = (await sendDiscord(hook, buf)) && ok; buf = ''; }
    buf += block;
  }
  if (buf.trim()) ok = (await sendDiscord(hook, buf)) && ok;
  return ok;
}

/** Windows-Benachrichtigung + Eintrag im Alarm-Verlauf. Klick öffnet die Seite. */
function notifyUser(title, text, links) {
  Store.addAlert({ title, text, links });
  try {
    if (Notification.isSupported()) {
      const n = new Notification({ title, body: text.slice(0, 250), icon: B.ICON });
      n.on('click', () => {
        if (links && links.page && links.payback) WebWin.openViaPayback(links.payback, links.page);
        else if (links && links.page) WebWin.open(links.page);
      });
      n.show();
    }
  } catch (e) { /* egal */ }
}

/** Payback-Weiterleitung vor dem Kauf (wenn eingeschaltet), Ergebnis landet in note. */
function paybackStarter(shopKey, note) {
  if (!Store.setting('paybackAutoBuy') || !Store.payback(shopKey) || !Payback.supports(shopKey)) return null;
  return async (wc, status) => {
    const r = await Payback.activate(wc, shopKey, status);
    note.text = r.ok ? '💳 Über Payback gekauft (°Punkte)' : '💳 Ohne Payback-Punkte: ' + r.detail;
  };
}

/** Payback vor dem automatischen Warenkorb (Amazon, wenn Payback für Amazon angehakt ist). */
function paybackCartStarter(shopKey, note) {
  if (!Store.payback(shopKey) || !Payback.supports(shopKey)) return null;
  return async (wc, status) => {
    const r = await Payback.activate(wc, shopKey, status);
    note.text = r.ok ? '💳 Über Payback (°Punkte beim Kauf)' : '💳 Ohne Payback: ' + r.detail;
  };
}

async function handle(domain, dropPct, id, r) {
  const autoBuyOn = !!Store.setting('autoBuy');
  let offerEvent = null;
  const upd = Store.update(id, (p) => {
    const prevOffer = p.offerId;
    if (r.offerId == null) offerEvent = null;
    else if (prevOffer == null) offerEvent = '🆕 OfferListingId gefunden';
    else if (r.offerId !== prevOffer) offerEvent = '🔄 Neues Angebot in der Kaufbox';
    const prevPrice = p.lastPrice;
    const prevAvail = p.available;
    const first = p.lastCheck === 0;
    p.lastCheck = Date.now();
    const nowAvail = r.available;
    if (nowAvail != null) p.available = nowAvail;
    if (r.offerId) p.offerId = r.offerId;
    if (r.seller) p.seller = r.seller;
    if (p.name === p.asin && r.title && r.title !== p.asin) p.name = String(r.title).slice(0, 80);

    let text = null;
    const price = r.price;
    if (price != null) {
      p.lastPrice = price;
      p.minPrice = p.minPrice == null ? price : Math.min(p.minPrice, price);
      const target = p.target;
      if (target != null && price <= target && p.lastAlertPrice !== price) text = `Zielpreis erreicht: ${money(price)} (Ziel ${money(target)})`;
      else if (dropPct > 0 && prevPrice != null && price <= prevPrice * (1 - dropPct / 100) && p.lastAlertPrice !== price) text = `Preis um mind. ${dropPct} % gefallen: ${money(prevPrice)} → ${money(price)}`;
      else if (!first && !prevAvail && nowAvail === true) text = `Wieder verfügbar: ${money(price)}`;
      if (text) p.lastAlertPrice = price;
    }
    const t = p.target;
    const priceOk = autoBuyOn && p.autoBuy && nowAvail === true && price != null && t != null && price <= t;
    const offerMismatch = priceOk && p.pinnedOffer != null && r.offerId !== p.pinnedOffer;
    const buyNow = priceOk && !offerMismatch;
    if (buyNow) {
      p.autoBuy = false; // nur EIN Versuch
      p.lastBuyAttempt = Date.now();
      p.lastBuyResult = 'läuft …';
      if (!text) { text = `Zielpreis erreicht: ${money(price)} (Ziel ${money(t)})`; p.lastAlertPrice = price; }
    }
    return { text, buyNow, offerMismatch };
  });
  if (!upd) return;
  const { text: alert, buyNow, offerMismatch } = upd;
  const hook = Store.setting('webhook');

  if (offerEvent && !alert) {
    const p = Store.get(id);
    if (p) await sendDiscord(hook, `${offerEvent}\n**${p.name}**\n${offerInfo(p)}`);
  }
  if (!alert) return;

  const product = Store.get(id);
  const name = product ? product.name : r.title;
  const ws = product ? webShop(product) : null;
  const url = product ? Store.pageUrl(product) : `https://www.${domain}/dp/${id}`;
  const cart = ws ? url : A.cartUrl(id, product ? Store.cartOffer(product) : null);
  let message = alert;
  if (offerMismatch) message += '\n⏸ Auto-Kauf wartet: in der Kaufbox liegt nicht das festgehaltene Angebot' + (r.seller ? ` (gerade: ${r.seller})` : '');
  let openUrl = Store.setting('openOnAlert') ? url : null;

  if (buyNow) {
    const visible = !!Store.setting('watchBuy');
    const note = { text: '' };
    const shopKey = product ? product.shop : 'amazon';
    const beforeStart = paybackStarter(shopKey, note);
    let res;
    try {
      res = ws
        ? await ShopBuyer.buy(ws, url, id, { dryRun: false, visible, beforeStart })
        : await A.buy(id, { dryRun: false, visible, beforeStart });
    } catch (e) {
      res = { outcome: A.Outcome.FAILED, detail: e.message || 'Fehler' };
    }
    const line = A.describe(res) + (note.text ? '\n' + note.text : '');
    Store.update(id, (p) => { p.lastBuyResult = line; });
    message += '\n' + line;
    if (openUrl) {
      if (visible) openUrl = null;
      else if (res.outcome === A.Outcome.ORDERED || res.outcome === A.Outcome.UNCLEAR) openUrl = ws ? ws.accountUrl : A.ordersUrl();
    }
  } else if (Store.setting('autoCart')) {
    let res;
    try {
      var cartNote = { text: '' };
      res = ws ? await ShopBuyer.addToCart(ws, url, id)
        : await A.addToCart(id, product ? Store.cartOffer(product) : null, { beforeStart: paybackCartStarter('amazon', cartNote) });
    } catch (e) {
      res = { result: 'FAILED', detail: e.message || 'Fehler' };
    }
    if (res.result === 'OK') Store.update(id, (p) => { p.lastCartAdd = Date.now(); });
    message += res.result === 'OK' ? '\n✅ Automatisch in den Warenkorb gelegt'
      : res.result === 'NOT_LOGGED_IN' ? '\n⚠️ Nicht angemeldet: ' + res.detail
        : `\n⚠️ Automatisch in den Warenkorb legen hat nicht geklappt (${res.detail}) – nutze den Link`;
    if (cartNote && cartNote.text) message += '\n' + cartNote.text;
  }

  const checkout = ws ? ws.cartUrl : A.checkoutUrl();
  if (openUrl) WebWin.open(openUrl);
  notifyUser(name, message, { page: url, cart, checkout });
  const p2 = Store.get(id);
  await sendDiscord(hook, `**${name}**\n${message}\n${p2 ? offerInfo(p2) : `Produkt: ${url}\nIn den Warenkorb: ${cart}`}\nZur Kasse: <${checkout}>`);
}

function handleCategory(shop, cat, items) {
  if (!items.length) throw new Error('Keine Produkte in der Kategorie gefunden');
  const lines = [];
  Store.update(cat.asin, (p) => {
    const first = p.lastCheck === 0;
    let old = {};
    try { old = JSON.parse(p.catState || '{}'); } catch (e) { old = {}; }
    const now = {};
    const target = p.target;
    for (const it of items) {
      const prev = old[it.url];
      const prevAvail = prev ? !!prev.a : false;
      const prevPrice = prev && prev.p != null ? Number(prev.p) : null;
      const priceTxt = it.price != null ? money(it.price) : 'Preis ?';
      if (!first) {
        if (!prev && it.available) lines.push(`🆕 Neu & online verfügbar: ${it.name} – ${priceTxt}\n<${it.url}>`);
        else if (!prev) lines.push(`🆕 Neu (noch ausverkauft): ${it.name} – ${priceTxt}`);
        else if (!prevAvail && it.available) lines.push(`✅ Wieder online verfügbar: ${it.name} – ${priceTxt}\n<${it.url}>`);
        else if (target != null && it.available && it.price != null && it.price <= target && (prevPrice == null || prevPrice > target)) lines.push(`💶 Unter ${money(target)}: ${it.name} – ${priceTxt}\n<${it.url}>`);
      }
      now[it.url] = { n: it.name, p: it.price, a: it.available };
    }
    p.catState = JSON.stringify(now);
    p.lastCheck = Date.now();
    p.available = items.some((x) => x.available);
  });
  if (!lines.length) return;
  const text = lines.join('\n');
  if (Store.setting('openOnAlert')) WebWin.open(cat.url);
  notifyUser(cat.name, text, { page: cat.url, cart: cat.url, checkout: shop.cartUrl });
  const hook = Store.setting('webhook');
  (async () => {
    let buf = `${shop.emoji} **${cat.name}** (${shop.label}-Kategorie)\n`;
    for (const l of lines) {
      if (buf.length + l.length > 1900) { await sendDiscord(hook, buf); buf = ''; }
      buf += l + '\n';
    }
    await sendDiscord(hook, buf);
  })();
}

/**
 * Treffer aus einem Telegram-Kanal: Alarm + je nach Einstellung auf die Liste setzen oder in den Warenkorb.
 * deal: {channel, post, title, price, link, resolved, target, action}
 */
async function telegramDeal(d) {
  const t = d.target;
  const lines = [];
  let addedId = null;
  if (d.action !== 'alert') {
    if (!t) {
      lines.push('ℹ️ Link führt nicht zu Amazon/Müller/MediaMarkt/BAUR – nur Alarm');
    } else {
      const id = t.shop === 'amazon' ? t.asin : t.id;
      if (!Store.get(id)) {
        const maxPrice = Store.setting('tgMaxPrice');
        const target = maxPrice != null && maxPrice !== '' ? Number(maxPrice) : d.price;
        Store.add(t.shop === 'amazon'
          ? { asin: id, name: (d.name || id).slice(0, 80), target, lastAlertPrice: d.price }
          : { asin: id, name: (d.name || id).slice(0, 80), target, lastAlertPrice: d.price, shop: t.shop, url: t.url });
        addedId = id;
        lines.push('👀 Auf die Überwachungsliste gesetzt' + (target != null ? ` (Zielpreis ${money(target)})` : ''));
      } else {
        lines.push('👀 Steht schon auf der Überwachungsliste');
      }
      if (d.action === 'cart') {
        const ws = t.shop === 'amazon' ? null : Shops.byKey(t.shop);
        let res;
        try {
          const pbNote = { text: '' };
          res = ws ? await ShopBuyer.addToCart(ws, t.url, id) : await A.addToCart(id, null, { beforeStart: Store.setting('tgPayback') !== false ? paybackCartStarter('amazon', pbNote) : null });
          if (pbNote.text) lines.push(pbNote.text);
        } catch (e) {
          res = { result: 'FAILED', detail: e.message || 'Fehler' };
        }
        if (res.result === 'OK') Store.update(id, (p) => { p.lastCartAdd = Date.now(); });
        lines.push(res.result === 'OK' ? '✅ Automatisch in den Warenkorb gelegt'
          : res.result === 'NOT_LOGGED_IN' ? '⚠️ Nicht angemeldet: ' + res.detail
            : '⚠️ Warenkorb hat nicht geklappt: ' + res.detail);
      }
    }
  }
  // saubere Produktadresse ohne Partner-Tag; Payback-Partner (Amazon, MediaMarkt, BAUR) über Payback öffnen
  const url = require('./telegram').cleanLink(d.resolved || d.link, t);
  const pb = Store.setting('tgPayback') !== false && t && Payback.supports(t.shop) ? t.shop : null;
  if (pb) lines.push('💳 Öffnen über Payback (°Punkte)');
  // Fußzeilen des Kanals (nur Links/Domains) weglassen
  const body = String(d.post.text || '').split('\n')
    .filter((l) => l.replace(/https?:\/\/\S+|[\w.äöüß-]+\.(deals|de|com|me|org|net|to|eu|ly)\b(\/\S*)?/gi, '').replace(/[|•·\-–\s]/g, '').length >= 3)
    .slice(0, 8).join('\n').slice(0, 500);
  const msg = `📣 Telegram @${d.channel}${d.price != null ? ' · ' + money(d.price) : ''}\n${body}` + (lines.length ? '\n' + lines.join('\n') : '');
  const ws = t && t.shop !== 'amazon' ? Shops.byKey(t.shop) : null;
  const cart = t && t.shop === 'amazon' ? A.cartUrl(t.asin, null) : url;
  notifyUser('📣 ' + d.title, msg, { page: url, cart, checkout: ws ? ws.cartUrl : A.checkoutUrl(), payback: pb });
  if (Store.setting('openOnAlert')) { if (pb) WebWin.openViaPayback(pb, url); else WebWin.open(url); }
  await sendDiscord(Store.setting('webhook'), `**${d.title}**\n${msg}\n<${url}>` + (pb ? `\n💳 Payback: <${Payback.PARTNERS[pb].page}>` : ''));
  if (addedId) checkOne(addedId).catch(() => {});
}

module.exports = { checkStores, checkAll, checkOne, offerInfo, sendDiscord, sendAllOffers, notifyUser, paybackStarter, telegramDeal };

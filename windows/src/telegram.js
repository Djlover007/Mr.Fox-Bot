'use strict';
/**
 * Telegram-Deals: liest öffentliche Telegram-Kanäle über die Webansicht (https://t.me/s/<kanal>,
 * ohne Anmeldung), filtert neue Beiträge nach Stichwörtern/Höchstpreis, löst den Deal-Link
 * (z. B. s.pirat.deals/…, amzn.to/…) zur Shopseite auf und reagiert je nach Einstellung:
 *   'alert' = nur Alarm, 'watch' = zusätzlich auf die Überwachungsliste, 'cart' = zusätzlich in den Warenkorb.
 * Gruppen (Chats) und private Kanäle haben keine Webansicht und gehen so nicht.
 */
const B = require('./browser');
const Store = require('./store');
const Scraper = require('./scraper');
const Shops = require('./shops');

/** Links in jedem Beitrag, die nicht zum Deal gehören (Werbung des Kanals, Telegram, WhatsApp …). */
const FOOTER_HOSTS = /(^|\.)(t\.me|telegram\.me|telegram\.org|whatsapp\.com|wa\.me|piratedeals\.de|pirate-deals\.com|instagram\.com|facebook\.com|tiktok\.com|youtube\.com|x\.com|twitter\.com)$/i;

const PARSE_JS = String.raw`(function(html){
var d=new DOMParser().parseFromString(html,'text/html');
var out=[];var ms=d.querySelectorAll('.tgme_widget_message[data-post]');
for(var i=0;i<ms.length;i++){var m=ms[i];var post=m.getAttribute('data-post')||'';var id=parseInt(post.split('/').pop(),10);
 if(!id)continue;
 var te=m.querySelector('.tgme_widget_message_text');var text='';var links=[];
 if(te){var c=te.cloneNode(true);var brs=c.querySelectorAll('br');for(var b=0;b<brs.length;b++)brs[b].replaceWith('\n');
  text=(c.textContent||'').replace(/[ \t]+/g,' ').replace(/\n{3,}/g,'\n\n').trim();
  var as=te.querySelectorAll('a[href]');for(var a=0;a<as.length;a++){var h=as[a].getAttribute('href');if(/^https?:/i.test(h))links.push(h);}}
 var bt=m.querySelectorAll('.tgme_widget_message_inline_button[href],a.tgme_widget_message_link_preview[href]');
 for(var k=0;k<bt.length;k++){var h2=bt[k].getAttribute('href');if(/^https?:/i.test(h2))links.push(h2);}
 var tm=m.querySelector('time[datetime]');
 out.push({id:id,post:post,text:text,links:links,time:tm?tm.getAttribute('datetime'):''});}
return JSON.stringify(out);})`;

function channelName(s) {
  return String(s || '').trim().replace(/^@/, '').replace(/^https?:\/\/(www\.)?t\.me\/(s\/)?/i, '').split(/[/?#]/)[0];
}

function channels() {
  return String(Store.setting('tgChannels') || '').split(/[\s,;]+/).map(channelName).filter(Boolean);
}

async function fetchChannel(name) {
  const r = await B.ses().fetch(`https://t.me/s/${encodeURIComponent(name)}`, { headers: { 'Accept-Language': 'de-DE,de;q=0.9' } });
  if (r.status !== 200) throw new Error(`Telegram @${name}: HTTP ${r.status}`);
  const html = await r.text();
  const posts = B.parse(await B.js(Scraper.parserWc(), PARSE_JS + '(' + JSON.stringify(html) + ')', 20000));
  if (!Array.isArray(posts)) throw new Error(`Telegram @${name}: Seite nicht lesbar`);
  if (!posts.length && !/tgme_channel_info/.test(html)) {
    throw new Error(`@${name} ist kein öffentlicher Kanal mit Webansicht (Gruppe oder privat?)`);
  }
  return posts.sort((a, b) => a.id - b.id);
}

/** Erster €-Betrag im Text (bei Deal-Posts meist der Angebotspreis). */
function dealPrice(text) {
  const m = /(\d{1,3}(?:\.\d{3})*(?:,\d{1,2})?|\d+(?:[.,]\d{1,2})?)\s?€|€\s?(\d{1,3}(?:\.\d{3})*(?:,\d{1,2})?)/.exec(text || '');
  if (!m) return null;
  return Scraper.parsePrice(m[1] || m[2]);
}

function title(text) {
  const lines = String(text || '').split('\n').map((l) => l.replace(/[^\p{L}\p{N}\s.,:;!?()&%€+\-–/»«"'|]/gu, '').trim()).filter((l) => l.length > 3);
  return (lines[0] || 'Telegram-Deal').slice(0, 100);
}

function words(s) { return String(s || '').toLowerCase().split(',').map((x) => x.trim()).filter(Boolean); }

/** Passt der Beitrag zum Filter? Gibt {ok, why} zurück. */
function matches(post, cfg) {
  const low = (post.text || '').toLowerCase();
  const ex = words(cfg.exclude).find((w) => low.includes(w));
  if (ex) return { ok: false, why: `Ausschlusswort „${ex}“` };
  const kws = words(cfg.keywords);
  if (kws.length) {
    const hit = kws.find((k) => k.split(/\s+/).every((w) => low.includes(w)));
    if (!hit) return { ok: false, why: 'kein Stichwort' };
  }
  const price = dealPrice(post.text);
  if (cfg.maxPrice != null && price != null && price > cfg.maxPrice) return { ok: false, why: `Preis ${price} € über Höchstpreis` };
  if (!dealLink(post)) return { ok: false, why: 'kein Deal-Link' };
  return { ok: true, why: kws.length ? 'Stichwort passt' : 'alle Deals' };
}

function dealLink(post) {
  for (const l of post.links || []) {
    try {
      if (!FOOTER_HOSTS.test(new URL(l).hostname)) return l;
    } catch (e) { /* ungültig */ }
  }
  return null;
}

/** Ist das eine Produktseite, mit der der Bot etwas anfangen kann? */
function target(url) {
  if (!url) return null;
  const asin = /amazon\.[a-z.]+\//i.test(url) ? Scraper.extractAsin(url) : null;
  if (asin) return { shop: 'amazon', asin, url };
  const ws = Shops.detect(url);
  if (ws) {
    const link = Shops.parse(ws, url);
    if (link && link.kind === 'product') return { shop: ws.key, id: link.id, url: link.url };
  }
  return null;
}

const LINK_IN_BODY = /https?:\/\/(?:www\.)?(?:amazon\.de\/[^"'\s<>]*?(?:dp|gp\/product)\/[A-Z0-9]{10}[^"'\s<>]*|amzn\.(?:to|eu)\/[^"'\s<>]+|mediamarkt\.de\/de\/product\/[^"'\s<>]+|baur\.de\/p\/[^"'\s<>]+|mueller\.de\/p\/[^"'\s<>]+)/i;

/** Deal-Link (Weiterleitung) bis zur Shopseite auflösen. Gibt die End-Adresse zurück (oder den Link selbst). */
async function resolve(url, depth = 0) {
  if (target(url) || depth > 2) return url;
  try {
    const r = await B.ses().fetch(url, { redirect: 'follow', headers: { 'Accept-Language': 'de-DE' } });
    if (target(r.url)) return r.url;
    const body = await r.text();
    const m = LINK_IN_BODY.exec(body.replace(/&amp;/g, '&'));
    if (m) return resolve(m[0], depth + 1);
  } catch (e) { /* weiter mit Browser */ }
  // Weiterleitung per JavaScript: im unsichtbaren Browser öffnen und warten
  const w = B.newWindow({ show: false });
  try {
    B.load(w.webContents, url);
    for (let i = 0; i < 15; i++) {
      await B.sleep(1000);
      const u = w.webContents.getURL();
      if (target(u)) return u;
    }
    return w.webContents.getURL() || url;
  } finally {
    if (!w.isDestroyed()) w.destroy();
  }
}

/** Zuletzt gemeldete Deals (Link bzw. Produkt) – 24 Stunden lang nicht doppelt melden. */
function seenList() {
  const now = Date.now();
  return (Store.setting('tgSeen') || []).filter((x) => x && now - x.t < 24 * 3600 * 1000);
}
function seen(key) { return seenList().some((x) => x.k === key); }
function remember(key) {
  const l = seenList();
  l.push({ k: key, t: Date.now() });
  Store.setSettings({ tgSeen: l.slice(-400) });
}

function cfg() {
  const s = Store.settings();
  return { keywords: s.tgKeywords || '', exclude: s.tgExclude || '', maxPrice: s.tgMaxPrice == null || s.tgMaxPrice === '' ? null : Number(s.tgMaxPrice), action: s.tgAction || 'alert' };
}

/**
 * Neue Beiträge aller Kanäle prüfen. Beim ersten Mal wird nur der Stand gemerkt (keine Alarmflut).
 * handlers: {onDeal: async (deal) => void}
 */
async function poll(onDeal) {
  const c = cfg();
  const errors = [];
  let found = 0;
  for (const ch of channels()) {
    try {
      const posts = await fetchChannel(ch);
      const key = 'tgLast_' + ch.toLowerCase();
      const last = Number(Store.setting(key) || 0);
      const maxId = posts.length ? posts[posts.length - 1].id : last;
      if (!last) { Store.setSettings({ [key]: maxId }); continue; }
      const fresh = posts.filter((p) => p.id > last).slice(-20);
      Store.setSettings({ [key]: Math.max(last, maxId) });
      for (const p of fresh) {
        const mm = matches(p, c);
        if (!mm.ok) continue;
        const link = dealLink(p);
        // Derselbe Deal in mehreren Kanälen (z. B. weitergeleitet): nur einmal melden
        if (seen('l:' + link)) continue;
        const resolved = await resolve(link);
        const tg = target(resolved);
        const tkey = tg ? 't:' + (tg.asin || tg.id) : null;
        if (tkey && seen(tkey)) { remember('l:' + link); continue; }
        remember('l:' + link);
        if (tkey) remember(tkey);
        found++;
        await onDeal({ channel: ch, post: p, title: title(p.text), price: dealPrice(p.text), link, resolved, target: tg, action: c.action });
      }
    } catch (e) {
      errors.push(e.message);
    }
  }
  return { found, errors };
}

/** Test: letzte Beiträge des ersten Kanals mit Filter-Ergebnis (löst die Links der passenden auf). */
async function test(n = 5) {
  const c = cfg();
  const out = [];
  for (const ch of channels()) {
    const posts = (await fetchChannel(ch)).slice(-n);
    for (const p of posts) {
      const mm = matches(p, c);
      let resolved = null;
      if (mm.ok) resolved = await resolve(dealLink(p));
      out.push({ channel: ch, id: p.id, title: title(p.text), price: dealPrice(p.text), ok: mm.ok, why: mm.why, resolved, target: resolved ? target(resolved) : null });
    }
  }
  return out;
}

module.exports = { poll, test, channels, channelName, matches, dealPrice, title, resolve, target, fetchChannel, PARSE_JS };

'use strict';
/**
 * Web-Shops (Müller, MediaMarkt, BAUR): Link-Erkennung und Auslesen im (unsichtbaren) Bot-Browser.
 * Gleiche Regeln und Skripte wie in der Android-App (Shops.kt).
 */
const B = require('./browser');
const { parsePrice, BlockedError } = require('./scraper');

const SHOPS = [
  {
    key: 'mueller', label: 'Müller', emoji: '🟠',
    base: 'https://www.mueller.de',
    cartUrl: 'https://www.mueller.de/cart/',
    accountUrl: 'https://www.mueller.de/my-account/',
    idPrefix: 'MUE',
    productRe: /mueller\.de\/p\/([^\s?#]*?-((?:[A-Za-z]{3})?\d{6,8}))\/?/,
    productUrl: (x) => `https://www.mueller.de/p/${x}/`,
    categoryRe: /mueller\.de\/(c\/[^\s?#]+?)\/?(?:[?#\s]|$)/,
    categoryUrl: (x) => `https://www.mueller.de/${x}/`,
    tileLinkSelector: 'a[href*="/p/"]',
    addButtonRe: '^in den warenkorb$',
    soldWords: ['ausverkauft', 'nicht verfügbar'],
    availWords: ['online verfügbar', 'lieferbar'],
    keepParams: [],
  },
  {
    key: 'mediamarkt', label: 'MediaMarkt', emoji: '🔴',
    base: 'https://www.mediamarkt.de',
    cartUrl: 'https://www.mediamarkt.de/checkout',
    accountUrl: 'https://www.mediamarkt.de/de/myaccount',
    idPrefix: 'MM',
    productRe: /mediamarkt\.de\/de\/product\/([^\s?#]*?-(\d{6,9}))\.html/,
    productUrl: (x) => `https://www.mediamarkt.de/de/product/${x}.html`,
    categoryRe: /mediamarkt\.de\/de\/category\/([^\s?#]+?)\.html/,
    categoryUrl: (x) => `https://www.mediamarkt.de/de/category/${x}.html`,
    tileLinkSelector: 'a[href*="/product/"]',
    addButtonRe: '^(in den warenkorb|in den warenkorb legen|zum warenkorb hinzufügen)$',
    soldWords: ['nicht verfügbar', 'nicht lieferbar', 'ausverkauft', 'nicht möglich', 'online nicht'],
    availWords: ['lieferung', 'lieferbar', 'online verfügbar', 'versand', 'abholung'],
    keepParams: [],
  },
  {
    key: 'baur', label: 'BAUR', emoji: '🟢',
    base: 'https://www.baur.de',
    cartUrl: 'https://www.baur.de/warenkorb',
    accountUrl: 'https://www.baur.de/mein-konto/uebersicht',
    idPrefix: 'BAUR',
    productRe: /baur\.de\/p\/((?:[^\s?#/]*?-)?([A-Za-z]{0,8}\d{6,}))\/?(?:[?#\s]|$)/,
    productUrl: (x) => `https://www.baur.de/p/${x}`,
    categoryRe: /baur\.de\/((?!p\/|warenkorb|mein-konto|merkzettel|service-hilfe)[a-z0-9-]+(?:\/[a-z0-9-]+)*)\/?(?:[?#\s]|$)/,
    categoryUrl: (x) => `https://www.baur.de/${x}/`,
    tileLinkSelector: 'a[href*="/p/"]',
    addButtonRe: '^(in den warenkorb|in den warenkorb legen)$',
    soldWords: ['ausverkauft', 'nicht lieferbar', 'nicht verfügbar', 'derzeit nicht', 'bald wieder'],
    availWords: ['lieferbar', 'sofort', 'werktag', 'auf lager', 'verfügbar'],
    keepParams: ['sku'],
  },
];

function byKey(key) { return SHOPS.find((s) => s.key === key) || null; }

function escapeRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

/** Link → {kind, id, url} oder null. */
function parse(shop, input) {
  const s = String(input || '').trim();
  let m = shop.productRe.exec(s);
  if (m) {
    const kept = [];
    for (const k of shop.keepParams) {
      const mm = new RegExp('[?&]' + escapeRe(k) + '=([^&#\\s]+)').exec(s);
      if (mm) kept.push([k, mm[1]]);
    }
    const id = `${shop.idPrefix}-${m[2].toUpperCase()}` + kept.map(([, v]) => '-' + v).join('');
    const url = shop.productUrl(m[1]) + (kept.length ? '?' + kept.map(([k, v]) => `${k}=${v}`).join('&') : '');
    return { kind: 'product', id, url };
  }
  m = shop.categoryRe.exec(s);
  if (m) {
    const p = m[1].replace(/\/+$/, '');
    return { kind: 'category', id: `${shop.idPrefix}C-${p}`, url: shop.categoryUrl(p) };
  }
  return null;
}

function detect(input) {
  const s = String(input || '');
  return SHOPS.find((sh) => sh.productRe.test(s) || sh.categoryRe.test(s)) || null;
}

/** Artikelnummer aus „MUE-PPN3115411“ → „3115411“ (für die Warenkorb-Prüfung). */
function articleNumber(id) {
  const m = /\d{6,9}/.exec(id);
  return m ? m[0] : id;
}

/** Gemeinsame JS-Hilfsfunktionen (wie COMMON_JS der Android-App). */
const COMMON_JS = String.raw`
function txt(e){return ((e.innerText||e.value||e.getAttribute('aria-label')||'')+'').replace(/\s+/g,' ').trim();}
function vis(e){if(e.disabled||e.getAttribute('aria-disabled')==='true')return false;var s=getComputedStyle(e);
 if(s.display==='none'||s.visibility==='hidden')return false;return e.offsetParent!==null||s.position==='fixed';}
function btn(re,ex){var els=document.querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]');
 for(var i=0;i<els.length;i++){var e=els[i];var t=txt(e);if(!t||t.length>60)continue;
  if(re.test(t)&&!(ex&&ex.test(t))&&vis(e))return e;}return null;}
function security(){var b=(document.body&&document.body.innerText)||'';if(b.length>=4000)return false;
 var w=['Sicherheitsprüfung','Just a moment','Einen Moment','Checking your browser','Verify you are human',
  'Bestätigen Sie, dass Sie ein Mensch sind','Überprüfung, ob','Access Denied','Zugriff verweigert'];
 for(var i=0;i<w.length;i++)if(b.indexOf(w[i])>=0)return true;return false;}
var PRICE_RE=/(\d{1,3}(?:\.\d{3})*,(?:\d{2}|[–-]+))/;
`;

function productJs(shop) {
  return '(function(){' + COMMON_JS + 'var ADD=new RegExp(' + JSON.stringify(shop.addButtonRe) + ",'i');" + String.raw`
if(security())return JSON.stringify({s:'security'});
var h1=document.querySelector('h1');if(!h1)return JSON.stringify({s:'wait'});
var r={s:'ok',n:txt(h1)};
var lds=document.querySelectorAll('script[type="application/ld+json"]');
for(var i=0;i<lds.length;i++){try{var j=JSON.parse(lds[i].textContent);var arr=Array.isArray(j)?j:(j['@graph']||[j]);
 for(var k=0;k<arr.length;k++){var o=arr[k];if(o&&(o['@type']==='Product'||(Array.isArray(o['@type'])&&o['@type'].indexOf('Product')>=0))){
  if(o.name)r.n=o.name;var of=o.offers;if(Array.isArray(of))of=of[0];
  if(of){if(of.price!=null)r.p=String(of.price);else if(of.lowPrice!=null)r.p=String(of.lowPrice);
   if(of.availability)r.av=String(of.availability);}}}}catch(e){}}
if(!r.p){var c=h1;for(var up=0;up<7&&c;up++){var t=c.innerText||'';if(t.indexOf('€')>=0){var m=t.match(PRICE_RE);if(m){r.p=m[1];break;}}c=c.parentElement;}}
r.cart=!!btn(ADD);
return JSON.stringify(r);})()`;
}

function categoryJs(shop) {
  return '(function(){' + COMMON_JS +
    'var LINK=' + JSON.stringify(shop.tileLinkSelector) + ';' +
    'var SOLD=' + JSON.stringify(shop.soldWords) + ';var AV=' + JSON.stringify(shop.availWords) + ';' + String.raw`
if(security())return JSON.stringify({s:'security'});
function has(low,ws){for(var i=0;i<ws.length;i++)if(low.indexOf(ws[i])>=0)return true;return false;}
function clean(h){return h.split('?')[0].split('#')[0];}
var out=[];var seen={};var as=document.querySelectorAll(LINK);
for(var i=0;i<as.length;i++){var a=as[i];var href=clean(a.href);if(seen[href])continue;
 var t=a,tile=null;for(var up=0;up<8&&t;up++){var it=t.innerText||'';if(it.indexOf('€')>=0&&PRICE_RE.test(it)){tile=t;break;}t=t.parentElement;}
 if(!tile)continue;
 var hs={};var ls=tile.querySelectorAll(LINK);for(var x=0;x<ls.length;x++)hs[clean(ls[x].href)]=1;
 if(Object.keys(hs).length>1)continue;
 seen[href]=1;
 var tx=(tile.innerText||'').replace(/\s+/g,' ');var low=tx.toLowerCase();var m=tx.match(PRICE_RE);
 var name='';var lines=(a.innerText||'').split('\n');
 for(var l=0;l<lines.length;l++){var s=lines[l].trim();if(s&&s.indexOf('€')<0&&s.length>3){name=s;break;}}
 if(!name){var imgs=tile.querySelectorAll('img[alt]');for(var g=0;g<imgs.length;g++){var al=imgs[g].alt||'';
  if(al&&al.indexOf('Wunschliste')<0&&al.length>3){name=al;break;}}}
 if(!name)name=a.getAttribute('title')||a.getAttribute('aria-label')||'';
 var sold=has(low,SOLD);
 out.push({u:href,n:name.slice(0,100),p:m?m[1]:'',a:!sold&&has(low,AV)});}
if(!out.length)return JSON.stringify({s:'wait'});
return JSON.stringify({s:'ok',items:out});})()`;
}

/**
 * Lädt url unsichtbar und führt js aus, bis es {s:'ok'} meldet. Wirft BlockedError bei
 * Bot-/Sicherheitsprüfung, sonst Error bei Zeitüberschreitung.
 */
async function extract(shop, url, code, timeoutMs = 45000) {
  const w = B.newWindow({ show: false });
  let last = '';
  try {
    B.load(w.webContents, url);
    await B.sleep(2500);
    const end = Date.now() + timeoutMs;
    while (Date.now() < end) {
      const o = B.parse(await B.js(w.webContents, code));
      last = o.s || '';
      if (last === 'ok') return o;
      await B.sleep(1500);
    }
  } finally {
    if (!w.isDestroyed()) w.destroy();
  }
  if (last === 'security') throw new BlockedError(`${shop.label}: Bot-/Sicherheitsprüfung nicht bestanden`);
  throw new Error(`${shop.label}-Seite nicht erkannt (Aufbau geändert?)`);
}

async function fetchProduct(shop, url) {
  const o = await extract(shop, url, productJs(shop));
  const av = String(o.av || '');
  let available;
  if (/InStock|LimitedAvailability/i.test(av)) available = true;
  else if (/OutOfStock|SoldOut|Discontinued/i.test(av)) available = false;
  else available = !!o.cart;
  let price = null;
  if (o.p) price = o.p.includes(',') ? parsePrice(o.p) : (isNaN(Number(o.p)) ? parsePrice(o.p) : Number(o.p));
  return { title: o.n || url, price, available, offerId: null, seller: shop.label };
}

async function fetchCategory(shop, url) {
  const o = await extract(shop, url, categoryJs(shop));
  return (o.items || []).map((it) => ({ url: it.u, name: it.n || it.u, price: parsePrice(it.p || ''), available: !!it.a }));
}

module.exports = { SHOPS, byKey, parse, detect, articleNumber, COMMON_JS, fetchProduct, fetchCategory, BlockedError };

'use strict';
/**
 * Amazon-Produktseite abrufen und auslesen (Preis, Verfügbarkeit, OfferListingId, Verkäufer).
 * Gleiche Regeln wie Scraper.kt der Android-App. Abruf über die Bot-Sitzung (Chromium-Netzwerk).
 */
const B = require('./browser');

class BlockedError extends Error {}

const OFFER_SELECTORS = [
  '#addToCart input[name=offerListingID]',
  "#addToCart input[name='items[0.base][offerListingId]']",
  '#buybox input[name=offerListingID]',
  "#buybox input[name='items[0.base][offerListingId]']",
  'input#offerListingID',
];

const SELLER_SELECTORS = [
  '#sellerProfileTriggerId',
  '#merchantInfoFeature_feature_div .offer-display-feature-text-message',
  '#merchant-info a',
  '#merchant-info',
];

const PRICE_SELECTORS = [
  '#corePrice_feature_div span.a-price span.a-offscreen',
  '#corePriceDisplay_desktop_feature_div span.priceToPay span.a-offscreen',
  '#corePriceDisplay_desktop_feature_div span.a-price span.a-offscreen',
  '#apex_desktop span.priceToPay span.a-offscreen',
  '#apex_desktop span.a-price span.a-offscreen',
  '#buybox span.a-price span.a-offscreen',
  '#priceblock_ourprice',
  '#priceblock_dealprice',
  '#price_inside_buybox',
];

/** "1.299,99 €" -> 1299.99, "$1,299.99" -> 1299.99 */
function parsePrice(text) {
  const cleaned = String(text || '').replace(/[\s ]/g, '');
  const m = /\d[\d.,]*/.exec(cleaned);
  if (!m) return null;
  let s = m[0].replace(/[.,]+$/, '');
  const hasComma = s.includes(',');
  const hasDot = s.includes('.');
  if (hasComma && hasDot) {
    s = s.lastIndexOf(',') > s.lastIndexOf('.') ? s.replace(/\./g, '').replace(',', '.') : s.replace(/,/g, '');
  } else if (hasComma) {
    const tail = s.slice(s.lastIndexOf(',') + 1);
    s = tail.length >= 1 && tail.length <= 2
      ? s.slice(0, s.lastIndexOf(',')).replace(/,/g, '') + '.' + tail
      : s.replace(/,/g, '');
  } else if (hasDot) {
    const tail = s.slice(s.lastIndexOf('.') + 1);
    if (tail.length === 3) s = s.replace(/\./g, '');
  }
  const v = Number(s);
  return isNaN(v) ? null : v;
}

function normOffer(id) {
  const s = String(id || '').trim();
  if (!s) return null;
  try { return decodeURIComponent(s.replace(/\+/g, '%2B')); } catch (e) { return s; }
}

function extractOfferId(input) {
  const s = String(input || '').trim();
  if (!s) return null;
  const m = /offerlistingid(?:\.1)?=([^&\s#]+)/i.exec(s);
  if (m) return normOffer(m[1]);
  return s.includes('/') || s.includes(' ') ? null : normOffer(s);
}

function extractAsin(input) {
  const s = String(input || '').trim();
  const m = /\/(?:dp|gp\/product)\/([A-Za-z0-9]{10})/.exec(s);
  if (m) return m[1].toUpperCase();
  if (/^[A-Za-z0-9]{10}$/.test(s)) return s.toUpperCase();
  return null;
}

/** Abruf über die Sitzung des Bots (ses = Electron-Session). */
async function fetchAmazon(ses, domain, asin) {
  const url = `https://www.${domain}/dp/${asin}`;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 25000);
  let resp;
  try {
    resp = await ses.fetch(url, {
      headers: { 'Accept-Language': 'de-DE,de;q=0.9,en;q=0.7' },
      credentials: 'include',
      signal: ctrl.signal,
    });
  } finally {
    clearTimeout(timer);
  }
  const code = resp.status;
  const html = await resp.text();
  if (code === 429 || code === 503 || html.includes('api-services-support@amazon.com') || html.includes('validateCaptcha')) {
    throw new BlockedError(`Blockiert (HTTP ${code})`);
  }
  if (code === 404) throw new Error('Produkt nicht gefunden (404)');
  if (code < 200 || code > 299) throw new Error(`HTTP ${code}`);
  return await parseAmazon(html, asin);
}

/** HTML im unsichtbaren Parser-Fenster auslesen (DOMParser: keine Skripte, keine Bilder). */
let parserWin = null;

function parserWc() {
  if (!parserWin || parserWin.isDestroyed()) {
    parserWin = B.newWindow({ show: false });
    parserWin.loadURL('about:blank').catch(() => {});
  }
  return parserWin.webContents;
}

const PARSE_JS = String.raw`(function(html,asin,PRICE,OFFER,SELLER){
var d=new DOMParser().parseFromString(html,'text/html');
function q(s){try{return d.querySelector(s);}catch(e){return null;}}
function tx(e){return e?(e.textContent||'').replace(/\s+/g,' ').trim():'';}
var r={title:tx(q('#productTitle'))||asin,priceText:'',avail:tx(q('#availability')).toLowerCase(),
 cart:!!q("#add-to-cart-button, input[name='submit.add-to-cart'], #buy-now-button"),offer:'',seller:''};
for(var i=0;i<PRICE.length;i++){var t=tx(q(PRICE[i]));if(t&&/\d/.test(t)){r.priceText=t;break;}}
for(var j=0;j<OFFER.length;j++){var e=q(OFFER[j]);if(e&&e.getAttribute('value')){r.offer=e.getAttribute('value');break;}}
for(var k=0;k<SELLER.length;k++){var s=tx(q(SELLER[k]));if(s){r.seller=s.slice(0,60);break;}}
return JSON.stringify(r);})`;

async function parseAmazon(html, asin) {
  const wc = parserWc();
  const code = PARSE_JS + '(' + [html, asin, PRICE_SELECTORS, OFFER_SELECTORS, SELLER_SELECTORS].map((x) => JSON.stringify(x)).join(',') + ')';
  const o = B.parse(await B.js(wc, code, 20000));
  if (!o.title) throw new Error('Amazon-Seite konnte nicht gelesen werden');
  const price = o.priceText ? parsePrice(o.priceText) : null;
  const unavailable = ['nicht verfügbar', 'currently unavailable', 'nicht auf lager'].some((w) => (o.avail || '').includes(w));
  let available = null;
  if (unavailable) available = false;
  else if (price != null || o.cart) available = true;
  return { title: o.title, price, available, offerId: normOffer(o.offer), seller: o.seller || null };
}

module.exports = { BlockedError, parsePrice, normOffer, extractOfferId, extractAsin, fetchAmazon, parseAmazon, parserWc };

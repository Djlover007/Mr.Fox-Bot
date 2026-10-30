'use strict';
/**
 * Datenspeicher des Windows-Bots: Produkte, Einstellungen, Alarm-Verlauf (JSON-Datei im
 * Benutzerordner) und Passwörter (verschlüsselt mit Windows-DPAPI über Electron safeStorage).
 * Das Produktformat ist dasselbe wie in der Android-App (Export/Import).
 */
const fs = require('fs');
const path = require('path');
const { app, safeStorage } = require('electron');

const SHOP_AMAZON = 'amazon';
const KIND_PRODUCT = 'product';
const KIND_CATEGORY = 'category';

const DEFAULTS = {
  domain: 'amazon.de',
  interval: 60, // Müller/MediaMarkt/BAUR in Minuten (min. 5)
  amazonInterval: 2, // Amazon in Minuten (min. 1)
  drop: 10,
  webhook: '',
  autoCart: false,
  autoBuy: false,
  openOnAlert: false,
  watchBuy: true,
  externalBrowser: false,
  preferPaypal: true,
  paybackAutoBuy: false,
  botWanted: false,
  autostart: false,
  keepAwake: true,
  theme: 'dark',
  autoUpdate: true,
  status: '–',
  lastRun_amazon: 0,
  lastRun_shops: 0,
  // Telegram-Deals
  tgEnabled: false,
  tgChannels: 'PirateDeals, PirateDealsAngebote',
  tgKeywords: '',
  tgExclude: '',
  tgMaxPrice: null,
  tgAction: 'alert',
  tgInterval: 1,
  tgPayback: true,
  lastRun_tg: 0,
};

let file = null;
let data = null;
const listeners = [];

function load() {
  if (data) return data;
  file = path.join(app.getPath('userData'), 'mrfox-data.json');
  try {
    data = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    data = {};
  }
  data.products = Array.isArray(data.products) ? data.products : [];
  data.settings = Object.assign({}, DEFAULTS, data.settings || {});
  data.secrets = data.secrets || {};
  data.alerts = Array.isArray(data.alerts) ? data.alerts : [];
  // einmalig: PirateDealsAngebote zum alten Standard-Kanal dazunehmen
  if (!data.settings.tgMig1) {
    if (String(data.settings.tgChannels).trim().toLowerCase() === 'piratedeals') data.settings.tgChannels = 'PirateDeals, PirateDealsAngebote';
    data.settings.tgMig1 = true;
  }
  return data;
}

function save() {
  load();
  const tmp = file + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data, null, 1), 'utf8');
  fs.renameSync(tmp, file);
  for (const l of listeners) {
    try { l(); } catch (e) { /* egal */ }
  }
}

function onChange(fn) { listeners.push(fn); }

// ---------- Produkte ----------
function normalize(p) {
  return {
    asin: String(p.asin),
    name: p.name || String(p.asin),
    target: p.target == null ? null : Number(p.target),
    lastPrice: p.lastPrice == null ? null : Number(p.lastPrice),
    minPrice: p.minPrice == null ? null : Number(p.minPrice),
    available: p.available !== false,
    lastCheck: Number(p.lastCheck || 0),
    lastAlertPrice: p.lastAlertPrice == null ? null : Number(p.lastAlertPrice),
    lastCartAdd: Number(p.lastCartAdd || 0),
    autoBuy: !!p.autoBuy,
    lastBuyAttempt: Number(p.lastBuyAttempt || 0),
    lastBuyResult: p.lastBuyResult || '',
    offerId: p.offerId || null,
    seller: p.seller || '',
    pinnedOffer: p.pinnedOffer || null,
    pinnedSeller: p.pinnedSeller || '',
    shop: p.shop || SHOP_AMAZON,
    kind: p.kind || KIND_PRODUCT,
    url: p.url || '',
    catState: typeof p.catState === 'string' ? p.catState : JSON.stringify(p.catState || {}),
    paused: !!p.paused,
  };
}

function products() { return load().products.map((p) => Object.assign({}, p)); }

function get(id) {
  const p = load().products.find((x) => x.asin === id);
  return p ? Object.assign({}, p) : null;
}

function add(p) {
  load();
  data.products = data.products.filter((x) => x.asin !== p.asin);
  data.products.push(normalize(p));
  save();
}

function remove(id) {
  load();
  data.products = data.products.filter((x) => x.asin !== id);
  save();
}

/** Ändert ein Produkt; gibt das Ergebnis von fn zurück (undefined, wenn es fehlt). */
function update(id, fn) {
  load();
  const p = data.products.find((x) => x.asin === id);
  if (!p) return undefined;
  const r = fn(p);
  save();
  return r;
}

function isWebShop(p) { return p.shop !== SHOP_AMAZON; }
function isCategory(p) { return p.kind === KIND_CATEGORY; }
function cartOffer(p) { return isWebShop(p) ? null : (p.pinnedOffer || p.offerId || null); }
function pageUrl(p) { return isWebShop(p) ? p.url : `https://www.${setting('domain')}/dp/${p.asin}`; }

// ---------- Einstellungen ----------
function setting(k) { return load().settings[k]; }

function settings() { return Object.assign({}, load().settings); }

function setSettings(obj) {
  load();
  Object.assign(data.settings, obj);
  save();
}

function shopEnabled(key) { return load().settings['shopOn_' + key] !== false; }
function setShopEnabled(key, on) { setSettings({ ['shopOn_' + key]: !!on }); }
function payback(key) { return !!load().settings['payback_' + key]; }
function setPayback(key, on) { setSettings({ ['payback_' + key]: !!on }); }
function isActive(p) { return !p.paused && shopEnabled(p.shop); }

function setStatus(text) {
  const d = new Date();
  const t = d.toLocaleString('de-DE', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  setSettings({ status: `${text} (${t})` });
}

// ---------- Zugänge (Passwort verschlüsselt) ----------
function enc(s) {
  if (safeStorage.isEncryptionAvailable()) return 'dp:' + safeStorage.encryptString(s).toString('base64');
  return 'b64:' + Buffer.from(s, 'utf8').toString('base64');
}

function dec(s) {
  if (!s) return null;
  try {
    if (s.startsWith('dp:')) return safeStorage.decryptString(Buffer.from(s.slice(3), 'base64'));
    if (s.startsWith('b64:')) return Buffer.from(s.slice(4), 'base64').toString('utf8');
  } catch (e) { /* unlesbar */ }
  return null;
}

/** key: 'amazon' oder Shop-Schlüssel (mueller, mediamarkt, baur). */
function loginEmail(key) { return load().secrets[key + '_mail'] || ''; }
function hasLogin(key) { return !!load().secrets[key + '_pw']; }
function loginPassword(key) { return dec(load().secrets[key + '_pw']); }

function saveLogin(key, email, password) {
  load();
  data.secrets[key + '_mail'] = email;
  if (password) data.secrets[key + '_pw'] = enc(password);
  save();
}

function clearLogin(key) {
  load();
  delete data.secrets[key + '_mail'];
  delete data.secrets[key + '_pw'];
  save();
}

// ---------- Alarm-Verlauf ----------
function addAlert(a) {
  load();
  data.alerts.unshift(Object.assign({ time: Date.now() }, a));
  data.alerts = data.alerts.slice(0, 60);
  save();
}

function alerts() { return load().alerts.slice(); }

function clearAlerts() {
  load();
  data.alerts = [];
  save();
}

// ---------- Export / Import (gleiches Format wie die Android-App) ----------
const SETTING_KEYS = ['domain', 'interval', 'amazonInterval', 'drop', 'webhook', 'autoCart', 'preferPaypal', 'tgEnabled', 'tgChannels', 'tgKeywords', 'tgExclude', 'tgMaxPrice', 'tgAction', 'tgInterval', 'tgPayback'];

function exportJson() {
  load();
  const s = {};
  for (const k of SETTING_KEYS) s[k] = data.settings[k];
  return {
    app: 'Mr.Fox Bot',
    format: 1,
    exported: Date.now(),
    from: 'windows',
    products: data.products,
    settings: s,
  };
}

/**
 * Übernimmt Produkte aus einer Export-Datei. Vorhandene Produkte mit gleicher ID werden ersetzt.
 * Auto-Kauf wird bei importierten Produkten IMMER ausgeschaltet – sonst könnten Handy und PC
 * denselben Artikel doppelt bestellen.
 */
function importJson(obj, withSettings) {
  if (!obj || !Array.isArray(obj.products)) throw new Error('Keine Mr.Fox-Bot-Exportdatei');
  load();
  let n = 0;
  let disarmed = 0;
  for (const raw of obj.products) {
    if (!raw || !raw.asin) continue;
    const p = normalize(raw);
    if (p.autoBuy) disarmed++;
    p.autoBuy = false;
    data.products = data.products.filter((x) => x.asin !== p.asin);
    data.products.push(p);
    n++;
  }
  if (withSettings && obj.settings) {
    for (const k of SETTING_KEYS) if (obj.settings[k] !== undefined) data.settings[k] = obj.settings[k];
  }
  save();
  return { count: n, disarmed };
}

module.exports = {
  SHOP_AMAZON, KIND_PRODUCT, KIND_CATEGORY,
  load, save, onChange,
  products, get, add, remove, update, normalize,
  isWebShop, isCategory, cartOffer, pageUrl,
  setting, settings, setSettings, shopEnabled, setShopEnabled, payback, setPayback, isActive, setStatus,
  loginEmail, hasLogin, loginPassword, saveLogin, clearLogin,
  addAlert, alerts, clearAlerts,
  exportJson, importJson,
};

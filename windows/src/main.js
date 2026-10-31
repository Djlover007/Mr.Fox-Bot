'use strict';
/**
 * Mr.Fox Bot für Windows – Hauptprozess: Fenster, Taskleisten-Symbol, Dauerbetrieb und
 * die Schnittstelle für die Oberfläche.
 */
const { app, BrowserWindow, Tray, Menu, ipcMain, dialog, shell, powerSaveBlocker, nativeImage, clipboard, nativeTheme } = require('electron');
const path = require('path');
const fs = require('fs');

const SELFTEST = process.argv.includes('--selftest') || process.env.MRFOX_SELFTEST === '1';
if (SELFTEST && process.env.MRFOX_USERDATA) app.setPath('userData', process.env.MRFOX_USERDATA);

const gotLock = SELFTEST || app.requestSingleInstanceLock();
if (!gotLock) app.quit();
app.setAppUserModelId('de.mrfox.bot');

const B = require('./browser');
const Store = require('./store');
const Shops = require('./shops');
const Scraper = require('./scraper');
const A = require('./amazon');
const ShopBuyer = require('./shopbuyer');
const Checker = require('./checker');
const Payback = require('./payback');
const WebWin = require('./webwin');
const Log = require('./log');
const Telegram = require('./telegram');
const Updater = require('./updater');
const { Notification } = require('electron');

let mainWin = null;
let tray = null;
let quitting = false;
let botTimer = null;
let botBusy = false;
let botActivity = '';
let awakeId = null;
let tgStatus = '';
let chromeStatus = '';
let updateInfo = null;
let updateStatus = '';
let updateNotified = '';

// ---------------- Automatisches Update ----------------
async function checkUpdate(manual) {
  try {
    const info = await Updater.check();
    if (!info.available) {
      updateInfo = null;
      pushState();
      return `Kein Update – du hast die neueste Version (${info.current}).`;
    }
    updateInfo = info;
    pushState();
    if (updateNotified !== info.version) {
      updateNotified = info.version;
      try {
        if (Notification.isSupported()) {
          const n = new Notification({ title: 'Mr.Fox Bot: Update ' + info.version, body: Store.setting('autoUpdate') !== false ? 'Wird automatisch installiert, sobald der Bot frei ist.' : 'Klicken zum Öffnen – dort „Jetzt installieren“.', icon: B.ICON });
          n.on('click', showMain);
          n.show();
        }
      } catch (e) { /* egal */ }
    }
    if (!manual && Store.setting('autoUpdate') !== false) maybeInstall();
    return `Update ${info.version} verfügbar (du hast ${info.current}).`;
  } catch (e) {
    if (manual) return '⚠️ ' + e.message;
    return '';
  }
}

let installing = false;
function maybeInstall() {
  if (!updateInfo || installing) return;
  if (botBusy || checking) { setTimeout(maybeInstall, 120000); return; } // nicht mitten in einer Prüfung/einem Kauf
  installing = true;
  Updater.install(updateInfo, (s) => { updateStatus = s; pushState(); }, () => { quitting = true; app.quit(); })
    .catch((e) => { installing = false; updateStatus = '⚠️ ' + e.message; pushState(); });
}

// ---------------- Dauerbetrieb ----------------
function botRunning() { return botTimer !== null; }

function time(t) { return t ? new Date(t).toLocaleTimeString('de-DE', { hour12: false }) : '–'; }

function summary() {
  const s = Store.settings();
  const a = s.lastRun_amazon;
  const next = a ? time(a + s.amazonInterval * 60000) : 'gleich';
  const all = Store.products();
  const active = all.filter((p) => Store.isActive(p)).length;
  const off = [['amazon', 'Amazon'], ...Shops.SHOPS.map((x) => [x.key, x.label])].filter(([k]) => !Store.shopEnabled(k)).map(([, l]) => l).join(', ');
  return `Amazon alle ${s.amazonInterval} Min. (zuletzt ${time(a)}, nächste ~${next}) · ` +
    `Müller/MediaMarkt/BAUR alle ${Math.max(5, s.interval)} Min. (zuletzt ${time(s.lastRun_shops)}) · ` +
    `${active} von ${all.length} Produkten aktiv` + (off ? ` · AUS: ${off}` : '') +
    (s.tgEnabled ? ` · Telegram (${Telegram.channels().map((c) => '@' + c).join(', ')}) alle ${Math.max(1, Number(s.tgInterval) || 1)} Min.${tgStatus ? ': ' + tgStatus : ''}` : '');
}

async function botTick() {
  if (botBusy) return;
  botBusy = true;
  try {
    const now = Date.now();
    const s = Store.settings();
    if (now - s.lastRun_amazon >= Math.max(1, s.amazonInterval) * 60000) {
      Store.setSettings({ lastRun_amazon: now });
      botActivity = 'Prüfe Amazon …';
      pushState();
      await Checker.checkAll((p) => !Store.isWebShop(p));
    }
    if (Date.now() - s.lastRun_shops >= Math.max(5, s.interval) * 60000) {
      Store.setSettings({ lastRun_shops: Date.now() });
      botActivity = 'Prüfe Müller/MediaMarkt/BAUR …';
      pushState();
      await Checker.checkAll((p) => Store.isWebShop(p));
    }
    if (Store.setting('tgEnabled') && Date.now() - Store.setting('lastRun_tg') >= Math.max(1, Number(Store.setting('tgInterval')) || 1) * 60000) {
      Store.setSettings({ lastRun_tg: Date.now() });
      botActivity = 'Lese Telegram-Deals …';
      pushState();
      const r = await Telegram.poll((d) => Checker.telegramDeal(d));
      tgStatus = (r.errors.length ? '⚠️ ' + r.errors.join(' · ') : 'OK') + ` (${time(Date.now())}${r.found ? ', ' + r.found + ' Treffer' : ''})`;
    }
  } catch (e) {
    Store.setStatus('Fehler im Dauerbetrieb: ' + e.message);
  } finally {
    botBusy = false;
    botActivity = '';
    updateTray();
    pushState();
  }
}

function startBot() {
  Store.setSettings({ botWanted: true });
  if (!botTimer) {
    botTimer = setInterval(botTick, 15000);
    setTimeout(botTick, 1000);
  }
  if (Store.setting('keepAwake') && awakeId === null) awakeId = powerSaveBlocker.start('prevent-app-suspension');
  updateTray();
  pushState();
}

function stopBot() {
  Store.setSettings({ botWanted: false });
  if (botTimer) clearInterval(botTimer);
  botTimer = null;
  if (awakeId !== null) { powerSaveBlocker.stop(awakeId); awakeId = null; }
  updateTray();
  pushState();
}

// ---------------- Fenster & Tray ----------------
function createMain(show = true) {
  mainWin = new BrowserWindow({
    width: 1180,
    height: 900,
    minWidth: 720,
    minHeight: 500,
    show,
    title: 'Mr.Fox Bot',
    icon: B.ICON,
    backgroundColor: '#14161f',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
    },
  });
  mainWin.setMenu(null);
  mainWin.loadFile(path.join(__dirname, 'ui', 'index.html'));
  mainWin.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  mainWin.on('close', (e) => {
    if (!quitting) {
      e.preventDefault();
      mainWin.hide();
      if (!app.__trayHintShown) {
        app.__trayHintShown = true;
        try { tray && tray.displayBalloon && tray.displayBalloon({ title: 'Mr.Fox Bot läuft weiter', content: 'Der Bot arbeitet im Hintergrund weiter. Rechtsklick auf den Fuchs unten rechts → Beenden.', iconType: 'info' }); } catch (x) { /* egal */ }
      }
    }
  });
}

function showMain() {
  if (!mainWin || mainWin.isDestroyed()) createMain(true);
  mainWin.show();
  mainWin.focus();
}

function updateTray() {
  if (!tray) return;
  const run = botRunning();
  tray.setToolTip((run ? '🟢 Mr.Fox Bot läuft' : '🔴 Mr.Fox Bot gestoppt') + (botActivity ? ' – ' + botActivity : ''));
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: run ? '🟢 Bot läuft' : '🔴 Bot gestoppt', enabled: false },
    { label: 'Öffnen', click: showMain },
    { label: run ? 'Dauerbetrieb stoppen' : 'Dauerbetrieb starten', click: () => (run ? stopBot() : startBot()) },
    { label: 'Jetzt prüfen', click: () => checkNow() },
    { type: 'separator' },
    { label: 'Beenden', click: () => { quitting = true; app.quit(); } },
  ]));
}

function createTray() {
  const img = nativeImage.createFromPath(B.ICON).resize({ width: 16, height: 16 });
  tray = new Tray(img);
  tray.on('click', showMain);
  updateTray();
}

function pushState() {
  if (mainWin && !mainWin.isDestroyed()) mainWin.webContents.send('changed');
}

// ---------------- Schnittstelle zur Oberfläche ----------------
let checking = false;
async function checkNow() {
  if (checking) return 'Prüfung läuft schon …';
  checking = true;
  botActivity = 'Prüfe alle Produkte …';
  pushState();
  try {
    await Checker.checkAll();
    const now = Date.now();
    Store.setSettings({ lastRun_amazon: now, lastRun_shops: now });
    return 'Prüfung fertig: ' + Store.setting('status');
  } finally {
    checking = false;
    botActivity = '';
    pushState();
  }
}

function state() {
  const s = Store.settings();
  const logins = { amazon: { mail: Store.loginEmail('amazon'), hasPw: Store.hasLogin('amazon') } };
  for (const sh of Shops.SHOPS) logins[sh.key] = { mail: Store.loginEmail(sh.key), hasPw: Store.hasLogin(sh.key) };
  for (const k of ['paypal', 'payback']) logins[k] = { mail: Store.loginEmail(k), hasPw: Store.hasLogin(k) };
  return {
    version: app.getVersion(),
    products: Store.products().map((p) => Object.assign(p, {
      pageUrl: Store.pageUrl(p),
      cartOffer: Store.cartOffer(p),
      active: Store.isActive(p),
      cartUrl: Store.isWebShop(p) ? null : A.cartUrl(p.asin, Store.cartOffer(p)),
    })),
    settings: s,
    logins,
    shops: Shops.SHOPS.map((x) => ({ key: x.key, label: x.label, emoji: x.emoji, base: x.base, cartUrl: x.cartUrl, accountUrl: x.accountUrl, enabled: Store.shopEnabled(x.key) })),
    amazonEnabled: Store.shopEnabled('amazon'),
    paybackPartners: Object.keys(Payback.PARTNERS).map((k) => ({ key: k, label: Payback.PARTNERS[k].label, on: Store.payback(k) })),
    bot: { running: botRunning(), activity: botActivity, summary: summary(), status: s.status, checking, chrome: chromeStatus },
    chromeFound: !!require('./chrome').findChrome(s.chromePath),
    alerts: Store.alerts(),
    autostart: app.getLoginItemSettings().openAtLogin,
    update: { available: !!updateInfo, version: updateInfo ? updateInfo.version : '', status: updateStatus, installing },
  };
}

function addProduct({ shop, url, name, target, offer }) {
  const t = target === '' || target == null ? null : Number(String(target).replace(',', '.'));
  if (t != null && isNaN(t)) return { ok: false, msg: 'Zielpreis ist keine Zahl.' };
  let p;
  if (shop && shop !== 'amazon') {
    const ws = Shops.byKey(shop);
    const link = Shops.parse(ws, url);
    if (!link) return { ok: false, msg: `Kein ${ws.label}-Link erkannt. Nutze den Link einer Produkt- oder Kategorieseite von ${ws.base}.` };
    p = { asin: link.id, name: (name || '').trim() || link.id, target: t, shop: ws.key, kind: link.kind, url: link.url };
  } else {
    const asin = Scraper.extractAsin(url);
    if (!asin) return { ok: false, msg: 'Keine ASIN erkannt. Nutze den vollen Link (…/dp/B0XXXXXXXX) oder die ASIN. Kurzlinks wie amzn.to gehen nicht.' };
    const pinned = Scraper.extractOfferId(offer || '') || (/offerlistingid/i.test(url) ? Scraper.extractOfferId(url) : null);
    p = { asin, name: (name || '').trim() || asin, target: t, pinnedOffer: pinned };
  }
  Store.add(p);
  return { ok: true, id: p.asin };
}

async function afterAdd(id) {
  const err = await Checker.checkOne(id);
  const p = Store.get(id);
  if (!err && p && !Store.isWebShop(p) && !p.offerId && p.pinnedOffer) {
    await Checker.sendDiscord(Store.setting('webhook'), `🆕 OfferListingId (von Hand)\n**${p.name}**\n${Checker.offerInfo(p)}`);
  }
  pushState();
  if (err) return 'Prüfung fehlgeschlagen: ' + err;
  if (!p) return 'Produkt fehlt.';
  if (Store.isCategory(p)) {
    let n = 0;
    let av = 0;
    try { const st = JSON.parse(p.catState); n = Object.keys(st).length; av = Object.values(st).filter((x) => x.a).length; } catch (e) { /* leer */ }
    return `Kategorie gemerkt: ${n} Produkte, davon ${av} online verfügbar`;
  }
  if (Store.isWebShop(p)) return `${Shops.byKey(p.shop).label}: ${p.lastPrice != null ? A.money(p.lastPrice) : 'Preis ?'}, ${p.available ? 'online verfügbar' : 'ausverkauft'}`;
  const offer = Store.cartOffer(p);
  return offer ? `OfferListingId gefunden${Store.setting('webhook') ? ' – bei Discord hinterlegt' : ''}:\n${offer}` : 'Keine OfferListingId gefunden (Artikel evtl. nicht verfügbar).';
}

const api = {
  state: () => state(),
  addProduct: async (a) => {
    const r = addProduct(a);
    if (!r.ok) return r;
    pushState();
    afterAdd(r.id).then((msg) => { if (mainWin && !mainWin.isDestroyed()) mainWin.webContents.send('toast', msg); });
    return { ok: true, msg: 'Hinzugefügt – prüfe …' };
  },
  remove: (id) => { Store.remove(id); pushState(); },
  togglePause: (id) => { Store.update(id, (p) => { p.paused = !p.paused; }); pushState(); },
  setTarget: (id, v) => {
    const t = v === '' || v == null ? null : Number(String(v).replace(',', '.'));
    if (t != null && isNaN(t)) return 'Keine Zahl.';
    Store.update(id, (p) => { p.target = t; if (t == null) p.autoBuy = false; });
    pushState();
    return t == null ? 'Zielpreis entfernt.' : 'Zielpreis: ' + A.money(t);
  },
  setAutoBuy: (id, on) => {
    const p = Store.get(id);
    if (!p) return 'Produkt fehlt.';
    if (on && p.target == null) return 'Auto-Kauf braucht einen Zielpreis.';
    if (on && !Store.setting('autoBuy')) return 'Erst in den Einstellungen „Auto-Kauf erlauben“ anhaken und speichern.';
    Store.update(id, (x) => { x.autoBuy = !!on; });
    pushState();
    return on ? `Auto-Kauf für ${p.name} ist scharf.` : `Auto-Kauf für ${p.name} entschärft.`;
  },
  togglePin: (id) => {
    const p = Store.get(id);
    if (!p) return 'Produkt fehlt.';
    if (p.pinnedOffer) {
      Store.update(id, (x) => { x.pinnedOffer = null; x.pinnedSeller = ''; });
      pushState();
      return 'Angebot freigegeben – Auto-Kauf nimmt wieder das Angebot in der Kaufbox.';
    }
    if (!p.offerId) return 'Noch keine OfferListingId bekannt. Erst „Jetzt prüfen“.';
    Store.update(id, (x) => { x.pinnedOffer = p.offerId; x.pinnedSeller = p.seller; });
    pushState();
    return 'Angebot festgehalten: ' + (p.seller || 'unbekannter Verkäufer');
  },
  checkNow: () => checkNow(),
  checkOne: async (id) => { const e = await Checker.checkOne(id); pushState(); return e ? 'Prüfung fehlgeschlagen: ' + e : 'Geprüft.'; },
  dryRun: async (id) => {
    const p = Store.get(id);
    if (!p) return 'Produkt fehlt.';
    const ws = Shops.byKey(p.shop);
    const visible = !!Store.setting('watchBuy');
    const note = { text: '' };
    const beforeStart = Checker.paybackStarter(p.shop, note);
    const r = ws
      ? await ShopBuyer.buy(ws, p.url, p.asin, { dryRun: true, visible, beforeStart })
      : await A.buy(p.asin, { dryRun: true, visible, beforeStart });
    return A.describe(r) + (note.text ? '\n' + note.text : '');
  },
  cartTest: async (id) => {
    const p = Store.get(id);
    if (!p) return 'Produkt fehlt.';
    const ws = Shops.byKey(p.shop);
    const r = ws ? await ShopBuyer.addToCart(ws, p.url, p.asin) : await A.addToCart(p.asin, Store.cartOffer(p));
    if (r.result === 'OK') { Store.update(id, (x) => { x.lastCartAdd = Date.now(); }); pushState(); }
    return r.result === 'OK' ? '✅ Im Warenkorb: ' + r.detail : r.result === 'NOT_LOGGED_IN' ? '⚠️ Nicht angemeldet: ' + r.detail : '⚠️ Nicht geklappt: ' + r.detail;
  },
  open: (url, checkout) => { WebWin.open(url, { checkout: !!checkout }); },
  openViaPayback: (key, url) => { WebWin.openViaPayback(key, url); },
  openInApp: (url) => { WebWin.open(url, { forceInApp: true }); },
  openExternal: (url) => { shell.openExternal(url); },
  saveSettings: (o) => {
    const clean = {
      domain: String(o.domain || 'amazon.de').trim().replace(/^www\./, '') || 'amazon.de',
      interval: Math.max(5, parseInt(o.interval, 10) || 60),
      amazonInterval: Math.max(1, parseInt(o.amazonInterval, 10) || 2),
      drop: Math.max(0, parseInt(o.drop, 10) || 0),
      webhook: String(o.webhook || '').trim(),
      autoCart: !!o.autoCart,
      autoBuy: !!o.autoBuy,
      openOnAlert: !!o.openOnAlert,
      watchBuy: !!o.watchBuy,
      externalBrowser: !!o.externalBrowser,
      preferPaypal: !!o.preferPaypal,
      paybackAutoBuy: !!o.paybackAutoBuy,
      keepAwake: !!o.keepAwake,
    };
    Store.setSettings(clean);
    if (botRunning()) {
      if (clean.keepAwake && awakeId === null) awakeId = powerSaveBlocker.start('prevent-app-suspension');
      if (!clean.keepAwake && awakeId !== null) { powerSaveBlocker.stop(awakeId); awakeId = null; }
    }
    pushState();
    return `Gespeichert. Amazon alle ${clean.amazonInterval} Min., Müller/MediaMarkt/BAUR alle ${clean.interval} Min.` + (clean.autoBuy ? ' Auto-Kauf erlaubt.' : '');
  },
  setAutostart: (on) => {
    app.setLoginItemSettings({ openAtLogin: !!on, args: ['--hidden'] });
    pushState();
    return on ? 'Startet jetzt mit Windows (im Hintergrund, Bot läuft weiter, wenn er an war).' : 'Startet nicht mehr mit Windows.';
  },
  setShopEnabled: (key, on) => { Store.setShopEnabled(key, on); pushState(); },
  setPayback: (key, on) => { Store.setPayback(key, on); pushState(); },
  saveLogin: (key, mail, pw) => {
    mail = String(mail || '').trim();
    if (!mail) return 'Bitte E-Mail eintragen.';
    if (!pw && !Store.hasLogin(key) && key !== 'paypal' && key !== 'payback') return 'Bitte Passwort eintragen.';
    Store.saveLogin(key, mail, pw || null);
    pushState();
    return 'Zugang verschlüsselt gespeichert (Windows-Datenschutz).';
  },
  clearLogin: (key) => { Store.clearLogin(key); pushState(); return 'Zugang gelöscht.'; },
  discordTest: async (hook) => ((await Checker.sendDiscord(hook || Store.setting('webhook'), '✅ Mr.Fox Bot (Windows): Testnachricht')) ? 'Testnachricht gesendet.' : 'Senden fehlgeschlagen. URL prüfen.'),
  sendAllOffers: async () => ((await Checker.sendAllOffers()) ? 'An Discord gesendet.' : 'Senden fehlgeschlagen (Webhook gespeichert? Produkte vorhanden?).'),
  botStart: () => { startBot(); return 'Dauerbetrieb gestartet – der Fuchs unten rechts zeigt, dass der Bot läuft.'; },
  botStop: () => { stopBot(); return 'Dauerbetrieb gestoppt.'; },
  clearAlerts: () => { Store.clearAlerts(); pushState(); },
  copyReport: () => { clipboard.writeText('Mr.Fox Ablauf (Windows ' + app.getVersion() + ')\n' + Log.text()); return 'Ablauf des letzten Probelaufs/Kaufs kopiert.'; },
  paybackTest: async (key) => {
    let w;
    if (A.useChrome()) {
      try { w = await require('./chrome').openTab(A.chromeOpts({ foreground: true })); } catch (e) { return '⚠️ ' + e.message; }
    } else {
      w = B.watchWindow('Payback-Test …', A.reportOf);
    }
    Log.start('Payback-Test ' + key);
    const r = await Payback.activate(w.webContents, key, (s) => { if (!w.isDestroyed()) w.setTitle('Payback-Test: ' + s); });
    if (!w.isDestroyed()) w.setTitle('Payback-Test: ' + (r.ok ? '✅ Weiterleitung zum Shop klappt' : '⚠️ ' + r.detail));
    return r.ok ? '✅ Payback-Weiterleitung zum Shop klappt.' : '⚠️ ' + r.detail;
  },
  paybackOpen: (key, name) => {
    if (name) clipboard.writeText(name);
    WebWin.open(Payback.PARTNERS[key].page, { forceInApp: true });
    return name ? 'Produktname kopiert. Auf Payback „Jetzt shoppen“ klicken und im Shop in die Suche einfügen.' : '';
  },
  categoryItems: (id) => {
    const p = Store.get(id);
    if (!p) return [];
    let st = {};
    try { st = JSON.parse(p.catState || '{}'); } catch (e) { st = {}; }
    return Object.keys(st).map((u) => ({ url: u, name: st[u].n, price: st[u].p, available: !!st[u].a }));
  },
  addFromCategory: (catId, url, name, target) => {
    const cat = Store.get(catId);
    if (!cat) return { ok: false, msg: 'Kategorie fehlt.' };
    const r = addProduct({ shop: cat.shop, url, name, target });
    if (!r.ok) return r;
    pushState();
    afterAdd(r.id).then((msg) => { if (mainWin && !mainWin.isDestroyed()) mainWin.webContents.send('toast', msg); });
    return { ok: true, msg: 'Hinzugefügt – prüfe …' };
  },
  exportProducts: async () => {
    const r = await dialog.showSaveDialog(mainWin, {
      title: 'Produkte exportieren',
      defaultPath: path.join(app.getPath('documents'), 'mrfox-produkte.json'),
      filters: [{ name: 'Mr.Fox Bot Produkte', extensions: ['json'] }],
    });
    if (r.canceled || !r.filePath) return '';
    fs.writeFileSync(r.filePath, JSON.stringify(Store.exportJson(), null, 1), 'utf8');
    return `${Store.products().length} Produkte exportiert: ${r.filePath}`;
  },
  importProducts: async (withSettings) => {
    const r = await dialog.showOpenDialog(mainWin, {
      title: 'Produkte importieren',
      properties: ['openFile'],
      filters: [{ name: 'Mr.Fox Bot Produkte', extensions: ['json'] }],
    });
    if (r.canceled || !r.filePaths.length) return '';
    try {
      const obj = JSON.parse(fs.readFileSync(r.filePaths[0], 'utf8'));
      const res = Store.importJson(obj, !!withSettings);
      pushState();
      return `${res.count} Produkte importiert.` + (res.disarmed ? ` Auto-Kauf bei ${res.disarmed} Produkt(en) ausgeschaltet (sonst würden Handy und PC doppelt kaufen) – bei Bedarf hier neu scharf schalten.` : '');
    } catch (e) {
      return 'Import fehlgeschlagen: ' + e.message;
    }
  },
  tgSave: (o) => {
    const max = o.maxPrice === '' || o.maxPrice == null ? null : Number(String(o.maxPrice).replace(',', '.'));
    if (max != null && isNaN(max)) return 'Höchstpreis ist keine Zahl.';
    const chans = String(o.channels || '').split(/[\s,;]+/).map(Telegram.channelName).filter(Boolean);
    const prev = Telegram.channels().join(',');
    Store.setSettings({
      tgEnabled: !!o.enabled,
      tgChannels: chans.join(', '),
      tgKeywords: String(o.keywords || '').trim(),
      tgExclude: String(o.exclude || '').trim(),
      tgMaxPrice: max,
      tgAction: ['alert', 'watch', 'cart'].includes(o.action) ? o.action : 'alert',
      tgInterval: Math.max(1, parseInt(o.interval, 10) || 1),
      tgPayback: o.payback !== false,
    });
    if (prev !== chans.join(',')) tgStatus = '';
    pushState();
    if (o.enabled && !botRunning()) return 'Gespeichert. Telegram wird nur im Dauerbetrieb gelesen – oben „Dauerbetrieb starten“.';
    return 'Gespeichert.' + (o.enabled ? ' Neue Beiträge ab jetzt werden geprüft (ältere lösen keinen Alarm aus).' : '');
  },
  tgTest: async () => {
    if (!Telegram.channels().length) return { error: 'Kein Kanal eingetragen.' };
    try {
      return { items: await Telegram.test(5) };
    } catch (e) {
      return { error: e.message };
    }
  },
  chromeOpen: async (url) => {
    try {
      const t = await require('./chrome').openTab(A.chromeOpts({ url, foreground: true }));
      WebWin.autofill(t.webContents, url);
      return 'Chrome ist offen – dort selbst anmelden („Angemeldet bleiben“ anhaken). Der Bot benutzt danach diese Anmeldung.';
    } catch (e) {
      return '⚠️ ' + e.message;
    }
  },
  chromeLogin: async () => {
    try {
      await require('./chrome').openTab(A.chromeOpts({ url: `https://www.${Store.setting('domain')}/gp/sign-in.html`, foreground: true }));
      return 'Chrome ist offen – dort bei Amazon anmelden und „Angemeldet bleiben“ anhaken. Danach kauft der Bot über dieses Chrome.';
    } catch (e) {
      return '⚠️ ' + e.message;
    }
  },
  checkUpdate: () => checkUpdate(true),
  installUpdate: () => { if (!updateInfo) return 'Kein Update vorhanden.'; maybeInstall(); return 'Update wird geladen und installiert – der Bot startet danach neu.'; },
  setTheme: (mode) => {
    const m = ['dark', 'light', 'system'].includes(mode) ? mode : 'dark';
    Store.setSettings({ theme: m });
    nativeTheme.themeSource = m;
    pushState();
    return '';
  },
  revealPassword: (key) => Store.loginPassword(key) || '',
  setSetting: (k, v) => {
    const allowed = ['amazonChrome', 'autoUpdate', 'paybackAutoBuy', 'preferPaypal', 'watchBuy', 'openOnAlert', 'externalBrowser', 'autoCart', 'keepAwake'];
    if (!allowed.includes(k)) return 'Nicht erlaubt.';
    Store.setSettings({ [k]: !!v });
    pushState();
    return '';
  },
  quit: () => { quitting = true; app.quit(); },
};

ipcMain.handle('call', async (_e, method, args) => {
  const f = api[method];
  if (!f) throw new Error('Unbekannt: ' + method);
  return f(...(args || []));
});

// ---------------- Start ----------------
app.on('second-instance', () => showMain());
app.on('before-quit', () => { quitting = true; });
app.on('window-all-closed', (e) => { /* im Hintergrund weiterlaufen (Tray) */ });

app.whenReady().then(async () => {
  if (!gotLock) return;
  require('./chrome').setStatusHook((s) => { chromeStatus = s; pushState(); });
  B.setupSession();
  Store.load();
  nativeTheme.themeSource = Store.setting('theme') || 'dark';
  if (SELFTEST) {
    const t = require('./selftest');
    const code = await t.run();
    app.exit(code);
    return;
  }
  createTray();
  const hidden = process.argv.includes('--hidden');
  createMain(!hidden);
  if (Store.setting('botWanted')) startBot();
  setTimeout(() => checkUpdate(false), 30000);
  setInterval(() => checkUpdate(false), 6 * 3600 * 1000);
  setInterval(pushState, 5000);
});

'use strict';
/**
 * Browserfenster des Bots. Alle Fenster teilen sich eine dauerhafte Sitzung („persist:mrfox“),
 * d. h. eine Anmeldung bei Amazon, einem Shop, PayPal oder Payback gilt für alle Abläufe.
 */
const { app, BrowserWindow, Menu, clipboard, session } = require('electron');
const path = require('path');

const PARTITION = 'persist:mrfox';
const ICON = path.join(__dirname, '..', 'build', 'icon.png');

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

/** Echte Chrome-Kennung ohne „Electron/…“ (sonst lehnen manche Bot-Prüfungen ab). */
function cleanUA(ua) {
  return ua.replace(/\s?Electron\/\S+/i, '').replace(/\s?mrfox-bot\/\S+/i, '').replace(/\s?Mr\.?Fox Bot\/\S+/i, '');
}

function ses() { return session.fromPartition(PARTITION); }

function setupSession() {
  const s = ses();
  const ua = cleanUA(s.getUserAgent());
  s.setUserAgent(ua, 'de-DE,de;q=0.9,en;q=0.7');
  app.userAgentFallback = cleanUA(app.userAgentFallback);
  return s;
}

/**
 * Neues Fenster mit Bot-Sitzung. Pop-ups (z. B. PayPal, Payback-Weiterleitung) werden im selben
 * Fenster geöffnet, App-Links (intent://, payback:// …) ignoriert.
 */
function newWindow({ show = false, title = 'Mr.Fox Bot', width = 1200, height = 900 } = {}) {
  const w = new BrowserWindow({
    show,
    width,
    height,
    title,
    icon: ICON,
    autoHideMenuBar: false,
    webPreferences: {
      partition: PARTITION,
      backgroundThrottling: false,
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
    },
  });
  w.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) setImmediate(() => { if (!w.isDestroyed()) load(w.webContents, url); });
    return { action: 'deny' };
  });
  w.webContents.on('will-navigate', (e, url) => {
    if (!/^(https?|about|data|blob):/i.test(url)) e.preventDefault();
  });
  // Seitentitel nicht über den Fenstertitel (Status) schreiben lassen
  w.on('page-title-updated', (e) => e.preventDefault());
  return w;
}

function load(wc, url) {
  if (!wc || wc.isDestroyed()) return;
  wc.loadURL(url).catch(() => { /* Weiterleitungen melden ERR_ABORTED – egal */ });
}

/** Führt JS in der Seite aus. Gibt undefined zurück bei Fehler/Seitenwechsel/Zeitüberschreitung. */
async function js(wc, code, ms = 10000) {
  if (!wc || wc.isDestroyed()) return undefined;
  try {
    return await Promise.race([
      wc.executeJavaScript(code, true).catch(() => undefined),
      sleep(ms).then(() => undefined),
    ]);
  } catch (e) {
    return undefined;
  }
}

/** JSON-Ergebnis eines Skripts lesen (Skripte liefern JSON.stringify(...)). */
function parse(raw) {
  if (raw == null) return {};
  if (typeof raw === 'object') return raw;
  try { return JSON.parse(raw); } catch (e) { return {}; }
}

/**
 * Sichtbares Ablauf-Fenster (Probelauf/Auto-Kauf): Status steht in der Titelleiste, im Menü
 * „Seitenbericht kopieren“. Nach dem Ende bleibt das Fenster offen.
 */
function watchWindow(title, reportFn) {
  const w = newWindow({ show: true, title });
  const menu = Menu.buildFromTemplate([
    {
      label: '📋 Seitenbericht kopieren',
      click: async () => {
        const t = await reportFn(w.webContents);
        clipboard.writeText(t);
        w.setTitle('Seitenbericht kopiert – im Chat einfügen');
      },
    },
    { label: '⟳ Neu laden', click: () => w.webContents.reload() },
    { label: 'Schließen', click: () => w.close() },
  ]);
  w.setMenu(menu);
  return w;
}

module.exports = { PARTITION, ICON, sleep, cleanUA, ses, setupSession, newWindow, load, js, parse, watchWindow };

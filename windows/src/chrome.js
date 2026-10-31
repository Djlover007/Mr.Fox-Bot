'use strict';
/**
 * Echtes Google Chrome für Amazon: Der Bot startet Chrome mit einem EIGENEN Profil (nur für den Bot,
 * deine normalen Chrome-Daten bleiben unberührt) und steuert es über die Chrome-Entwicklerschnittstelle (CDP).
 * Amazon akzeptiert die Anmeldung dort – im eingebauten Browser meldet es „Bitte aktiviere Cookies“.
 *
 * Ein Tab verhält sich wie ein Bot-Fenster: {webContents: {loadURL, executeJavaScript, getURL, isLoading,
 * getTitle, isDestroyed}, setTitle, isDestroyed, destroy, on('closed')}.
 */
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const PORT = 9333;
let onStatus = null; // (text) => void – zeigt den Stand in der Bot-Oberfläche

function setStatusHook(fn) { onStatus = fn; }

/** chrome.exe suchen (auch Edge als Ersatz). */
function findChrome(custom) {
  const c = [];
  if (custom) c.push(custom);
  if (process.platform === 'win32') {
    for (const base of [process.env.PROGRAMFILES, process.env['PROGRAMFILES(X86)'], process.env.LOCALAPPDATA]) {
      if (base) c.push(path.join(base, 'Google', 'Chrome', 'Application', 'chrome.exe'));
    }
    for (const base of [process.env['PROGRAMFILES(X86)'], process.env.PROGRAMFILES]) {
      if (base) c.push(path.join(base, 'Microsoft', 'Edge', 'Application', 'msedge.exe'));
    }
  } else if (process.platform === 'darwin') {
    c.push('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome');
  } else {
    c.push('/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium', '/usr/bin/chromium-browser');
  }
  return c.find((p) => { try { return fs.statSync(p).isFile(); } catch (e) { return false; } }) || null;
}

function getJson(p, method = 'GET') {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port: PORT, path: p, method, timeout: 3000 }, (res) => {
      let b = '';
      res.on('data', (d) => { b += d; });
      res.on('end', () => { try { resolve(JSON.parse(b)); } catch (e) { reject(new Error('Chrome antwortet nicht verständlich')); } });
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(new Error('Zeitüberschreitung')); });
    req.end();
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Läuft das Bot-Chrome? Sonst starten. */
async function ensureChrome({ chromePath, profileDir, startUrl } = {}) {
  try { await getJson('/json/version'); return true; } catch (e) { /* starten */ }
  const exe = findChrome(chromePath);
  if (!exe) throw new Error('Google Chrome nicht gefunden – bitte Chrome installieren (oder in den Einstellungen „Amazon über Chrome“ ausschalten)');
  fs.mkdirSync(profileDir, { recursive: true });
  const args = [
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${profileDir}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-features=Translate',
  ];
  if (process.env.MRFOX_CHROME_HEADLESS) args.push('--headless=new', '--no-sandbox');
  args.push(startUrl || 'about:blank');
  spawn(exe, args, { detached: true, stdio: 'ignore' }).unref();
  for (let i = 0; i < 40; i++) {
    await sleep(500);
    try { await getJson('/json/version'); return true; } catch (e) { /* weiter warten */ }
  }
  throw new Error('Chrome ließ sich nicht starten (Port ' + PORT + ')');
}

// ---------------- minimaler WebSocket-Client (nur was CDP braucht) ----------------
function wsConnect(url) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const key = crypto.randomBytes(16).toString('base64');
    const req = http.request({
      host: u.hostname, port: u.port, path: u.pathname + u.search,
      headers: { Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Version': '13', 'Sec-WebSocket-Key': key },
    });
    req.on('error', reject);
    req.on('response', (res) => reject(new Error('WebSocket abgelehnt: HTTP ' + res.statusCode)));
    req.on('upgrade', (res, socket, head) => {
      const listeners = { message: [], close: [] };
      let buf = head && head.length ? Buffer.from(head) : Buffer.alloc(0);
      let frag = [];
      let closed = false;
      const write = (opcode, payload) => {
        if (closed) return;
        const len = payload.length;
        let hdr;
        if (len < 126) { hdr = Buffer.alloc(2); hdr[1] = 0x80 | len; } else if (len < 65536) { hdr = Buffer.alloc(4); hdr[1] = 0x80 | 126; hdr.writeUInt16BE(len, 2); } else { hdr = Buffer.alloc(10); hdr[1] = 0x80 | 127; hdr.writeBigUInt64BE(BigInt(len), 2); }
        hdr[0] = 0x80 | opcode;
        const mask = crypto.randomBytes(4);
        const out = Buffer.alloc(len);
        for (let i = 0; i < len; i++) out[i] = payload[i] ^ mask[i & 3];
        socket.write(Buffer.concat([hdr, mask, out]));
      };
      const finish = () => { if (closed) return; closed = true; listeners.close.forEach((f) => f()); };
      socket.on('data', (d) => {
        buf = Buffer.concat([buf, d]);
        while (buf.length >= 2) {
          const fin = (buf[0] & 0x80) !== 0;
          const op = buf[0] & 0x0f;
          const masked = (buf[1] & 0x80) !== 0;
          let len = buf[1] & 0x7f;
          let off = 2;
          if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; } else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
          const mOff = off;
          if (masked) off += 4;
          if (buf.length < off + len) return;
          let payload = buf.subarray(off, off + len);
          if (masked) { const m = buf.subarray(mOff, mOff + 4); payload = Buffer.from(payload.map((b, i) => b ^ m[i & 3])); }
          buf = buf.subarray(off + len);
          if (op === 0x8) { finish(); socket.end(); return; }
          if (op === 0x9) { write(0xA, payload); continue; }
          if (op === 0xA) continue;
          frag.push(Buffer.from(payload));
          if (fin) {
            const msg = Buffer.concat(frag).toString('utf8');
            frag = [];
            listeners.message.forEach((f) => f(msg));
          }
        }
      });
      socket.on('close', finish);
      socket.on('error', finish);
      resolve({
        send: (s) => write(0x1, Buffer.from(s, 'utf8')),
        on: (ev, f) => listeners[ev].push(f),
        close: () => { try { write(0x8, Buffer.alloc(0)); socket.end(); } catch (e) { /* egal */ } finish(); },
        get closed() { return closed; },
      });
    });
    req.end();
  });
}

/** CDP-Sitzung zu einem Tab. */
async function cdp(wsUrl) {
  const ws = await wsConnect(wsUrl);
  let id = 0;
  const pending = new Map();
  const handlers = [];
  ws.on('message', (m) => {
    let o;
    try { o = JSON.parse(m); } catch (e) { return; }
    if (o.id && pending.has(o.id)) {
      const p = pending.get(o.id);
      pending.delete(o.id);
      if (o.error) p.reject(new Error(o.error.message)); else p.resolve(o.result);
    } else if (o.method) {
      handlers.forEach((h) => h(o.method, o.params || {}));
    }
  });
  ws.on('close', () => { for (const p of pending.values()) p.reject(new Error('Chrome-Tab geschlossen')); pending.clear(); });
  return {
    send: (method, params = {}) => new Promise((resolve, reject) => {
      if (ws.closed) { reject(new Error('Chrome-Tab geschlossen')); return; }
      const i = ++id;
      pending.set(i, { resolve, reject });
      ws.send(JSON.stringify({ id: i, method, params }));
    }),
    onEvent: (h) => handlers.push(h),
    onClose: (f) => ws.on('close', f),
    close: () => ws.close(),
    get closed() { return ws.closed; },
  };
}

/**
 * Neuen Tab im Bot-Chrome öffnen. opts: {chromePath, profileDir, url, foreground}
 * Gibt ein fensterähnliches Objekt zurück (siehe oben).
 */
async function openTab(opts) {
  await ensureChrome(opts);
  const t = await getJson('/json/new?' + encodeURIComponent(opts.url || 'about:blank'), 'PUT')
    .catch(() => getJson('/json/new?' + encodeURIComponent(opts.url || 'about:blank')));
  const s = await cdp(t.webSocketDebuggerUrl);
  let url = t.url || opts.url || 'about:blank';
  let loading = !!opts.url;
  let title = '';
  let destroyed = false;
  const closedCbs = [];
  s.onEvent((m, p) => {
    if (m === 'Page.frameNavigated' && p.frame && !p.frame.parentId) url = p.frame.url + (p.frame.urlFragment || '');
    if (m === 'Page.navigatedWithinDocument') url = p.url;
    if (m === 'Page.frameStartedLoading') loading = true;
    if (m === 'Page.frameStoppedLoading' || m === 'Page.loadEventFired') loading = false;
    if (m === 'Inspector.detached' || m === 'Target.targetDestroyed') { destroyed = true; }
  });
  s.onClose(() => { destroyed = true; closedCbs.forEach((f) => f()); });
  await s.send('Page.enable');
  if (opts.foreground) s.send('Page.bringToFront').catch(() => {});
  // Ist die Seite schon fertig geladen?
  s.send('Runtime.evaluate', { expression: 'document.readyState+"|"+location.href', returnByValue: true })
    .then((r) => { const v = String(r.result.value || ''); if (v.startsWith('complete')) loading = false; url = v.split('|').slice(1).join('|') || url; })
    .catch(() => {});
  const wc = {
    isDestroyed: () => destroyed,
    getURL: () => url,
    isLoading: () => loading,
    getTitle: () => title,
    loadURL: async (u) => { loading = true; url = u; await s.send('Page.navigate', { url: u }); },
    reload: () => s.send('Page.reload').catch(() => {}),
    executeJavaScript: async (code) => {
      const r = await s.send('Runtime.evaluate', { expression: code, returnByValue: true, awaitPromise: true });
      if (r.exceptionDetails) throw new Error('Skriptfehler');
      try { title = String((await s.send('Runtime.evaluate', { expression: 'document.title', returnByValue: true })).result.value || ''); } catch (e) { /* egal */ }
      return r.result ? r.result.value : undefined;
    },
  };
  return {
    webContents: wc,
    setTitle: (txt) => { if (onStatus) onStatus(txt); },
    getTitle: () => title,
    isDestroyed: () => destroyed,
    destroy: () => {
      if (destroyed) return;
      destroyed = true;
      getJson('/json/close/' + t.id).catch(() => {});
      s.close();
    },
    on: (ev, f) => { if (ev === 'closed') closedCbs.push(f); },
  };
}

/** Bot-Chrome beenden (falls es läuft). */
async function closeChrome() {
  try {
    const v = await getJson('/json/version');
    const s = await cdp(v.webSocketDebuggerUrl);
    await s.send('Browser.close').catch(() => {});
    s.close();
  } catch (e) { /* läuft nicht */ }
}

module.exports = { closeChrome, findChrome, ensureChrome, openTab, setStatusHook, PORT, wsConnect };

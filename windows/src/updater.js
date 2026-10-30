'use strict';
/**
 * Automatisches Update: liest version.json aus dem neuesten GitHub-Release, lädt bei einer neueren
 * Version das Setup herunter und installiert es still (NSIS /S) – danach startet der Bot von selbst neu.
 */
const { app, net } = require('electron');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const VERSION_URL = 'https://github.com/Djlover007/Mr.Fox-Bot/releases/latest/download/version.json';

/** "5.10.0" > "5.9.0" ? */
function newer(a, b) {
  const pa = String(a).split('.').map((x) => parseInt(x, 10) || 0);
  const pb = String(b).split('.').map((x) => parseInt(x, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) > (pb[i] || 0);
  }
  return false;
}

/** Gibt {available, version, url, current} zurück oder wirft einen Fehler mit verständlicher Meldung. */
async function check(url = VERSION_URL) {
  let r;
  try {
    r = await net.fetch(url + (url.includes('?') ? '&' : '?') + 't=' + Date.now(), { headers: { 'Cache-Control': 'no-cache' } });
  } catch (e) {
    throw new Error('Update-Server nicht erreichbar (' + e.message + ')');
  }
  if (r.status === 404) throw new Error('Keine Update-Infos gefunden – ist das GitHub-Repo öffentlich?');
  if (r.status !== 200) throw new Error('Update-Prüfung: HTTP ' + r.status);
  const j = await r.json();
  const w = j.windows || {};
  return { available: !!w.version && newer(w.version, app.getVersion()), version: w.version, url: w.url, current: app.getVersion() };
}

/** Setup laden und still installieren. onStatus(text) für die Anzeige. Beendet danach den Bot. */
async function install(info, onStatus, quit) {
  if (process.platform !== 'win32') throw new Error('Automatische Installation nur unter Windows');
  onStatus && onStatus(`Lade Update ${info.version} …`);
  const r = await net.fetch(info.url);
  if (r.status !== 200) throw new Error('Download fehlgeschlagen: HTTP ' + r.status);
  const buf = Buffer.from(await r.arrayBuffer());
  if (buf.length < 5 * 1024 * 1024) throw new Error('Download unvollständig');
  const file = path.join(app.getPath('temp'), `MrFoxBot-Setup-${info.version}.exe`);
  fs.writeFileSync(file, buf);
  onStatus && onStatus(`Installiere Update ${info.version} – der Bot startet gleich neu …`);
  // /S = still installieren (gleicher Ordner wie bisher), --force-run = danach wieder starten
  spawn(file, ['/S', '--force-run'], { detached: true, stdio: 'ignore' }).unref();
  setTimeout(() => quit(), 1500);
}

module.exports = { check, install, newer, VERSION_URL };

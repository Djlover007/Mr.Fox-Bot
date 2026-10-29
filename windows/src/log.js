'use strict';
/** Ablaufprotokoll des letzten Kauf-/Probelaufs (für den Seitenbericht). */
const lines = [];
const snaps = [];

function stamp() { return new Date().toLocaleTimeString('de-DE', { hour12: false }); }

function start(s) {
  lines.length = 0;
  snaps.length = 0;
  add(s);
}

function add(s) {
  lines.push(stamp() + ' ' + String(s).slice(0, 160));
  if (lines.length > 80) lines.shift();
}

function snapshot(t) { if (snaps.length < 3) snaps.push(t); }

function text() {
  return lines.join('\n') + snaps.map((s) => '\n\n=== Automatisch aufgenommene Seite ===\n' + s).join('');
}

module.exports = { start, add, snapshot, text };

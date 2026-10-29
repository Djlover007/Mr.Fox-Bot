'use strict';
/* Oberfläche des Windows-Bots (läuft ohne Node-Zugriff; alles geht über window.bot.call). */
const $ = (id) => document.getElementById(id);
const call = (m, ...a) => window.bot.call(m, ...a);
let S = null; // letzter Zustand
let shopSel = 'amazon';
let filter = null;
let settingsLoaded = false;

function money(v) { return v == null ? '?' : Number(v).toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' €'; }
function fmt(t) { return t ? new Date(t).toLocaleString('de-DE', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : '–'; }
function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }
function el(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === 'onclick') e.addEventListener('click', v);
    else if (k === 'class') e.className = v;
    else if (k === 'html') e.innerHTML = v;
    else e.setAttribute(k, v);
  }
  for (const k of kids) if (k != null) e.append(k.nodeType ? k : document.createTextNode(k));
  return e;
}

function toast(msg, ms = 6000) {
  if (!msg) return;
  const t = el('div', { class: 'toast' }, msg);
  $('toasts').append(t);
  setTimeout(() => t.remove(), ms);
}

function modal(title, body, buttons) {
  return new Promise((resolve) => {
    $('mTitle').textContent = title;
    const b = $('mBody');
    b.innerHTML = '';
    if (typeof body === 'string') b.append(el('div', { class: 'msg' }, body)); else b.append(body);
    const bs = $('mBtns');
    bs.innerHTML = '';
    for (const [label, val, cls] of buttons || [['OK', true, 'primary']]) {
      bs.append(el('button', { class: cls || '', onclick: () => { $('modal').classList.add('hidden'); resolve(val); } }, label));
    }
    $('modal').classList.remove('hidden');
  });
}

/** Knopf, der während der Aktion gesperrt ist und das Ergebnis als Meldung zeigt. */
function act(label, fn, cls) {
  const b = el('button', { class: cls || '' }, label);
  b.addEventListener('click', async () => {
    b.disabled = true;
    try { const r = await fn(); if (typeof r === 'string') toast(r); } catch (e) { toast('Fehler: ' + e.message); }
    b.disabled = false;
  });
  return b;
}

// ---------------- Tabs ----------------
document.querySelectorAll('nav button').forEach((b) => b.addEventListener('click', () => {
  document.querySelectorAll('nav button').forEach((x) => x.classList.toggle('on', x === b));
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('on', t.id === 'tab-' + b.dataset.tab));
}));

// ---------------- Bot-Status ----------------
function renderBot() {
  const bot = S.bot;
  const box = $('botBox');
  box.className = 'bot' + (bot.activity || bot.checking ? ' busy' : bot.running ? ' run' : '');
  $('botTitle').textContent = bot.running
    ? '🟢 Bot läuft (Dauerbetrieb)' + (bot.activity ? ' – ' + bot.activity : '')
    : '🔴 Dauerbetrieb aus' + (bot.activity ? ' – ' + bot.activity : ' – starte ihn, damit automatisch geprüft wird');
  $('botSum').textContent = bot.summary;
  $('botStatus').textContent = 'Status: ' + bot.status;
  $('botBtn').textContent = bot.running ? 'Dauerbetrieb stoppen' : 'Dauerbetrieb starten';
  $('botBtn').className = bot.running ? '' : 'primary';
}
$('botBtn').addEventListener('click', async () => toast(await call(S.bot.running ? 'botStop' : 'botStart')));
$('checkBtn').addEventListener('click', async () => {
  $('checkBtn').disabled = true;
  toast('Prüfung läuft …', 3000);
  toast(await call('checkNow'));
  $('checkBtn').disabled = false;
});

// ---------------- Produkt hinzufügen ----------------
function renderShopPick() {
  const box = $('shopPick');
  box.innerHTML = '';
  const opts = [{ key: 'amazon', label: '🟡 Amazon' }, ...S.shops.map((s) => ({ key: s.key, label: s.emoji + ' ' + s.label }))];
  for (const o of opts) {
    box.append(el('button', { class: shopSel === o.key ? 'on' : '', onclick: () => { shopSel = o.key; renderShopPick(); } }, o.label));
  }
  const ws = S.shops.find((s) => s.key === shopSel);
  $('inUrl').placeholder = ws ? `${ws.label}-Link: Produkt oder Kategorie` : 'Amazon-Link oder ASIN';
  $('inTarget').placeholder = ws ? 'Zielpreis in € (optional; bei Kategorie: Alarm unter diesem Preis)' : 'Zielpreis in € (optional)';
  $('inOffer').style.display = ws ? 'none' : '';
}
$('inUrl').addEventListener('input', () => {
  const t = $('inUrl').value;
  const m = [['mueller.de', 'mueller'], ['mediamarkt.de', 'mediamarkt'], ['baur.de', 'baur'], ['amazon.', 'amazon']].find(([d]) => t.includes(d));
  if (m && m[1] !== shopSel) { shopSel = m[1]; renderShopPick(); }
});
$('addBtn').addEventListener('click', async () => {
  const r = await call('addProduct', { shop: shopSel, url: $('inUrl').value, name: $('inName').value, target: $('inTarget').value, offer: $('inOffer').value });
  toast(r.msg);
  if (r.ok) ['inUrl', 'inName', 'inTarget', 'inOffer'].forEach((i) => { $(i).value = ''; });
});

// ---------------- Produktliste ----------------
function shopOf(p) { return S.shops.find((s) => s.key === p.shop) || null; }

function renderFilters() {
  const box = $('filters');
  box.innerHTML = '';
  const all = S.products;
  const tabs = [[null, `Alle (${all.length})`], ['amazon', `🟡 Amazon (${all.filter((p) => p.shop === 'amazon').length})`],
    ...S.shops.map((s) => [s.key, `${s.emoji} ${s.label} (${all.filter((p) => p.shop === s.key).length})`])];
  for (const [k, l] of tabs) box.append(el('button', { class: filter === k ? 'on' : '', onclick: () => { filter = k; renderList(); renderFilters(); } }, l));
}

function describe(p) {
  const lines = [];
  if (p.kind === 'category') {
    if (!p.lastCheck) return 'Kategorie – noch nicht geprüft';
    let n = 0; let av = 0;
    try { const st = JSON.parse(p.catState || '{}'); n = Object.keys(st).length; av = Object.values(st).filter((x) => x.a).length; } catch (e) { /* leer */ }
    lines.push(`Kategorie: ${n} Produkte, davon ${av} online verfügbar`);
    if (p.target != null) lines.push('Alarm unter: ' + money(p.target));
    lines.push('Geprüft: ' + fmt(p.lastCheck));
    return lines.join('\n');
  }
  if (!p.lastCheck) lines.push('Noch nicht geprüft');
  else {
    if (p.target != null) lines.push('Zielpreis: ' + money(p.target));
    if (p.minPrice != null) lines.push('Tiefstpreis: ' + money(p.minPrice));
    lines.push('Geprüft: ' + fmt(p.lastCheck));
  }
  if (p.seller) lines.push('Verkauf: ' + p.seller);
  if (p.pinnedOffer) lines.push('📌 Angebot festgehalten' + (p.pinnedSeller ? ` (${p.pinnedSeller})` : '') + (p.offerId && p.offerId !== p.pinnedOffer ? ' – gerade liegt ein ANDERES Angebot in der Kaufbox' : ''));
  if (p.cartOffer) lines.push('OfferListingId: ' + p.cartOffer);
  if (p.lastBuyResult) lines.push('Letzter Auto-Kauf' + (p.lastBuyAttempt ? ` (${fmt(p.lastBuyAttempt)})` : '') + ': ' + p.lastBuyResult);
  return lines.join('\n');
}

async function armAutoBuy(p) {
  if (p.autoBuy) return call('setAutoBuy', p.asin, false);
  if (p.target == null) return 'Auto-Kauf braucht einen Zielpreis – erst „Zielpreis ändern“.';
  if (!S.settings.autoBuy) return 'Erst unter Einstellungen „Auto-Kauf erlauben“ anhaken und speichern.';
  const ws = shopOf(p);
  const text = `${p.name} wird automatisch und ohne Rückfrage bestellt, sobald der Preis bei höchstens ${money(p.target)} liegt (Versandkosten kommen ggf. dazu).\n\n` +
    (ws
      ? `• ${ws.label}: Artikel kommt in den Warenkorb, dann wird der GANZE Warenkorb bestellt – leere ihn vorher, wenn noch anderes drin liegt\n• Voreinstellungen aus deinem ${ws.label}-Konto (Lieferart, Adresse); Zahlung PayPal, wenn angeboten\n` +
        (!S.logins[ws.key].hasPw ? `• ⚠️ Kein ${ws.label}-Zugang gespeichert – Anmeldung klappt nur, wenn der Bot schon angemeldet ist\n` : '')
      : '• Menge 1, über „Jetzt kaufen“ (der übrige Warenkorb wird nicht mitbestellt)\n• Standard-Lieferadresse und -Zahlungsart deines Amazon-Kontos\n') +
    '• Nur EIN Versuch, danach schaltet sich Auto-Kauf für dieses Produkt aus\n• Läuft er auch auf dem Handy für dasselbe Produkt, kann doppelt bestellt werden!\n\nTeste vorher den Probelauf.';
  const ok = await modal('Auto-Kauf scharf schalten?', text, [['Abbrechen', false], ['Scharf schalten', true, 'primary']]);
  return ok ? call('setAutoBuy', p.asin, true) : '';
}

async function editTarget(p) {
  const inp = el('input', { value: p.target != null ? String(p.target).replace('.', ',') : '', placeholder: 'leer = kein Zielpreis' });
  const ok = await modal('Zielpreis ändern: ' + p.name, el('label', {}, 'Zielpreis in €', inp), [['Abbrechen', false], ['Speichern', true, 'primary']]);
  return ok ? call('setTarget', p.asin, inp.value.trim()) : '';
}

async function showCategory(p) {
  const items = await call('categoryItems', p.asin);
  if (!items.length) return toast('Noch keine Produkte gesehen – erst „Jetzt prüfen“.');
  const box = el('div');
  for (const it of items) {
    box.append(el('div', { class: 'catitem' },
      el('span', {}, (it.available ? '✅ ' : '❌ ') + it.name + ' – ' + money(it.price)),
      el('span', {},
        el('button', { onclick: () => call('open', it.url) }, 'Öffnen'), ' ',
        el('button', {
          class: 'primary',
          onclick: async () => {
            const inp = el('input', { placeholder: 'Zielpreis in € (optional)' });
            const ok = await modal('Einzeln überwachen?', el('div', {}, el('div', { class: 'msg' }, it.name), el('label', {}, 'Zielpreis', inp)), [['Abbrechen', false], ['Hinzufügen', true, 'primary']]);
            if (ok) toast((await call('addFromCategory', p.asin, it.url, it.name, inp.value.trim())).msg);
          },
        }, 'Einzeln überwachen'))));
  }
  modal(p.name + ' (✅ online verfügbar)', box, [['Schließen', true]]);
}

function row(p) {
  const ws = shopOf(p);
  const isCat = p.kind === 'category';
  const off = !p.active;
  const head = el('div', { class: 'head' },
    el('div', { class: 'name', title: 'Seite öffnen', onclick: () => call('open', p.pageUrl) }, (ws ? ws.emoji : '🟡') + ' ' + p.name),
    isCat ? null : el('div', { class: 'price' }, p.lastCheck ? (p.lastPrice != null ? money(p.lastPrice) : 'Preis ?') : ''));
  const flags = el('div', { class: 'flags' });
  if (p.paused) flags.append(el('span', { class: 'tag' }, '⏸ pausiert'));
  else if (off) flags.append(el('span', { class: 'tag' }, '⏹ Shop ausgeschaltet'));
  if (!isCat && p.lastCheck) flags.append(el('span', { class: 'tag ' + (p.available ? 'ok' : 'no') }, p.available ? 'verfügbar' : 'nicht verfügbar'));
  if (p.autoBuy) flags.append(el('span', { class: 'tag armed' }, '🛒 Auto-Kauf scharf'));
  const acts = el('div', { class: 'actions' });
  acts.append(act(p.paused ? '▶ Fortsetzen' : '⏸ Pausieren', () => call('togglePause', p.asin)));
  acts.append(act('⟳ Prüfen', () => call('checkOne', p.asin)));
  if (isCat) {
    acts.append(act('Produkte der Kategorie', () => showCategory(p)));
    acts.append(act('Kategorie öffnen', () => call('open', p.url)));
    acts.append(act('Zielpreis ändern', () => editTarget(p)));
  } else {
    if (ws) acts.append(act('Zur Produktseite', () => call('open', p.url)));
    else acts.append(act('In den Warenkorb (Link)', () => call('open', p.cartUrl)));
    acts.append(act('Automatisch in Warenkorb (Test)', () => { toast('Test läuft, bitte etwa 20–90 Sekunden warten …', 4000); return call('cartTest', p.asin); }));
    acts.append(act('Zur Kasse', () => call('open', ws ? ws.cartUrl : `https://www.${S.settings.domain}/gp/cart/view.html`, !ws)));
    acts.append(act('Zielpreis ändern', () => editTarget(p)));
    acts.append(act(p.autoBuy ? 'Auto-Kauf entschärfen' : 'Auto-Kauf scharf schalten', () => armAutoBuy(p), p.autoBuy ? 'danger' : ''));
    if (!ws) acts.append(act(p.pinnedOffer ? 'Angebot freigeben' : 'Aktuelles Angebot festhalten', () => call('togglePin', p.asin)));
    acts.append(act('Auto-Kauf Probelauf (bestellt nicht)', async () => {
      toast('Probelauf läuft, bis zu 3 Minuten … Es wird nichts bestellt.', 5000);
      const r = await call('dryRun', p.asin);
      modal('Probelauf: ' + p.name, r, [['Ablauf kopieren', 'copy'], ['OK', true, 'primary']]).then(async (v) => { if (v === 'copy') toast(await call('copyReport')); });
      return '';
    }));
    const pb = S.paybackPartners.find((x) => x.key === p.shop && x.on);
    if (pb) acts.append(act('💳 Über Payback kaufen', () => call('paybackOpen', p.shop, p.name)));
  }
  acts.append(act('Entfernen', async () => {
    const ok = await modal('Entfernen?', p.name, [['Abbrechen', false], ['Entfernen', true, 'primary']]);
    return ok ? call('remove', p.asin) : '';
  }, 'danger'));
  return el('div', { class: 'prod' + (off ? ' off' : '') }, head, flags, el('div', { class: 'meta' }, describe(p)), acts);
}

function renderList() {
  const box = $('list');
  box.innerHTML = '';
  const items = S.products.filter((p) => filter == null || p.shop === filter);
  if (!items.length) { box.append(el('div', { class: 'hint' }, 'Noch keine Produkte.')); return; }
  for (const key of ['amazon', ...S.shops.map((s) => s.key)]) {
    const g = items.filter((p) => p.shop === key);
    if (!g.length) continue;
    const ws = S.shops.find((s) => s.key === key);
    box.append(el('div', { class: 'group' }, ws ? `${ws.emoji} ${ws.label}` : '🟡 Amazon'));
    for (const p of g) box.append(row(p));
  }
}

// ---------------- Alarme ----------------
function renderAlerts() {
  const box = $('alertList');
  box.innerHTML = '';
  $('alertCount').textContent = S.alerts.length ? String(Math.min(S.alerts.length, 99)) : '';
  if (!S.alerts.length) { box.append(el('div', { class: 'hint' }, 'Noch keine Alarme.')); return; }
  for (const a of S.alerts) {
    const links = a.links || {};
    box.append(el('div', { class: 'alert' },
      el('div', { class: 't' }, fmt(a.time)),
      el('b', {}, a.title),
      el('div', { class: 'txt' }, a.text),
      el('div', { class: 'row' },
        links.page ? el('button', { onclick: () => call('open', links.page) }, 'Seite öffnen') : null,
        links.cart && links.cart !== links.page ? el('button', { onclick: () => call('open', links.cart) }, 'In den Warenkorb') : null,
        links.checkout ? el('button', { onclick: () => call('open', links.checkout, true) }, 'Zur Kasse') : null)));
  }
}
$('clearAlerts').addEventListener('click', () => call('clearAlerts'));

// ---------------- Einstellungen ----------------
function renderSettings() {
  const sw = $('shopSwitches');
  sw.innerHTML = '';
  const list = [{ key: 'amazon', label: '🟡 Amazon', enabled: S.amazonEnabled }, ...S.shops.map((s) => ({ key: s.key, label: s.emoji + ' ' + s.label, enabled: s.enabled }))];
  for (const s of list) {
    const cb = el('input', { type: 'checkbox' });
    cb.checked = s.enabled;
    cb.addEventListener('change', () => { call('setShopEnabled', s.key, cb.checked); toast(`${s.label}: Überwachung ${cb.checked ? 'an' : 'aus'}`); });
    sw.append(el('label', { class: 'cb' }, cb, s.label));
  }
  $('sAutostart').checked = S.autostart;
  if (settingsLoaded) return; // Eingaben nicht überschreiben, während getippt wird
  settingsLoaded = true;
  const s = S.settings;
  $('sDomain').value = s.domain;
  $('sAmazonInterval').value = s.amazonInterval;
  $('sInterval').value = s.interval;
  $('sDrop').value = s.drop;
  $('sWebhook').value = s.webhook;
  $('sAutoCart').checked = s.autoCart;
  $('sAutoBuy').checked = s.autoBuy;
  $('sPaypal').checked = s.preferPaypal;
  $('sOpen').checked = s.openOnAlert;
  $('sWatch').checked = s.watchBuy;
  $('sExternal').checked = s.externalBrowser;
  $('sAwake').checked = s.keepAwake;
}
$('saveSettings').addEventListener('click', async () => {
  toast(await call('saveSettings', {
    domain: $('sDomain').value, amazonInterval: $('sAmazonInterval').value, interval: $('sInterval').value,
    drop: $('sDrop').value, webhook: $('sWebhook').value, autoCart: $('sAutoCart').checked, autoBuy: $('sAutoBuy').checked,
    preferPaypal: $('sPaypal').checked, openOnAlert: $('sOpen').checked, watchBuy: $('sWatch').checked,
    externalBrowser: $('sExternal').checked, paybackAutoBuy: S.settings.paybackAutoBuy, keepAwake: $('sAwake').checked,
  }));
});
$('sAutostart').addEventListener('change', async () => toast(await call('setAutostart', $('sAutostart').checked)));
$('discordTest').addEventListener('click', async () => toast(await call('discordTest', $('sWebhook').value.trim())));
$('sendOffers').addEventListener('click', async () => toast(await call('sendAllOffers')));
document.addEventListener('click', (e) => {
  const b = e.target.closest('[data-login]');
  if (b) call('openInApp', b.dataset.login);
});

// ---------------- Zugänge ----------------
/** Zugangs-Karten neu aufbauen – außer während dort getippt wird. */
function loginsBusy() { return $('loginCards').contains(document.activeElement); }
function loginCard(key, title, hint, loginUrl) {
  const mail = el('input', { placeholder: 'E-Mail', value: S.logins[key].mail || '' });
  const pw = el('input', { type: 'password', placeholder: S.logins[key].hasPw ? 'Passwort (gespeichert – leer lassen zum Behalten)' : 'Passwort' });
  const status = el('div', { class: 'hint' }, S.logins[key].hasPw ? '🔒 Passwort gespeichert (verschlüsselt)' : 'Kein Passwort gespeichert');
  return el('div', { class: 'card' },
    el('h2', {}, title),
    el('p', { class: 'hint' }, hint),
    el('div', { class: 'grid2' }, mail, pw),
    status,
    el('div', { class: 'row' },
      act('Zugang speichern', async () => { const r = await call('saveLogin', key, mail.value, pw.value); pw.value = ''; return r; }, 'primary'),
      act('Zugang löschen', async () => { mail.value = ''; pw.value = ''; return call('clearLogin', key); }, 'danger'),
      act('Im Bot-Browser anmelden', () => call('openInApp', loginUrl))));
}
function renderLogins() {
  if (loginsBusy()) return;
  const box = $('loginCards');
  box.innerHTML = '';
  box.append(loginCard('amazon', '🟡 Amazon-Zugang',
    'Auto-Kauf und automatischer Warenkorb melden sich damit bei Bedarf selbst an. Bei Code/Captcha einmal „Im Bot-Browser anmelden“.',
    `https://www.${S.settings.domain}/gp/sign-in.html`));
  for (const s of S.shops) {
    box.append(loginCard(s.key, `${s.emoji} ${s.label}-Zugang`, `Für Auto-Kauf und Warenkorb bei ${s.label}.`, s.accountUrl));
  }
  box.append(el('div', { class: 'card' }, el('h2', {}, '🅿️ PayPal'),
    el('p', { class: 'hint' }, 'Das PayPal-Passwort wird nicht gespeichert. Einmal im Bot-Browser anmelden und „Eingeloggt bleiben“ anhaken – dann kann der Bot nach dem Bestellen „Jetzt zahlen“ drücken.'),
    act('Bei PayPal anmelden (Bot-Browser)', () => call('openInApp', 'https://www.paypal.com/signin'), 'primary')));
}

// ---------------- Payback ----------------
function renderPayback() {
  $('pAuto').checked = S.settings.paybackAutoBuy;
  const box = $('paybackCards');
  box.innerHTML = '';
  for (const p of S.paybackPartners) {
    const cb = el('input', { type: 'checkbox' });
    cb.checked = p.on;
    cb.addEventListener('change', () => call('setPayback', p.key, cb.checked));
    box.append(el('div', { class: 'card' },
      el('label', { class: 'cb' }, cb, `${p.label}: über Payback (Knopf „💳 Über Payback kaufen“ und Auto-Kauf über Payback)`),
      el('div', { class: 'row' },
        act(`Payback-Seite ${p.label} öffnen`, () => call('paybackOpen', p.key, '')),
        act('Weiterleitung testen', () => call('paybackTest', p.key)))));
  }
}
$('pAuto').addEventListener('change', () => call('setSetting', 'paybackAutoBuy', $('pAuto').checked));

// ---------------- Telegram ----------------
let tgLoaded = false;
function renderTelegram() {
  if (tgLoaded) return;
  tgLoaded = true;
  const s = S.settings;
  $('tgEnabled').checked = !!s.tgEnabled;
  $('tgChannels').value = s.tgChannels || '';
  $('tgKeywords').value = s.tgKeywords || '';
  $('tgExclude').value = s.tgExclude || '';
  $('tgMaxPrice').value = s.tgMaxPrice == null ? '' : String(s.tgMaxPrice).replace('.', ',');
  $('tgInterval').value = s.tgInterval || 1;
  document.querySelectorAll('input[name=tgAction]').forEach((r) => { r.checked = r.value === (s.tgAction || 'alert'); });
}
$('tgSave').addEventListener('click', async () => {
  const action = (document.querySelector('input[name=tgAction]:checked') || {}).value || 'alert';
  if ($('tgEnabled').checked && !$('tgKeywords').value.trim()) {
    const ok = await modal('Ohne Stichwörter?', 'Ohne Stichwörter meldet der Bot JEDEN Deal des Kanals – bei großen Kanälen sind das hunderte am Tag' +
      (action !== 'alert' ? ' und alle landen auf deiner Liste' + (action === 'cart' ? ' und im Warenkorb' : '') : '') + '.\n\nTrotzdem speichern?',
    [['Abbrechen', false], ['Trotzdem speichern', true, 'primary']]);
    if (!ok) return;
  }
  toast(await call('tgSave', {
    enabled: $('tgEnabled').checked, channels: $('tgChannels').value, keywords: $('tgKeywords').value,
    exclude: $('tgExclude').value, maxPrice: $('tgMaxPrice').value.trim(), interval: $('tgInterval').value, action,
  }), 8000);
});
$('tgTest').addEventListener('click', async () => {
  $('tgTest').disabled = true;
  toast('Lese Telegram … (passende Links werden aufgelöst, kann etwas dauern)', 4000);
  const r = await call('tgTest');
  $('tgTest').disabled = false;
  if (r.error) return modal('Telegram-Test', '⚠️ ' + r.error);
  const box = el('div');
  box.append(el('p', { class: 'hint' }, 'Test mit den GESPEICHERTEN Filtern (erst speichern, dann testen).'));
  for (const it of r.items) {
    const tg = it.target ? (it.target.shop === 'amazon' ? 'Amazon ' + it.target.asin : it.target.shop + ' ' + it.target.id) : (it.resolved ? 'anderer Shop' : '');
    box.append(el('div', { class: 'catitem' },
      el('span', {}, (it.ok ? '✅ ' : '⏭️ ') + `@${it.channel} #${it.id}: ${it.title}` + (it.price != null ? ' – ' + money(it.price) : '') + `\n(${it.why}${tg ? ' → ' + tg : ''})`),
      it.resolved ? el('button', { onclick: () => call('open', it.resolved) }, 'Öffnen') : el('span')));
  }
  return modal('Telegram-Test: letzte Beiträge', box, [['Schließen', true]]);
});

// ---------------- Daten ----------------
$('exportBtn').addEventListener('click', async () => toast(await call('exportProducts'), 9000));
$('importBtn').addEventListener('click', async () => toast(await call('importProducts', $('importSettings').checked), 12000));
$('copyReport').addEventListener('click', async () => toast(await call('copyReport')));

// ---------------- Laden ----------------
async function refresh() {
  S = await call('state');
  $('ver').textContent = 'Version ' + S.version;
  $('sigver').textContent = 'Version ' + S.version + ' (Windows)';
  renderBot();
  renderShopPick();
  renderFilters();
  renderList();
  renderAlerts();
  renderSettings();
  renderLogins();
  renderPayback();
  renderTelegram();
}

let pending = false;
window.bot.onChanged(() => {
  if (pending) return;
  pending = true;
  setTimeout(() => { pending = false; refresh(); }, 250);
});
window.bot.onToast((m) => toast(m, 9000));
refresh();

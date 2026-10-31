'use strict';
/**
 * Müller: Verfügbarkeit in Filialen per Postleitzahl.
 * Der Bot öffnet die Produktseite (unsichtbar oder sichtbar), drückt „Filialverfügbarkeit“ o. Ä., trägt die PLZ ein,
 * startet die Suche und liest die Filialliste (Name, Adresse, Status). Klappt ein Schritt nicht, wird die Seite
 * für den Seitenbericht aufgenommen.
 */
const B = require('./browser');
const Log = require('./log');
const { REPORT_JS } = require('./report');

const STEP_JS = String.raw`(function(PLZ){
function txt(e){return ((e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('title')||'')+'').replace(/\s+/g,' ').trim();}
function vis(e){if(!e)return false;var s=getComputedStyle(e);if(s.display==='none'||s.visibility==='hidden')return false;return e.offsetParent!==null||s.position==='fixed';}
function setv(el,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(el,v);
 el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
// Cookie-Hinweis weg
var all=document.querySelectorAll('button,a,[role=button]');
for(var i=0;i<all.length;i++){var t=txt(all[i]);if(/^(alle akzeptieren|alles akzeptieren|akzeptieren|zustimmen|einverstanden)$/i.test(t)&&vis(all[i])){all[i].click();return JSON.stringify({s:'cookie'});}}
var ST=/(nicht |kein |aus)?(verfügbar|vorrätig|auf lager|lagernd|ausverkauft|vergriffen|nicht lieferbar|wenige|letzte stück|reservierbar|abholbereit)/i;
// 1) Ergebnisliste vorhanden? Filial-Einträge mit PLZ (5 Ziffern) und Status
var cand=document.querySelectorAll('li,article,[role=listitem],[class*=store i],[class*=filiale i],[class*=branch i],[class*=shop-item i],[class*=location i],tr');
var out=[],seen={};
for(var k=0;k<cand.length;k++){var c=cand[k];if(!vis(c))continue;var x=(c.innerText||'').replace(/[ \t]+/g,' ').trim();
 if(x.length<15||x.length>500||!/\b\d{5}\b/.test(x)||!ST.test(x))continue;
 // nur die kleinsten Einträge (keine Container mit mehreren Filialen)
 var inner=c.querySelectorAll('li,article,[role=listitem],[class*=store i],[class*=filiale i],[class*=branch i]');var nested=false;
 for(var n=0;n<inner.length;n++){var y=(inner[n].innerText||'');if(/\b\d{5}\b/.test(y)&&ST.test(y)&&y.length<x.length){nested=true;break;}}
 if(nested)continue;
 var lines=x.split('\n').map(function(l){return l.trim();}).filter(Boolean);
 var name=lines[0];var addr=lines.slice(1).filter(function(l){return /\b\d{5}\b/.test(l)||/str\.|straße|platz|weg|allee|gasse/i.test(l);}).slice(0,2).join(', ');
 var m=x.match(ST);var status=m?m[0]:'';
 var stLine=lines.filter(function(l){return ST.test(l);})[0]||status;
 var av=/(verfügbar|vorrätig|auf lager|lagernd|wenige|letzte stück|reservierbar|abholbereit)/i.test(stLine)&&!/(nicht|kein|ausverkauft|vergriffen)/i.test(stLine);
 var key=name+'|'+addr;if(seen[key])continue;seen[key]=1;
 out.push({name:name.slice(0,80),addr:addr.slice(0,120),status:stLine.slice(0,60),a:av});}
if(out.length)return JSON.stringify({s:'ok',stores:out.slice(0,15)});
// 2) PLZ-Feld sichtbar? → eintragen und suchen
var ins=document.querySelectorAll('input');
for(var j=0;j<ins.length;j++){var e=ins[j];if(!vis(e))continue;var ty=(e.type||'text').toLowerCase();if(['text','search','tel','number',''].indexOf(ty)<0)continue;
 var lab=((e.placeholder||'')+' '+(e.name||'')+' '+(e.id||'')+' '+(e.getAttribute('aria-label')||'')+' '+((e.labels&&e.labels[0]&&e.labels[0].innerText)||'')).toLowerCase();
 if(!/plz|postleitzahl|ort|stadt|adresse|standort|filiale|zip|location/.test(lab))continue;
 if(e.value===PLZ){
  // schon eingetragen → Suche auslösen
  var box=e.closest('form,[role=dialog],[class*=modal i],[class*=layer i],[class*=overlay i]')||e.parentElement.parentElement||document;
  var sb=null,bs=box.querySelectorAll('button,[role=button],input[type=submit]');
  for(var b=0;b<bs.length;b++){if(vis(bs[b])&&/suchen|finden|anzeigen|los|ok|filialen|prüfen|^$/i.test(txt(bs[b]))&&bs[b]!==e){sb=bs[b];break;}}
  e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
  e.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
  if(sb)sb.click();else if(e.form&&e.form.requestSubmit)e.form.requestSubmit();
  return JSON.stringify({s:'search',btn:sb?txt(sb).slice(0,30):'Enter'});}
 e.focus();setv(e,PLZ);return JSON.stringify({s:'typed'});}
// 3) Knopf „Filialverfügbarkeit“ o. Ä. drücken
var RE=/filial(verfügbarkeit|bestand|suche|finder)|(in|im|aus) (der |einer |deiner |meiner )?(filiale|markt)|filiale (finden|suchen|wählen|prüfen|anzeigen|ändern)|verfügbarkeit (in|im|prüfen)|im markt verfügbar|vor ort|markt (finden|wählen)/i;
for(var q=0;q<all.length;q++){var t2=txt(all[q]);if(t2&&t2.length<70&&RE.test(t2)&&vis(all[q])){all[q].click();return JSON.stringify({s:'opened',btn:t2.slice(0,40)});}}
return JSON.stringify({s:'wait'});})`;

/**
 * Filialen für ein Müller-Produkt abfragen. Gibt [{name, addr, status, a}] zurück; wirft bei Misserfolg.
 * visible: sichtbares Fenster (zum Zuschauen), sonst unsichtbar.
 */
async function check(url, plz, { visible = false } = {}) {
  const w = visible ? B.watchWindow('Müller-Filialen …', require('./amazon').reportOf) : B.newWindow({ show: false });
  const wc = w.webContents;
  const title = (s) => { if (!w.isDestroyed()) w.setTitle('Müller-Filialen (PLZ ' + plz + '): ' + s); };
  Log.add('Filial-Check ' + plz + ': ' + url);
  let last = '';
  let typed = 0;
  let searched = 0;
  let opened = 0;
  try {
    B.load(wc, url);
    await B.sleep(3000);
    const end = Date.now() + 60000;
    while (Date.now() < end) {
      if (w.isDestroyed()) throw new Error('Fenster geschlossen');
      const o = B.parse(await B.js(wc, STEP_JS + '(' + JSON.stringify(String(plz)) + ')'));
      if (o.s !== last) Log.add('Filiale: ' + JSON.stringify(o).slice(0, 140));
      last = o.s || '';
      if (o.s === 'ok') {
        title(`${o.stores.filter((x) => x.a).length} von ${o.stores.length} Filialen haben es`);
        return o.stores;
      }
      if (o.s === 'opened' && ++opened > 3) break;
      if (o.s === 'typed' && ++typed > 3) break;
      if (o.s === 'search' && ++searched > 4) break;
      title({ opened: 'Filialsuche geöffnet …', typed: 'PLZ eingetragen …', search: 'suche …', cookie: 'Cookie-Hinweis …' }[o.s] || 'warte …');
      await B.sleep(o.s === 'search' ? 3000 : 1500);
    }
    const rep = await B.js(wc, REPORT_JS);
    if (rep) Log.snapshot(rep);
    throw new Error(last === 'wait' ? 'Kein Knopf für die Filialverfügbarkeit gefunden' : last === 'search' ? 'Keine Filialliste erkannt' : 'Filialsuche hat nicht geklappt (' + last + ')');
  } finally {
    if (!visible && !w.isDestroyed()) w.destroy();
  }
}

module.exports = { check, STEP_JS };

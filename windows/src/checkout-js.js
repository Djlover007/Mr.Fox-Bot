'use strict';

/** Seiten-Skripte für die Web-Shop-Kasse (aus ShopBuyer.kt der Android-App übernommen). */

const PAYPAL_SELECT_JS_BODY = String.raw`
function lab(e){return e.tagName==='IMG'?(e.alt||''):txt(e);}
function radioText(r){var t='';if(r.id){var l=document.querySelector('label[for="'+r.id+'"]');if(l)t=txt(l)+' '+(l.querySelector('img[alt]')?l.querySelector('img[alt]').alt:'');}
 var L=r.closest('label');if(L)t+=' '+txt(L)+' '+(L.querySelector('img[alt]')?L.querySelector('img[alt]').alt:'');
 if(!t.trim()){var b=r.parentElement;for(var up=0;up<4&&b;up++){if(b.querySelectorAll('input[type=radio],[role=radio]').length===1){t=(b.innerText||'')+' '+[].map.call(b.querySelectorAll('img[alt]'),function(i){return i.alt;}).join(' ');}b=b.parentElement;}}
 if(r.getAttribute('aria-label'))t+=' '+r.getAttribute('aria-label');
 if(r.getAttribute('value'))t+=' '+r.getAttribute('value');
 return t.replace(/\s+/g,' ').trim();}
function on(r){return r.checked||r.getAttribute('aria-checked')==='true'||r.getAttribute('aria-selected')==='true';}
var rs=document.querySelectorAll('input[type=radio],[role=radio]');
var pp=null,list=[];
for(var i=0;i<rs.length;i++){var r=rs[i];var t=radioText(r);list.push((on(r)?'[x] ':'[ ] ')+t.slice(0,25));
 if(!pp&&/paypal/i.test(t)&&!/express/i.test(t))pp=r;}
if(!pp){
 // Karten mit „wählen“-Knopf je Zahlungsart (z. B. BAUR: data-testid inactive-trigger / active-trigger)
 function isTrig(e){var id=e.getAttribute('data-testid')||'';return /^(in)?active-trigger$/.test(id)||((e.tagName==='BUTTON'||e.getAttribute('role')==='button')&&/^(wählen|auswählen)$/i.test(txt(e)));}
 function hasPP(c){var tc=(c.textContent||'');return (/paypal/i.test(tc)||!!c.querySelector('img[alt*=paypal i],[class*=paypal i]'))&&!/paypal express|später bezahlen mit paypal|paypal ratenzahlung/i.test(tc);}
 var trs=[].filter.call(document.querySelectorAll('button,[role=button],[data-testid]'),isTrig);
 for(var m=0;m<trs.length;m++){var tr=trs[m];var cc=tr;
  for(var up2=0;up2<8&&cc.parentElement;up2++){cc=cc.parentElement;
   var nT=[].filter.call(cc.querySelectorAll('button,[role=button],[data-testid]'),isTrig).length;if(nT>1)break;
   if(hasPP(cc)){if((tr.getAttribute('data-testid')||'')==='active-trigger')return 'already';
    if(vis(tr)){tr.click();return 'clicked-card';}break;}}}
 // Kein Auswahlfeld: PayPal als anklickbare Kachel (z. B. BAUR). Nie Links in Kopf-/Fußzeile, nie „Express“.
 var cand=document.querySelectorAll('button,[role=button],[role=option],[role=tab],li,label,div,span');var best=null,bl=1e9;
 for(var k=0;k<cand.length;k++){var c=cand[k];if(c.closest('footer,header,nav'))continue;var a=c.closest('a[href]');
  if(a&&!/^(#|javascript:)/i.test(a.getAttribute('href')||''))continue;
  var ct=(txt(c)+' '+[].map.call(c.querySelectorAll('img[alt]'),function(i){return i.alt;}).join(' ')).replace(/\s+/g,' ').trim();
  if(!/paypal/i.test(ct)||/express|ratenzahlung|später bezahlen|pay later/i.test(ct)||ct.length>80||!vis(c))continue;
  var clk=c.tagName==='BUTTON'||c.tagName==='LABEL'||/^(button|option|tab)$/.test(c.getAttribute('role')||'')||getComputedStyle(c).cursor==='pointer';
  if(clk&&ct.length<bl){best=c;bl=ct.length;}}
 if(best){var sel=function(e){for(var u=0;u<4&&e;u++){if(e.getAttribute('aria-checked')==='true'||e.getAttribute('aria-selected')==='true'||e.getAttribute('aria-pressed')==='true'||/(^|[-_ ])(selected|active|checked)/i.test(e.getAttribute('class')||''))return true;e=e.parentElement;}return false;};
  if(sel(best))return 'already';best.click();return 'clicked-tile:'+txt(best).slice(0,30);}
 // PayPal versteckt (zugeklappter Bereich)? → den zugehörigen Aufklapp-Knopf drücken
 var hid=[].filter.call(document.querySelectorAll('body *:not(script):not(style)'),function(e){return e.children.length<3&&/paypal/i.test(e.textContent+' '+(e.alt||''))&&!/express/i.test(e.textContent)&&!e.closest('footer,header,nav,script')&&!vis(e);});
 if(hid.length){var tg=document.querySelectorAll('[aria-expanded=false],summary');
  for(var g=0;g<tg.length;g++){var x=tg[g];if(!vis(x))continue;var ctl=x.getAttribute('aria-controls');var reg=ctl?document.getElementById(ctl):(x.tagName==='SUMMARY'?x.parentElement:x.parentElement);
   if(reg&&/paypal/i.test(reg.textContent)){x.click();return 'expand:'+txt(x).slice(0,30);}}}
 var more=btn(/weitere zahlungsarten|andere zahlungsart|alle zahlungsarten|mehr zahlungsarten|zahlungsart wechseln|sofort bezahlen|direkt bezahlen|online bezahlen/i);
 if(more&&!/paypal express/i.test(txt(more))&&(!more.getAttribute('aria-expanded')||more.getAttribute('aria-expanded')==='false'))
  {more.click();return 'expand:'+txt(more).slice(0,30);}
 return 'none|'+list.join(' · ')+(hid.length?' (PayPal versteckt: '+hid.length+')':'');}
if(on(pp))return 'already';
// wie ein Finger: zuerst die Beschriftung antippen, sonst das Feld selbst
var l=(pp.id&&document.querySelector('label[for="'+pp.id+'"]'))||pp.closest('label');
if(l&&vis(l)){l.click();}else{pp.click();}
if(!on(pp))pp.click();
if(!on(pp)){pp.checked=true;pp.dispatchEvent(new Event('input',{bubbles:true}));pp.dispatchEvent(new Event('change',{bubbles:true}));}
return on(pp)?'clicked':'clicked-unconfirmed';})()`;

const PAYMENT_CHANGE_JS_BODY = String.raw`
var head=/^(zahlungsart|zahlungsmethode|bezahlart|bezahlmethode|zahlung|zahlungsweise|deine zahlungsart|ihre zahlungsart)\s*:?$/i;
var chg=/^(ändern|bearbeiten|auswählen|zahlungsart ändern|zahlungsart auswählen|andere zahlungsart( wählen)?)$/i;
var other=/lieferadresse|lieferservice|rechnungsadresse|rechnungsanschrift/i;
function isEdit(b){var t=txt(b);if(t&&chg.test(t))return true;
 var al=((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('title')||'')+' '+(b.getAttribute('class')||'')+' '+(b.getAttribute('data-testid')||''));
 if(/bearbeit|ändern|edit|pencil|stift/i.test(al))return true;
 if(!t&&(b.tagName==='BUTTON'||b.tagName==='A'||b.getAttribute('role')==='button')&&b.querySelector('svg,img'))return true;
 // anklickbare Grafik ohne Text (z. B. Stift-Symbol als <div>/<span>/<svg> mit Mauszeiger „Hand“)
 if(!t&&(b.tagName==='svg'||b.tagName==='SVG'||b.tagName==='IMG'||b.querySelector('svg,img'))&&getComputedStyle(b).cursor==='pointer')return true;
 return false;}
function fire(b){var c=b.closest('button,a,[role=button]')||b;
 ['pointerdown','mousedown','pointerup','mouseup'].forEach(function(n){try{c.dispatchEvent(new (n.indexOf('pointer')===0&&window.PointerEvent?PointerEvent:MouseEvent)(n,{bubbles:true,cancelable:true}));}catch(x){}});
 if(typeof c.click==='function')c.click();else c.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));}
var eds=document.querySelectorAll('[data-testid=card-action-edit],[data-testid*=edit i]');
for(var q=0;q<eds.length;q++){var ed=eds[q];var cb=ed.parentElement;
 for(var w=0;w<6&&cb;w++){var ct=(cb.innerText||'').replace(/\s+/g,' ').trim();
  if(ct){if(other.test(ct)&&!/^zahlungsart/i.test(ct))break;
   if(/^(zahlungsart|zahlungsmethode|bezahlart)\b/i.test(ct)){if(/paypal/i.test(ct)&&!/paypal (express|checkout)/i.test(ct))return 'paypal';fire(ed);return 'clicked';}}
  cb=cb.parentElement;}}
var els=document.querySelectorAll('h1,h2,h3,h4,h5,legend,dt,span,div,p,strong,b,label');
for(var i=0;i<els.length;i++){var e=els[i];var t=txt(e);if(!t||t.length>30||!head.test(t))continue;
 var box=e.parentElement;
 for(var up=0;up<6&&box;up++){var bt=(box.innerText||'').replace(/\s+/g,' ');
  if(bt.length>600)break;
  if(/paypal/i.test(bt)&&!/paypal (express|checkout)/i.test(bt))return 'paypal';
  if(other.test(bt))break; // nicht in den Abschnitt Lieferadresse o. Ä. geraten
  var bs=box.querySelectorAll('button,a,[role=button],[aria-label],[title],svg,img,[class*="edit" i],[class*="pencil" i],div,span');
  for(var j=0;j<bs.length;j++){if(isEdit(bs[j])&&vis(bs[j])){var tg=bs[j];var ic=tg.querySelector&&tg.querySelector('svg,img');if(ic&&!txt(tg))tg=ic;fire(tg);return 'clicked';}}
  box=box.parentElement;}
 var pt=(e.parentElement&&e.parentElement.parentElement&&e.parentElement.parentElement.innerText||'').replace(/\s+/g,' ').trim().slice(0,80);
 return 'none:'+pt;}
return 'none';})()`;

const PAYPAL_PAY_JS_BODY = String.raw`
var pw=document.querySelector('input[type=password]');
var em=document.querySelector('#email,input[name=login_email],input[type=email]');
var b=btn(/^(jetzt zahlen|zahlung abschließen|zustimmen und weiter|zustimmen und zahlen|weiter zur überprüfung|weiter|jetzt kaufen|kaufen|pay now|continue|complete purchase|agree (&|and) continue)$/i);
if((pw&&vis(pw))||(em&&vis(em)&&!em.value))return 'login';
if(b){b.click();return 'clicked:'+txt(b);}
return 'wait';})()`;

const DECLINE_JS_BODY = String.raw`
var re=/ich möchte (mein gerät|meine geräte|das gerät|den artikel) nicht (schützen|absichern|versichern)|ohne (geräte)?schutz|keinen? (geräte)?schutz|keine (garantie|versicherung|absicherung|garantieverlängerung)|ohne (garantie|versicherung|absicherung)|nicht absichern|nein,? danke/i;
function radios(el){return el.querySelectorAll('input[type=radio],[role=radio]');}
var els=document.querySelectorAll('label,button,[role=radio],[role=button],span,div,p');
for(var i=0;i<els.length;i++){var e=els[i];if(e.children.length>4)continue;var t=txt(e);
 if(!t||t.length>90||!re.test(t))continue;
 // nur das kleinste passende Element (nicht den Bereich, der auch das Bezahl-Angebot enthält)
 var deeper=false;for(var c=0;c<e.children.length;c++){if(re.test(txt(e.children[c]))){deeper=true;break;}}
 if(deeper)continue;
 if(e.tagName==='BUTTON'||e.getAttribute('role')==='button'){if(!vis(e))continue;e.click();return 'btn';}
 var r=null;var box=e;for(var up=0;up<6&&box;up++){
  if(box.matches&&box.matches('input[type=radio],[role=radio]')){r=box;break;}
  var rs=radios(box);if(rs.length===1){r=rs[0];break;}if(rs.length>1)break;box=box.parentElement;}
 if(r&&(r.checked||r.getAttribute('aria-checked')==='true'))return 'already';
 if(!vis(e)&&!(r&&vis(r)))continue;
 (r||e).click();return 'clicked';}
return 'none';})()`;

const PICK_JS = String.raw`
function dlg(){var ds=document.querySelectorAll('[role=dialog],[aria-modal=true],dialog[open],[class*="modal" i],[class*="drawer" i],[class*="sheet" i],[class*="overlay" i],[class*="layer" i]');
 var best=null;for(var i=0;i<ds.length;i++){var d=ds[i];if(!vis(d))continue;
  if(d.querySelector('button,a,[role=button],input[type=submit]'))best=d;}return best;}
function pickIn(root,re,ex){var els=root.querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]');
 for(var i=0;i<els.length;i++){var e=els[i];var t=txt(e);if(!t||t.length>60||EX.indexOf(t)>=0)continue;
  if(re.test(t)&&!(ex&&ex.test(t))&&vis(e))return e;}return null;}
function roots(){var d=dlg();return d?[d,document]:[document];}
`;

const PAY_SECTION_JS = String.raw`
function maxQty(){var b=(document.body&&document.body.innerText)||'';var re=/(anzahl|menge|stückzahl)\s*:?\s*(\d+)/gi;var m,mx=0;
 while((m=re.exec(b))!==null){var n=parseInt(m[2],10);if(n>mx&&n<100)mx=n;}
 var sels=document.querySelectorAll('select[name*=qty i],select[name*=quant i],select[name*=menge i],input[name*=qty i],input[name*=quant i]');
 for(var i=0;i<sels.length;i++){var n2=parseInt(sels[i].value,10);if(n2>mx&&n2<100)mx=n2;}
 return mx;}
function paySec(){var head=/^(zahlungsart|zahlungsmethode|bezahlart|bezahlmethode|zahlung|zahlungsweise)\s*:?$/i;
 var els=document.querySelectorAll('h1,h2,h3,h4,h5,legend,dt,span,div,p,strong,b,label');
 for(var i=0;i<els.length;i++){var e=els[i];var t=txt(e);if(!t||t.length>30||!head.test(t))continue;
  var box=e.parentElement;for(var up=0;up<5&&box;up++){var bt=(box.innerText||'').replace(/\s+/g,' ').trim();
   if(bt.length>t.length+8)return bt.slice(0,160);box=box.parentElement;}}
 return '';}
`;

const FINAL_RE = `/zum genannten preis bestellen|jetzt zahlungspflichtig bestellen|bestellung abschicken|zahlungspflichtig bestellen|kostenpflichtig bestellen|zahlungspflichtig kaufen|jetzt kaufen|^kaufen$|kaufen und bezahlen|bestellung absenden|jetzt bestellen/i`;

const NEXT_RE = `/^(weiter|weiter zu.*|weiter zur .*|zur zahlung|zur bestellübersicht|zur übersicht|bestellung prüfen|übernehmen|speichern und weiter|auswählen und weiter|bestätigen|fortfahren|speichern|auswahl speichern|zahlungsart übernehmen|zahlungsart speichern)$/i`;

module.exports = { PAYPAL_SELECT_JS_BODY, PAYMENT_CHANGE_JS_BODY, PAYPAL_PAY_JS_BODY, DECLINE_JS_BODY, PICK_JS, PAY_SECTION_JS, FINAL_RE, NEXT_RE };

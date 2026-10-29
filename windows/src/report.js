'use strict';
/** Seitenbericht für die Fehlersuche (gleich wie in der Android-App). */
const REPORT_JS = String.raw`(function(){
function t(e){return ((e.innerText||e.value||'')+'').replace(/\s+/g,' ').trim().slice(0,60);}
function a(e,n){var v=e.getAttribute(n);return v?(' '+n+'="'+v.slice(0,40)+'"'):'';}
function seen(e){var s=getComputedStyle(e);return s.display!=='none'&&s.visibility!=='hidden';}
var out=['URL: '+location.href,'Titel: '+document.title];
var hs=document.querySelectorAll('h1,h2,h3,h4,legend');out.push('--- Überschriften');
for(var i=0;i<hs.length&&i<30;i++)if(seen(hs[i]))out.push(hs[i].tagName+': '+t(hs[i]));
var rs=document.querySelectorAll('input[type=radio],input[type=checkbox],[role=radio],[role=option],[aria-checked],[aria-selected]');out.push('--- Auswahlfelder');
for(var j=0;j<rs.length&&j<40;j++){var r=rs[j];var lab='';if(r.id){var l=document.querySelector('label[for="'+r.id+'"]');if(l)lab=t(l);}
 var L=r.closest('label');if(!lab&&L)lab=t(L);
 var box=r.parentElement;var bt=box?t(box):'';
 out.push(r.tagName+(r.type?'['+r.type+']':'')+a(r,'id')+a(r,'name')+a(r,'value')+a(r,'role')+' checked='+(r.checked||r.getAttribute('aria-checked')||r.getAttribute('aria-selected'))+(seen(r)?'':' (unsichtbar)')+' | Label: '+lab+' | Umgebung: '+bt);}
var bs=document.querySelectorAll('button,a,[role=button],input[type=submit]');out.push('--- Knöpfe');
var n=0;for(var k=0;k<bs.length&&n<60;k++){var b=bs[k];if(!seen(b))continue;var tx=t(b);var al=a(b,'aria-label')+a(b,'title')+a(b,'data-testid');
 if(!tx&&!al)al=' class="'+((b.getAttribute('class')||'').slice(0,40))+'"';out.push(b.tagName+': '+tx+al);n++;}
out.push('--- Klickbare Symbole (ohne Text)');
var cs=document.querySelectorAll('div,span,svg,img,i');var nc=0;
for(var c=0;c<cs.length&&nc<25;c++){var el=cs[c];if(t(el)||!seen(el))continue;if(getComputedStyle(el).cursor!=='pointer')continue;
 if(el.parentElement&&getComputedStyle(el.parentElement).cursor==='pointer'&&!t(el.parentElement))continue;
 var near='';var up2=el.parentElement;for(var z=0;z<4&&up2&&!near;z++){near=t(up2);up2=up2.parentElement;}
 out.push(el.tagName+a(el,'class').slice(0,50)+a(el,'data-testid')+' | in der Nähe: '+near.slice(0,40));nc++;}
out.push('--- Abschnitte (Text)');
var hd=document.querySelectorAll('h2,h3,h4,div,span,p');var ns=0;
for(var d=0;d<hd.length&&ns<8;d++){var hx=hd[d];var ht=t(hx);if(!/^(zahlungsart|lieferservice|lieferadresse|rechnungsadresse)$/i.test(ht))continue;
 var bx=hx.parentElement;for(var y=0;y<4&&bx;y++){var bxt=(bx.innerText||'').replace(/\s+/g,' ').trim();if(bxt.length>ht.length+8){out.push(ht+': '+bxt.slice(0,140));break;}bx=bx.parentElement;}ns++;}
var nd=document.getElementById('__NEXT_DATA__');if(nd){var js=nd.textContent;var re2=/.{0,80}paypal.{0,80}/gi;var mm;var nn=0;out.push('--- Seitendaten mit „paypal“');
 while((mm=re2.exec(js))!==null&&nn<6){out.push(mm[0].replace(/"(firstName|lastName|street|email|phone|city|zip)[^,]*,/gi,''));nn++;}}
var pp=[].filter.call(document.querySelectorAll('body *:not(script)'),function(e){return e.children.length===0&&/paypal/i.test((e.innerText||e.alt||e.getAttribute('aria-label')||''));});
var hp=[].filter.call(document.querySelectorAll('body *:not(script):not(style)'),function(e){return e.children.length<3&&/paypal/i.test(e.textContent+' '+(e.alt||''))&&!e.getClientRects().length;});
out.push('--- Versteckt mit „PayPal“ ('+hp.length+')');
for(var hq=0;hq<hp.length&&hq<5;hq++){var he=hp[hq];var hc=[];var hx2=he;for(var hu=0;hu<6&&hx2;hu++){hc.push(hx2.tagName+(hx2.getAttribute('aria-expanded')?'[exp='+hx2.getAttribute('aria-expanded')+']':'')+(hx2.getAttribute('class')?'.'+hx2.getAttribute('class').split(' ')[0].slice(0,25):''));hx2=hx2.parentElement;}
 out.push(hc.join(' < ')+' | '+(he.textContent||he.alt||'').replace(/\s+/g,' ').trim().slice(0,60));}
var ex=document.querySelectorAll('[aria-expanded],summary,details');out.push('--- Aufklappbare Bereiche ('+ex.length+')');
for(var ei=0;ei<ex.length&&ei<15;ei++)out.push(ex[ei].tagName+' exp='+ex[ei].getAttribute('aria-expanded')+' | '+t(ex[ei]));
out.push('--- Seitentext (Anfang)');out.push(((document.querySelector('main')||document.body).innerText||'').replace(/\s+/g,' ').slice(0,900));
out.push('--- Elemente mit „PayPal“ ('+pp.length+')');
for(var q=0;q<pp.length&&q<10;q++){var e=pp[q];var chain=[];var x=e;for(var u=0;u<4&&x;u++){chain.push(x.tagName+(x.getAttribute('role')?'[role='+x.getAttribute('role')+']':'')+(x.getAttribute('class')?'.'+x.getAttribute('class').split(' ')[0].slice(0,25):''));x=x.parentElement;}
 out.push(chain.join(' < ')+' | '+t(e));}
return out.join('\n').slice(0,7000);})()`;

module.exports = { REPORT_JS };

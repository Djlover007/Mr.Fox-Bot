# Mr.Fox Bot (Android)

Überwacht **Amazon**, **Müller** (mueller.de), **MediaMarkt** (mediamarkt.de) und **BAUR** (baur.de).

Android-App, die Preis und Verfügbarkeit von Amazon-Produkten im Hintergrund überwacht und dich
per Benachrichtigung und optional per Discord informiert (Zielpreis erreicht, Preis um X % gefallen,
wieder verfügbar).

## Funktionen
- Produkte per Amazon-Link oder ASIN, optional mit Zielpreis
- Hintergrundprüfung (mind. alle 15 Minuten)
- Benachrichtigung mit Buttons „In den Warenkorb“ und „Zur Kasse“
- Discord-Webhook (optional) mit Testbutton
- Automatische Amazon-Anmeldung: Zugang (E-Mail/Passwort) verschlüsselt im Android-Tresor; die App meldet
  sich im App-Fenster, beim Auto-Kauf und beim automatischen Warenkorb selbst an (auch wenn Amazon vor der
  Kasse erneut das Passwort will). Nicht automatisch gehen: Captcha, Bestätigungscode (Zwei-Schritt-
  Verifizierung) und Freigabe per Amazon-App/SMS – dann einmal über „In der App anmelden“ selbst bestätigen.
- „Bei Amazon anmelden (Chrome)“ öffnet die Anmeldung in Chrome (Chromes Passwortmanager füllt aus).
  Auto-Kauf und automatischer Warenkorb nutzen davon unabhängig die Anmeldung der App.
- Optional: Artikel bei Alarm automatisch in den Warenkorb legen (Testbutton pro Produkt)
- „Zur Kasse“ öffnet nur den Bezahlvorgang, das endgültige „Jetzt kaufen“ tippst du selbst
- Optional: **Auto-Kauf** – bestellt ein Produkt selbstständig, sobald der Zielpreis erreicht ist
  (mit Probelauf und nur einem Versuch, siehe unten)

## Läuft der Bot? / Dauerbetrieb (Amazon alle 2 Minuten)
- Ganz oben in der App steht der Zustand: 🟢 läuft · 🟠 soll laufen, wurde aber beendet · 🔴 aus –
  mit letzter und nächster Prüfung und Knopf **„Dauerbetrieb starten/stoppen“**.
- Im Dauerbetrieb zeigt eine **feste Benachrichtigung** „🟢 Mr.Fox Bot läuft“ (mit Stopp-Knopf).
- Amazon wird dann im **Amazon-Prüfintervall** geprüft (Standard 2 Min., min. 1), Müller/MediaMarkt im
  eigenen Intervall (min. 5 Min.). Ohne Dauerbetrieb erlaubt Android nur etwa alle 15 Minuten.
- Nach einem Neustart des Handys startet der Dauerbetrieb von selbst wieder.
- **Ein/Aus je Shop:** Schalter oben für Amazon, Müller und MediaMarkt.
- **Ein/Aus je Produkt:** „⏸ Überwachung pausieren“ / „▶ fortsetzen“ beim Produkt bzw. bei der Kategorie.
- **Akku-Optimierung ausschalten** (Knopf oben), sonst kann Android den Dienst beenden.
- Hinweis: Der Dauerbetrieb hält das Handy wach und braucht mehr Akku. Sehr kurze Amazon-Intervalle
  mit vielen Produkten können zu Captchas führen – dann das Intervall erhöhen.

## Müller (mueller.de)
- Beim Hinzufügen oben **Amazon** oder **Müller** wählen (ein eingefügter mueller.de-Link stellt automatisch um).
- **Müller-Produkt** (Link `mueller.de/p/…`): Preis und Verfügbarkeit („In den Warenkorb“ vorhanden =
  verfügbar), Zielpreis-, Preisfall- und Wieder-verfügbar-Alarme, automatischer Warenkorb, Auto-Kauf
  und Probelauf – wie bei Amazon.
- **Müller-Kategorie** (Link `mueller.de/c/…`, z. B. Spielekonsolen): meldet neue Produkte, Produkte die
  wieder online verfügbar sind, und – mit Zielpreis – verfügbare Produkte unter diesem Preis. Die erste
  Prüfung merkt sich nur den Stand. „Produkte der Kategorie anzeigen“ listet alles; Antippen übernimmt
  ein Produkt als einzelnes Müller-Produkt (z. B. für Auto-Kauf).
- Müller prüft Besucher mit einer „Sicherheitsprüfung“. Deshalb lädt die App Müller-Seiten in einem
  unsichtbaren Browserfenster (dauert etwas länger als Amazon).
- **Auto-Kauf bei Müller:** Produkt in den Warenkorb → Warenkorb → „Zur Kasse“ → Anmeldung mit dem
  gespeicherten **Müller-Zugang** → „Weiter“-Schritte mit den Voreinstellungen deines Kontos →
  „Zahlungspflichtig bestellen“. Achtung: Dabei wird der **ganze Müller-Warenkorb** bestellt.
  Erst den Probelauf testen (am besten mit „sichtbarem Fenster“), er hält vor dem Bestellknopf an.
- Zusatzangebote wie Geräteschutz/Garantie/Versicherung lehnt der Auto-Kauf immer ab („Ich möchte mein
  Gerät nicht schützen“, „Nein, danke“); lässt sich die Ablehnung nicht sicher auswählen, bricht er ab.
- Die Müller-Knöpfe werden über ihre Beschriftung gefunden. Ändert Müller Texte oder Ablauf (oder
  verlangt die Zahlungsart eine Weiterleitung, z. B. PayPal), kann der Auto-Kauf hängen bleiben.
- Die Liste lässt sich oben nach Sparte filtern: Alle / Amazon / Müller / MediaMarkt / BAUR.

## MediaMarkt (mediamarkt.de)
Funktioniert wie Müller (gleicher Mechanismus, siehe oben):
- **Produkt:** Link `mediamarkt.de/de/product/_…-1234567.html`
- **Kategorie:** Link `mediamarkt.de/de/category/….html` (neu / wieder verfügbar / unter Zielpreis)
- Eigener **MediaMarkt-Zugang** für Auto-Kauf; Warenkorb und Kasse unter `/checkout`.
- MediaMarkt hat eine strenge Bot-Prüfung. Kommt die App nicht durch, meldet sie
  „MediaMarkt: Bot-/Sicherheitsprüfung nicht bestanden“ – dann einmal über „Bei MediaMarkt in der
  App anmelden“ die Seite öffnen und ggf. die Prüfung bestätigen.
- Auch hier wird beim Auto-Kauf der **ganze MediaMarkt-Warenkorb** bestellt.

## BAUR (baur.de)
Funktioniert wie Müller/MediaMarkt:
- **Produkt:** Link `baur.de/p/…-1234567890/`
- **Kategorie:** z. B. `baur.de/multimedia/spielkonsolen/` (neu / wieder lieferbar / unter Zielpreis)
- Eigener **BAUR-Zugang** für Auto-Kauf; Warenkorb `/warenkorb`. Auch hier bestellt der Auto-Kauf den
  ganzen BAUR-Warenkorb. BAUR ist Payback-Partner („💳 Über Payback kaufen“).

## PAYBACK (°Punkte bei Amazon und MediaMarkt)
Payback lässt Online-Shopping auf dem Handy nur über die **Payback-App** zu (eine Weiterleitung im
App-Browser blockt Payback). Deshalb halbautomatisch:
1. Unter „PAYBACK“ für Amazon und/oder MediaMarkt **„Payback-Knopf anzeigen“** anhaken.
2. Alarme (Benachrichtigung) und Produkte bekommen den Knopf **„💳 Über Payback kaufen“**. Er öffnet die
   Payback-App beim Shop und kopiert den Produktnamen in die Zwischenablage.
3. In Payback **„Jetzt shoppen“** tippen, im Shop bzw. in dessen App den Namen in die Suche einfügen, kaufen.
   (Laut Payback zählt auch die Amazon-App.) Coupons vorher in der Payback-App aktivieren.

**Payback-Automatik (optional):** Knopf „Payback-Automatik einschalten“ → in den Bedienungshilfen
„Mr.Fox Bot – Payback-Automatik“ einschalten (falls gesperrt: App-Info → ⋮ → „Eingeschränkte
Einstellungen zulassen“). Danach drückt sie nach „Über Payback kaufen“ in der Payback-App sichtbare
„Aktivieren“-Coupons, einmal „Zur Amazon-App“/„Zur MediaMarkt-App“, „Verstanden“ und im Shop den
Cookie-Hinweis („Alle akzeptieren“ – ohne Cookies keine °Punkte). Sie wird nur in den 90 Sekunden nach
dem Tippen in Mr.Fox Bot aktiv und schaltet sich nach dem Cookie-Hinweis sofort ab.

**Auto-Kauf über Payback (nur Amazon):** Haken „Auto-Kauf bei Amazon über Payback erledigen“. Löst der
Auto-Kauf aus und ist das Handy **entsperrt**, erledigt die Payback-Automatik alles: Payback → „Zur
Amazon-App“ → „Verstanden“ → Cookies → in der Amazon-App die ASIN suchen → Produkt → „Jetzt kaufen“
→ Schutzangebote ablehnen → bestellen → Bestätigung. Das Ergebnis kommt als eigene Meldung.
Ist das Handy gesperrt (oder fehlt etwas), läuft der normale Auto-Kauf ohne °Punkte. Hängt der
Payback-Weg vor dem Bestellen (max. 3 Min.), kauft die App ebenfalls normal. Erst mit
„💳 Probelauf über Payback“ testen (hält vor dem Bestellen an, Handy dabei nicht anfassen).

Ohne diesen Haken läuft der **Auto-Kauf** ohne Payback, also ohne °Punkte. Müller ist kein Payback-Partner.

## OfferListingId (Angebot / Verkäufer)
- Bei jeder Prüfung liest die App die **OfferListingId** und den Verkäufer des Angebots in der Kaufbox aus.
- „In den Warenkorb“ (Button und Benachrichtigung) legt genau dieses Angebot in den Warenkorb.
  Der automatische Warenkorb nutzt dafür den schnellen Weg über den Warenkorb-Link (ohne Produktseite)
  und fällt sonst auf die Produktseite zurück.
- Beim **Hinzufügen** prüft die App das Produkt sofort und liest die OfferListingId aus (ohne eigenen
  Namen wird der Amazon-Titel übernommen).
- **Discord:** Die OfferListingId wird für jedes Produkt gepostet, sobald sie gefunden wird, und erneut,
  wenn ein anderes Angebot in die Kaufbox kommt. Alarme enthalten sie ebenfalls. Der Knopf
  „Alle OfferListingIds an Discord senden“ schickt die Liste aller Produkte.
- **Angebot festhalten:** Button „Aktuelles Angebot festhalten“ beim Produkt, oder beim Hinzufügen eine
  OfferListingId eintragen (auch ein Link mit `OfferListingId.1=…` wird erkannt). Dann kauft Auto-Kauf
  nur, wenn genau dieses Angebot in der Kaufbox liegt (z. B. nur „Verkauf durch Amazon“); sonst wartet
  er scharf auf die nächste Prüfung.

## Fenster
Unter Einstellungen → „Fenster“:
- **Bei Alarm Amazon automatisch öffnen:** öffnet die Produktseite, nach einem Auto-Kauf die Bestellübersicht.
- **Probelauf und Auto-Kauf in sichtbarem Fenster:** du siehst live, was die App klickt (Statusbalken oben).
  Das Fenster bleibt am Ende offen. Schließt du es mittendrin, wird abgebrochen.
- **Links in Chrome öffnen:** Buttons in App und Benachrichtigung öffnen Chrome statt des App-Fensters.
  Chrome hat eine eigene Amazon-Anmeldung, und „Zur Kasse“ wird dort nicht automatisch geklickt.

Android lässt Apps aus dem Hintergrund keine Fenster öffnen. Gib deshalb über den Knopf die Erlaubnis
**„Über anderen Apps einblenden“**. Ohne sie öffnet sich das Fenster bei Alarm nur bei gesperrtem
Bildschirm (wie ein Wecker), und der Auto-Kauf im Hintergrund läuft unsichtbar.

## Auto-Kauf
Einrichten:
1. Unter „Amazon-Zugang“ E-Mail und Passwort speichern – die App meldet sich dann selbst an.
2. Unter Einstellungen „Auto-Kauf erlauben“ ankreuzen und speichern.
3. Produkt mit Zielpreis anlegen, dann **„Auto-Kauf Probelauf“** drücken. Der Probelauf geht über
   „Jetzt kaufen“ bis zur Bestellseite, sucht den Bestellknopf und den Gesamtbetrag und meldet
   das Ergebnis – bestellt aber nichts. Nur wenn der Probelauf den Bestellknopf findet, funktioniert
   der echte Kauf.
4. „Auto-Kauf scharf schalten“ und bestätigen.

Sicherungen:
- Bestellt wird nur, wenn der Hauptschalter an ist, das Produkt scharf ist, Amazon es als verfügbar
  zeigt und der Preis ≤ Zielpreis ist.
- Es gibt keinen Höchstbetrag für die Bestellung: Versandkosten werden nicht geprüft, der
  Gesamtbetrag auf der Bestellseite wird nur zur Info gelesen.
- Gekauft wird über „Jetzt kaufen“ (Menge 1), nicht über den Warenkorb – andere Artikel im Warenkorb
  werden also nicht mitbestellt.
- Pro Scharfschalten **genau ein Versuch**: Die App schaltet Auto-Kauf vor dem Versuch aus, egal ob
  er klappt. Für den nächsten Kauf erneut scharf schalten.
- Es läuft nie mehr als ein Kaufvorgang gleichzeitig.
- Ergebnis kommt als Benachrichtigung und auf Discord; „unklar“ heißt: **sofort Bestellungen prüfen**
  (z. B. wenn nach dem Klick keine Bestätigungsseite erkannt wurde).

Grenzen:
- Bestellt wird mit Standard-Lieferadresse und Standard-Zahlungsart deines Amazon-Kontos.
- Verlangt die Bank eine Bestätigung (3-D Secure / Freigabe in der Banking-App) oder Amazon ein
  Captcha bzw. eine erneute Anmeldung, scheitert der Auto-Kauf oder ist „unklar“.
- Die Knöpfe und Felder auf Amazons Bestellseite ändern sich gelegentlich. Dann meldet der
  Probelauf keinen Betrag mehr und der Auto-Kauf bricht ab, bis die Selektoren in `AutoBuyer.kt`
  angepasst sind.
- Amazon erlaubt automatisierte Bestellungen in seinen Nutzungsbedingungen nicht. Nutzung auf
  eigenes Risiko; im Extremfall kann Amazon das Konto einschränken.

## Aufbau des Projekts
```
.github/workflows/build-apk.yml      GitHub baut die APK
build.gradle.kts, settings.gradle.kts, gradle.properties
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/values/strings.xml, themes.xml
app/src/main/res/drawable/ic_launcher.png     App-Symbol (Fuchs)
app/src/main/java/de/amazonmonitor/
    MainActivity.kt   Oberfläche
    Store.kt          Daten, Einstellungen, Verschlüsselung (Vault)
    Scraper.kt        Preis von der Amazon-Seite lesen
    CheckWorker.kt    Hintergrundprüfung, Alarme, Discord
    CartAdder.kt      automatisch in den Warenkorb legen
    AutoBuyer.kt      Auto-Kauf über „Jetzt kaufen“ (mit Probelauf)
    WebActivity.kt    Amazon-Fenster, Anmeldung, Zur-Kasse-Klick
    WatchActivity.kt  sichtbares Fenster für Probelauf/Auto-Kauf
    Links.kt          Links im App-Fenster oder in Chrome öffnen
    Shops.kt          Web-Shops (Müller, MediaMarkt): Links erkennen, Produkt-/Kategorieseiten auslesen
    ShopBuyer.kt      Web-Shops: Warenkorb und Auto-Kauf
    BotService.kt     Dauerbetrieb mit fester Benachrichtigung (kurze Prüfintervalle)
    PaybackClicker.kt Payback-Automatik (Bedienungshilfe): „Zur Amazon-App“ drücken
    Payback.kt        PAYBACK: Payback-App beim Shop öffnen, Produktname kopieren
```

## APK bauen und installieren
1. Alle Dateien in ein GitHub-Repository hochladen (die Ordnerstruktur muss genau stimmen,
   `drawable` liegt direkt unter `res`, nicht unter `values`).
2. Unter **Actions** baut der Workflow „Build APK“ (ca. 1–2 Minuten).
3. Unter **Releases** liegt danach `app-debug.apk`. Auf dem Handy installieren.

## Updates ohne Datenverlust (fester Signaturschlüssel, einmalig einrichten)
Ohne festen Schlüssel signiert GitHub jeden Build neu. Dann musst du die App vor jedem Update
deinstallieren, und Produkte, Einstellungen und Login gehen verloren. So richtest du einen festen
Schlüssel ein:

1. Schlüssel erzeugen (am PC mit installiertem Java):
   ```
   keytool -genkeypair -v -keystore mrfox.jks -alias mrfox -keyalg RSA -keysize 2048 -validity 10000
   ```
   Passwort merken. Die Datei `mrfox.jks` gut aufheben, **nicht** ins Repository hochladen.
2. Datei in Base64 umwandeln:
   - Linux/macOS: `base64 -w0 mrfox.jks > mrfox.txt` (macOS: `base64 -i mrfox.jks -o mrfox.txt`)
   - Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("mrfox.jks")) > mrfox.txt`
3. Im GitHub-Repository unter **Settings → Secrets and variables → Actions** vier Secrets anlegen:
   - `SIGNING_KEYSTORE_BASE64` – Inhalt von `mrfox.txt`
   - `SIGNING_STORE_PASSWORD` – das Keystore-Passwort
   - `SIGNING_KEY_ALIAS` – `mrfox`
   - `SIGNING_KEY_PASSWORD` – das Schlüssel-Passwort (bei keytool meist dasselbe)
4. Die bisher installierte Version **ein letztes Mal** deinstallieren und den neuen Build installieren.
   Ab dann lassen sich Updates einfach drüberinstallieren.

## Hinweise
- Amazon erlaubt automatisiertes Auslesen nicht ausdrücklich und blockiert häufige Abfragen
  mit Captchas. Nimm ein großzügiges Intervall (z. B. 60 Minuten).
- Ändert Amazon sein HTML, können Preiserkennung, Warenkorb-Automatik und Kassen-Klick ausfallen.
- Akku-Optimierung für die App ausschalten, sonst verschiebt Android die Prüfungen stark.
- Kurzlinks (amzn.to, a.co) werden nicht erkannt, nutze den langen Link mit `/dp/…`.

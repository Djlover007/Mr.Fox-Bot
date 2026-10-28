# Mr.Fox Bot (Android)

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
  Verifizierung) und Freigabe per Amazon-App/SMS – dann einmal über „Bei Amazon anmelden“ selbst bestätigen.
- Optional: Artikel bei Alarm automatisch in den Warenkorb legen (Testbutton pro Produkt)
- „Zur Kasse“ öffnet nur den Bezahlvorgang, das endgültige „Jetzt kaufen“ tippst du selbst
- Optional: **Auto-Kauf** – bestellt ein Produkt selbstständig, sobald der Zielpreis erreicht ist
  (mit Höchstbetrag, Probelauf und nur einem Versuch, siehe unten)

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
  „Bei Amazon anmelden“ öffnet immer das App-Fenster, weil Auto-Kauf und Warenkorb dessen Anmeldung nutzen.

Android lässt Apps aus dem Hintergrund keine Fenster öffnen. Gib deshalb über den Knopf die Erlaubnis
**„Über anderen Apps einblenden“**. Ohne sie öffnet sich das Fenster bei Alarm nur bei gesperrtem
Bildschirm (wie ein Wecker), und der Auto-Kauf im Hintergrund läuft unsichtbar.

## Auto-Kauf
Einrichten:
1. Unter „Amazon-Zugang“ E-Mail und Passwort speichern – die App meldet sich dann selbst an.
2. Unter Einstellungen „Auto-Kauf erlauben“ ankreuzen, einen **Höchstbetrag pro Bestellung
   (inkl. Versand)** eintragen und speichern. Ohne Höchstbetrag bleibt Auto-Kauf aus.
3. Produkt mit Zielpreis anlegen, dann **„Auto-Kauf Probelauf“** drücken. Der Probelauf geht über
   „Jetzt kaufen“ bis zur Bestellseite, sucht den Bestellknopf und den Gesamtbetrag und meldet
   das Ergebnis – bestellt aber nichts. Nur wenn der Probelauf den Bestellknopf findet, funktioniert
   der echte Kauf.
4. „Auto-Kauf scharf schalten“ und bestätigen.

Sicherungen:
- Bestellt wird nur, wenn der Hauptschalter an ist, das Produkt scharf ist, Amazon es als verfügbar
  zeigt und der Preis ≤ Zielpreis und ≤ Höchstbetrag ist.
- Zeigt die Bestellseite einen Gesamtbetrag über dem Höchstbetrag, wird abgebrochen. Ist der
  Gesamtbetrag nicht lesbar, wird trotzdem bestellt (Versandkosten sind dann nicht geprüft).
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

# Mr.Fox Bot (Android)

Android-App, die Preis und Verfügbarkeit von Amazon-Produkten im Hintergrund überwacht und dich
per Benachrichtigung und optional per Discord informiert (Zielpreis erreicht, Preis um X % gefallen,
wieder verfügbar).

## Funktionen
- Produkte per Amazon-Link oder ASIN, optional mit Zielpreis
- Hintergrundprüfung (mind. alle 15 Minuten)
- Benachrichtigung mit Buttons „In den Warenkorb“ und „Zur Kasse“
- Discord-Webhook (optional) mit Testbutton
- Amazon-Anmeldung im App-Fenster; Zugang (E-Mail/Passwort) optional, verschlüsselt im Android-Tresor
- Optional: Artikel bei Alarm automatisch in den Warenkorb legen (Testbutton pro Produkt)
- „Zur Kasse“ öffnet nur den Bezahlvorgang, das endgültige „Jetzt kaufen“ tippst du selbst

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
    WebActivity.kt    Amazon-Fenster, Anmeldung, Zur-Kasse-Klick
```

## APK bauen und installieren
1. Alle Dateien in ein GitHub-Repository hochladen (die Ordnerstruktur muss genau stimmen,
   `drawable` liegt direkt unter `res`, nicht unter `values`).
2. Unter **Actions** baut der Workflow „Build APK“ (ca. 1–2 Minuten).
3. Unter **Releases** liegt danach `app-debug.apk`. Auf dem Handy installieren,
   die alte Version vorher deinstallieren (GitHub signiert jeden Build neu).

## Hinweise
- Amazon erlaubt automatisiertes Auslesen nicht ausdrücklich und blockiert häufige Abfragen
  mit Captchas. Nimm ein großzügiges Intervall (z. B. 60 Minuten).
- Ändert Amazon sein HTML, können Preiserkennung, Warenkorb-Automatik und Kassen-Klick ausfallen.
- Akku-Optimierung für die App ausschalten, sonst verschiebt Android die Prüfungen stark.
- Kurzlinks (amzn.to, a.co) werden nicht erkannt, nutze den langen Link mit `/dp/…`.

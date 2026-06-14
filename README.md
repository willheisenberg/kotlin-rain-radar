# OpenRain — DWD Regenradar Mobile App

<p align="center">
  <img src="assets/logo.png" width="320" height="320" alt="OpenRain Logo" />
</p>

Diese mobile Android-App (**OpenRain**) basiert auf dem KDE Plasma DWD-Regenradar-Widget und wurde vollständig in **Kotlin & Jetpack Compose** mit **Osmdroid** für interaktive OpenStreetMap-Karten implementiert.

## Features

- **Interaktive Karte** — Nahtlose OpenStreetMap-Integration mit stufenlosem Zoom und Pan.
- **Echtzeit DWD Radar-Overlay** — Animiertes, transparentes Regenradar direkt vom DWD-WMS.
- **Flüssige Vorladetechnologie** — Sämtliche Radarframes werden im Hintergrund geladen und im Cache gehalten, um eine vollkommen flüssige Animation ohne Flackern zu gewährleisten.
- **Verlauf & Vorhersage** — Umschaltbar zwischen vergangenen Regenradaraufzeichnungen und der 2-stündigen DWD-Niederschlagsprognose.
- **Standortbestimmung** — Springe direkt zu deinem eigenen Standort (erfordert GPS-Freigabe).
- **Modernes Design** — Premium Dark-Mode Oberfläche mit minimalistischer Farbpalette und integrierter Intensitätslegende.

## Screenshots

<p align="center">
  <img src="assets/screenshot_app_controls.png" width="250" alt="OpenRain App mit Steuerung" />
  <img src="assets/screenshot_app_fullscreen.png" width="250" alt="OpenRain App Vollbild" />
  <img src="assets/screenshot_widget_v2.png" width="250" alt="OpenRain Homescreen Widget" />
</p>

## Struktur & Architektur

Die App folgt modernen Android-Architekturrichtlinien (MVVM):
- **`data/DwdWmsClient.kt`** — Berechnet dynamisch die 5-Minuten-Zeitfenster und projiziert Kachel-Koordinaten (x, y, zoom) via EPSG:3857 in das Bounding-Box-Format des WMS-Dienstes.
- **`ui/RadarViewModel.kt`** — Hält den Zustand (Animation, Zeitschritte, Play/Pause) mithilfe sicherer Kotlin Coroutines im `viewModelScope`.
- **`ui/components/RadarMapView.kt`** — Bindet die Karte über ein Compose-`AndroidView` ein und verwaltet die Layer-Schichten hocheffizient (nicht aktive Schichten werden im Hintergrund vorgeladen).
- **`ui/RadarScreen.kt`** — Deklaratives Jetpack Compose-Layout mit Material 3 Komponenten.

## Ausführen und Testen

1. Öffne das Projekt-Verzeichnis `kotlin-rain-radar` in **Android Studio**.
2. Lass Gradle das Projekt synchronisieren.
3. Starte die App auf einem Android-Emulator oder physischen Device (erfordert Android API 26+).

---

## APK bauen (Build APK)

Es gibt zwei zuverlässige Wege, die Installationsdatei (`.apk`) für dein Android-Gerät zu erzeugen:

### Methode 1: Über die Android Studio Oberfläche (Empfohlen)
Diese Methode ist am sichersten, da Android Studio automatisch seine eigene, kompatible Java-Umgebung (JBR) nutzt und somit Konflikte mit neueren Java-Versionen auf deinem Betriebssystem (z. B. OpenJDK 26 unter Arch Linux) vermeidet.

1. Öffne das Projekt in **Android Studio**.
2. Klicke in der oberen Menüleiste auf **Build** -> **Build Bundle(s) / APK(s)** -> **Build APK(s)**.
3. Android Studio kompiliert die App. Sobald der Vorgang abgeschlossen ist, erscheint unten rechts ein Pop-up-Fenster.
4. Klicke im Pop-up auf **locate**. Dein Dateimanager öffnet sich direkt im Ordner mit der fertigen APK-Datei (`app-debug.apk`).
   - *Alternativ findest du die Datei unter:* `app/build/outputs/apk/debug/app-debug.apk`

### Methode 2: Über das Terminal (Kommandozeile)

Du kannst den Build direkt im Terminal starten:

```bash
./gradlew assembleDebug
```

*(Hinweis: Der Build erfordert Java 17. Solltest du unter Arch Linux eine neuere Standard-Javaversion wie Java 26 aktiv haben und Kompilierfehler erhalten, kannst du dem Befehl die Java-17-Umgebung voranstellen, z. B. `JAVA_HOME=/usr/lib/jvm/java-17-openjdk/ ./gradlew assembleDebug`)*

Die fertige APK-Datei liegt nach dem Durchlauf unter:
`app/build/outputs/apk/debug/app-debug.apk`


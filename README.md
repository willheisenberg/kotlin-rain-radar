# OpenRain — DWD Rain Radar Mobile App

<p align="center">
  <img src="assets/logo.png" width="320" height="320" alt="OpenRain Logo" />
</p>

This mobile Android app (**OpenRain**) is based on the KDE Plasma DWD rain radar widget and was implemented entirely in **Kotlin & Jetpack Compose** using **Osmdroid** for interactive OpenStreetMap maps.

## Features

- **Interactive Map** — Seamless OpenStreetMap integration with smooth zooming and panning.
- **Real-Time DWD Radar Overlay** — Animated, transparent rain radar directly from the DWD WMS.
- **Smooth Preloading Technology** — All radar frames are loaded in the background and cached to ensure a completely smooth animation without flickering.
- **History & Forecast** — Toggle between past rain radar records and the 2-hour DWD precipitation forecast.
- **Location Tracking** — Jump directly to your own location (requires GPS permission).
- **Modern Design** — Premium dark-mode interface with a minimalist color palette and integrated intensity legend.

## Screenshots

<p align="center">
  <img src="assets/screenshot_app_controls.png" width="250" alt="OpenRain App with Controls" />
  <img src="assets/screenshot_app_fullscreen.png" width="250" alt="OpenRain App Fullscreen" />
  <img src="assets/screenshot_widget_v2.png" width="250" alt="OpenRain Homescreen Widget" />
</p>

## Structure & Architecture

The app follows modern Android architecture guidelines (MVVM) and optionally utilizes an optimization server:
- **[DwdWmsClient.kt](file:///home/will/ownProjects/kotlin-rain-radar/app/src/main/java/com/example/rainradar/data/DwdWmsClient.kt)** — Calculates the 5-minute time windows dynamically, manages the optimized WebP proxy downloads, and handles the direct DWD WMS fallback in case of server failure.
- **[RadarViewModel.kt](file:///home/will/ownProjects/kotlin-rain-radar/app/src/main/java/com/example/rainradar/ui/RadarViewModel.kt)** — Manages the state (animation, time steps, play/pause) using secure Kotlin Coroutines within the `viewModelScope`.
- **[RadarMapView.kt](file:///home/will/ownProjects/kotlin-rain-radar/app/src/main/java/com/example/rainradar/ui/components/RadarMapView.kt)** — Manages the map overlays (bypasses the local pixel cleanup if WebP images were loaded from the proxy).
- **[RadarScreen.kt](file:///home/will/ownProjects/kotlin-rain-radar/app/src/main/java/com/example/rainradar/ui/RadarScreen.kt)** — Declarative Jetpack Compose layout using Material 3 components.
- **`/server` (Ktor Server)** — Standalone Kotlin server that fetches PNG frames from the DWD, cleans them up (removes background/borders), converts them to WebP, and serves them via RAM + disk cache.

## Running and Testing

1. Open the project directory `kotlin-rain-radar` in **Android Studio**.
2. Let Gradle sync the project.
3. Start the app on an Android emulator or a physical device (requires Android API 26+).

### ADB and scrcpy workflow

Requires Python 3, an Android SDK with an AVD, and `scrcpy` for mirroring/recording.
The script reads the SDK path from `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or
`local.properties`. Gradle uses this project's configured JVM toolchain.

#### Emulator und scrcpy starten

Alle Befehle im Projektordner ausführen. **Emulator und scrcpy sind zwei getrennte
Programme:** Der Emulator führt Android aus; scrcpy zeigt dessen Bildschirm und
ermöglicht die Bedienung. Das Schließen von scrcpy beendet den Emulator nicht.

Zuerst prüfen, was bereits läuft:

```bash
scripts/android-dev.py doctor
# Alternativ: nur verbundene Geräte anzeigen
adb devices -l
```

Steht dort beispielsweise `emulator-5554` mit Status `device`, läuft bereits ein
Emulator. Dann direkt mit Terminal 2 weitermachen. Ein weiterer Start desselben
AVD führt zur Meldung „Running multiple emulators with the same AVD“. Dafür ist
kein zweiter Emulator und kein `-read-only` nötig.

**Terminal 1 – nur falls der Emulator noch nicht läuft:**

```bash
scripts/android-dev.py emulator --avd Pixel_8
```

Das Terminal bleibt belegt. Standardmäßig startet der Emulator **ohne eigenes
Fenster** (`-no-window`), läuft aber trotzdem. Für ein eigenes Emulatorfenster:

```bash
scripts/android-dev.py emulator --avd Pixel_8 --window
```

Diese beiden Startbefehle sind Alternativen. Andere verfügbare AVD-Namen zeigt
`doctor` an.

**Terminal 2 – nach dem Android-Start:**

```bash
# App bauen, installieren und öffnen
scripts/android-dev.py run
# Bildschirm anzeigen und mit Maus/Tastatur bedienen
scripts/android-dev.py mirror
```

Ist die App bereits installiert und geöffnet, reicht `mirror`. Für die vorhandene
Debug-APK ohne erneuten Build: `scripts/android-dev.py run --no-build`.

#### scrcpy und Emulator wieder beenden

- **Nur scrcpy schließen:** Fenster schließen oder im Terminal von `mirror`
  **Strg+C** drücken. Android und die App laufen im Emulator weiter.
- **Eine Aufnahme beenden:** `record --seconds 30` endet nach 30 Sekunden von
  selbst. Vorzeitig im Aufnahme-Terminal **Strg+C** drücken.
- **Emulator vollständig beenden:** Im Start-Terminal des Emulators **Strg+C**
  drücken. Ist das Terminal nicht mehr verfügbar oder wurde der Emulator von
  einem anderen Prozess gestartet, den folgenden ADB-Befehl verwenden.

```bash
# Seriennummer des laufenden Emulators ermitteln
adb devices -l
# Genau diese Emulatorinstanz beenden (Seriennummer ggf. anpassen)
adb -s emulator-5554 emu kill
# Prüfen: Der Emulator sollte nicht mehr in der Liste stehen
adb devices -l
```

`emu kill` beendet den Emulator auch dann, wenn er unsichtbar im Hintergrund
läuft. Verbundene scrcpy-Sitzungen verlieren dabei ihre Verbindung. Installierte
Apps und App-Daten bleiben erhalten; das Skript startet mit `-no-snapshot-save`,
also ohne beim Beenden einen neuen Snapshot des laufenden Zustands anzulegen.

Falls auch der lokale ADB-Dienst nicht mehr benötigt wird:

```bash
adb kill-server
```

Das ist optional und betrifft alle lokalen ADB-Verbindungen, auch Android Studio.
**`adb kill-server` beendet keine Emulatoren**; dafür zuerst `emu kill` verwenden.
Ein späterer ADB-Aufruf startet den Dienst bei Bedarf erneut.

With multiple emulators, or to deliberately use a physical device, pass
`--serial SERIAL` before the command. Without it, only a single ready emulator
is selected. Installation preserves app data and fails on a signing mismatch;
the script does not uninstall existing apps. The package ID is read from the
built APK metadata.

```bash
scripts/android-dev.py test
scripts/android-dev.py seek 0.8
scripts/android-dev.py capture
# Run recording in another terminal while seeking:
scripts/android-dev.py record --seconds 30 --output captures/forecast.mp4
```

`test` runs Android regression tests with local image fixtures: a forecast
arriving after selection, seeking across forecast generations, and pausing
playback on manual selection. These tests do not require weather or map servers.
`seek` uses the visible timeline's accessibility bounds; loading and permission
dialogs must be finished first. By default it waits one second for decoding and
rendering; use `--settle-seconds 3` for a slower emulator. `capture` saves a PNG, UI hierarchy and recent
logcat under the ignored `captures/` directory. Screenshots and logs may contain
the device's location or other displayed data; inspect them before sharing.

For a visual check, seek to several positions in the forecast section (roughly
0.61–1.0), including rapid changes and a change during a background refresh.
Compare both the displayed time and the rain overlay; a changed time alone
does not demonstrate a successful frame change. Weather frames can legitimately
look similar. If an individual frame is missing, the renderer uses a cached
neighbour, as before. When the selected frame arrives, it replaces that fallback
automatically. There is no missing-frame message on the map or timeline.
During a background refresh, the previous forecast generation stays available
until all 24 replacement forecast frames have loaded successfully.

### Datenquelle im Ladefenster

Das Ladefenster zeigt die tatsächlich verwendeten Downloadwege an:

- **Vom Server:** Bilder werden vom konfigurierten Proxy geladen.
- **Direkt von DWD · Aufbereitung auf dem Gerät:** Die App lädt die DWD-Bilder
  direkt und bereitet sie lokal für die Darstellung auf.
- **Bilder aus lokalem Cache:** Diese Bilder waren bereits auf dem Gerät vorhanden.

Bei einem Serverausfall können Server- und DWD-Downloads kurz gleichzeitig laufen;
dann werden beide Quellen angezeigt. Fehlgeschlagene Downloads zählen nicht als
„geladen“. Die Downloadrunde endet spätestens nach 90 Sekunden; fehlende Bilder
werden beim nächsten Hintergrundversuch erneut angefragt. Ein noch brauchbarer
Vorhersagesatz bleibt während des Nachladens erhalten.

Die Proxy-Adresse wird beim **Build** festgelegt, nicht beim Start der App:

```properties
# local.properties (nicht einchecken): echte HTTPS-Adresse einsetzen
proxy.url=https://DEIN-SERVER/radar
```

Ohne `proxy.url` lädt die App direkt von DWD. Es wird nicht mehr stillschweigend
`http://10.0.2.2:8080/radar` verwendet. `10.0.2.2` bezeichnet den Rechner aus Sicht
des Android-Emulators und ist keine öffentliche Serveradresse fürs Handy.
Danach `scripts/android-dev.py run` ausführen, damit die Änderung im APK ankommt.
Alternativ lässt sich die Adresse für einen einzelnen Build überschreiben:

```bash
./gradlew :app:assembleDebug -PradarProxyUrl=https://DEIN-SERVER/radar
scripts/android-dev.py run --no-build
```

Live-Prüfungen benötigen außerdem Netzwerkzugriff für die Kartenkacheln.

### Download-Absicherung

Direkte DWD-Downloads haben ein gemeinsames Limit von fünf gleichzeitigen
Anfragen, auch beim Fallback und bei Widget-Downloads. Ein fehlgeschlagener Proxy
wird für 30 Sekunden übersprungen; jeder einzelne HTTP-Download hat eine
Gesamtfrist von 20 Sekunden einschließlich des Antwortinhalts. Coroutine-Abbruch
schließt die zugehörige HTTP-Anfrage, und Wiederholungen warten ohne blockierende
`Thread.sleep`-Aufrufe.

Der Server liest ebenfalls den kompletten Antwortinhalt unter dieser Frist,
bevor ImageIO das Bild verarbeitet. Doppelte Vorladerunden werden übersprungen;
Anfragen für denselben Cache-Eintrag teilen sich das Ergebnis, und fertige
Dateien werden atomar veröffentlicht. Ein Serverupdate ist separat vom APK
zu bauen und auszurollen.

```bash
./gradlew :app:testDebugUnitTest :server:test
scripts/android-dev.py test
```

Die Tests decken unter anderem einen nach den HTTP-Headern stockenden Download,
Abbruch der Verbindung, begrenzten DWD-Fallback, gemischte Quellenanzeigen und
das Beibehalten der alten Vorhersage bei einem unvollständigen Ersatz ab.

---

## Building the APK (Build APK)

There are two reliable ways to generate the installation file (`.apk`) for your Android device:

### Method 1: Using the Android Studio UI (Recommended)
This method is the safest, as Android Studio automatically uses its own compatible Java Runtime (JBR), avoiding conflicts with newer Java versions on your operating system (e.g., OpenJDK 26 on Arch Linux).

1. Open the project in **Android Studio**.
2. In the top menu bar, click on **Build** -> **Build Bundle(s) / APK(s)** -> **Build APK(s)**.
3. Android Studio compiles the app. Once the process is complete, a pop-up window appears in the bottom right corner.
4. Click on **locate** in the pop-up. Your file manager will open directly in the directory containing the finished APK file (`app-debug.apk`).
   - *Alternatively, you can find the file at:* `app/build/outputs/apk/debug/app-debug.apk`

### Method 2: Via Terminal (Command Line)

You can start the build directly in the terminal:

```bash
./gradlew assembleDebug
```

*(Note: The build requires Java 17. If you have a newer default Java version active on your operating system, e.g., Java 26, and encounter compilation errors, you can prepend the Java 17 path to the command, e.g.: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk/ ./gradlew assembleDebug`)*

The compiled APK file is located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Setting up the Server Proxy (VPS / Remote Server)

The server component (`/server`) is a standalone Kotlin JVM project and can be run on any Linux server (e.g., a VPS) via Docker or directly.

### Method 1: Deployment via Docker Compose (Recommended)

1. Copy the `server/` directory to your remote server (e.g., via `git clone` or `scp`).
2. Ensure **Docker** and **Docker Compose** are installed on the server.
3. Navigate to the `server/` directory on the server and start the container in the background:
   ```bash
   docker compose up -d --build
   ```
4. The server will now be accessible on port `8080`. The cleaned and converted WebP images are cached persistently in the local `./cache` directory.

### Method 2: Running Directly (via Gradle)

Requires Java JDK 17 (or newer) installed on the system:
```bash
cd server
./gradlew run
```

### Connecting the App to the Server

Der produktive Radar-Endpunkt ist `https://openrain.getbankless.de/radar`.
Ergebnisse des Upgrades und Lasttests sowie Rollback-Anweisungen stehen im
[Lasttestbericht vom 15.09.2026](server/LOAD-TEST-2026-09-15.md).

1. Open the file `local.properties` at the root of the project.
2. Add or modify the `proxy.url` property with the IP address or domain of your server:
   ```properties
   proxy.url="https://your-domain.com/radar"
   ```
3. *(Note: To disable the proxy and connect the app directly to the DWD server again, simply set the property to an empty string `proxy.url=""` or delete it)*.
4. Rebuild the APK (see the "Building the APK" section) and install it on your device. The compiler will automatically inject this URL during build time.

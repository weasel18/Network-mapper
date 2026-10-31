# Network Mapper (Android)

Field app for mapping and locating network infrastructure: fiber junctions/handholes, splice
enclosures, cabinets, slack loops, fiber paths, access points, wireless bridges, switches, routers,
poles and demarcs. It also maps Wi-Fi signal strength by GPS and attaches GPS-tagged photos to pins.

## Features

- **Sites**: keep each job or location separate. Every site has its own pins, paths, photos and Wi-Fi surveys. Tap the
  site name at the top of the map to switch sites, or to create, rename or delete them. Data saves automatically.
- **Map** (OpenStreetMap, topo, or Esri satellite imagery; no API key needed). Tiles you've viewed are cached for offline use.
- **Pins**: drop one at your GPS position (+ button) or long-press anywhere. There are 11 asset types, each with
  suggested detail fields (e.g. splice count, tray count, SSID/MAC/IP, azimuth/tilt, port count), plus custom fields,
  status and notes. Pins can't be moved by accident: tap a pin, then **Move**, then tap the new spot, drag it, or use GPS,
  and **Save position** (or Cancel).
- **Locate**: tap a pin to see its distance and bearing from you, or hand off to a navigation app.
- **Paths**: draw fiber (underground/aerial), conduit, copper or wireless links. Tap the map to add points, tap pins to
  snap the ends to junctions or bridges, or turn on **Auto-track** and walk the route with GPS. You can set length,
  fiber count, cable type and colour coding.
  Tap **Finish** to save. To change a route later, tap it on the map, then **Edit / extend route**. Existing points are
  locked in **Add points** mode. Switch to **Edit points** to select a point, drag it, delete it, or insert a point
  next to it. **Undo** steps back through every change.
  Switch between *adding at end (B)* and *adding at start (A)* to continue the route from either end, by tapping or
  Auto-track. An unfinished route is kept if the app is closed.
- **Photos**: the camera button creates a *photo pin* at your location. You can also add photos to any pin or path.
  GPS is stored in the database and written to the JPEG's EXIF data.
- **Wi-Fi survey**: a foreground service logs every scan result (SSID, BSSID, RSSI, channel) with the GPS fix as you
  walk. It also logs the connected AP's RSSI every 2 m, which isn't affected by scan throttling. The heatmap can show the
  strongest signal at each spot, one SSID across all its APs, or a single AP.
  Tap an AP, bridge or router pin and choose **Signal map** to see only that device's coverage, with reliable
  (≥ -67 dBm) and usable (≥ -80 dBm) range measured from the pin. The first time, link the pin to its radio(s); the
  app lists the networks heard closest to the pin and auto-selects the other bands/SSIDs of the same box.
- **Tap the signal trail**: tap any dot on the Wi-Fi heatmap to see every AP heard within 5 m of that spot, with RSSI,
  band/channel and BSSID. From there, show one AP's coverage or name it.
- **Name APs by MAC / BSSID**: use ✎ on the map's "Wi-Fi heard here" card or on any row of the Wi-Fi screen. You can
  apply the name to the AP's other radios (same MAC prefix) and drop a linked AP pin where it was heard strongest.
  Names show everywhere (lists, legend, CSV/GeoJSON exports) and travel in backups.
- **Export / import sites**: choose which sites to export (all by default).
  - A *Full backup ZIP* holds everything: sites, pins, paths, colours, photos with captions, and Wi-Fi surveys, plus
    GeoJSON, KML and CSV for QGIS/ArcGIS/Excel.
  - A *KMZ* is for viewing in Google Earth.
  - When importing, choose which sites in the file to load and whether each becomes a new site or merges into an
    existing one. Duplicates are skipped. Older single-site backups import too.

## Build

A GitHub Actions workflow (`.github/workflows/android.yml`) builds a debug APK on every push. Download it from the
run's **Artifacts** section.

Locally (Android SDK + JDK 17):

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```

Requires Android 8.0 (API 26) or newer.

## Installing updates

Builds are signed with the fixed key in `app/debug.keystore`, so each new APK installs over the previous one and
keeps your data. Builds made before version 1.1 used a random key per build and the package id
`com.networkmapper.app`. To move data from one of those:

1. In the old app: **Export & data → Full data ZIP**, and save or send the ZIP somewhere you can open it on the phone
   (Drive, Files, email). Finish any route you're drawing first.
2. Install the new APK. It installs **alongside** the old app.
3. In the new app: **Export & data → Import / restore sites…**, pick the file, and choose the site to import into.
4. Once everything is there, uninstall the old copy.

The committed key is fine for an internal, sideloaded tool. Use a private release key before distributing more widely.

## Notes

- Android allows apps 4 Wi-Fi scans per 2 minutes. For denser surveys, turn off *Developer options → Wi-Fi scan throttling*.
- All data stays on the device (Room database + app-private photo folder) until you export it.

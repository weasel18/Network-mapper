# Network Mapper (Android)

Field app for mapping and locating network infrastructure: fiber junctions/handholes, splice
enclosures, cabinets, slack loops, fiber paths, access points, wireless bridges, switches, routers,
poles and demarcs. It also maps Wi-Fi signal strength by GPS and attaches GPS-tagged photos to pins.

## Features

- **Map** (OpenStreetMap, topo, or Esri satellite imagery; no API key needed). Tiles you've viewed are cached for offline use.
- **Pins**: drop one at your GPS position (+ button) or long-press anywhere. There are 11 asset types, each with
  suggested detail fields (e.g. splice count, tray count, SSID/MAC/IP, azimuth/tilt, port count), plus custom fields,
  status and notes. Unlock pins in *Layers* to drag a pin to a corrected position.
- **Locate**: tap a pin to see its distance and bearing from you, or hand off to a navigation app.
- **Paths**: draw fiber (underground/aerial), conduit, copper or wireless links. Tap the map to add points, tap pins to
  snap the ends to junctions or bridges, or turn on **Auto-track** and walk the route with GPS. You can set length,
  fiber count, cable type and colour coding.
  Tap **Finish** to save. To change a route later, tap it on the map, then **Edit / extend route**. Tap a point to select
  it, drag it to move it, or delete it or insert a point next to it. **Undo** steps back through every change.
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
- **Export**: KMZ for Google Earth (with embedded photos), or a ZIP with GeoJSON, KML, Wi-Fi CSV/GeoJSON and photos
  for QGIS/ArcGIS.

## Build

A GitHub Actions workflow (`.github/workflows/android.yml`) builds a debug APK on every push. Download it from the
run's **Artifacts** section.

Locally (Android SDK + JDK 17):

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```

Requires Android 8.0 (API 26) or newer.

## Notes

- Android allows apps 4 Wi-Fi scans per 2 minutes. For denser surveys, turn off *Developer options → Wi-Fi scan throttling*.
- All data stays on the device (Room database + app-private photo folder) until you export it.

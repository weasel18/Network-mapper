# Network Mapper (Android)

Field app for mapping and locating network infrastructure: fiber junctions/handholes, splice
enclosures, cabinets, slack loops, fiber paths, access points, wireless bridges, switches, routers,
poles and demarcs. It also maps Wi-Fi signal strength by GPS and attaches GPS-tagged photos to pins.

## Features

- **Sites**: keep each job or location separate. Every site has its own pins, paths, photos and Wi-Fi surveys. Tap the
  site name at the top of the map to switch sites, or to create, rename or delete them. Data saves automatically.
- **Map** (OpenStreetMap, topo, Esri satellite imagery, or satellite with road and place labels; no API key needed).
  Map tiles you've viewed are kept on the phone (up to 1 GB) so they still show with no signal; browse your sites'
  areas while online before heading somewhere without coverage.
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
- **Pre-filled AP pins**: on an AP, wireless bridge or router pin, **Fill from Wi-Fi** suggests APs you've named,
  APs recorded near the pin and APs in range now. Tap one to fill in its MAC / BSSID (and its other radios), SSID,
  channel or frequency, and your saved name.
- **Choose which Wi-Fi to map**: on the Wi-Fi screen, use 👁 on any network to hide that AP (all its radios) or that
  SSID, or to **only map** certain SSIDs. The filter button there reviews and undoes these, can hide networks with no
  name, and can remove already-recorded readings of filtered networks.
  - Filtered networks aren't recorded and are left out of the lists, the heatmap and suggestions.
  - Each site has its own filter (the one for the site that is open); it syncs between phones and the browser. A filter
    set up before filters were per site is copied into every existing site once, so you can then trim each site separately.
- **Antenna coverage patterns**: give an AP, bridge or router pin an **Omni**, **Sector** or **Directional / PtP**
  pattern with azimuth, beamwidth and range. It's drawn on the map in strong/good/weak bands (toggle in Layers).
  - Set azimuth with the slider or **Aim with phone**, which uses the compass corrected to true north.
  - **From survey** sets the range from where the pin's radios were actually recorded.
  - The pattern is stored in the pin's details, so it's included in exports and backups.
- **UniFi import** (Wi-Fi screen → **UniFi**, or Export & data): connect to your UniFi Network controller and pull
  device names, models, IPs and AP radio BSSIDs. Names are saved for every MAC/BSSID, so survey dots and lists show
  UniFi names. Matching pins (by MAC) get model, IP and AP radios, plus the name if it's still automatic. New AP pins
  pre-fill from the imported devices.
  - Sign in with an **API key** (UniFi OS console, Network 9.0+) or a local **username & password** (UniFi OS or
    classic controller). Username & password always includes radio BSSIDs; an API key includes them only if the
    controller allows it.
  - A self-signed controller certificate is trusted only after you confirm its SHA-256 fingerprint, and only that
    exact certificate is accepted afterwards. The API key or password is stored encrypted with the Android Keystore
    if you choose "Remember".
  - **Fill from UniFi**: switch, gateway/router, AP and bridge pins list your UniFi devices (matching type first;
    refresh pulls the latest list). Picking one fills in the name (only replacing an automatic name), hostname, model,
    IP, MAC, firmware and port count, plus radios/SSIDs/channels for APs, and links the pin for live status.
  - **Live status**: tap a pin that matches an imported UniFi device to see live data on its card, refreshed every
    10 s: online/offline, uptime, clients, CPU/memory, uplink speed and throughput, and firmware. APs also show each
    radio's channel, Tx power, clients and utilisation; switches show port chips (up/down, speed, PoE W, uplink).
    It uses the remembered login and the last site loaded on the UniFi screen, and pauses in the background.
- **Export / import sites**: choose which sites to export (all by default).
  - A *Full backup ZIP* holds everything: sites, pins, paths, colours, photos with captions, and Wi-Fi surveys, plus
    GeoJSON, KML and CSV for QGIS/ArcGIS/Excel.
  - A *KMZ* is for viewing in Google Earth.
  - When importing, choose which sites in the file to load and whether each becomes a new site or merges into an
    existing one. Duplicates are skipped. Older single-site backups import too.

## Cloud sync (several phones at once)

Run the small sync server in [`server/`](server/README.md) on your own VPS (Docker on Debian; automatic HTTPS with a
domain, or a self-signed certificate for an IP-only setup). Then on each phone go to ⋮ → **Cloud sync**:

1. Enter the server address and the team access key (`SYNC_KEY`), give the phone a name, and tap **Test & save**.
2. Choose **All sites** or just the sites this phone should work on.
3. Tap **Sync now**, and/or turn on **Automatic sync**: every 15 min to 3 h, plus about a minute after you change
   something. You can limit it to Wi-Fi only.

How it behaves:
- Pins, routes, photos, Wi-Fi surveys, AP names and coverage patterns all sync.
- The latest edit of a pin or route wins. Deletes sync too.
- Phones without signal keep working and catch up later.
- Each phone's name is recorded on the server with its changes.

The server also hosts a **browser version**: open the server address (e.g. `https://map.weaselnet.net/`) on a PC and
sign in with the same key. From the browser you can view and edit pins, routes, coverage patterns, photos, AP names
and the Wi-Fi filter, and see the Wi-Fi heatmaps. See [server/README.md](server/README.md#browser-version).

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
  While surveying, the map and Wi-Fi screen show how often scans actually arrive and warn when Android is refusing them.
- All data stays on the device (Room database + app-private photo folder) until you export it.

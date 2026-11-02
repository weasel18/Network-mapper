# Network Mapper sync server

Lets several phones map at the same time and share sites, pins, paths, photos, Wi-Fi surveys and AP
names. It's a single small Python program (standard library only) with a SQLite database, run with Docker.

It also serves a **browser version** of the app at the same address (for example `https://map.yourcompany.com/`).

## Install on Debian

```sh
# 1. Docker (official convenience script) and git
sudo apt update && sudo apt install -y git openssl
curl -fsSL https://get.docker.com | sudo sh

# 2. Get the server files
git clone https://github.com/weasel18/Network-mapper.git
cd Network-mapper/server

# 3. Settings
cp .env.example .env
sed -i "s/^SYNC_KEY=.*/SYNC_KEY=$(openssl rand -hex 24)/" .env
nano .env          # set DOMAIN if you have one
grep SYNC_KEY .env # this is the access key you type into each phone
```

### Option A – with a domain name (recommended)

Point a DNS A record (e.g. `map.yourcompany.com`) at the VPS IP, open ports 80 and 443, then:

```sh
sudo docker compose up -d
```

Caddy gets a Let's Encrypt certificate automatically. In the app use `https://map.yourcompany.com`.

#### Hosts with their own firewall (OpenMPTCProuter / Shorewall, firewalld)

Shorewall drops anything not allowed and resets Docker's port rules when it restarts. Use host networking
and open the ports in Shorewall instead:

```sh
printf 'ACCEPT\tnet\t$FW\ttcp\t80,443\n' | sudo tee -a /etc/shorewall/rules
sudo shorewall check && sudo shorewall restart
sudo docker compose -f docker-compose.host.yml up -d --build
```

(After an OpenMPTCProuter update, check that the line is still in `/etc/shorewall/rules`.)

### Option B – IP address only

```sh
./make-cert.sh 203.0.113.10          # your VPS IP; prints the certificate fingerprint
sudo docker compose -f docker-compose.ip.yml up -d
```

In the app use `https://203.0.113.10`. The first time, the app shows the certificate fingerprint;
check it matches the one `make-cert.sh` printed, then tap **Trust**.

### Firewall

If you use `ufw`: `sudo ufw allow 80,443/tcp` (Option B only needs 443).

## Check it's running

```sh
curl https://map.yourcompany.com/api/health        # {"ok":true,...}
sudo docker compose logs -f sync
```

## Updating

```sh
cd Network-mapper && git pull && cd server
sudo docker compose up -d --build     # add -f docker-compose.ip.yml for Option B
```

## Backups

Everything lives in the `sync-data` Docker volume (`sync.db` + `photos/`):

```sh
sudo docker compose exec sync python -c "import sqlite3;sqlite3.connect('/data/sync.db').execute(\"VACUUM INTO '/data/backup.db'\")"
sudo docker compose cp sync:/data ./sync-backup-$(date +%F)
```

## Browser version

Open the server address in any browser (Chrome, Edge, Firefox, Safari; desktop or tablet) and sign in with
the same access key (`SYNC_KEY`). Give the browser a name, such as "Office PC", so you can see who changed what.

- **Map:** street, satellite and topographic layers, showing pins, routes and AP coverage patterns for the chosen site.
- **Wi-Fi heatmap:** pick *strongest signal*, a single BSSID, or a pin (shows only that AP's radios).
- **Pins:** **＋ Pin**, then click the map. Click a pin to edit its type, name, status, details, coverage pattern
  and notes, add photos (HEIC photos are converted to JPEG), or delete it. **Move pin** lets you drag it.
- **Locations (closets / racks):** several devices in one place show as a single pin with a chip per device type
  (e.g. `R` `S×2` `A`) and the location's name. Dropping or moving a pin within about 6 m of another one offers to
  add it to that location, or to create a new named location with it. Click the location to see its devices, add one,
  or move them all together. Open a device to take it out again. **☰ → Combine stacked pins** fixes pins already
  sitting on top of each other.
- **Routes:** **〰 Route**, choose the kind, then click the map. Clicking a pin snaps the route to it. Drag
  points to move them, and right-click (or Alt-click) a point to remove it. Then click **Finish**. **Edit / extend
  route** changes an existing route the same way.
- **Sites:** use the **⋯** button next to the site list to add, rename, zoom to or delete a site.
  **Save this view as start view** remembers where the map is (position and zoom): opening that site, on the web or
  on a phone, goes straight there. Without a start view, the map zooms to fit the site's pins.
- **☰ menu:** pins & routes list with search, AP names, Wi-Fi filter, phones & browsers that have synced,
  GeoJSON export of the site, and CSV export of the Wi-Fi survey.
- The browser checks for changes from the phones every 15 seconds. Phones pick up the browser's edits on their
  next sync.

Surveys (walking Wi-Fi scans) and GPS pins still come from the phones; browsers can't scan Wi-Fi.

### UniFi in the browser (optional)

The browser version can show live UniFi device status using Ubiquiti's **cloud** API (`api.ui.com`), so neither the
server nor your laptop needs a VPN to the console. The console must be linked to your UI.com account.

1. Sign in at [unifi.ui.com](https://unifi.ui.com), open **API** and create an API key (read access is enough).
2. On the VPS, add it to `server/.env`: `UNIFI_API_KEY=...` and restart:
   `sudo docker compose -f docker-compose.host.yml up -d`.

Then, on switch, gateway, AP and bridge pins:
- a **UniFi** section shows online/offline, model, IP, MAC, firmware (and available updates), uptime and the console;
- pins whose device is offline get a red ring on the map (a location pin turns red if any device in it is offline);
- **Fill details** copies name, model, IP and MAC from a UniFi device you pick (then press Save).

Pins are matched to UniFi devices by the MAC in their details (an AP's radio BSSID works too), or by exactly the
same name. The device list refreshes every minute; **Refresh** fetches it immediately. The key stays on the server.
The cloud API gives device-level status only; per-port and per-client details still come from the phone app's
direct connection to the console.

## How sync works

- Every record has a permanent ID shared by all phones. Phones **push** what changed and **pull**
  everything newer than what they've seen; the server hands out a running sequence number.
- **Latest edit wins** per record (pin, path, site, photo details, AP name). Survey readings and photos
  are only ever added, so they never conflict. Deleting syncs too.
- The access key is a shared team password (`Authorization: Bearer …`); each phone also sends its
  name, which is recorded with its changes.

## Tests

```sh
python3 -m unittest test_server.py
```

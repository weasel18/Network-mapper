# Network Mapper sync server

Lets several phones map at the same time and share sites, pins, paths, photos, Wi-Fi surveys and AP
names. It's a single small Python program (standard library only) with a SQLite database, run with Docker.

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

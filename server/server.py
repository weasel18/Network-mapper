#!/usr/bin/env python3
"""
Network Mapper sync server.

A small, dependency-free (Python standard library only) server that lets several phones share
sites, pins, paths, photos, Wi-Fi surveys and AP names. Each phone pushes its changes and pulls
everyone else's; the newest edit of a record wins.

Storage: one SQLite file plus a folder of photo files, both under DATA_DIR.

Environment:
  SYNC_KEY    required  shared team access key (send as "Authorization: Bearer <key>")
  DATA_DIR    default /data
  PORT        default 8080
  HOST        default 0.0.0.0 (use 127.0.0.1 behind a proxy on the host network)
  TLS_CERT / TLS_KEY   optional; serve HTTPS directly (for IP-only setups without a proxy)
  MAX_PHOTO_MB         default 25
  UNIFI_API_KEY        optional; UniFi Site Manager (cloud) API key from unifi.ui.com -> Settings -> API.
                       Lets the browser app show UniFi device status. The key stays on the server.

API (all JSON unless noted, all require the key except /api/health):
  GET  /api/health                         -> {"ok": true, "version": ...}
  GET  /api/sites                          -> {"sites": [{id, name, notes, updatedAt, assets, paths, sessions}]}
  POST /api/push   {"changes": [change]}   -> {"applied": n, "ignored": n, "seq": n}
  GET  /api/pull?since=S[&sites=a,b][&collections=c,d][&exclude_device=X][&limit=N]
                                           -> {"changes": [change+seq], "next": S2, "more": bool}
  PUT  /api/photos/<uid>   (raw bytes)     -> {"ok": true}
  GET  /api/photos/<uid>                   -> raw bytes / 404
  GET  /api/devices                        -> {"devices": [{id, name, lastSeen}]}
  GET  /api/unifi[?refresh=1]              -> {"configured": bool, "devices": [...], "fetchedAt": ms, "error"?: str}
  GET  /, /<file>                          -> the browser app (web/), no key needed to load the page

A change is {"c": collection, "id": uid, "site": siteUid, "parent": parentUid, "u": updatedAtMs,
             "d": deleted, "data": {...}}.
"""

import hmac
import json
import os
import re
import sqlite3
import ssl
import sys
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import urllib.error
import urllib.request
from urllib.parse import parse_qs, quote, urlparse

VERSION = "1.3.0"
WEB_DIR = os.environ.get("WEB_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "web"))
CONTENT_TYPES = {
    ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
    ".png": "image/png", ".svg": "image/svg+xml", ".ico": "image/x-icon", ".json": "application/json",
}
COLLECTIONS = {"sites", "assets", "paths", "photos", "sessions", "readings", "ap_names", "settings"}
# Not tied to a site: AP names and shared settings (e.g. the Wi-Fi filter).
GLOBAL_COLLECTIONS = {"ap_names", "settings"}
ID_RE = re.compile(r"^[A-Za-z0-9:._-]{1,80}$")
PHOTO_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
MAX_JSON_BYTES = 32 * 1024 * 1024


class Store:
    """All database access, serialised by one lock (SQLite + threads)."""

    def __init__(self, data_dir):
        os.makedirs(data_dir, exist_ok=True)
        self.photo_dir = os.path.join(data_dir, "photos")
        os.makedirs(self.photo_dir, exist_ok=True)
        self.db = sqlite3.connect(os.path.join(data_dir, "sync.db"), check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=NORMAL")
        self.lock = threading.Lock()
        with self.lock, self.db:
            self.db.executescript(
                """
                CREATE TABLE IF NOT EXISTS records (
                    c TEXT NOT NULL,
                    id TEXT NOT NULL,
                    site TEXT NOT NULL DEFAULT '',
                    parent TEXT NOT NULL DEFAULT '',
                    u INTEGER NOT NULL,
                    d INTEGER NOT NULL DEFAULT 0,
                    data TEXT,
                    device TEXT NOT NULL DEFAULT '',
                    device_name TEXT NOT NULL DEFAULT '',
                    seq INTEGER NOT NULL,
                    PRIMARY KEY (c, id)
                );
                CREATE INDEX IF NOT EXISTS records_seq ON records(seq);
                CREATE INDEX IF NOT EXISTS records_site ON records(site, seq);
                CREATE INDEX IF NOT EXISTS records_parent ON records(parent);
                CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v INTEGER NOT NULL);
                INSERT OR IGNORE INTO meta (k, v) VALUES ('seq', 0);
                CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, last_seen INTEGER NOT NULL
                );
                """
            )

    def _next_seq(self):
        self.db.execute("UPDATE meta SET v = v + 1 WHERE k = 'seq'")
        return self.db.execute("SELECT v FROM meta WHERE k = 'seq'").fetchone()[0]

    def seen(self, device, name):
        if not device:
            return
        with self.lock, self.db:
            self.db.execute(
                "INSERT INTO devices (id, name, last_seen) VALUES (?, ?, ?) "
                "ON CONFLICT(id) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen",
                (device, name or device, int(time.time() * 1000)),
            )

    def push(self, changes, device, device_name):
        applied = ignored = 0
        removed_photos = []
        with self.lock, self.db:
            for ch in changes:
                c = ch.get("c")
                rid = str(ch.get("id", ""))
                if c not in COLLECTIONS or not ID_RE.match(rid):
                    ignored += 1
                    continue
                u = int(ch.get("u") or 0)
                deleted = bool(ch.get("d"))
                site = str(ch.get("site") or "")
                parent = str(ch.get("parent") or "")
                data = None if deleted else json.dumps(ch.get("data") or {}, separators=(",", ":"))
                row = self.db.execute(
                    "SELECT u, device, site, parent FROM records WHERE c = ? AND id = ?", (c, rid)
                ).fetchone()
                if row is not None:
                    old_u, old_dev, old_site, old_parent = row
                    # Last writer wins; ties broken by device id so every server agrees.
                    if old_u > u or (old_u == u and old_dev >= device):
                        ignored += 1
                        continue
                    # Deletions often arrive without context; keep what we knew.
                    site = site or old_site
                    parent = parent or old_parent
                seq = self._next_seq()
                self.db.execute(
                    "INSERT INTO records (c, id, site, parent, u, d, data, device, device_name, seq) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    "ON CONFLICT(c, id) DO UPDATE SET site = excluded.site, parent = excluded.parent, "
                    "u = excluded.u, d = excluded.d, data = excluded.data, device = excluded.device, "
                    "device_name = excluded.device_name, seq = excluded.seq",
                    (c, rid, site, parent, u, 1 if deleted else 0, data, device, device_name, seq),
                )
                if deleted and c == "sessions":
                    # Readings are owned by their survey session.
                    self.db.execute("DELETE FROM records WHERE c = 'readings' AND parent = ?", (rid,))
                if deleted and c == "photos" and PHOTO_RE.match(rid):
                    removed_photos.append(rid)
                applied += 1
            seq = self.db.execute("SELECT v FROM meta WHERE k = 'seq'").fetchone()[0]
        # A deleted photo's image file goes too (after the delete is committed).
        for rid in removed_photos:
            try:
                os.remove(self.photo_path(rid))
            except FileNotFoundError:
                pass
        return {"applied": applied, "ignored": ignored, "seq": seq}

    def pull(self, since, sites, collections, exclude_device, limit):
        where = ["seq > ?"]
        args = [since]
        if sites:
            marks = ",".join("?" * len(sites))
            glob = ",".join("?" * len(GLOBAL_COLLECTIONS))
            where.append(f"((c = 'sites' AND id IN ({marks})) OR c IN ({glob}) OR (c != 'sites' AND site IN ({marks})))")
            args += list(sites) + sorted(GLOBAL_COLLECTIONS) + list(sites)
        if collections:
            marks = ",".join("?" * len(collections))
            where.append(f"c IN ({marks})")
            args += list(collections)
        if exclude_device:
            where.append("device != ?")
            args.append(exclude_device)
        sql = (
            "SELECT c, id, site, parent, u, d, data, device_name, seq FROM records WHERE "
            + " AND ".join(where)
            + " ORDER BY seq LIMIT ?"
        )
        with self.lock:
            rows = self.db.execute(sql, args + [limit + 1]).fetchall()
            top = self.db.execute("SELECT v FROM meta WHERE k = 'seq'").fetchone()[0]
        more = len(rows) > limit
        rows = rows[:limit]
        changes = []
        for c, rid, site, parent, u, d, data, dev_name, seq in rows:
            ch = {"c": c, "id": rid, "site": site, "parent": parent, "u": u, "d": bool(d), "seq": seq, "by": dev_name}
            if not d and data:
                ch["data"] = json.loads(data)
            changes.append(ch)
        # Not truncated: every matching record up to `top` was returned (same lock), so the cursor can
        # jump there and filtered pulls don't rescan non-matching records.
        nxt = rows[-1][8] if more else max(since, top)
        return {"changes": changes, "next": nxt, "more": more}

    def sites(self):
        with self.lock:
            rows = self.db.execute(
                "SELECT id, data, u FROM records WHERE c = 'sites' AND d = 0 ORDER BY u DESC"
            ).fetchall()
            counts = {}
            for c, site, n in self.db.execute(
                "SELECT c, site, COUNT(*) FROM records WHERE d = 0 AND c IN ('assets','paths','sessions') GROUP BY c, site"
            ):
                counts.setdefault(site, {})[c] = n
        out = []
        for rid, data, u in rows:
            d = json.loads(data or "{}")
            n = counts.get(rid, {})
            out.append({
                "id": rid, "name": d.get("name", ""), "notes": d.get("notes", ""), "updatedAt": u,
                "assets": n.get("assets", 0), "paths": n.get("paths", 0), "sessions": n.get("sessions", 0),
            })
        return {"sites": out}

    def devices(self):
        with self.lock:
            rows = self.db.execute("SELECT id, name, last_seen FROM devices ORDER BY last_seen DESC").fetchall()
        return {"devices": [{"id": i, "name": n, "lastSeen": t} for i, n, t in rows]}

    def photo_path(self, uid):
        return os.path.join(self.photo_dir, uid)


class Handler(BaseHTTPRequestHandler):
    server_version = "NetworkMapperSync/" + VERSION
    store: Store = None
    key: str = ""
    max_photo = 25 * 1024 * 1024

    def log_message(self, fmt, *args):  # quieter, single-line logs
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    # ---- helpers ----
    def _send(self, status, body=b"", ctype="application/json", cache="no-store"):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cache-Control", cache)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, status, obj):
        self._send(status, json.dumps(obj, separators=(",", ":")).encode())

    def _error(self, status, msg):
        self._json(status, {"error": msg})

    def _authorised(self):
        auth = self.headers.get("Authorization", "")
        token = auth[7:] if auth.startswith("Bearer ") else ""
        if not token or not hmac.compare_digest(token.encode(), self.key.encode()):
            self._error(HTTPStatus.UNAUTHORIZED, "bad or missing access key")
            return False
        self.store.seen(self.headers.get("X-Device-Id", ""), self.headers.get("X-Device-Name", ""))
        return True

    def _body(self, limit):
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit:
            raise ValueError("body too large")
        return self.rfile.read(n) if n else b""

    # ---- routes ----
    def _static(self, path):
        """Serves the browser app from WEB_DIR (no directory listing, no path traversal)."""
        rel = "index.html" if path in ("", "/") else path.lstrip("/")
        full = os.path.realpath(os.path.join(WEB_DIR, rel))
        root = os.path.realpath(WEB_DIR)
        if not full.startswith(root + os.sep) or not os.path.isfile(full):
            return self._error(404, "not found")
        ext = os.path.splitext(full)[1].lower()
        with open(full, "rb") as f:
            body = f.read()
        cache = "no-cache" if ext in (".html", ".js", ".css") else "public, max-age=86400"
        self._send(200, body, CONTENT_TYPES.get(ext, "application/octet-stream"), cache)

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/api/health":
            return self._json(200, {"ok": True, "version": VERSION})
        if not url.path.startswith("/api/"):
            return self._static(url.path)
        if not self._authorised():
            return
        q = parse_qs(url.query)
        if url.path == "/api/sites":
            return self._json(200, self.store.sites())
        if url.path == "/api/devices":
            return self._json(200, self.store.devices())
        if url.path == "/api/unifi":
            return self._json(200, self.unifi.devices(refresh=q.get("refresh", ["0"])[0] == "1"))
        if url.path == "/api/pull":
            try:
                since = int(q.get("since", ["0"])[0])
                limit = max(1, min(int(q.get("limit", ["1000"])[0]), 5000))
            except ValueError:
                return self._error(400, "since/limit must be numbers")
            sites = [s for s in q.get("sites", [""])[0].split(",") if s]
            cols = [c for c in q.get("collections", [""])[0].split(",") if c in COLLECTIONS]
            exclude = q.get("exclude_device", [""])[0]
            return self._json(200, self.store.pull(since, sites, cols, exclude, limit))
        m = re.match(r"^/api/photos/([^/]+)$", url.path)
        if m:
            uid = m.group(1)
            if not PHOTO_RE.match(uid):
                return self._error(400, "bad photo id")
            p = self.store.photo_path(uid)
            if not os.path.exists(p):
                return self._error(404, "no such photo")
            with open(p, "rb") as f:
                return self._send(200, f.read(), "application/octet-stream")
        self._error(404, "not found")

    do_HEAD = do_GET

    def do_POST(self):
        url = urlparse(self.path)
        if not self._authorised():
            return
        if url.path != "/api/push":
            return self._error(404, "not found")
        try:
            body = json.loads(self._body(MAX_JSON_BYTES) or b"{}")
            changes = body.get("changes", [])
            if not isinstance(changes, list):
                raise ValueError("changes must be a list")
        except (ValueError, json.JSONDecodeError) as e:
            return self._error(400, "bad request: %s" % e)
        dev = self.headers.get("X-Device-Id", "")
        name = self.headers.get("X-Device-Name", "")
        self._json(200, self.store.push(changes, dev, name))

    def do_PUT(self):
        url = urlparse(self.path)
        if not self._authorised():
            return
        m = re.match(r"^/api/photos/([^/]+)$", url.path)
        if not m or not PHOTO_RE.match(m.group(1)):
            return self._error(404, "not found")
        try:
            data = self._body(self.max_photo)
        except ValueError:
            return self._error(413, "photo too large")
        tmp = self.store.photo_path(m.group(1)) + ".part"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, self.store.photo_path(m.group(1)))
        self._json(200, {"ok": True})


def _mac(value):
    hexes = re.sub(r"[^0-9a-fA-F]", "", str(value or "")).lower()
    return ":".join(hexes[i:i + 2] for i in range(0, 12, 2)) if len(hexes) == 12 else ""


class UnifiCloud:
    """Device list from the UniFi Site Manager API (api.ui.com), cached briefly. The API key never leaves the server."""

    def __init__(self, api_key, base_url="https://api.ui.com", ttl=60):
        # Tolerate KEY="..." / '...' and stray spaces or Windows line endings in .env.
        self.api_key = (api_key or "").strip().strip('"').strip("'").strip()
        self.base_url = base_url.rstrip("/")
        self.ttl = ttl
        self.lock = threading.Lock()
        self.cached = None
        self.cached_at = 0.0

    def _get(self, path):
        req = urllib.request.Request(self.base_url + path, headers={"X-API-KEY": self.api_key, "Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read().decode("utf-8"))

    @staticmethod
    def _device(d, host):
        return {
            "mac": _mac(d.get("mac")),
            "name": d.get("name") or "",
            "model": d.get("model") or d.get("shortname") or "",
            "ip": d.get("ip") or "",
            "status": (d.get("status") or "").lower(),
            "firmware": d.get("version") or "",
            "updateAvailable": d.get("updateAvailable") or "",
            "productLine": d.get("productLine") or "",
            "isConsole": bool(d.get("isConsole")),
            "startupTime": d.get("startupTime") or "",
            "console": host.get("hostName") or "",
        }

    def devices(self, refresh=False):
        if not self.api_key:
            return {"configured": False, "devices": []}
        with self.lock:
            if self.cached and not refresh and time.time() - self.cached_at < self.ttl:
                return self.cached
            try:
                out, token = [], None
                for _ in range(50):  # pages
                    body = self._get("/v1/devices" + (f"?nextToken={quote(token)}" if token else ""))
                    for host in body.get("data") or []:
                        for d in host.get("devices") or []:
                            out.append(self._device(d, host))
                    token = body.get("nextToken")
                    if not token:
                        break
                self.cached = {"configured": True, "devices": out, "fetchedAt": int(time.time() * 1000)}
                self.cached_at = time.time()
                return self.cached
            except urllib.error.HTTPError as e:
                detail = ""
                try:
                    body = json.loads(e.read().decode("utf-8", "replace") or "{}")
                    detail = str(body.get("message") or body.get("error") or body.get("code") or "")[:200]
                except (ValueError, OSError, AttributeError):
                    pass
                said = f" – api.ui.com said: HTTP {e.code}" + (f" “{detail}”" if detail else "")
                if e.code in (401, 403):
                    msg = ("The UniFi cloud refused the API key (check UNIFI_API_KEY; it must be a Site Manager key from "
                           f"unifi.ui.com → API, {len(self.api_key)} characters received){said}")
                else:
                    msg = f"UniFi cloud error{said}"
            except (urllib.error.URLError, TimeoutError, OSError) as e:
                msg = f"Can't reach the UniFi cloud ({getattr(e, 'reason', e)})"
            except ValueError:
                msg = "Unexpected answer from the UniFi cloud"
            stale = dict(self.cached or {"configured": True, "devices": []})
            stale["error"] = msg
            return stale


def make_server(data_dir, key, port, host="0.0.0.0", cert=None, keyfile=None, max_photo_mb=25, unifi=None):
    if not key or len(key) < 16:
        raise SystemExit("SYNC_KEY must be set to a random string of at least 16 characters")
    handler = type("BoundHandler", (Handler,), {
        "store": Store(data_dir), "key": key, "max_photo": max_photo_mb * 1024 * 1024,
        "unifi": unifi or UnifiCloud(""),
    })
    httpd = ThreadingHTTPServer((host, port), handler)
    if cert and keyfile:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cert, keyfile)
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    return httpd


def main():
    httpd = make_server(
        data_dir=os.environ.get("DATA_DIR", "/data"),
        key=os.environ.get("SYNC_KEY", ""),
        port=int(os.environ.get("PORT", "8080")),
        host=os.environ.get("HOST", "0.0.0.0"),
        cert=os.environ.get("TLS_CERT"),
        keyfile=os.environ.get("TLS_KEY"),
        max_photo_mb=int(os.environ.get("MAX_PHOTO_MB", "25")),
        unifi=UnifiCloud(os.environ.get("UNIFI_API_KEY", ""), os.environ.get("UNIFI_API_URL", "https://api.ui.com")),
    )
    scheme = "https" if os.environ.get("TLS_CERT") else "http"
    print(f"Network Mapper sync server {VERSION} on {scheme}://{httpd.server_address[0]}:{httpd.server_port}", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()

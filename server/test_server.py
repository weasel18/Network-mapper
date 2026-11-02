"""Run: python3 -m unittest test_server.py"""
import json
import os
import shutil
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

import server

KEY = "test-key-0123456789abcdef"


class SyncServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp()
        cls.httpd = server.make_server(cls.dir, KEY, 0, host="127.0.0.1")
        cls.base = "http://127.0.0.1:%d" % cls.httpd.server_port
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        shutil.rmtree(cls.dir)

    def call(self, method, path, body=None, device="dev-a", key=KEY, raw=False):
        data = body if isinstance(body, (bytes, type(None))) else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Authorization", "Bearer " + key)
        req.add_header("X-Device-Id", device)
        req.add_header("X-Device-Name", device.upper())
        if data is not None and not raw:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req) as r:
                out = r.read()
                return r.status, (out if raw else json.loads(out))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def pull_all(self, **params):
        since, out = 0, []
        while True:
            q = "&".join(["since=%d" % since, "limit=2"] + ["%s=%s" % kv for kv in params.items()])
            _, r = self.call("GET", "/api/pull?" + q)
            out += r["changes"]
            since = r["next"]
            if not r["more"]:
                return out, since

    def test_01_auth_and_health(self):
        self.assertEqual(self.call("GET", "/api/health", key="")[0], 200)
        self.assertEqual(self.call("GET", "/api/sites", key="wrong-key-xxxxxxxxxx")[0], 401)

    def test_02_push_pull_last_writer_wins_and_filters(self):
        site = {"c": "sites", "id": "s1", "site": "s1", "u": 1000, "data": {"name": "HQ"}}
        other = {"c": "sites", "id": "s2", "site": "s2", "u": 1000, "data": {"name": "Branch"}}
        pin = {"c": "assets", "id": "a1", "site": "s1", "u": 1000, "data": {"name": "SP-1"}}
        pin2 = {"c": "assets", "id": "a2", "site": "s2", "u": 1000, "data": {"name": "AP-B"}}
        name = {"c": "ap_names", "id": "aa:bb:cc:dd:ee:01", "u": 1000, "data": {"name": "AP-Lobby"}}
        code, r = self.call("POST", "/api/push", {"changes": [site, other, pin, pin2, name]})
        self.assertEqual((code, r["applied"]), (200, 5))

        # Older edit from another phone loses; newer wins.
        _, r = self.call("POST", "/api/push", {"changes": [dict(pin, u=900, data={"name": "old"})]}, device="dev-b")
        self.assertEqual(r["ignored"], 1)
        _, r = self.call("POST", "/api/push", {"changes": [dict(pin, u=2000, data={"name": "SP-1 renamed"})]}, device="dev-b")
        self.assertEqual(r["applied"], 1)

        changes, top = self.pull_all()
        a1 = [c for c in changes if c["id"] == "a1"]
        self.assertEqual(len(a1), 1)
        self.assertEqual(a1[0]["data"]["name"], "SP-1 renamed")
        self.assertEqual(a1[0]["by"], "DEV-B")

        # Site filter: only s1 records + global AP names.
        changes, _ = self.pull_all(sites="s1")
        self.assertEqual({c["id"] for c in changes}, {"s1", "a1", "aa:bb:cc:dd:ee:01"})

        # exclude_device hides a phone's own changes.
        changes, _ = self.pull_all(exclude_device="dev-a")
        self.assertEqual({c["id"] for c in changes}, {"a1"})

        # Cursor: nothing new after the top.
        _, r = self.call("GET", "/api/pull?since=%d" % top)
        self.assertEqual(r["changes"], [])

        _, r = self.call("GET", "/api/sites")
        hq = [s for s in r["sites"] if s["id"] == "s1"][0]
        self.assertEqual((hq["name"], hq["assets"]), ("HQ", 1))

    def test_03_delete_keeps_site_and_cascades_readings(self):
        sess = {"c": "sessions", "id": "w1", "site": "s9", "u": 1, "data": {"name": "Walk"}}
        rd = [{"c": "readings", "id": "r%d" % i, "site": "s9", "parent": "w1", "u": 1, "data": {"rssi": -50}} for i in range(3)]
        self.call("POST", "/api/push", {"changes": [sess] + rd})
        # Tombstone without site context still reaches phones syncing only s9.
        self.call("POST", "/api/push", {"changes": [{"c": "sessions", "id": "w1", "u": 5, "d": True}]})
        changes, _ = self.pull_all(sites="s9")
        mine = [(c["id"], c["d"]) for c in changes if c["c"] != "ap_names"]  # AP names are global
        self.assertEqual(mine, [("w1", True)])

    def test_05_shared_settings_are_global(self):
        f = {"c": "settings", "id": "wifi_filter", "u": 10, "data": {"only": ["Corp"]}}
        self.assertEqual(self.call("POST", "/api/push", {"changes": [f]})[1]["applied"], 1)
        changes, _ = self.pull_all(sites="some-other-site", collections="settings")
        self.assertEqual([c["data"]["only"] for c in changes], [["Corp"]])

    def test_04_photos(self):
        code, _ = self.call("PUT", "/api/photos/0123456789abcdef", b"\xff\xd8jpegbytes", raw=True)
        self.assertEqual(code, 200)
        code, body = self.call("GET", "/api/photos/0123456789abcdef", raw=True)
        self.assertEqual((code, body), (200, b"\xff\xd8jpegbytes"))
        self.assertEqual(self.call("GET", "/api/photos/missing-photo-1", raw=False)[0], 404)
        self.assertEqual(self.call("GET", "/api/photos/..%2Fsync.db")[0], 400)

        # Deleting the photo record removes the image file too.
        rec = {"c": "photos", "id": "0123456789abcdef", "site": "s-photo", "parent": "pin-1", "u": 1000, "d": False,
               "data": {"asset": "pin-1", "fileName": "a.jpg"}}
        self.call("POST", "/api/push", {"changes": [rec]})
        self.call("POST", "/api/push", {"changes": [dict(rec, u=2000, d=True, data=None)]})
        self.assertEqual(self.call("GET", "/api/photos/0123456789abcdef", raw=True)[0], 404)


class UnifiCloudTest(unittest.TestCase):
    """The /api/unifi proxy, against a fake api.ui.com."""

    def test_devices_paging_cache_and_errors(self):
        import http.server
        calls = []

        class Fake(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                calls.append((self.path, self.headers.get("X-API-KEY")))
                if self.headers.get("X-API-KEY") != "good-key":
                    self.send_response(401); self.end_headers(); return
                if "nextToken=p2" in self.path:
                    body = {"data": [{"hostName": "UDM Pro", "devices": [
                        {"mac": "7483C2AABB02", "name": "Lobby AP", "model": "U6 Pro", "ip": "10.0.0.21", "status": "offline",
                         "version": "6.6.77", "productLine": "network"}]}]}
                else:
                    body = {"data": [{"hostName": "UDM Pro", "devices": [
                        {"mac": "74:83:C2:AA:BB:01", "name": "Core SW", "shortname": "USW-24-PoE", "ip": "10.0.0.2", "status": "online",
                         "version": "7.1.66", "updateAvailable": "7.2.1", "isConsole": False}]}], "nextToken": "p2"}
                raw = json.dumps(body).encode()
                self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers(); self.wfile.write(raw)

        fake = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Fake)
        threading.Thread(target=fake.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{fake.server_port}"
        try:
            self.assertEqual(server.UnifiCloud("").devices(), {"configured": False, "devices": []})

            cloud = server.UnifiCloud("good-key", base)
            r = cloud.devices()
            self.assertTrue(r["configured"])
            self.assertEqual([d["mac"] for d in r["devices"]], ["74:83:c2:aa:bb:01", "74:83:c2:aa:bb:02"])
            sw = r["devices"][0]
            self.assertEqual((sw["name"], sw["model"], sw["status"], sw["updateAvailable"], sw["console"]),
                             ("Core SW", "USW-24-PoE", "online", "7.2.1", "UDM Pro"))
            self.assertEqual(r["devices"][1]["status"], "offline")
            n = len(calls)
            cloud.devices()  # cached
            self.assertEqual(len(calls), n)
            cloud.devices(refresh=True)
            self.assertEqual(len(calls), n + 2)

            quoted = server.UnifiCloud(' "good-key"\r', base).devices()  # KEY="..." with a Windows line ending
            self.assertNotIn("error", quoted)
            self.assertEqual(len(quoted["devices"]), 2)

            bad = server.UnifiCloud("wrong-key", base).devices()
            self.assertIn("refused the API key", bad["error"])
            self.assertIn("HTTP 401", bad["error"])
            self.assertIn("9 characters", bad["error"])
            self.assertEqual(bad["devices"], [])
        finally:
            fake.shutdown()


if __name__ == "__main__":
    unittest.main()

/* Network Mapper – browser app. Talks to the same sync API as the phones (server.py). */
"use strict";

// ---------------------------------------------------------------- shared model (mirrors the Android app)

const ASSET_TYPES = {
  FIBER_JUNCTION: { label: "Fiber junction / Handhole", color: "#1E88E5", glyph: "J", fields: ["Box ID", "Box size", "Lid type", "Depth", "Conduits in/out"] },
  SPLICE: { label: "Splice enclosure", color: "#8E24AA", glyph: "X", fields: ["Enclosure ID", "Enclosure model", "Tray count", "Splice count", "Fiber count", "Cables in", "Splice diagram ref"] },
  FDH: { label: "Fiber cabinet / FDH / Patch panel", color: "#5E35B1", glyph: "F", fields: ["Cabinet ID", "Model", "Port count", "Splitter ratio", "Feeder cable"] },
  SLACK_LOOP: { label: "Slack loop / Coil", color: "#039BE5", glyph: "L", fields: ["Cable ID", "Slack length", "Fiber count"] },
  ACCESS_POINT: { label: "Wi-Fi access point", color: "#43A047", glyph: "A", fields: ["SSID", "MAC / BSSID", "IP address", "Model", "Channel", "Mount height", "Switch port"], bssidField: "MAC / BSSID" },
  WIRELESS_BRIDGE: { label: "Wireless bridge / PtP radio", color: "#FB8C00", glyph: "B", fields: ["Link name", "Model", "IP address", "MAC", "Frequency", "Channel width", "Azimuth (°)", "Tilt (°)", "Peer device", "Signal (dBm)"], bssidField: "MAC" },
  SWITCH: { label: "Switch", color: "#00897B", glyph: "S", fields: ["Hostname", "Model", "IP address", "MAC", "Port count", "Uplink", "VLANs", "PoE budget"] },
  ROUTER: { label: "Router / Gateway / OLT", color: "#3949AB", glyph: "R", fields: ["Hostname", "Model", "IP address", "WAN / Uplink", "Serial", "Wi-Fi MAC / BSSID"], bssidField: "Wi-Fi MAC / BSSID" },
  POLE: { label: "Pole / Tower", color: "#6D4C41", glyph: "P", fields: ["Pole ID", "Owner", "Height", "Attachment height", "Make-ready notes"] },
  BUILDING_ENTRY: { label: "Building entry / Demarc", color: "#D81B60", glyph: "D", fields: ["Address", "Room / Location", "Contact", "Demarc type"] },
  LOCATION: { label: "Location / Closet / Rack", color: "#37474F", glyph: "▣", fields: ["Building / Floor", "Room", "Rack / Cabinet", "Access / Keys", "Power / UPS", "Contact"] },
  OTHER: { label: "Other / Photo pin", color: "#757575", glyph: "?", fields: [] },
};
// Pins dropped or moved this close to another pin are offered to share its location (closet / rack).
const GROUP_RADIUS_M = 6;
const CHIP_PRIORITY = ["ROUTER", "SWITCH", "ACCESS_POINT", "WIRELESS_BRIDGE", "FDH", "SPLICE", "FIBER_JUNCTION", "SLACK_LOOP", "BUILDING_ENTRY", "POLE", "OTHER"];
const PATH_KINDS = {
  FIBER_UNDERGROUND: { label: "Fiber – underground", color: "#1565C0", dashed: false, fiber: true, cable: "Cable type", fields: ["Cable ID", "Manufacturer", "Buffer tubes", "Conduit size", "Depth", "Install date"] },
  FIBER_AERIAL: { label: "Fiber – aerial", color: "#6A1B9A", dashed: false, fiber: true, cable: "Cable type", fields: ["Cable ID", "Manufacturer", "Buffer tubes", "Strand / Messenger", "Install date"] },
  CONDUIT: { label: "Conduit / Duct (empty)", color: "#757575", dashed: true, fiber: false, cable: "Duct material", fields: ["Conduit size", "Count", "Depth", "Pull tape"] },
  WIRELESS_LINK: { label: "Wireless link (PtP / PtMP)", color: "#EF6C00", dashed: true, fiber: false, cable: null, fields: ["Frequency", "Channel width", "Capacity", "Signal (dBm)", "SSID"] },
  COPPER: { label: "Copper / Ethernet", color: "#2E7D32", dashed: false, fiber: false, cable: "Cable category", fields: ["Pairs", "Shielded / UTP", "PoE", "Termination", "Install date"] },
};
const STATUSES = ["Active", "Planned", "Under construction", "Inactive", "Damaged", "Abandoned"];
const PATH_COLORS = [0xFF1565C0, 0xFF6A1B9A, 0xFFEF6C00, 0xFF2E7D32, 0xFFC62828, 0xFF00838F, 0xFFF9A825, 0xFF4E342E, 0xFF212121, 0xFFAD1457];
const PATTERN_KEYS = { pattern: "Coverage pattern", azimuth: "Azimuth (°)", beam: "Beamwidth (°)", range: "Coverage range (m)" };
const COLLECTIONS = "sites,assets,paths,photos,sessions,ap_names,settings";
const WIFI_SVG = (px) => `<svg class="wifi-ico" viewBox="0 0 24 24" width="${px}" height="${px}" fill="none" stroke="#fff" stroke-width="2.8" stroke-linecap="round" role="img" aria-label="Wi-Fi AP"><path d="M2.5 9.2a13.5 13.5 0 0 1 19 0"/><path d="M6 12.8a8.5 8.5 0 0 1 12 0"/><path d="M9.5 16.3a3.6 3.6 0 0 1 5 0"/><circle cx="12" cy="19.6" r="1.7" fill="#fff" stroke="none"/></svg>`;
/** What's drawn inside a pin: a Wi-Fi icon for access points, otherwise the type's letter. */
const glyphHtml = (type, px = 18) => (type === "ACCESS_POINT" ? WIFI_SVG(px) : esc((ASSET_TYPES[type] || ASSET_TYPES.OTHER).glyph));

// ---------------------------------------------------------------- helpers

const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const uid = () => crypto.randomUUID();
const argbToCss = (n) => "#" + (Number(n) % 0x1000000).toString(16).padStart(6, "0");
const cssToArgb = (hex) => 0xFF000000 + parseInt(hex.slice(1), 16);
const num = (s) => { const m = String(s ?? "").match(/-?\d+(?:[.,]\d+)?/); return m ? parseFloat(m[0].replace(",", ".")) : null; };
const fmtTime = (ms) => (ms ? new Date(ms).toLocaleString() : "");
const decodePoints = (s) => String(s || "").split(";").map((p) => p.split(",").map(Number)).filter((p) => p.length === 2 && p.every(Number.isFinite));
const encodePoints = (pts) => pts.map(([a, b]) => `${a},${b}`).join(";");
function distanceM(a, b) {
  const R = 6371008.8, t = Math.PI / 180;
  const dLat = (b[0] - a[0]) * t, dLon = (b[1] - a[1]) * t;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a[0] * t) * Math.cos(b[0] * t) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}
const pathLength = (pts) => pts.slice(1).reduce((s, p, i) => s + distanceM(pts[i], p), 0);
const fmtDist = (m) => (m < 1000 ? `${Math.round(m)} m (${Math.round(m * 3.28084)} ft)` : `${(m / 1000).toFixed(2)} km (${(m / 1609.344).toFixed(2)} mi)`);
function rssiColor(r) {
  if (r >= -50) return "#1b9e3e"; if (r >= -60) return "#7cb342"; if (r >= -67) return "#fdd835";
  if (r >= -75) return "#fb8c00"; if (r >= -85) return "#e53935"; return "#8e0000";
}
function parseMacs(text) {
  const re = /(?<![0-9a-f])([0-9a-f]{2})[:-]?([0-9a-f]{2})[:-]?([0-9a-f]{2})[:-]?([0-9a-f]{2})[:-]?([0-9a-f]{2})[:-]?([0-9a-f]{2})(?![0-9a-f])/gi;
  return [...String(text || "").matchAll(re)].map((m) => m.slice(1, 7).join(":").toLowerCase());
}

// ---------------------------------------------------------------- state

const S = {
  key: "", deviceId: "", deviceName: "",
  cursor: 0,
  sites: new Map(), assets: new Map(), paths: new Map(), photos: new Map(), sessions: new Map(),
  apNames: new Map(), filter: { only: [], ssids: [], bssids: [], noName: false }, filterU: 0,
  site: "", selected: null, // {c:"assets"|"paths", id}
  mode: null, // null | "add-pin" | "draw" | "edit-route" | "move-pin"
  draft: null, // { kind, points:[[lat,lon]], startAsset, endAsset, editing: uid|null }
  readings: new Map(), // siteUid -> {since, list}
  photoUrls: new Map(),
};

// ---------------------------------------------------------------- API

async function api(method, path, body, raw = false) {
  const headers = { Authorization: "Bearer " + S.key, "X-Device-Id": S.deviceId, "X-Device-Name": S.deviceName };
  if (body !== undefined && !raw) headers["Content-Type"] = "application/json";
  const res = await fetch(path, { method, headers, body: body === undefined ? undefined : raw ? body : JSON.stringify(body) });
  if (res.status === 401) { throw Object.assign(new Error("The access key was refused."), { auth: true }); }
  if (!res.ok) throw new Error(`Server error ${res.status}`);
  return raw === "blob" ? res.blob() : res.json();
}

/** Applies one change (from the server or our own push) to local state. */
function applyChange(ch) {
  const map = { sites: S.sites, assets: S.assets, paths: S.paths, photos: S.photos, sessions: S.sessions, ap_names: S.apNames }[ch.c];
  if (ch.c === "settings") {
    if (ch.id === "wifi_filter" && !ch.d && ch.u >= S.filterU) { S.filter = Object.assign({ only: [], ssids: [], bssids: [], noName: false }, ch.data); S.filterU = ch.u; }
    return;
  }
  if (!map) return;
  const old = map.get(ch.id);
  if (old && old.u > ch.u) return;
  if (ch.d) map.delete(ch.id); else map.set(ch.id, { id: ch.id, site: ch.site, parent: ch.parent, u: ch.u, data: ch.data || {}, by: ch.by });
}

async function pullAll() {
  let changed = 0;
  for (;;) {
    const r = await api("GET", `/api/pull?since=${S.cursor}&limit=5000&collections=${COLLECTIONS}`);
    r.changes.forEach(applyChange);
    changed += r.changes.length;
    S.cursor = r.next;
    if (!r.more) break;
  }
  return changed;
}

async function loadReadings(site) {
  const entry = S.readings.get(site) || { since: 0, list: new Map() };
  for (;;) {
    const r = await api("GET", `/api/pull?since=${entry.since}&limit=5000&collections=readings&sites=${encodeURIComponent(site)}`);
    r.changes.forEach((ch) => { if (ch.d) entry.list.delete(ch.id); else entry.list.set(ch.id, ch.data); });
    entry.since = r.next;
    if (!r.more) break;
  }
  S.readings.set(site, entry);
  return [...entry.list.values()];
}

function change(c, id, site, parent, data) { return { c, id, site: site || "", parent: parent || "", u: Date.now(), d: false, data }; }
function tomb(c, id, site, parent) { return { c, id, site: site || "", parent: parent || "", u: Date.now(), d: true }; }

async function push(changes) {
  if (!changes.length) return;
  await api("POST", "/api/push", { changes });
  changes.forEach((ch) => applyChange({ ...ch, by: S.deviceName }));
  render();
}

// ---------------------------------------------------------------- map

let map, layers = {}, baseLayers;
function initMap() {
  map = L.map("map", { preferCanvas: true, zoomControl: true }).setView([39.5, -98.35], 4);
  baseLayers = {
    "Street": L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 22, maxNativeZoom: 19, attribution: "© OpenStreetMap contributors" }),
    "Satellite": L.tileLayer("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", { maxZoom: 22, maxNativeZoom: 19, attribution: "Imagery © Esri, Maxar, Earthstar Geographics" }),
    "Topographic": L.tileLayer("https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png", { maxZoom: 22, maxNativeZoom: 17, attribution: "© OpenTopoMap (CC-BY-SA)" }),
  };
  let base = "Street";
  try { if (baseLayers[localStorage.getItem("nm.base")]) base = localStorage.getItem("nm.base"); } catch (e) { /* storage blocked */ }
  baseLayers[base].addTo(map);
  L.control.layers(baseLayers, null, { position: "bottomleft" }).addTo(map);
  map.on("baselayerchange", (e) => { try { localStorage.setItem("nm.base", e.name); } catch (err) { /* ignore */ } });
  L.control.scale({ position: "bottomright" }).addTo(map);
  layers.heat = L.layerGroup().addTo(map);
  layers.patterns = L.layerGroup().addTo(map);
  layers.paths = L.layerGroup().addTo(map);
  layers.draft = L.layerGroup().addTo(map);
  layers.pins = L.layerGroup().addTo(map);
  map.on("click", onMapClick);
  layers.legend = L.control({ position: "bottomright" });
  layers.legend.onAdd = () => {
    const d = L.DomUtil.create("div", "legend");
    d.innerHTML = "dBm" + [[-50, "≥-50"], [-60, "-60"], [-67, "-67"], [-75, "-75"], [-85, "-85"], [-90, "<-85"]].map(([r, t]) => `<i style="background:${rssiColor(r)}"></i>${t}`).join("");
    return d;
  };
}

const siteAssets = () => [...S.assets.values()].filter((a) => a.site === S.site);
const sitePaths = () => [...S.paths.values()].filter((p) => p.site === S.site);
const typeOf = (a) => ASSET_TYPES[a.data.type] || ASSET_TYPES.OTHER;

// ---------------------------------------------------------------- locations (several devices in one closet / rack)

const isLoc = (a) => !!a && a.data.type === "LOCATION";
function locationOf(a) {
  const l = a.data.group ? S.assets.get(a.data.group) : null;
  return isLoc(l) && l.site === a.site ? l : null;
}
const membersOf = (loc) => [...S.assets.values()].filter((m) => m.data.group === loc.id && !isLoc(m) && m.site === loc.site)
  .sort((x, y) => CHIP_PRIORITY.indexOf(x.data.type) - CHIP_PRIORITY.indexOf(y.data.type) || x.data.name.localeCompare(y.data.name));
function locationChips(members) {
  if (!members.length) return [[esc(ASSET_TYPES.LOCATION.glyph), ASSET_TYPES.LOCATION.color]];
  const byType = new Map();
  members.forEach((m) => byType.set(m.data.type, (byType.get(m.data.type) || 0) + 1));
  const rank = (t) => (CHIP_PRIORITY.includes(t) ? CHIP_PRIORITY.indexOf(t) : 99);
  const types = [...byType.keys()].sort((a, b) => rank(a) - rank(b));
  const chips = types.map((t) => { const x = ASSET_TYPES[t] || ASSET_TYPES.OTHER, n = byType.get(t); return [glyphHtml(t, 15) + (n > 1 ? `×${n}` : ""), x.color]; });
  if (chips.length <= 4) return chips;
  const rest = types.slice(3).reduce((s, t) => s + byType.get(t), 0);
  return [...chips.slice(0, 3), [`+${rest}`, "#607D8B"]];
}
function locationIcon(loc, members, selected) {
  const chips = locationChips(members).map(([html, c]) => `<span style="background:${c}">${html}</span>`).join("");
  return L.divIcon({ className: "", iconSize: [0, 0], iconAnchor: [0, 0],
    html: `<div class="loc-pin${selected ? " selected" : ""}">${chips}</div><div class="loc-label">${esc(loc.data.name)}</div>` });
}
/** A location, or a lone device, near pt that a new / moved device could share. Existing locations win. */
function groupOfferFor(pt, moving) {
  if (isLoc(moving)) return null;
  const near = siteAssets()
    .filter((a) => a.id !== moving?.id && a.id !== moving?.data.group && (isLoc(a) || !locationOf(a)))
    .map((a) => [a, distanceM(pt, [a.data.lat, a.data.lon])]).filter(([, d]) => d <= GROUP_RADIUS_M)
    .sort((x, y) => x[1] - y[1]);
  const best = near.find(([a]) => isLoc(a)) || near[0];
  return best ? { target: best[0], dist: best[1] } : null;
}
function newLocation(at, site, name) {
  const id = uid();
  const n = [...S.assets.values()].filter((a) => isLoc(a) && a.site === site).length + 1;
  return change("assets", id, site, "", { type: "LOCATION", name: (name || "").trim() || `Location ${n}`, lat: at.data.lat, lon: at.data.lon,
    alt: at.data.alt ?? null, acc: at.data.acc ?? null, status: "Active", notes: "", attributes: {}, createdAt: Date.now(), group: "" });
}
const joinChange = (dev, loc) => change("assets", dev.id, dev.site, "", { ...dev.data, group: loc.id, lat: loc.data.lat, lon: loc.data.lon, acc: loc.data.acc ?? null, alt: loc.data.alt ?? null });
/** Takes a device out of its location, a few metres away so it can be clicked. */
const takeOutChange = (dev, at, bearing = 90) => { const [lat, lon] = destination(at, bearing, 4); return change("assets", dev.id, dev.site, "", { ...dev.data, group: "", lat, lon, acc: null }); };
const releaseChanges = (loc) => { const ms = membersOf(loc); return ms.map((m, i) => takeOutChange(m, [loc.data.lat, loc.data.lon], (360 * i) / ms.length)); };
/** Changes that put `dev` (an existing record, or null for a brand-new pin) into the offered location; returns [location, changes]. */
function joinOffer(offer, locName) {
  if (isLoc(offer.target)) return [offer.target, []];
  const locCh = newLocation(offer.target, offer.target.site, locName);
  const loc = { id: locCh.id, data: locCh.data, site: locCh.site };
  return [loc, [locCh, joinChange(offer.target, loc)]];
}
function offerPanel(offer, what, separateLabel, onJoin, onSeparate) {
  const t = offer.target, d = `${Math.round(offer.dist)} m`;
  openPanel(`<h2>${isLoc(t) ? `Add to ${esc(t.data.name)}?` : "Same location?"} <button class="close" data-close>✕</button></h2>
    <p>${isLoc(t) ? `“${esc(t.data.name)}” is ${d} away. Put ${esc(what)} in it? It will show on that location's pin instead of its own.`
      : `“${esc(t.data.name)}” (${esc(typeOf(t).label)}) is ${d} away. Put ${esc(what)} and it together in one location, shown as a single pin?`}</p>
    ${isLoc(t) ? "" : `<label>Location name <input id="o-name" placeholder="e.g. IDF-2 closet, Rack A"></label>`}
    <div class="actions"><button class="primary" id="o-join">${isLoc(t) ? "Add to location" : "Create location"}</button><button id="o-sep">${esc(separateLabel)}</button></div>`);
  $("#o-join").onclick = () => onJoin($("#o-name") ? $("#o-name").value : "").catch((e) => alert(e.message));
  $("#o-sep").onclick = () => onSeparate().catch((e) => alert(e.message));
}

function pinIcon(type, selected) {
  const t = ASSET_TYPES[type] || ASSET_TYPES.OTHER;
  return L.divIcon({ className: "", iconSize: [28, 28], iconAnchor: [14, 14], html: `<div class="pin${selected ? " selected" : ""}" style="background:${t.color}">${glyphHtml(type)}</div>` });
}

function readPattern(attrs) {
  const raw = String(attrs[PATTERN_KEYS.pattern] || "").toLowerCase();
  const type = raw.startsWith("omni") ? "omni" : raw.startsWith("sector") ? "sector" : raw.startsWith("directional") ? "directional" : null;
  if (!type) return null;
  const az = ((num(attrs[PATTERN_KEYS.azimuth]) || 0) % 360 + 360) % 360;
  const beam = type === "omni" ? 360 : num(attrs[PATTERN_KEYS.beam]) || (type === "sector" ? 120 : 15);
  const range = num(attrs[PATTERN_KEYS.range]) || 30;
  return { type, az, beam, range };
}
function destination([lat, lon], bearingDeg, distM) {
  const R = 6371008.8, d = distM / R, b = (bearingDeg * Math.PI) / 180, p1 = (lat * Math.PI) / 180, l1 = (lon * Math.PI) / 180;
  const p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(b));
  const l2 = l1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2));
  return [(p2 * 180) / Math.PI, (l2 * 180) / Math.PI];
}
function drawPattern(center, p, color, selected) {
  const bands = [[1, "#fb8c00", 0.19], [0.8, "#fdd835", 0.2], [0.5, "#43a047", 0.22]];
  bands.forEach(([f, fill, op]) => {
    const r = p.range * f;
    let shape;
    if (p.type === "omni") shape = L.circle(center, { radius: r });
    else {
      const pts = [center];
      for (let a = p.az - p.beam / 2; a <= p.az + p.beam / 2 + 0.01; a += Math.max(1, p.beam / 40)) pts.push(destination(center, a, r));
      shape = L.polygon(pts);
    }
    shape.setStyle({ color, weight: f === 1 ? (selected ? 3 : 1.5) : 0, opacity: 0.8, fillColor: fill, fillOpacity: op, interactive: false });
    layers.patterns.addLayer(shape);
  });
}

function render() {
  renderSiteSelect();
  layers.pins.clearLayers(); layers.paths.clearLayers(); layers.patterns.clearLayers(); layers.draft.clearLayers();
  const showPins = $("#chk-pins").checked, showPaths = $("#chk-paths").checked, showPatterns = $("#chk-patterns").checked;
  if (showPaths) {
    for (const p of sitePaths()) {
      if (S.draft && S.draft.editing === p.id) continue;
      const pts = decodePoints(p.data.points);
      if (pts.length < 2) continue;
      const kind = PATH_KINDS[p.data.kind] || PATH_KINDS.FIBER_UNDERGROUND;
      const sel = S.selected && S.selected.id === p.id;
      const line = L.polyline(pts, { color: p.data.color ? argbToCss(p.data.color) : kind.color, weight: sel ? 7 : 4, dashArray: kind.dashed ? "10 6" : null, opacity: 0.95 });
      line.on("click", (e) => { L.DomEvent.stopPropagation(e); if (!S.mode) select("paths", p.id); });
      layers.paths.addLayer(line);
    }
  }
  if (showPins) {
    for (const a of siteAssets()) {
      const sel = S.selected && S.selected.id === a.id;
      const pos = [a.data.lat, a.data.lon];
      const loc = isLoc(a) ? null : locationOf(a);
      if (loc) { // drawn inside its location's pin; only its coverage pattern is drawn here
        const pat = showPatterns && readPattern(a.data.attributes || {});
        if (pat) drawPattern([loc.data.lat, loc.data.lon], pat, typeOf(a).color, sel);
        continue;
      }
      const members = isLoc(a) ? membersOf(a) : [];
      const icon = isLoc(a) ? locationIcon(a, members, sel || members.some((x) => S.selected && x.id === S.selected.id)) : pinIcon(a.data.type, sel);
      const m = L.marker(pos, { icon, draggable: S.mode === "move-pin" && sel, title: a.data.name });
      m.on("click", (e) => { L.DomEvent.stopPropagation(e); onPinClick(a); });
      m.on("dragend", () => { const ll = m.getLatLng(); S.movedTo = [ll.lat, ll.lng]; showHint("Pin moved – Save to keep it", [["Save position", savePinMove], ["Cancel", cancelMode]]); });
      layers.pins.addLayer(m);
      if (showPatterns) {
        const pat = readPattern(a.data.attributes || {});
        if (pat) drawPattern(pos, pat, (ASSET_TYPES[a.data.type] || ASSET_TYPES.OTHER).color, sel);
      }
    }
  }
  renderDraft();
  $("#status").textContent = `${siteAssets().length} pins • ${sitePaths().length} routes`;
}

function renderDraft() {
  const d = S.draft;
  if (!d) return;
  if (d.points.length > 1) layers.draft.addLayer(L.polyline(d.points, { color: "#ff6f00", weight: 4, dashArray: "8 6" }));
  d.points.forEach((pt, i) => {
    const v = L.marker(pt, { draggable: true, icon: L.divIcon({ className: "", iconSize: [14, 14], iconAnchor: [7, 7], html: '<div class="vertex"></div>' }) });
    v.on("drag", () => { const ll = v.getLatLng(); d.points[i] = [ll.lat, ll.lng]; if (i === 0) d.startAsset = ""; if (i === d.points.length - 1) d.endAsset = ""; });
    v.on("dragend", render);
    v.on("contextmenu click", (e) => {
      L.DomEvent.stopPropagation(e);
      if (e.type === "contextmenu" || e.originalEvent.altKey) { d.points.splice(i, 1); render(); }
    });
    layers.draft.addLayer(v);
  });
}

// ---------------------------------------------------------------- interaction

function showHint(text, buttons = []) {
  const h = $("#hint");
  h.innerHTML = `<span>${esc(text)}</span>`;
  buttons.forEach(([label, fn]) => { const b = document.createElement("button"); b.textContent = label; b.onclick = fn; h.appendChild(b); });
  h.classList.remove("hidden");
}
const hideHint = () => $("#hint").classList.add("hidden");

function cancelMode() {
  S.mode = null; S.draft = null; S.movedTo = null; hideHint();
  $("#btn-add-pin").classList.remove("active"); $("#btn-draw-path").classList.remove("active");
  render();
  if (S.selected) openDetails();
}

const closeLayers = () => document.body.classList.remove("layers-open");
/** Short message at the bottom of the screen. */
function toast(msg) {
  const t = document.createElement("div");
  t.className = "toast"; t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), 2800);
}

function onMapClick(e) {
  if (document.body.classList.contains("layers-open")) { closeLayers(); return; }
  const pt = [e.latlng.lat, e.latlng.lng];
  if (S.mode === "add-pin") { S.mode = null; $("#btn-add-pin").classList.remove("active"); hideHint(); openNewPin(pt); return; }
  if (S.mode === "draw" || S.mode === "edit-route") { S.draft.points.push(pt); S.draft.endAsset = ""; render(); return; }
  if (!S.mode) { S.selected = null; closePanel(); render(); }
}

function onPinClick(a) {
  if (S.mode === "draw" || S.mode === "edit-route") {
    // Snap the route to the pin; first / last points remember the pin.
    S.draft.points.push([a.data.lat, a.data.lon]);
    if (S.draft.points.length === 1) S.draft.startAsset = a.id;
    S.draft.endAsset = a.id;
    render();
    return;
  }
  if (!S.mode) select("assets", a.id);
}

function select(c, id) { S.selected = { c, id }; render(); openDetails(); }

function startAddPin() {
  cancelMode(); S.mode = "add-pin"; $("#btn-add-pin").classList.add("active");
  showHint("Click the map where the pin goes", [["Cancel", cancelMode]]);
}

function startDraw(kind, editing) {
  S.mode = editing ? "edit-route" : "draw";
  const p = editing ? S.paths.get(editing) : null;
  S.draft = { kind, points: p ? decodePoints(p.data.points) : [], startAsset: p ? p.data.startAsset || "" : "", endAsset: p ? p.data.endAsset || "" : "", editing: editing || null };
  $("#btn-draw-path").classList.add("active");
  closePanel();
  updateDrawHint();
  render();
}
function updateDrawHint() {
  showHint("Click the map to add points • click a pin to snap • drag points to move • right-click (or Alt-click) a point to delete it",
    [["Undo", () => { S.draft.points.pop(); render(); }], ["Finish", finishDraw], ["Cancel", cancelMode]]);
}

async function finishDraw() {
  const d = S.draft;
  if (!d || d.points.length < 2) { alert("A route needs at least 2 points."); return; }
  try {
    if (d.editing) {
      const p = S.paths.get(d.editing);
      await push([change("paths", p.id, p.site, "", { ...p.data, points: encodePoints(d.points), startAsset: d.startAsset || "", endAsset: d.endAsset || "" })]);
      const id = p.id; cancelMode(); select("paths", id);
    } else {
      const id = uid();
      const names = (u) => (S.assets.get(u) ? S.assets.get(u).data.name : "?");
      const name = d.startAsset && d.endAsset ? `${names(d.startAsset)} → ${names(d.endAsset)}` : `${PATH_KINDS[d.kind].label} ${new Date().toLocaleString()}`;
      await push([change("paths", id, S.site, "", {
        kind: d.kind, name, points: encodePoints(d.points), fiberCount: null, cableType: "", status: "Active", notes: "",
        color: null, attributes: {}, startAsset: d.startAsset || "", endAsset: d.endAsset || "", createdAt: Date.now(),
      })]);
      cancelMode(); select("paths", id);
    }
  } catch (e) { alert(e.message); }
}

async function savePinMove() {
  const a = S.selected && S.assets.get(S.selected.id), to = S.movedTo;
  if (!a || !to) return cancelMode();
  const moved = change("assets", a.id, a.site, "", { ...a.data, lat: to[0], lon: to[1], acc: null });
  if (isLoc(a)) { // its devices move with it
    await push([moved, ...membersOf(a).map((m) => change("assets", m.id, m.site, "", { ...m.data, lat: to[0], lon: to[1], acc: null }))]);
    return cancelMode();
  }
  const offer = groupOfferFor(to, a);
  if (!offer) { await push([moved]); return cancelMode(); }
  hideHint();
  offerPanel(offer, `“${a.data.name}”`, "Just move it", async (name) => {
    const [loc, changes] = joinOffer(offer, name);
    await push([...changes, joinChange(a, loc)]);
    cancelMode(); select("assets", loc.id);
  }, async () => { await push([moved]); cancelMode(); });
}

// ---------------------------------------------------------------- side panel

const panel = () => $("#panel");
// ---- Save buttons: grey "Saving…", then green "Saved ✓" until something in the panel is edited.
const isSaved = (rec) => !!S.saved && S.saved.id === rec.id && S.saved.u === rec.u;
const saveButton = (id, rec) => `<button class="primary${isSaved(rec) ? " saved" : ""}" id="${id}">${isSaved(rec) ? "Saved ✓" : "Save"}</button>`;
/** doSave() pushes the change and returns its timestamp. */
function wireSave(id, rec, doSave) {
  const btn = $("#" + id);
  const edited = (e) => {
    if (e.target.id === "photo-input" || !btn.classList.contains("saved")) return;
    btn.classList.remove("saved"); btn.textContent = "Save"; S.saved = null;
  };
  panel().oninput = edited;
  panel().onchange = edited;
  btn.onclick = async () => {
    btn.disabled = true; btn.textContent = "Saving…";
    try {
      const u = await doSave();
      S.saved = { id: rec.id, u };
      openDetails();
    } catch (e) {
      btn.disabled = false; btn.textContent = "Save";
      alert(e.message);
    }
  };
}
function closePanel() { panel().classList.add("hidden"); panel().innerHTML = ""; document.body.classList.remove("panel-open"); }
function openPanel(html) { panel().oninput = panel().onchange = null; panel().innerHTML = html; panel().classList.remove("hidden"); document.body.classList.add("panel-open"); closeLayers(); panel().querySelectorAll("[data-close]").forEach((b) => (b.onclick = () => { S.selected = null; if (S.mode) cancelMode(); closePanel(); render(); })); }

function optionList(items, current) { return items.map(([v, l]) => `<option value="${esc(v)}"${v === current ? " selected" : ""}>${esc(l)}</option>`).join(""); }

function attributesEditor(suggested, attrs, hidden = []) {
  const keys = [...new Set([...suggested, ...Object.keys(attrs)])].filter((k) => !hidden.includes(k));
  return `<div id="attrs">${keys.map((k) => `<div class="kv"><span>${esc(k)}</span><input data-attr="${esc(k)}" value="${esc(attrs[k] || "")}"><span></span></div>`).join("")}</div>
    <button type="button" id="add-attr">+ Custom field</button>`;
}
function readAttributes() {
  const out = {};
  panel().querySelectorAll("[data-attr]").forEach((i) => { if (i.value.trim()) out[i.dataset.attr] = i.value.trim(); });
  return out;
}
function wireAttributeAdder() {
  const b = $("#add-attr");
  if (b) b.onclick = () => {
    const k = prompt("Field name"); if (!k) return;
    const row = document.createElement("div"); row.className = "kv";
    row.innerHTML = `<span>${esc(k)}</span><input data-attr="${esc(k)}"><span></span>`;
    $("#attrs").appendChild(row);
  };
}

function openDetails() {
  if (!S.selected) return;
  if (S.selected.c === "assets") openAsset(S.assets.get(S.selected.id));
  else openPath(S.paths.get(S.selected.id));
}

function photoSection(owner) {
  const photos = [...S.photos.values()].filter((p) => p.parent === owner || p.data.asset === owner || p.data.path === owner);
  return `<h3>Photos (${photos.length})</h3>${photos.length ? '<p class="muted small">Click a photo to view it full size or delete it.</p>' : ""}<div class="photos" id="photos">${photos.map((p) => `<img data-photo="${esc(p.id)}" alt="${esc(p.data.caption || "photo")}" title="${esc(p.data.caption || "")}">`).join("")}</div>
    <label>Add photos <input type="file" id="photo-input" accept="image/*,.heic,.heif" multiple></label>
    <p id="photo-status" class="muted small"></p>`;
}

// HEIC / HEIF (iPhone and many Android cameras): only Safari can display it, so the browser converts it to JPEG.
const HEIF_BRANDS = /^(heic|heix|hevc|hevx|heim|heis|hevm|hevs|mif1|msf1)$/;
async function isHeif(blob) {
  const b = new Uint8Array(await blob.slice(0, 12).arrayBuffer());
  const s = String.fromCharCode(...b);
  return s.slice(4, 8) === "ftyp" && HEIF_BRANDS.test(s.slice(8, 12));
}
let heicLib = null;
function loadHeicLib() {
  heicLib = heicLib || new Promise((ok, fail) => {
    const s = document.createElement("script");
    s.src = "vendor/heic-to/heic-to.js"; // heic-to 1.6.6 (libheif 1.23)
    s.onload = () => ok(window.HeicTo);
    s.onerror = () => { heicLib = null; fail(new Error("Couldn't load the HEIC converter")); };
    document.head.appendChild(s);
  });
  return heicLib;
}
async function heifToJpeg(blob) {
  try { // Safari decodes HEIC itself; re-encode through a canvas.
    const bmp = await createImageBitmap(blob);
    const c = document.createElement("canvas");
    c.width = bmp.width; c.height = bmp.height;
    c.getContext("2d").drawImage(bmp, 0, 0); bmp.close();
    const out = await new Promise((ok) => c.toBlob(ok, "image/jpeg", 0.88));
    if (out) return out;
  } catch (e) { /* not supported natively – fall through */ }
  return (await loadHeicLib())({ blob, type: "image/jpeg", quality: 0.88 });
}

async function loadPhotoThumbs() {
  for (const img of panel().querySelectorAll("img[data-photo]")) {
    const id = img.dataset.photo;
    try {
      if (!S.photoUrls.has(id)) {
        let blob = await api("GET", `/api/photos/${encodeURIComponent(id)}`, undefined, "blob");
        if (await isHeif(blob)) blob = await heifToJpeg(blob); // older uploads saved as HEIC
        S.photoUrls.set(id, URL.createObjectURL(blob));
      }
      img.src = S.photoUrls.get(id);
      img.onclick = () => openLightbox(id, img.src);
    } catch (e) { img.alt = "can't show photo"; img.title = e.message || ""; }
  }
}
function openLightbox(id, src) {
  const p = S.photos.get(id);
  const info = [p?.data.caption, p?.data.takenAt ? fmtTime(p.data.takenAt) : "", p?.by ? "by " + p.by : ""].filter(Boolean).join(" • ");
  const lb = document.createElement("div");
  lb.className = "lightbox";
  lb.innerHTML = `<img src="${src}" alt="">
    <div class="lb-bar"><span>${esc(info)}</span><button class="danger" data-lb="delete">Delete photo</button><button data-lb="close">Close</button></div>`;
  lb.onclick = (e) => { if (e.target === lb || e.target.tagName === "IMG" || e.target.dataset.lb === "close") lb.remove(); };
  lb.querySelector("[data-lb=delete]").onclick = async (e) => {
    e.stopPropagation();
    if (!p || !confirm("Delete this photo? It's removed from the server and from every phone on their next sync.")) return;
    try {
      await push([tomb("photos", id, p.site, p.parent || p.data.asset || p.data.path)]);
      lb.remove();
      URL.revokeObjectURL(S.photoUrls.get(id) || "");
      S.photoUrls.delete(id);
      openDetails();
    } catch (err) { alert(err.message); }
  };
  document.body.appendChild(lb);
}
function wirePhotoUpload(ownerKind, owner, site, pos) {
  const input = $("#photo-input");
  if (!input) return;
  input.onchange = async () => {
    const status = $("#photo-status");
    const files = [...input.files];
    input.disabled = true;
    try {
      for (const [i, file] of files.entries()) {
        let blob = file, ext = (file.name.split(".").pop() || "jpg").toLowerCase();
        if (await isHeif(file)) {
          status.textContent = `Converting ${file.name} to JPEG… (${i + 1}/${files.length})`;
          blob = await heifToJpeg(file); ext = "jpg";
        }
        status.textContent = `Uploading ${file.name}… (${i + 1}/${files.length})`;
        const id = uid();
        await api("PUT", `/api/photos/${id}`, await blob.arrayBuffer(), true);
        await push([change("photos", id, site, owner, {
          asset: ownerKind === "assets" ? owner : "", path: ownerKind === "paths" ? owner : "",
          fileName: `IMG_web_${Date.now()}_${Math.floor(Math.random() * 9000 + 1000)}.${ext}`,
          lat: pos ? pos[0] : null, lon: pos ? pos[1] : null, acc: null, caption: file.name, takenAt: file.lastModified || Date.now(),
        })]);
      }
    } catch (e) {
      alert(`Photo upload failed: ${e && e.message ? e.message : e}`);
    }
    openDetails();
  };
}

function openNewPin(pt) {
  openPanel(`<h2>New pin <button class="close" data-close>✕</button></h2>
    <p class="muted">${pt[0].toFixed(6)}, ${pt[1].toFixed(6)}</p>
    <label>Type <select id="np-type">${optionList(Object.entries(ASSET_TYPES).map(([k, t]) => [k, t.label]), "FIBER_JUNCTION")}</select></label>
    <label>Name <input id="np-name" placeholder="optional"></label>
    <div class="actions"><button class="primary" id="np-create">Create</button></div>`);
  $("#np-create").onclick = () => {
    const type = $("#np-type").value, name = $("#np-name").value.trim();
    const label = ASSET_TYPES[type].label.split(" /")[0].split(" (")[0];
    const offer = type === "LOCATION" ? null : groupOfferFor(pt, null);
    if (!offer) return createPin(type, name, pt, "", []).catch((e) => alert(e.message));
    offerPanel(offer, `the new ${label} pin`, "Separate pin", async (locName) => {
      const [loc, changes] = joinOffer(offer, locName);
      await createPin(type, name, [loc.data.lat, loc.data.lon], loc.id, changes);
    }, () => createPin(type, name, pt, "", []));
  };
}
async function createPin(type, name, at, group, extra, site = S.site) {
  const n = [...S.assets.values()].filter((a) => a.site === site && a.data.type === type).length + 1;
  const label = ASSET_TYPES[type].label.split(" /")[0].split(" (")[0];
  const id = uid();
  await push([...extra, change("assets", id, site, "", {
    type, name: name || `${label} ${n}`, lat: at[0], lon: at[1], alt: null, acc: null,
    status: "Active", notes: "", attributes: {}, createdAt: Date.now(), group: group || "",
  })]);
  select("assets", id);
}

function openAsset(a) {
  if (!a) return closePanel();
  const d = a.data, t = ASSET_TYPES[d.type] || ASSET_TYPES.OTHER;
  const attrs = d.attributes || {};
  const pat = readPattern(attrs);
  const macs = t.bssidField ? parseMacs(attrs[t.bssidField]) : [];
  const inLoc = isLoc(a) ? null : locationOf(a);
  const members = isLoc(a) ? membersOf(a) : [];
  openPanel(`<h2><span class="pin" style="background:${t.color}">${glyphHtml(d.type)}</span> ${esc(d.name)} <button class="close" data-close>✕</button></h2>
    <p class="muted small">Last change ${fmtTime(a.u)}${a.by ? " by " + esc(a.by) : ""}</p>
    ${inLoc ? `<p><a href="#" id="f-loc">${esc(ASSET_TYPES.LOCATION.glyph)} In ${esc(inLoc.data.name)} – show all devices there</a></p>` : ""}
    ${isLoc(a) ? `<h3>Devices here (${members.length})</h3>
      ${members.map((m) => `<div class="list-item" data-member="${esc(m.id)}"><span class="pin mini" style="background:${typeOf(m).color}">${glyphHtml(m.data.type, 12)}</span> ${esc(m.data.name)} <span class="muted small">${esc(typeOf(m).label)} • ${esc(m.data.status)}</span></div>`).join("") || '<p class="muted small">No devices here yet – add the router, switches and APs.</p>'}
      <div class="grid2"><select id="f-add-type">${optionList(Object.entries(ASSET_TYPES).filter(([k]) => k !== "LOCATION").map(([k, x]) => [k, x.label]), "SWITCH")}</select><button type="button" id="f-add">+ Add device here</button></div>` : ""}
    <label>Type <select id="f-type">${optionList(Object.entries(ASSET_TYPES).map(([k, x]) => [k, x.label]), d.type)}</select></label>
    <label>Name / ID <input id="f-name" value="${esc(d.name)}"></label>
    <label>Status <select id="f-status">${optionList([...new Set([...STATUSES, d.status])].map((s) => [s, s]), d.status)}</select></label>
    <p class="small">${Number(d.lat).toFixed(7)}, ${Number(d.lon).toFixed(7)}${d.acc ? ` • ±${Math.round(d.acc)} m` : ""}
      ${inLoc ? '<button type="button" id="f-takeout">Take out of location</button>' : `<button type="button" id="f-move">${isLoc(a) ? "Move (with its devices)" : "Move pin"}</button>`}
      <a href="https://www.google.com/maps/dir/?api=1&destination=${d.lat},${d.lon}" target="_blank" rel="noopener">Directions</a></p>
    ${macs.length ? `<p class="small">Radios: ${macs.map((m) => `<span class="chip">${esc(S.apNames.get(m)?.data.name || m)}</span>`).join("")}
      <button type="button" id="f-signal">Signal map</button></p>` : ""}
    ${t.bssidField || pat ? `<h3>Coverage pattern</h3>
      <div class="grid2">
        <label>Pattern <select id="p-type">${optionList([["", "None"], ["Omni", "Omni (360°)"], ["Sector", "Sector"], ["Directional", "Directional / PtP"]], pat ? pat.type[0].toUpperCase() + pat.type.slice(1) : "")}</select></label>
        <label>Range (m) <input id="p-range" type="number" min="1" value="${pat ? pat.range : 30}"></label>
        <label>Azimuth (°) <input id="p-az" type="number" min="0" max="359" value="${pat ? pat.az : 0}"></label>
        <label>Beamwidth (°) <input id="p-beam" type="number" min="1" max="360" value="${pat ? pat.beam : 120}"></label>
      </div>` : ""}
    <h3>Details</h3>
    ${attributesEditor(t.fields, attrs, Object.values(PATTERN_KEYS))}
    <label>Notes <textarea id="f-notes">${esc(d.notes || "")}</textarea></label>
    ${photoSection(a.id)}
    <div class="actions">${saveButton("f-save", a)}<button class="danger" id="f-delete">Delete</button></div>`);
  wireAttributeAdder();
  loadPhotoThumbs();
  wirePhotoUpload("assets", a.id, a.site, [d.lat, d.lon]);
  if ($("#f-move")) $("#f-move").onclick = () => { S.mode = "move-pin"; render(); showHint("Drag the pin to its new position", [["Cancel", cancelMode]]); };
  if ($("#f-takeout")) $("#f-takeout").onclick = async () => { await push([takeOutChange(a, [inLoc.data.lat, inLoc.data.lon])]); openDetails(); };
  if ($("#f-loc")) $("#f-loc").onclick = (e) => { e.preventDefault(); select("assets", inLoc.id); };
  panel().querySelectorAll("[data-member]").forEach((el) => (el.onclick = () => select("assets", el.dataset.member)));
  if ($("#f-add")) $("#f-add").onclick = () => createPin($("#f-add-type").value, "", [d.lat, d.lon], a.id, [], a.site).catch((e) => alert(e.message));
  if ($("#f-signal")) $("#f-signal").onclick = () => { $("#heat-select").value = "dev:" + a.id; refreshHeat(); };
  wireSave("f-save", a, async () => {
    const attributes = readAttributes();
    // Keep pattern keys in the same format the phones write.
    Object.values(PATTERN_KEYS).forEach((k) => { if (attrs[k] !== undefined) attributes[k] = attrs[k]; });
    if ($("#p-type")) {
      const ptype = $("#p-type").value;
      delete attributes[PATTERN_KEYS.pattern]; delete attributes[PATTERN_KEYS.beam]; delete attributes[PATTERN_KEYS.range];
      if (ptype) {
        attributes[PATTERN_KEYS.pattern] = ptype;
        attributes[PATTERN_KEYS.range] = String(Math.round(num($("#p-range").value) || 30));
        if (ptype !== "Omni") {
          attributes[PATTERN_KEYS.azimuth] = String(Math.round(((num($("#p-az").value) || 0) % 360 + 360) % 360));
          attributes[PATTERN_KEYS.beam] = String(Math.round(num($("#p-beam").value) || 120));
        }
      }
    }
    const type = $("#f-type").value;
    // A location turned into something else lets its devices go; a device turned into a location leaves its own.
    const extra = isLoc(a) && type !== "LOCATION" ? releaseChanges(a) : [];
    const ch = change("assets", a.id, a.site, "", { ...d, type, name: $("#f-name").value.trim() || d.name, status: $("#f-status").value, notes: $("#f-notes").value, attributes,
      group: type === "LOCATION" ? "" : d.group || "" });
    await push([ch, ...extra]);
    return ch.u;
  });
  $("#f-delete").onclick = async () => {
    if (!confirm(isLoc(a) ? `Delete location “${d.name}” and its photos? The ${members.length} device(s) in it are kept as separate pins.` : `Delete “${d.name}” and its photos?`)) return;
    const photos = [...S.photos.values()].filter((p) => p.data.asset === a.id);
    await push([...(isLoc(a) ? releaseChanges(a) : []), ...photos.map((p) => tomb("photos", p.id, a.site, a.id)), tomb("assets", a.id, a.site)]);
    S.selected = null; closePanel(); render();
  };
}

function openPath(p) {
  if (!p) return closePanel();
  const d = p.data, k = PATH_KINDS[d.kind] || PATH_KINDS.FIBER_UNDERGROUND;
  const pts = decodePoints(d.points);
  const end = (u) => (u && S.assets.get(u) ? esc(S.assets.get(u).data.name) : "—");
  openPanel(`<h2>${esc(d.name)} <button class="close" data-close>✕</button></h2>
    <p class="muted small">Last change ${fmtTime(p.u)}${p.by ? " by " + esc(p.by) : ""}</p>
    <label>Kind <select id="r-kind">${optionList(Object.entries(PATH_KINDS).map(([key, x]) => [key, x.label]), d.kind)}</select></label>
    <label>Name / Cable ID <input id="r-name" value="${esc(d.name)}"></label>
    <div class="grid2">
      <label id="r-fiber-wrap" class="${k.fiber ? "" : "hidden"}">Fiber count <input id="r-fiber" type="number" min="0" value="${d.fiberCount ?? ""}"></label>
      <label id="r-cable-wrap" class="${k.cable ? "" : "hidden"}"><span id="r-cable-label">${esc(k.cable || "")}</span> <input id="r-cable" value="${esc(d.cableType || "")}"></label>
    </div>
    <label>Status <select id="r-status">${optionList([...new Set([...STATUSES, d.status])].map((s) => [s, s]), d.status)}</select></label>
    <label>Line colour <select id="r-color">${optionList([["", "Auto (by kind)"], ...PATH_COLORS.map((c) => [String(c), argbToCss(c)])], d.color ? String(d.color) : "")}</select></label>
    <p class="small">Length ${fmtDist(pathLength(pts))} • ${pts.length} points<br>From: ${end(d.startAsset)} • To: ${end(d.endAsset)}</p>
    <button type="button" id="r-route">Edit / extend route</button>
    <h3>Details</h3>
    ${attributesEditor(k.fields, d.attributes || {})}
    <label>Notes <textarea id="r-notes">${esc(d.notes || "")}</textarea></label>
    ${photoSection(p.id)}
    <div class="actions">${saveButton("r-save", p)}<button class="danger" id="r-delete">Delete</button></div>`);
  wireAttributeAdder();
  loadPhotoThumbs();
  wirePhotoUpload("paths", p.id, p.site, pts[0]);
  $("#r-kind").onchange = () => {
    const nk = PATH_KINDS[$("#r-kind").value];
    $("#r-fiber-wrap").classList.toggle("hidden", !nk.fiber);
    $("#r-cable-wrap").classList.toggle("hidden", !nk.cable);
    $("#r-cable-label").textContent = nk.cable || "";
  };
  $("#r-route").onclick = () => startDraw(d.kind, p.id);
  wireSave("r-save", p, async () => {
    const kind = $("#r-kind").value, nk = PATH_KINDS[kind];
    const color = $("#r-color").value;
    const ch = change("paths", p.id, p.site, "", {
      ...d, kind, name: $("#r-name").value.trim() || d.name, status: $("#r-status").value, notes: $("#r-notes").value,
      fiberCount: nk.fiber && $("#r-fiber").value !== "" ? parseInt($("#r-fiber").value, 10) : null,
      cableType: nk.cable ? $("#r-cable").value.trim() : "", color: color ? Number(color) : null, attributes: readAttributes(),
    });
    await push([ch]);
    return ch.u;
  });
  $("#r-delete").onclick = async () => {
    if (!confirm(`Delete route “${d.name}”?`)) return;
    const photos = [...S.photos.values()].filter((x) => x.data.path === p.id);
    await push([...photos.map((x) => tomb("photos", x.id, p.site, p.id)), tomb("paths", p.id, p.site)]);
    S.selected = null; closePanel(); render();
  };
}

// ---------------------------------------------------------------- lists & tools

function openList() {
  const assets = siteAssets().sort((a, b) => a.data.name.localeCompare(b.data.name));
  const paths = sitePaths().sort((a, b) => a.data.name.localeCompare(b.data.name));
  openPanel(`<h2>Pins &amp; routes <button class="close" data-close>✕</button></h2>
    <input id="l-q" placeholder="Search…">
    <h3>Pins (${assets.length})</h3><div id="l-pins">${assets.map((a) => `<div class="list-item" data-a="${esc(a.id)}"><span class="pin" style="display:inline-block;width:20px;height:20px;line-height:17px;font-size:10px;background:${(ASSET_TYPES[a.data.type] || ASSET_TYPES.OTHER).color}">${glyphHtml(a.data.type, 12)}</span> ${esc(a.data.name)} <span class="muted small">${esc((ASSET_TYPES[a.data.type] || ASSET_TYPES.OTHER).label)}${locationOf(a) ? " • in " + esc(locationOf(a).data.name) : ""}</span></div>`).join("")}</div>
    <h3>Routes (${paths.length})</h3><div id="l-paths">${paths.map((p) => `<div class="list-item" data-p="${esc(p.id)}">${esc(p.data.name)} <span class="muted small">${esc((PATH_KINDS[p.data.kind] || {}).label || "")} • ${fmtDist(pathLength(decodePoints(p.data.points)))}</span></div>`).join("")}</div>`);
  panel().querySelectorAll("[data-a]").forEach((el) => (el.onclick = () => { const a = S.assets.get(el.dataset.a); map.setView([a.data.lat, a.data.lon], Math.max(map.getZoom(), 18)); select("assets", a.id); }));
  panel().querySelectorAll("[data-p]").forEach((el) => (el.onclick = () => { const p = S.paths.get(el.dataset.p); const pts = decodePoints(p.data.points); if (pts.length) map.fitBounds(pts, { maxZoom: 19 }); select("paths", p.id); }));
  $("#l-q").oninput = () => {
    const q = $("#l-q").value.toLowerCase();
    panel().querySelectorAll(".list-item").forEach((el) => el.classList.toggle("hidden", !el.textContent.toLowerCase().includes(q)));
  };
}

function openApNames() {
  const rows = [...S.apNames.values()].sort((a, b) => a.data.name.localeCompare(b.data.name));
  openPanel(`<h2>AP names <button class="close" data-close>✕</button></h2>
    <p class="muted small">Your names for Wi-Fi radios, by BSSID / MAC. Shared with every phone.</p>
    <div class="grid2"><input id="n-mac" placeholder="aa:bb:cc:dd:ee:ff"><input id="n-name" placeholder="Name"></div>
    <div class="actions"><button class="primary" id="n-add">Save name</button></div>
    ${rows.map((r) => `<div class="kv"><code>${esc(r.id)}</code><input data-name="${esc(r.id)}" value="${esc(r.data.name)}"><button data-del="${esc(r.id)}">✕</button></div>`).join("")}`);
  $("#n-add").onclick = async () => {
    const macs = parseMacs($("#n-mac").value), name = $("#n-name").value.trim();
    if (!macs.length || !name) return alert("Enter a MAC / BSSID and a name.");
    await push(macs.map((m) => change("ap_names", m, "", "", { name })));
    openApNames();
  };
  panel().querySelectorAll("[data-name]").forEach((i) => (i.onchange = () => push([change("ap_names", i.dataset.name, "", "", { name: i.value.trim() })])));
  panel().querySelectorAll("[data-del]").forEach((b) => (b.onclick = async () => { await push([tomb("ap_names", b.dataset.del, "")]); openApNames(); }));
}

function filterAllows(ssid, bssid) {
  const f = S.filter;
  if ((f.bssids || []).includes(String(bssid).toLowerCase())) return false;
  if (!ssid) return !f.noName && !(f.only || []).length;
  if ((f.ssids || []).includes(ssid)) return false;
  return !(f.only || []).length || f.only.includes(ssid);
}

function openFilter() {
  const f = S.filter;
  const list = (arr, key) => (arr || []).map((v) => `<span class="chip">${esc(key === "bssids" ? S.apNames.get(v)?.data.name || v : v)} <button data-rm="${key}" data-v="${esc(v)}">✕</button></span>`).join("") || '<span class="muted small">none</span>';
  openPanel(`<h2>Wi-Fi filter <button class="close" data-close>✕</button></h2>
    <p class="muted small">Networks to leave out of mapping. Shared with every phone (they stop recording filtered networks).</p>
    <h3>Only map these SSIDs</h3><div>${list(f.only, "only")}</div>
    <div class="grid2"><input id="w-only" placeholder="SSID"><button id="w-only-add">Add</button></div>
    <h3>Hidden SSIDs</h3><div>${list(f.ssids, "ssids")}</div>
    <div class="grid2"><input id="w-ssid" placeholder="SSID"><button id="w-ssid-add">Hide</button></div>
    <h3>Hidden APs / BSSIDs</h3><div>${list(f.bssids, "bssids")}</div>
    <div class="grid2"><input id="w-bssid" placeholder="aa:bb:cc:dd:ee:ff"><button id="w-bssid-add">Hide</button></div>
    <p><label class="row"><input type="checkbox" id="w-noname" ${f.noName ? "checked" : ""}> Hide networks with no name</label></p>`);
  const save = async (nf) => { await push([change("settings", "wifi_filter", "", "", nf)]); openFilter(); refreshHeat(); };
  const add = (key, v) => { if (v) save({ ...f, [key]: [...new Set([...(f[key] || []), v])] }); };
  $("#w-only-add").onclick = () => add("only", $("#w-only").value.trim());
  $("#w-ssid-add").onclick = () => add("ssids", $("#w-ssid").value.trim());
  $("#w-bssid-add").onclick = () => parseMacs($("#w-bssid").value).forEach((m) => add("bssids", m));
  $("#w-noname").onchange = () => save({ ...f, noName: $("#w-noname").checked });
  panel().querySelectorAll("[data-rm]").forEach((b) => (b.onclick = () => save({ ...f, [b.dataset.rm]: (f[b.dataset.rm] || []).filter((x) => x !== b.dataset.v) })));
}

function stackedPins() {
  const lone = siteAssets().filter((a) => !isLoc(a) && !locationOf(a));
  const parent = lone.map((_, i) => i);
  const find = (i) => { while (parent[i] !== i) i = parent[i]; return i; };
  for (let i = 0; i < lone.length; i++) for (let j = i + 1; j < lone.length; j++) {
    if (distanceM([lone[i].data.lat, lone[i].data.lon], [lone[j].data.lat, lone[j].data.lon]) <= GROUP_RADIUS_M) parent[find(i)] = find(j);
  }
  const groups = new Map();
  lone.forEach((a, i) => { const r = find(i); groups.set(r, [...(groups.get(r) || []), a]); });
  return [...groups.values()].filter((g) => g.length > 1).sort((x, y) => y.length - x.length);
}
function openCombine() {
  const clusters = stackedPins();
  openPanel(`<h2>Combine stacked pins <button class="close" data-close>✕</button></h2>
    <p class="muted small">${clusters.length ? "These pins are within a few metres of each other. Combine each group into one location pin." : "No pins within a few metres of each other in this site."}</p>
    ${clusters.map((c, i) => `<div class="card stack">${c.map((a) => `<div><span class="pin mini" style="background:${typeOf(a).color}">${glyphHtml(a.data.type, 12)}</span> ${esc(a.data.name)}</div>`).join("")}
      <label>Location name <input data-stack-name="${i}" placeholder="e.g. IDF-2 closet"></label>
      <button class="primary" data-stack="${i}">Combine ${c.length} pins</button></div>`).join("")}`);
  panel().querySelectorAll("[data-stack]").forEach((b) => (b.onclick = async () => {
    const pins = clusters[Number(b.dataset.stack)];
    const anchor = pins.reduce((best, p) => ((p.data.acc ?? Infinity) < (best.data.acc ?? Infinity) ? p : best), pins[0]);
    const locCh = newLocation(anchor, anchor.site, panel().querySelector(`[data-stack-name="${b.dataset.stack}"]`).value);
    const loc = { id: locCh.id, data: locCh.data };
    await push([locCh, ...pins.map((p) => joinChange(p, loc))]);
    openCombine();
  }));
}

async function openDevices() {
  const r = await api("GET", "/api/devices");
  openPanel(`<h2>Phones &amp; browsers <button class="close" data-close>✕</button></h2>
    ${r.devices.map((d) => `<div class="list-item">${esc(d.name)}<br><span class="muted small">last sync ${fmtTime(d.lastSeen)}</span></div>`).join("") || "<p class='muted'>No devices yet.</p>"}`);
}

// ---------------------------------------------------------------- heatmap

async function refreshHeat() {
  layers.heat.clearLayers();
  const v = $("#heat-select").value;
  if (!v || !S.site) { layers.legend.remove(); return; }
  $("#status").textContent = "Loading Wi-Fi survey…";
  let readings;
  try { readings = await loadReadings(S.site); } catch (e) { $("#status").textContent = e.message; return; }
  let subset;
  if (v === "*") subset = readings.filter((r) => filterAllows(r.ssid, r.bssid));
  else if (v.startsWith("dev:")) {
    const a = S.assets.get(v.slice(4));
    const t = a && ASSET_TYPES[a.data.type];
    const macs = new Set(t && t.bssidField ? parseMacs((a.data.attributes || {})[t.bssidField]) : []);
    subset = readings.filter((r) => macs.has(String(r.bssid).toLowerCase()));
  } else subset = readings.filter((r) => r.bssid === v);
  const spots = new Map();
  for (const r of subset) {
    const k = `${Math.round(r.lat * 1e5)}:${Math.round(r.lon * 1e5)}`;
    const old = spots.get(k);
    if (!old || r.rssi > old.rssi) spots.set(k, r);
  }
  for (const r of spots.values()) {
    const name = S.apNames.get(String(r.bssid).toLowerCase())?.data.name;
    layers.heat.addLayer(L.circleMarker([r.lat, r.lon], { radius: 6, stroke: false, fillColor: rssiColor(r.rssi), fillOpacity: 0.75 })
      .bindTooltip(`${esc(name || r.ssid || "(hidden)")} ${r.rssi} dBm<br><span class="small">${esc(r.bssid)}</span>`));
  }
  layers.legend.addTo(map);
  // Offer per-AP heatmaps for networks seen in this site.
  const sel = $("#heat-select"), current = sel.value;
  const seen = new Map();
  readings.filter((r) => filterAllows(r.ssid, r.bssid)).forEach((r) => { const o = seen.get(r.bssid); if (!o || r.rssi > o.rssi) seen.set(r.bssid, r); });
  sel.innerHTML = `<option value="">Wi-Fi heatmap: off</option><option value="*">Heatmap: strongest signal</option>` +
    [...seen.values()].sort((a, b) => b.rssi - a.rssi).map((r) => `<option value="${esc(r.bssid)}">${esc(S.apNames.get(r.bssid)?.data.name || r.ssid || "(hidden)")} – ${esc(r.bssid)}</option>`).join("") +
    siteAssets().filter((a) => (ASSET_TYPES[a.data.type] || {}).bssidField).map((a) => `<option value="dev:${esc(a.id)}">Pin: ${esc(a.data.name)}</option>`).join("");
  sel.value = current;
  $("#status").textContent = `${spots.size} survey spots`;
}

// ---------------------------------------------------------------- sites

function renderSiteSelect() {
  const sel = $("#site-select");
  const sites = [...S.sites.values()].sort((a, b) => a.data.name.localeCompare(b.data.name));
  if (!S.site || !S.sites.has(S.site)) S.site = sites[0] ? sites[0].id : "";
  const html = sites.map((s) => `<option value="${esc(s.id)}"${s.id === S.site ? " selected" : ""}>${esc(s.data.name)}</option>`).join("") || "<option>No sites yet</option>";
  if (sel.innerHTML !== html) sel.innerHTML = html;
}
/** Opens the site's start view if it has one, otherwise fits the map to its pins and routes. */
function openSiteView() {
  const h = S.sites.get(S.site)?.data.home;
  if (h && Number.isFinite(h.lat) && Number.isFinite(h.lon)) map.setView([h.lat, h.lon], Number.isFinite(h.zoom) ? h.zoom : 18);
  else fitSite();
}
function fitSite() {
  const pts = [...siteAssets().map((a) => [a.data.lat, a.data.lon]), ...sitePaths().flatMap((p) => decodePoints(p.data.points))];
  if (pts.length) map.fitBounds(pts, { padding: [40, 40], maxZoom: 19 });
}
async function siteAction(action) {
  const s = S.sites.get(S.site);
  if (action === "site-new") {
    const name = prompt("New site name"); if (!name) return;
    const id = uid();
    await push([change("sites", id, id, "", { name: name.trim(), notes: "", createdAt: Date.now() })]);
    S.site = id; localStorage.setItem("nm.site", id); render();
  } else if (action === "site-rename" && s) {
    const name = prompt("Site name", s.data.name); if (name === null) return;
    const notes = prompt("Notes (address, customer, job #)", s.data.notes || ""); if (notes === null) return;
    await push([change("sites", s.id, s.id, "", { ...s.data, name: name.trim() || s.data.name, notes })]);
  } else if (action === "site-fit") fitSite();
  else if (action === "site-home-set" && s) {
    const c = map.getCenter();
    await push([change("sites", s.id, s.id, "", { ...s.data, home: { lat: c.lat, lon: c.lng, zoom: map.getZoom() } })]);
    toast("Start view saved – opening this site now goes here");
  } else if (action === "site-home-go") openSiteView();
  else if (action === "site-home-clear" && s) {
    await push([change("sites", s.id, s.id, "", { ...s.data, home: null })]);
    toast("Start view cleared");
  }
  else if (action === "site-delete" && s) {
    if (prompt(`Type the site name to delete “${s.data.name}” and everything in it on all phones:`) !== s.data.name) return;
    const children = [...siteAssets().map((a) => tomb("assets", a.id, s.id)), ...sitePaths().map((p) => tomb("paths", p.id, s.id)),
      ...[...S.sessions.values()].filter((x) => x.site === s.id).map((x) => tomb("sessions", x.id, s.id))];
    await push([...children, tomb("sites", s.id, s.id)]);
    S.site = ""; render();
  }
}

// ---------------------------------------------------------------- export

function download(name, text, type) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([text], { type }));
  a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
}
function exportGeoJson() {
  const s = S.sites.get(S.site);
  const features = [
    ...siteAssets().map((a) => ({ type: "Feature", geometry: { type: "Point", coordinates: [a.data.lon, a.data.lat] },
      properties: { id: a.id, featureType: "asset", siteName: s?.data.name, ...a.data, typeLabel: (ASSET_TYPES[a.data.type] || {}).label, locationName: locationOf(a)?.data.name || "" } })),
    ...sitePaths().map((p) => ({ type: "Feature", geometry: { type: "LineString", coordinates: decodePoints(p.data.points).map(([la, lo]) => [lo, la]) },
      properties: { id: p.id, featureType: "path", siteName: s?.data.name, ...p.data, points: undefined, lengthM: pathLength(decodePoints(p.data.points)) } })),
  ];
  download(`${(s?.data.name || "site").replace(/[^\w.-]+/g, "_")}.geojson`, JSON.stringify({ type: "FeatureCollection", features }, null, 2), "application/geo+json");
}
async function exportCsv() {
  const readings = await loadReadings(S.site);
  const q = (v) => (/[",\n]/.test(String(v ?? "")) ? `"${String(v).replace(/"/g, '""')}"` : String(v ?? ""));
  const rows = ["time,latitude,longitude,accuracy_m,ssid,bssid,ap_name,rssi_dbm,frequency_mhz,connected"];
  readings.forEach((r) => rows.push([new Date(r.t).toISOString(), r.lat, r.lon, r.acc ?? "", q(r.ssid), r.bssid, q(S.apNames.get(r.bssid)?.data.name || ""), r.rssi, r.freq, r.conn].join(",")));
  download(`wifi_survey_${(S.sites.get(S.site)?.data.name || "site").replace(/[^\w.-]+/g, "_")}.csv`, rows.join("\n"), "text/csv");
}

// ---------------------------------------------------------------- startup

async function start() {
  $("#login").classList.add("hidden");
  ["#topbar", "#main"].forEach((s) => $(s).classList.remove("hidden"));
  if (!map) initMap();
  S.site = localStorage.getItem("nm.site") || "";
  $("#status").textContent = "Loading…";
  await pullAll();
  render();
  openSiteView();
  setInterval(async () => {
    try { if (await pullAll()) { render(); if (S.selected && !S.mode && !panel().contains(document.activeElement)) openDetails(); } }
    catch (e) { $("#status").textContent = e.message; }
  }, 15000);
}

function showLogin(msg) {
  $("#login").classList.remove("hidden");
  $("#login-error").textContent = msg || "";
  $("#login-name").value = localStorage.getItem("nm.name") || "";
}

window.addEventListener("DOMContentLoaded", () => {
  S.deviceId = localStorage.getItem("nm.device") || "web-" + uid();
  localStorage.setItem("nm.device", S.deviceId);
  $("#login-form").onsubmit = async (e) => {
    e.preventDefault();
    S.key = $("#login-key").value.trim();
    S.deviceName = $("#login-name").value.trim() || "Browser";
    try {
      await api("GET", "/api/sites");
      localStorage.setItem("nm.name", S.deviceName);
      if ($("#login-remember").checked) localStorage.setItem("nm.key", S.key); else sessionStorage.setItem("nm.key", S.key);
      start();
    } catch (err) { $("#login-error").textContent = err.message; }
  };
  $("#btn-add-pin").onclick = startAddPin;
  $("#btn-draw-path").onclick = () => {
    cancelMode();
    openPanel(`<h2>Draw a route <button class="close" data-close>✕</button></h2>
      <label>Kind <select id="d-kind">${optionList(Object.entries(PATH_KINDS).map(([k, x]) => [k, x.label]), "FIBER_UNDERGROUND")}</select></label>
      <div class="actions"><button class="primary" id="d-go">Start drawing</button></div>`);
    $("#d-go").onclick = () => startDraw($("#d-kind").value, null);
  };
  ["#chk-pins", "#chk-paths", "#chk-patterns"].forEach((s) => ($(s).onchange = render));
  $("#heat-select").onchange = refreshHeat;
  $("#site-select").onchange = () => { S.site = $("#site-select").value; localStorage.setItem("nm.site", S.site); S.selected = null; closePanel(); render(); openSiteView(); refreshHeat(); };
  const toggle = (id) => $(id).classList.toggle("hidden");
  $("#btn-more").onclick = () => { $("#site-menu").classList.add("hidden"); closeLayers(); toggle("#menu"); };
  $("#btn-layers").onclick = () => { $("#menu").classList.add("hidden"); $("#site-menu").classList.add("hidden"); document.body.classList.toggle("layers-open"); };
  $("#btn-site-menu").onclick = () => {
    $("#menu").classList.add("hidden");
    closeLayers();
    const hasHome = !!S.sites.get(S.site)?.data.home;
    document.querySelectorAll('[data-action="site-home-go"], [data-action="site-home-clear"]').forEach((el) => el.classList.toggle("hidden", !hasHome));
    toggle("#site-menu");
  };
  document.querySelectorAll(".menu button").forEach((b) => (b.onclick = async () => {
    b.parentElement.classList.add("hidden");
    const a = b.dataset.action;
    try {
      if (a === "list") openList();
      else if (a === "apnames") openApNames();
      else if (a === "filter") openFilter();
      else if (a === "devices") await openDevices();
      else if (a === "combine") openCombine();
      else if (a === "export-geojson") exportGeoJson();
      else if (a === "export-csv") await exportCsv();
      else if (a === "logout") { localStorage.removeItem("nm.key"); sessionStorage.removeItem("nm.key"); location.reload(); }
      else if (a.startsWith("site-")) await siteAction(a);
    } catch (e) { alert(e.message); }
  }));
  document.addEventListener("keydown", (e) => {
    if (e.key !== "Escape") return;
    const lb = document.querySelector(".lightbox");
    if (lb) lb.remove(); else if (S.mode) cancelMode();
  });

  const saved = localStorage.getItem("nm.key") || sessionStorage.getItem("nm.key");
  if (saved) {
    S.key = saved; S.deviceName = localStorage.getItem("nm.name") || "Browser";
    api("GET", "/api/sites").then(start).catch((e) => showLogin(e.auth ? "Please sign in again." : e.message));
  } else showLogin();
});

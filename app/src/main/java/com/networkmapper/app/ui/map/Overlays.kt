package com.networkmapper.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.view.MotionEvent
import com.networkmapper.app.data.Asset
import com.networkmapper.app.data.AssetType
import com.networkmapper.app.data.Coverage
import com.networkmapper.app.data.CoveragePattern
import com.networkmapper.app.data.PatternType
import com.networkmapper.app.data.GeoPt
import com.networkmapper.app.data.NetPath
import com.networkmapper.app.data.isLocation
import com.networkmapper.app.ui.Draft
import com.networkmapper.app.ui.HeatPoint
import com.networkmapper.app.ui.MapSettings
import com.networkmapper.app.ui.PinMove
import com.networkmapper.app.ui.Selection
import com.networkmapper.app.ui.TileStyle
import com.networkmapper.app.util.Wifi
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import kotlin.math.abs
import kotlin.math.hypot

object TileSources {
    /** Esri World Imagery uses z/y/x ordering, so it needs a custom URL builder. */
    val esriImagery: ITileSource = object : OnlineTileSourceBase(
        "EsriWorldImagery", 0, 19, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "Imagery © Esri, Maxar, Earthstar Geographics",
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
    }

    fun forStyle(style: TileStyle): ITileSource = when (style) {
        TileStyle.STREET -> TileSourceFactory.MAPNIK
        TileStyle.TOPO -> TileSourceFactory.OpenTopo
        TileStyle.SATELLITE -> esriImagery
    }
}

/** Owns every osmdroid overlay and rebuilds them from app state. */
class MapOverlayController(private val context: Context, val mapView: MapView) {

    var onAssetClick: (Asset) -> Unit = {}
    var onPathClick: (NetPath) -> Unit = {}
    var onMapTap: (GeoPt) -> Boolean = { false }
    var onLongPress: (GeoPt) -> Boolean = { false }
    var onAssetDragged: (Asset, GeoPt) -> Unit = { _, _ -> }
    var onUserPan: () -> Unit = {}
    var onVertexTap: (Int) -> Unit = {}
    /** Tap on a Wi-Fi heatmap dot; return true if handled. */
    var onHeatTap: (HeatPoint) -> Boolean = { false }
    var onVertexDragStart: (Int) -> Unit = {}
    var onVertexDrag: (Int, GeoPt) -> Unit = { _, _ -> }

    private val density = context.resources.displayMetrics.density

    private val events = MapEventsOverlay(object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean =
            p != null && onMapTap(GeoPt(p.latitude, p.longitude))

        override fun longPressHelper(p: GeoPoint?): Boolean =
            p != null && onLongPress(GeoPt(p.latitude, p.longitude))
    })
    private val touchWatcher = TouchWatcherOverlay(density) { onUserPan() }
    private val heat = HeatmapOverlay(density) { onHeatTap(it) }
    private val patterns = PatternOverlay(density)
    private val draftOverlay = DraftOverlay(
        density,
        onTap = { onVertexTap(it) },
        onDragStart = { onVertexDragStart(it) },
        onDrag = { i, p -> onVertexDrag(i, p) },
    )
    private val myLocation = MyLocationOverlay(density)
    private val scaleBar = ScaleBarOverlay(mapView).apply {
        setAlignBottom(true)
        setScaleBarOffset((16 * density).toInt(), (16 * density).toInt())
    }
    private val copyright = CopyrightOverlay(context)
    private val iconCache = HashMap<String, Drawable>()
    private var currentTiles: TileStyle? = null

    fun setTileStyle(style: TileStyle) {
        if (style == currentTiles) return
        currentTiles = style
        mapView.setTileSource(TileSources.forStyle(style))
    }

    fun setLocation(location: Location?) {
        myLocation.location = location
        mapView.invalidate()
    }

    /** Cheap update for the in-progress route (called on every vertex drag / new point). */
    fun setDraft(draft: Draft?) {
        draftOverlay.draft = draft
        mapView.invalidate()
    }

    fun render(
        assets: List<Asset>,
        paths: List<NetPath>,
        settings: MapSettings,
        drafting: Boolean,
        editingPathId: Long?,
        heatPoints: List<HeatPoint>,
        selected: Selection?,
        pinMove: PinMove?,
    ) {
        val overlays = mapView.overlays
        overlays.clear()
        overlays.add(events)
        overlays.add(touchWatcher)
        // Devices inside a location (closet / rack) are drawn as part of the location's pin.
        val locations = assets.filter { it.isLocation }.associateBy { it.uid }
        val membersOf = assets.filter { !it.isLocation && it.groupUid in locations }.groupBy { it.groupUid }
        fun shownPoint(a: Asset): GeoPt {
            val holder = if (!a.isLocation) locations[a.groupUid] ?: a else a
            val moving = pinMove?.takeIf { it.assetId == holder.id }
            return moving?.current ?: GeoPt(holder.latitude, holder.longitude)
        }
        if (settings.showPatterns && settings.showAssets) {
            val selectedAssetId = (selected as? Selection.AssetSel)?.id
            patterns.items = assets.filter { it.type !in settings.hiddenTypes }.mapNotNull { a ->
                val pattern = Coverage.read(a.attributes) ?: return@mapNotNull null
                val at = shownPoint(a)
                PatternOverlay.Item(
                    lat = at.lat,
                    lon = at.lon,
                    pattern = pattern,
                    color = a.type.colorArgb.toInt(),
                    selected = a.id == selectedAssetId,
                )
            }
            if (patterns.items.isNotEmpty()) overlays.add(patterns)
        }
        if (settings.showHeatmap) {
            heat.points = heatPoints
            overlays.add(heat)
        }
        if (settings.showPaths) {
            val selectedPathId = (selected as? Selection.PathSel)?.id
            // The path being re-edited is drawn by the draft overlay instead.
            paths.filter { it.points.size >= 2 && it.id != editingPathId }
                .forEach { overlays.add(buildPolyline(it, it.id == selectedPathId)) }
        }
        if (settings.showAssets) {
            val selectedAssetId = (selected as? Selection.AssetSel)?.id
            // Pins can only be dragged in explicit Move mode, and only the pin being moved.
            assets.filter { it.type !in settings.hiddenTypes || it.id == pinMove?.assetId }.forEach {
                if (!it.isLocation && it.groupUid in locations) return@forEach // drawn inside its location
                val moving = pinMove?.assetId == it.id
                val shown = if (moving && pinMove != null) {
                    it.copy(latitude = pinMove.current.lat, longitude = pinMove.current.lon)
                } else {
                    it
                }
                if (it.isLocation) {
                    val members = membersOf[it.uid].orEmpty()
                    val sel = it.id == selectedAssetId || moving || members.any { m -> m.id == selectedAssetId }
                    overlays.add(buildLocationMarker(shown, members.filter { m -> m.type !in settings.hiddenTypes }, sel, draggable = moving))
                } else {
                    overlays.add(buildMarker(shown, it.id == selectedAssetId || moving, draggable = moving))
                }
            }
        }
        // Above the pins so route vertices can be grabbed; taps elsewhere still reach pins (to snap).
        if (drafting) overlays.add(draftOverlay)
        overlays.add(myLocation)
        overlays.add(scaleBar)
        overlays.add(copyright)
        mapView.invalidate()
    }

    private fun buildPolyline(path: NetPath, selected: Boolean): Polyline {
        val line = Polyline(mapView)
        line.setPoints(path.points.map { GeoPoint(it.lat, it.lon) })
        val paint = line.outlinePaint
        paint.color = (path.colorArgb ?: path.kind.colorArgb).toInt()
        paint.strokeWidth = (if (selected) 8f else 5f) * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.isAntiAlias = true
        if (path.kind.dashed) paint.pathEffect = DashPathEffect(floatArrayOf(12f * density, 6f * density), 0f)
        line.setOnClickListener { _, _, _ ->
            onPathClick(path)
            true
        }
        return line
    }

    private fun buildMarker(asset: Asset, selected: Boolean, draggable: Boolean): Marker {
        val m = Marker(mapView)
        m.position = GeoPoint(asset.latitude, asset.longitude)
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        m.icon = icon(asset.type, selected)
        m.title = asset.name
        m.isDraggable = draggable
        m.setOnMarkerClickListener { _, _ ->
            onAssetClick(asset)
            true
        }
        if (draggable) {
            m.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(marker: Marker?) {}
                override fun onMarkerDragStart(marker: Marker?) {}
                override fun onMarkerDragEnd(marker: Marker?) {
                    val p = marker?.position ?: return
                    onAssetDragged(asset, GeoPt(p.latitude, p.longitude))
                }
            })
        }
        return m
    }

    private fun buildLocationMarker(location: Asset, members: List<Asset>, selected: Boolean, draggable: Boolean): Marker {
        val m = Marker(mapView)
        m.position = GeoPoint(location.latitude, location.longitude)
        val (drawable, anchorY) = locationIcon(location.name, members, selected)
        m.icon = drawable
        m.setAnchor(Marker.ANCHOR_CENTER, anchorY)
        m.title = location.name
        m.isDraggable = draggable
        m.setOnMarkerClickListener { _, _ ->
            onAssetClick(location)
            true
        }
        if (draggable) {
            m.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(marker: Marker?) {}
                override fun onMarkerDragStart(marker: Marker?) {}
                override fun onMarkerDragEnd(marker: Marker?) {
                    val p = marker?.position ?: return
                    onAssetDragged(location, GeoPt(p.latitude, p.longitude))
                }
            })
        }
        return m
    }

    private val locationIcons = HashMap<String, Pair<Drawable, Float>>()

    /** One rounded pin with a coloured chip per device type, e.g. [R] [S×2] [A], and the location name below. */
    private fun locationIcon(name: String, members: List<Asset>, selected: Boolean): Pair<Drawable, Float> {
        val chips = LocationChips.of(members)
        val label = if (name.length > 20) name.take(19) + "…" else name
        val key = "$label|${chips.joinToString { "${it.text}:${it.color}" }}|$selected"
        return locationIcons.getOrPut(key) { drawLocationIcon(label, chips, selected) }
    }

    private fun drawLocationIcon(label: String, chips: List<LocationChips.Chip>, selected: Boolean): Pair<Drawable, Float> {
        val d = density
        val chipH = 22f * d
        val gap = 3f * d
        val pad = 4f * d
        val halo = if (selected) 5f * d else 0f
        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12.5f * d
            textAlign = Paint.Align.CENTER
        }
        val widths = chips.map { maxOf(chipH, chipPaint.measureText(it.text) + 12f * d) }
        val bodyW = pad * 2 + widths.sum() + gap * (widths.size - 1).coerceAtLeast(0)
        val bodyH = chipH + pad * 2
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11f * d
            textAlign = Paint.Align.CENTER
        }
        val labelW = labelPaint.measureText(label) + 8f * d
        val w = maxOf(bodyW + halo * 2, labelW).toInt() + 2
        val labelTop = halo * 2 + bodyH + 2f * d
        val h = (labelTop + 15f * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val left = (w - bodyW) / 2f
        val top = halo
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (selected) {
            p.color = 0x88FFEB3B.toInt()
            c.drawRoundRect(left - halo, top - halo, left + bodyW + halo, top + bodyH + halo, bodyH / 2 + halo, bodyH / 2 + halo, p)
        }
        p.color = 0x55000000
        c.drawRoundRect(left, top + 1.5f * d, left + bodyW, top + bodyH + 1.5f * d, bodyH / 2, bodyH / 2, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawRoundRect(left, top, left + bodyW, top + bodyH, bodyH / 2, bodyH / 2, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * d
        p.color = AssetType.LOCATION.colorArgb.toInt()
        c.drawRoundRect(left + d, top + d, left + bodyW - d, top + bodyH - d, bodyH / 2 - d, bodyH / 2 - d, p)
        p.style = Paint.Style.FILL
        var x = left + pad
        chips.forEachIndexed { i, chip ->
            val cw = widths[i]
            p.color = chip.color
            c.drawRoundRect(x, top + pad, x + cw, top + pad + chipH, chipH / 2, chipH / 2, p)
            chipPaint.color = 0xFFFFFFFF.toInt()
            val ty = top + pad + chipH / 2 - (chipPaint.descent() + chipPaint.ascent()) / 2
            c.drawText(chip.text, x + cw / 2, ty, chipPaint)
            x += cw + gap
        }
        // Name with a white halo so it reads on any map.
        val ly = labelTop - labelPaint.ascent()
        labelPaint.style = Paint.Style.STROKE
        labelPaint.strokeWidth = 3f * d
        labelPaint.color = 0xFFFFFFFF.toInt()
        c.drawText(label, w / 2f, ly, labelPaint)
        labelPaint.style = Paint.Style.FILL
        labelPaint.color = 0xFF263238.toInt()
        c.drawText(label, w / 2f, ly, labelPaint)
        return BitmapDrawable(context.resources, bmp) to (top + bodyH / 2) / h
    }

    private fun icon(type: AssetType, selected: Boolean): Drawable =
        iconCache.getOrPut("${type.name}:$selected") { buildIcon(type, selected) }

    private fun buildIcon(type: AssetType, selected: Boolean): Drawable {
        val sizeDp = if (selected) 40f else 30f
        val size = (sizeDp * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (selected) {
            paint.color = 0x55FFEB3B
            c.drawCircle(r, r, r, paint)
        }
        val inner = if (selected) r * 0.75f else r - density
        paint.color = 0xFFFFFFFF.toInt()
        c.drawCircle(r, r, inner, paint)
        paint.color = type.colorArgb.toInt()
        c.drawCircle(r, r, inner - 2.5f * density, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = inner * 0.95f
        val y = r - (paint.descent() + paint.ascent()) / 2f
        c.drawText(type.glyph, r, y, paint)
        return BitmapDrawable(context.resources, bmp)
    }
}

/** Notices when the user drags the map (to cancel follow-me mode) without consuming the event. */
class TouchWatcherOverlay(private val density: Float, private val onPan: () -> Unit) : Overlay() {
    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent?, mapView: MapView?): Boolean {
        val e = event ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_MOVE ->
                if (hypot(e.x - downX, e.y - downY) > 12 * density || e.pointerCount > 1) onPan()
        }
        return false
    }
}

/**
 * Antenna coverage patterns: omni circles or sector / directional wedges, shaded in three
 * bands (strong ≤50 %, good ≤80 %, weak ≤100 % of the range) and outlined in the pin's colour.
 */
class PatternOverlay(private val density: Float) : Overlay() {
    data class Item(val lat: Double, val lon: Double, val pattern: CoveragePattern, val color: Int, val selected: Boolean)

    var items: List<Item> = emptyList()
    private val bands = listOf(
        1.0f to Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30FB8C00; style = Paint.Style.FILL },
        0.8f to Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x34FDD835; style = Paint.Style.FILL },
        0.5f to Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3843A047; style = Paint.Style.FILL },
    )
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val p1 = Point()
    private val p2 = Point()
    private val rect = android.graphics.RectF()

    override fun draw(c: Canvas?, proj: Projection?) {
        if (c == null || proj == null) return
        val maxR = maxOf(c.width, c.height) * 4f
        for (item in items) {
            proj.toPixels(GeoPoint(item.lat, item.lon), p1)
            proj.toPixels(GeoPoint(item.lat + item.pattern.rangeM / 111_320.0, item.lon), p2)
            val r = abs(p1.y - p2.y).toFloat()
            if (r < 4f * density) continue
            val cx = p1.x.toFloat()
            val cy = p1.y.toFloat()
            for ((fraction, paint) in bands) shape(c, cx, cy, (r * fraction).coerceAtMost(maxR), item.pattern, paint)
            outline.color = (item.color and 0x00FFFFFF) or (if (item.selected) 0xF0000000.toInt() else 0x99000000.toInt())
            outline.strokeWidth = (if (item.selected) 3f else 1.5f) * density
            shape(c, cx, cy, r.coerceAtMost(maxR), item.pattern, outline)
        }
    }

    private fun shape(c: Canvas, cx: Float, cy: Float, r: Float, p: CoveragePattern, paint: Paint) {
        if (p.type == PatternType.OMNI) {
            c.drawCircle(cx, cy, r, paint)
            return
        }
        rect.set(cx - r, cy - r, cx + r, cy + r)
        val beam = p.effectiveBeamwidth.toFloat()
        // Canvas angles start at 3 o'clock; azimuth starts at north.
        c.drawArc(rect, (p.azimuth - 90.0 - beam / 2.0).toFloat(), beam, true, paint)
    }
}

/** Draws Wi-Fi survey samples as colour-coded dots. */
class HeatmapOverlay(private val density: Float, private val onTap: (HeatPoint) -> Boolean) : Overlay() {
    var points: List<HeatPoint> = emptyList()
    private val hit = Point()

    /** Picks the dot nearest the finger (within ~18 dp). */
    override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
        val ev = e ?: return false
        val mv = mapView ?: return false
        val proj = mv.projection
        val slop = 18f * density
        var best: HeatPoint? = null
        var bestDist = Float.MAX_VALUE
        val g = GeoPoint(0.0, 0.0)
        for (p in points) {
            g.setCoords(p.lat, p.lon)
            proj.toPixels(g, hit)
            val d = hypot(hit.x - ev.x, hit.y - ev.y)
            if (d < slop && d < bestDist) {
                best = p
                bestDist = d
            }
        }
        val chosen = best ?: return false
        return onTap(chosen)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pt = Point()
    private val geo = GeoPoint(0.0, 0.0)

    override fun draw(c: Canvas?, proj: Projection?) {
        if (c == null || proj == null) return
        val bb = proj.boundingBox
        val zoom = proj.zoomLevel
        val radius = (if (zoom >= 18) 9f else if (zoom >= 16) 6f else 4f) * density
        for (p in points) {
            if (p.lat < bb.latSouth || p.lat > bb.latNorth || p.lon < bb.lonWest || p.lon > bb.lonEast) continue
            geo.setCoords(p.lat, p.lon)
            proj.toPixels(geo, pt)
            paint.color = (Wifi.rssiColor(p.rssi) and 0x00FFFFFF or 0xC0000000).toInt()
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), radius, paint)
        }
    }
}

/**
 * Shows the in-progress route. Tap a vertex to select it; press and drag a vertex to move it.
 * Touches that don't start on a vertex fall through to the map (tap to add a point, pan, zoom).
 */
class DraftOverlay(
    private val density: Float,
    private val onTap: (Int) -> Unit,
    private val onDragStart: (Int) -> Unit,
    private val onDrag: (Int, GeoPt) -> Unit,
) : Overlay() {
    var draft: Draft? = null

    private val accent = 0xFFFF6F00.toInt()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        color = accent
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }
    private val vertex = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = 0xFFFFFFFF.toInt()
    }
    private val selectedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1565C0.toInt() }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x551565C0 }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 9f * density
    }
    private val a = Point()
    private val b = Point()

    // Touch state
    private var activeIndex = -1
    private var dragging = false
    private var downX = 0f
    private var downY = 0f

    override fun draw(c: Canvas?, proj: Projection?) {
        if (c == null || proj == null) return
        val d = draft ?: return
        val pts = d.points
        for (i in 1 until pts.size) {
            proj.toPixels(GeoPoint(pts[i - 1].pt.lat, pts[i - 1].pt.lon), a)
            proj.toPixels(GeoPoint(pts[i].pt.lat, pts[i].pt.lon), b)
            c.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), line)
        }
        val activeEnd = if (d.extendFrom == com.networkmapper.app.ui.DraftEnd.START) 0 else pts.lastIndex
        pts.forEachIndexed { i, p ->
            proj.toPixels(GeoPoint(p.pt.lat, p.pt.lon), a)
            val x = a.x.toFloat()
            val y = a.y.toFloat()
            val isEnd = i == 0 || i == pts.lastIndex
            // Bigger "handles" in Edit-points mode so they're easy to grab.
            val r = (if (isEnd) 8f else if (d.editPoints) 7f else 4.5f) * density
            if (i == d.selectedIndex) {
                c.drawCircle(x, y, r * 2.2f, halo)
                c.drawCircle(x, y, r * 1.3f, selectedFill)
                c.drawCircle(x, y, r * 1.3f, ring)
            } else {
                if (i == activeEnd && pts.size > 1) c.drawCircle(x, y, r * 1.9f, halo)
                c.drawCircle(x, y, r, vertex)
                if (p.assetId != null || isEnd) c.drawCircle(x, y, r, ring)
            }
            if (isEnd && pts.size > 1) {
                c.drawText(if (i == 0) "A" else "B", x, y - (label.descent() + label.ascent()) / 2f, label)
            }
        }
    }

    private fun hitVertex(x: Float, y: Float, proj: Projection, d: Draft): Int? {
        val slop = 22f * density
        var best: Int? = null
        var bestDist = Float.MAX_VALUE
        d.points.forEachIndexed { i, p ->
            proj.toPixels(GeoPoint(p.pt.lat, p.pt.lon), a)
            val dist = hypot(a.x - x, a.y - y)
            if (dist < slop && dist < bestDist) {
                best = i
                bestDist = dist
            }
        }
        return best
    }

    override fun onTouchEvent(event: MotionEvent?, mapView: MapView?): Boolean {
        val e = event ?: return false
        val mv = mapView ?: return false
        val d = draft ?: return false
        // Outside Edit-points mode the route can't be grabbed: touches pan the map / add points.
        if (!d.editPoints) {
            activeIndex = -1
            return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitVertex(e.x, e.y, mv.projection, d) ?: run {
                    activeIndex = -1
                    return false
                }
                activeIndex = hit
                dragging = false
                downX = e.x
                downY = e.y
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger: hand over to pinch-zoom.
                activeIndex = -1
                dragging = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeIndex < 0) return false
                if (!dragging && hypot(e.x - downX, e.y - downY) > 8f * density) {
                    dragging = true
                    onDragStart(activeIndex)
                }
                if (dragging) {
                    val g = mv.projection.fromPixels(e.x.toInt(), e.y.toInt())
                    onDrag(activeIndex, GeoPt(g.latitude, g.longitude))
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (activeIndex < 0) return false
                if (!dragging) onTap(activeIndex)
                activeIndex = -1
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                activeIndex = -1
                dragging = false
            }
        }
        return false
    }
}

/** Blue dot + accuracy circle for the current GPS fix (fed from our own LocationTracker). */
class MyLocationOverlay(private val density: Float) : Overlay() {
    var location: Location? = null
    private val accuracyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x302196F3; style = Paint.Style.FILL }
    private val accuracyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x902196F3.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1976D2.toInt() }
    private val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1976D2.toInt(); style = Paint.Style.FILL }
    private val p1 = Point()
    private val p2 = Point()
    private val path = android.graphics.Path()

    override fun draw(c: Canvas?, proj: Projection?) {
        if (c == null || proj == null) return
        val loc = location ?: return
        proj.toPixels(GeoPoint(loc.latitude, loc.longitude), p1)
        val x = p1.x.toFloat()
        val y = p1.y.toFloat()
        if (loc.hasAccuracy()) {
            proj.toPixels(GeoPoint(loc.latitude + loc.accuracy / 111_320.0, loc.longitude), p2)
            val r = abs(p1.y - p2.y).toFloat()
            if (r > 8 * density) {
                c.drawCircle(x, y, r, accuracyFill)
                c.drawCircle(x, y, r, accuracyStroke)
            }
        }
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.7f) {
            c.save()
            c.rotate(loc.bearing, x, y)
            path.reset()
            path.moveTo(x, y - 16 * density)
            path.lineTo(x - 7 * density, y - 5 * density)
            path.lineTo(x + 7 * density, y - 5 * density)
            path.close()
            c.drawPath(path, heading)
            c.restore()
        }
        c.drawCircle(x, y, 9 * density, white)
        c.drawCircle(x, y, 6.5f * density, blue)
    }
}

/** The chips on a location pin: one per device type, most important first, e.g. R, S×2, A. */
object LocationChips {
    data class Chip(val text: String, val color: Int)

    private val priority = listOf(
        AssetType.ROUTER, AssetType.SWITCH, AssetType.ACCESS_POINT, AssetType.WIRELESS_BRIDGE, AssetType.FDH,
        AssetType.SPLICE, AssetType.FIBER_JUNCTION, AssetType.SLACK_LOOP, AssetType.BUILDING_ENTRY, AssetType.POLE, AssetType.OTHER,
    )
    private const val MAX = 4

    fun of(members: List<Asset>): List<Chip> {
        if (members.isEmpty()) return listOf(Chip(AssetType.LOCATION.glyph, AssetType.LOCATION.colorArgb.toInt()))
        val byType = members.groupBy { it.type }.toList().sortedBy { (t, _) -> priority.indexOf(t).let { if (it < 0) 99 else it } }
        val chips = byType.map { (t, list) -> Chip(if (list.size > 1) "${t.glyph}×${list.size}" else t.glyph, t.colorArgb.toInt()) }
        if (chips.size <= MAX) return chips
        val rest = byType.drop(MAX - 1).sumOf { it.second.size }
        return chips.take(MAX - 1) + Chip("+$rest", 0xFF607D8B.toInt())
    }
}

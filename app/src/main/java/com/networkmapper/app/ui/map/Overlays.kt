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
import com.networkmapper.app.data.GeoPt
import com.networkmapper.app.data.NetPath
import com.networkmapper.app.ui.Draft
import com.networkmapper.app.ui.HeatPoint
import com.networkmapper.app.ui.MapSettings
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

    private val density = context.resources.displayMetrics.density

    private val events = MapEventsOverlay(object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean =
            p != null && onMapTap(GeoPt(p.latitude, p.longitude))

        override fun longPressHelper(p: GeoPoint?): Boolean =
            p != null && onLongPress(GeoPt(p.latitude, p.longitude))
    })
    private val touchWatcher = TouchWatcherOverlay(density) { onUserPan() }
    private val heat = HeatmapOverlay(density)
    private val draftOverlay = DraftOverlay(density)
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

    fun render(
        assets: List<Asset>,
        paths: List<NetPath>,
        settings: MapSettings,
        draft: Draft?,
        heatPoints: List<HeatPoint>,
        selected: Selection?,
    ) {
        val overlays = mapView.overlays
        overlays.clear()
        overlays.add(events)
        overlays.add(touchWatcher)
        if (settings.showHeatmap) {
            heat.points = heatPoints
            overlays.add(heat)
        }
        if (settings.showPaths) {
            val selectedPathId = (selected as? Selection.PathSel)?.id
            paths.filter { it.points.size >= 2 }.forEach { overlays.add(buildPolyline(it, it.id == selectedPathId)) }
        }
        draftOverlay.draft = draft
        if (draft != null) overlays.add(draftOverlay)
        if (settings.showAssets) {
            val selectedAssetId = (selected as? Selection.AssetSel)?.id
            val draggable = !settings.lockPins && draft == null
            assets.filter { it.type !in settings.hiddenTypes }.forEach {
                overlays.add(buildMarker(it, it.id == selectedAssetId, draggable))
            }
        }
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

/** Draws Wi-Fi survey samples as colour-coded dots. */
class HeatmapOverlay(private val density: Float) : Overlay() {
    var points: List<HeatPoint> = emptyList()
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

/** Shows the in-progress path being drawn. */
class DraftOverlay(private val density: Float) : Overlay() {
    var draft: Draft? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = 0xFFFF6F00.toInt()
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }
    private val vertex = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF6F00.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = 0xFFFFFFFF.toInt()
    }
    private val a = Point()
    private val b = Point()

    override fun draw(c: Canvas?, proj: Projection?) {
        if (c == null || proj == null) return
        val d = draft ?: return
        val pts = d.points
        for (i in 1 until pts.size) {
            proj.toPixels(GeoPoint(pts[i - 1].pt.lat, pts[i - 1].pt.lon), a)
            proj.toPixels(GeoPoint(pts[i].pt.lat, pts[i].pt.lon), b)
            c.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), line)
        }
        pts.forEachIndexed { i, p ->
            proj.toPixels(GeoPoint(p.pt.lat, p.pt.lon), a)
            val r = (if (i == 0 || i == pts.lastIndex) 7f else 4.5f) * density
            c.drawCircle(a.x.toFloat(), a.y.toFloat(), r, vertex)
            if (p.assetId != null || i == 0 || i == pts.lastIndex) c.drawCircle(a.x.toFloat(), a.y.toFloat(), r, ring)
        }
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

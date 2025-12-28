package com.exitreminder.gpstester

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Geocoder
import android.os.BatteryManager
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.widget.*
import android.widget.ScrollView
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var etAddress: EditText
    private lateinit var seekRadius: SeekBar
    private lateinit var tvRadius: TextView
    private lateinit var seekTolerance: SeekBar
    private lateinit var tvTolerance: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvAccuracy: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvNextCheck: TextView
    private lateinit var tvCheckCount: TextView
    private lateinit var tvETA: TextView
    private lateinit var tvStartDistance: TextView
    private lateinit var rvLog: RecyclerView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var mapView: MapView
    private lateinit var tvWifiStatus: TextView
    private lateinit var tvWifiSSID: TextView
    private lateinit var tvHomeDistance: TextView
    private lateinit var switchWifiHybrid: Switch
    private lateinit var btnSetWifiHome: Button
    private lateinit var directionSection: LinearLayout
    private lateinit var tvDirectionStatus: TextView
    private lateinit var tvHouseToSnapDist: TextView
    private lateinit var snapInfoSection: LinearLayout
    private lateinit var tvOriginalCoords: TextView
    private lateinit var tvSnappedCoords: TextView
    private lateinit var tvRoadDistance: TextView
    private lateinit var tvRoadName: TextView

    private val logAdapter = LogAdapter()

    // Map Marker
    private var currentPosMarker: Marker? = null
    private var targetMarker: Marker? = null
    private var houseMarker: Marker? = null
    private var snapMarker: Marker? = null
    private var houseToSnapLine: Polyline? = null
    private var routeLine: Polyline? = null
    private var snapToleranceCircle: Polygon? = null
    private var targetToleranceCircle: Polygon? = null
    private var targetLat: Double = 0.0
    private var targetLng: Double = 0.0

    // WLAN Home Positionen für Karte
    private var wifiHouseLat: Double = 0.0
    private var wifiHouseLng: Double = 0.0
    private var wifiSnapLat: Double = 0.0
    private var wifiSnapLng: Double = 0.0

    // Route-Daten
    private var currentRoute: RoadSnapService.RouteResult? = null
    private var routePolyline: Polyline? = null

    // Trigger-Linie HOME (entlang der Straße)
    private var triggerLine: Polyline? = null
    private var triggerLineStart: Pair<Double, Double>? = null
    private var triggerLineEnd: Pair<Double, Double>? = null

    // Trigger-Linie ZIEL (entlang der Straße)
    private var targetTriggerLine: Polyline? = null
    private var targetTriggerLineStart: Pair<Double, Double>? = null
    private var targetTriggerLineEnd: Pair<Double, Double>? = null
    private var startDistanceValue: Float = 0f
    private var triggerTolerance: Float = 15f  // Default 15m Toleranz
    private var locationManager: PredictiveLocationManager? = null
    private var wifiManager: WifiLocationManager? = null
    private var service: ForegroundLocationService? = null
    private var isBound = false

    // Geofence Wake-Up Manager
    private var geofenceManager: GeofenceWakeupManager? = null
    private var currentReminderId: String = ""
    private var isInSleepMode: Boolean = false

    // Sleep Status UI
    private lateinit var sleepStatusSection: LinearLayout
    private lateinit var tvSleepIcon: TextView
    private lateinit var tvSleepStatus: TextView
    private lateinit var tvSleepDetail: TextView
    private lateinit var tvSleepBattery: TextView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as ForegroundLocationService.LocalBinder
            service = localBinder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        checkPermissions()

        // Geofence Manager initialisieren
        geofenceManager = GeofenceWakeupManager(this)
    }

    private fun initViews() {
        etLat = findViewById(R.id.etLat)
        etLng = findViewById(R.id.etLng)
        etAddress = findViewById(R.id.etAddress)
        seekRadius = findViewById(R.id.seekRadius)
        tvRadius = findViewById(R.id.tvRadius)
        seekTolerance = findViewById(R.id.seekTolerance)
        tvTolerance = findViewById(R.id.tvTolerance)
        tvDistance = findViewById(R.id.tvDistance)
        tvAccuracy = findViewById(R.id.tvAccuracy)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvMode = findViewById(R.id.tvMode)
        tvNextCheck = findViewById(R.id.tvNextCheck)
        tvCheckCount = findViewById(R.id.tvCheckCount)
        tvETA = findViewById(R.id.tvETA)
        tvStartDistance = findViewById(R.id.tvStartDistance)
        rvLog = findViewById(R.id.rvLog)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        mapView = findViewById(R.id.mapView)
        tvWifiStatus = findViewById(R.id.tvWifiStatus)
        tvWifiSSID = findViewById(R.id.tvWifiSSID)
        tvHomeDistance = findViewById(R.id.tvHomeDistance)
        switchWifiHybrid = findViewById(R.id.switchWifiHybrid)
        btnSetWifiHome = findViewById(R.id.btnSetWifiHome)
        directionSection = findViewById(R.id.directionSection)
        tvDirectionStatus = findViewById(R.id.tvDirectionStatus)
        tvHouseToSnapDist = findViewById(R.id.tvHouseToSnapDist)
        snapInfoSection = findViewById(R.id.snapInfoSection)
        tvOriginalCoords = findViewById(R.id.tvOriginalCoords)
        tvSnappedCoords = findViewById(R.id.tvSnappedCoords)
        tvRoadDistance = findViewById(R.id.tvRoadDistance)
        tvRoadName = findViewById(R.id.tvRoadName)

        rvLog.layoutManager = LinearLayoutManager(this)
        rvLog.adapter = logAdapter

        // Sleep Status Views
        sleepStatusSection = findViewById(R.id.sleepStatusSection)
        tvSleepIcon = findViewById(R.id.tvSleepIcon)
        tvSleepStatus = findViewById(R.id.tvSleepStatus)
        tvSleepDetail = findViewById(R.id.tvSleepDetail)
        tvSleepBattery = findViewById(R.id.tvSleepBattery)

        // OSMDroid konfigurieren
        initMap()

        // Default: Breitenstein 17, Walchwil (Beispiel)
        etLat.setText("47.1189")
        etLng.setText("8.5160")
    }

    private fun initMap() {
        // OSMDroid Konfiguration
        val config = Configuration.getInstance()
        config.userAgentValue = "GPSPredictiveTester/1.0"
        val cacheDir = File(cacheDir, "osmdroid/tiles")
        cacheDir.mkdirs()
        config.osmdroidTileCache = cacheDir

        // Map Setup
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

        // Default Position (Schweiz)
        mapView.controller.setZoom(15.0)
        mapView.controller.setCenter(GeoPoint(47.1189, 8.5160))
    }

    private fun updateMap(currentLat: Double, currentLng: Double) {
        mapView.overlays.clear()

        // WLAN Home Marker wieder hinzufügen (falls vorhanden)
        triggerLine?.let { mapView.overlays.add(it) }  // Trigger-Linie zuerst (unter anderen)
        snapToleranceCircle?.let { mapView.overlays.add(it) }
        houseToSnapLine?.let { mapView.overlays.add(it) }
        houseMarker?.let { mapView.overlays.add(it) }
        snapMarker?.let { mapView.overlays.add(it) }

        // Route-Linie wieder hinzufügen (falls vorhanden)
        routePolyline?.let { mapView.overlays.add(it) }

        val currentPos = GeoPoint(currentLat, currentLng)
        val targetPos = GeoPoint(targetLat, targetLng)

        // Ziel-Trigger-LINIE (gelb) oder Kreis als Fallback
        if (targetTriggerLineStart != null && targetTriggerLineEnd != null) {
            targetTriggerLine = Polyline().apply {
                addPoint(GeoPoint(targetTriggerLineStart!!.first, targetTriggerLineStart!!.second))
                addPoint(GeoPoint(targetTriggerLineEnd!!.first, targetTriggerLineEnd!!.second))
                outlinePaint.color = Color.parseColor("#eab308")  // Gelb
                outlinePaint.strokeWidth = 12f
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            }
            mapView.overlays.add(targetTriggerLine)
        } else {
            // Fallback: Kreis
            val triggerRadius = if (::seekRadius.isInitialized) seekRadius.progress.toDouble() else 50.0
            targetToleranceCircle = createCircle(
                targetPos,
                triggerRadius,
                Color.parseColor("#40eab308"),  // Gelb, 25% transparent
                Color.parseColor("#eab308")      // Gelb Rand
            )
            mapView.overlays.add(targetToleranceCircle)
        }

        // Route-Linie nur zeichnen wenn keine echte Route vorhanden
        if (routePolyline == null) {
            routeLine = Polyline().apply {
                addPoint(currentPos)
                addPoint(targetPos)
                outlinePaint.color = Color.parseColor("#9898a8")  // Grau = Luftlinie
                outlinePaint.strokeWidth = 3f
                outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
            }
            mapView.overlays.add(routeLine)
        }

        // Aktueller Standort (grün)
        currentPosMarker = Marker(mapView).apply {
            position = currentPos
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "Du bist hier"
            icon = resources.getDrawable(android.R.drawable.presence_online, null)
        }
        mapView.overlays.add(currentPosMarker)

        // Ziel (rot)
        targetMarker = Marker(mapView).apply {
            position = targetPos
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Ziel"
            icon = resources.getDrawable(android.R.drawable.ic_menu_myplaces, null)
        }
        mapView.overlays.add(targetMarker)

        // Kamera so positionieren, dass beide Punkte sichtbar sind
        val centerLat = (currentLat + targetLat) / 2
        val centerLng = (currentLng + targetLng) / 2
        mapView.controller.setCenter(GeoPoint(centerLat, centerLng))

        // Zoom berechnen basierend auf Distanz
        val results = FloatArray(1)
        android.location.Location.distanceBetween(currentLat, currentLng, targetLat, targetLng, results)
        val distance = results[0]
        val zoom = when {
            distance < 100 -> 18.0
            distance < 300 -> 17.0
            distance < 500 -> 16.0
            distance < 1000 -> 15.0
            distance < 2000 -> 14.0
            else -> 13.0
        }
        mapView.controller.setZoom(zoom)

        mapView.invalidate()
    }

    /**
     * Zeigt das Ziel auf der Karte und berechnet Route vom Home
     */
    private fun showTargetOnMap(lat: Double, lng: Double) {
        android.util.Log.d("Route", "=== showTargetOnMap CALLED: target=$lat,$lng ===")
        logAdapter.addEntry(LogEntry(
            timestamp = System.currentTimeMillis(),
            distance = 0f, accuracy = 0f, speedKmh = 0f,
            category = SpeedCategory.STILL, mode = PrecisionMode.LOW_POWER, nextCheckSec = 0f,
            event = "🎯 ZIEL GESUCHT: ${"%.5f".format(lat)}, ${"%.5f".format(lng)}"
        ))

        mapView.overlays.clear()

        // WLAN Home Marker wieder hinzufügen (falls vorhanden)
        triggerLine?.let { mapView.overlays.add(it) }  // Trigger-Linie zuerst (unter anderen)
        snapToleranceCircle?.let { mapView.overlays.add(it) }
        houseToSnapLine?.let { mapView.overlays.add(it) }
        houseMarker?.let { mapView.overlays.add(it) }
        snapMarker?.let { mapView.overlays.add(it) }

        val targetPos = GeoPoint(lat, lng)

        // Alte Ziel-Elemente entfernen
        targetTriggerLine?.let { mapView.overlays.remove(it) }
        targetToleranceCircle?.let { mapView.overlays.remove(it) }

        // Ziel-Trigger-LINIE (gelb, entlang der Straße) statt Kreis
        if (targetTriggerLineStart != null && targetTriggerLineEnd != null) {
            targetTriggerLine = Polyline().apply {
                addPoint(GeoPoint(targetTriggerLineStart!!.first, targetTriggerLineStart!!.second))
                addPoint(GeoPoint(targetTriggerLineEnd!!.first, targetTriggerLineEnd!!.second))
                outlinePaint.color = Color.parseColor("#eab308")  // Gelb
                outlinePaint.strokeWidth = 12f  // Dick für Sichtbarkeit
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            }
            mapView.overlays.add(targetTriggerLine)
        } else {
            // Fallback: Kreis wenn keine Linie verfügbar
            val triggerRadius = if (::seekRadius.isInitialized) seekRadius.progress.toDouble() else 50.0
            targetToleranceCircle = createCircle(
                targetPos,
                triggerRadius,
                Color.parseColor("#40eab308"),  // Gelb, 25% transparent
                Color.parseColor("#eab308")      // Gelb Rand
            )
            mapView.overlays.add(targetToleranceCircle)
        }

        // Ziel-Marker (rot)
        targetMarker = Marker(mapView).apply {
            position = targetPos
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "🎯 Ziel"
            icon = resources.getDrawable(android.R.drawable.ic_menu_myplaces, null).apply {
                setTint(Color.parseColor("#ef4444"))  // Rot
            }
        }
        mapView.overlays.add(targetMarker)

        // Route vom Home (Snap-Punkt) zum Ziel berechnen
        // Prüfe verschiedene Quellen für Home-Koordinaten
        android.util.Log.d("Route", "showTargetOnMap: snapMarker=$snapMarker, wifiSnapLat=$wifiSnapLat")

        val homeLat: Double
        val homeLng: Double
        val homeSource: String

        when {
            // 1. Priorität: snapMarker (Map Marker)
            snapMarker != null && snapMarker!!.position != null -> {
                homeLat = snapMarker!!.position.latitude
                homeLng = snapMarker!!.position.longitude
                homeSource = "snapMarker"
            }
            // 2. Priorität: Gespeicherte Snap-Koordinaten
            wifiSnapLat != 0.0 && wifiSnapLng != 0.0 -> {
                homeLat = wifiSnapLat
                homeLng = wifiSnapLng
                homeSource = "wifiSnap"
            }
            // 3. Priorität: houseMarker
            houseMarker != null && houseMarker!!.position != null -> {
                homeLat = houseMarker!!.position.latitude
                homeLng = houseMarker!!.position.longitude
                homeSource = "houseMarker"
            }
            // 4. Priorität: Gespeicherte Haus-Koordinaten
            wifiHouseLat != 0.0 && wifiHouseLng != 0.0 -> {
                homeLat = wifiHouseLat
                homeLng = wifiHouseLng
                homeSource = "wifiHouse"
            }
            else -> {
                // Kein Home gefunden
                android.util.Log.d("Route", "NO HOME - cannot calculate route!")
                logAdapter.addEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = 0f, accuracy = 0f, speedKmh = 0f,
                    category = SpeedCategory.STILL, mode = PrecisionMode.LOW_POWER, nextCheckSec = 0f,
                    event = "⚠️ KEIN STARTPUNKT! Starte erst Tracking mit WLAN."
                ))
                Toast.makeText(this, "⚠️ Kein Startpunkt - starte Tracking!", Toast.LENGTH_LONG).show()
                mapView.controller.setCenter(targetPos)
                mapView.controller.setZoom(17.0)
                mapView.invalidate()
                return
            }
        }

        android.util.Log.d("Route", "Using $homeSource: $homeLat, $homeLng -> $lat, $lng")
        logAdapter.addEntry(LogEntry(
            timestamp = System.currentTimeMillis(),
            distance = 0f, accuracy = 0f, speedKmh = 0f,
            category = SpeedCategory.STILL, mode = PrecisionMode.LOW_POWER, nextCheckSec = 0f,
            event = "📍 START=$homeSource: ${"%.4f".format(homeLat)},${"%.4f".format(homeLng)}"
        ))

        logAdapter.addEntry(LogEntry(
            timestamp = System.currentTimeMillis(),
            distance = 0f, accuracy = 0f, speedKmh = 0f,
            category = SpeedCategory.STILL, mode = PrecisionMode.LOW_POWER, nextCheckSec = 0f,
            event = "🚀 Starte Route-Berechnung..."
        ))

        // Route async berechnen
        CoroutineScope(Dispatchers.Main).launch {
            try {
                calculateAndShowRoute(homeLat, homeLng, lat, lng)
            } catch (e: Exception) {
                android.util.Log.e("Route", "Route exception: ${e.message}", e)
                logAdapter.addEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = 0f, accuracy = 0f, speedKmh = 0f,
                    category = SpeedCategory.STILL, mode = PrecisionMode.LOW_POWER, nextCheckSec = 0f,
                    event = "❌ ROUTE EXCEPTION: ${e.message}"
                ))
            }
        }

        mapView.invalidate()
    }

    /**
     * Berechnet Route entlang der Straßen und zeigt sie auf der Karte
     */
    private suspend fun calculateAndShowRoute(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double) {
        // Zeige die exakten Koordinaten
        logAdapter.addEntry(LogEntry(
            timestamp = System.currentTimeMillis(),
            distance = 0f,
            accuracy = 0f,
            speedKmh = 0f,
            category = SpeedCategory.STILL,
            mode = PrecisionMode.LOW_POWER,
            nextCheckSec = 0f,
            event = "🗺️ Route: ${"%.5f".format(fromLat)},${"%.5f".format(fromLng)} → ${"%.5f".format(toLat)},${"%.5f".format(toLng)}"
        ))

        val route = RoadSnapService.getRoute(fromLat, fromLng, toLat, toLng)

        if (route != null) {
            currentRoute = route

            // Alte Route entfernen
            routePolyline?.let { mapView.overlays.remove(it) }

            // Neue Route zeichnen (blau, entlang der Straßen)
            routePolyline = Polyline().apply {
                for (point in route.routePoints) {
                    addPoint(GeoPoint(point.first, point.second))
                }
                outlinePaint.color = Color.parseColor("#3b82f6")  // Blau
                outlinePaint.strokeWidth = 6f
            }
            mapView.overlays.add(routePolyline)

            // Log mit Straßen-Distanz
            val distanceKm = route.distanceMeters / 1000f
            val durationMin = (route.durationSeconds / 60f).toInt()

            logAdapter.addEntry(LogEntry(
                timestamp = System.currentTimeMillis(),
                distance = route.distanceMeters,
                accuracy = 0f,
                speedKmh = 0f,
                category = SpeedCategory.STILL,
                mode = PrecisionMode.LOW_POWER,
                nextCheckSec = 0f,
                event = "🛣️ Straßen-Route: ${route.distanceMeters.toInt()}m (~${durationMin} Min)"
            ))

            // Karte so zoomen, dass Route sichtbar ist
            val minLat = minOf(fromLat, toLat)
            val maxLat = maxOf(fromLat, toLat)
            val minLng = minOf(fromLng, toLng)
            val maxLng = maxOf(fromLng, toLng)
            val centerLat = (minLat + maxLat) / 2
            val centerLng = (minLng + maxLng) / 2

            mapView.controller.setCenter(GeoPoint(centerLat, centerLng))

            // Zoom basierend auf Distanz
            val zoom = when {
                route.distanceMeters < 200 -> 18.0
                route.distanceMeters < 500 -> 17.0
                route.distanceMeters < 1000 -> 16.0
                route.distanceMeters < 2000 -> 15.0
                route.distanceMeters < 5000 -> 14.0
                else -> 13.0
            }
            mapView.controller.setZoom(zoom)

            mapView.invalidate()

            Toast.makeText(
                this,
                "🛣️ Straßen-Distanz: ${route.distanceMeters.toInt()}m",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            // Zeige detaillierten Fehlergrund
            val errorReason = RoadSnapService.lastRouteError ?: "Unbekannt"
            logAdapter.addEntry(LogEntry(
                timestamp = System.currentTimeMillis(),
                distance = 0f,
                accuracy = 0f,
                speedKmh = 0f,
                category = SpeedCategory.STILL,
                mode = PrecisionMode.LOW_POWER,
                nextCheckSec = 0f,
                event = "⚠️ Route FEHLER: $errorReason"
            ))

            // Luftlinie berechnen und anzeigen
            val results = FloatArray(1)
            android.location.Location.distanceBetween(fromLat, fromLng, toLat, toLng, results)
            val luftlinie = results[0].toInt()

            logAdapter.addEntry(LogEntry(
                timestamp = System.currentTimeMillis(),
                distance = results[0],
                accuracy = 0f,
                speedKmh = 0f,
                category = SpeedCategory.STILL,
                mode = PrecisionMode.LOW_POWER,
                nextCheckSec = 0f,
                event = "📏 Fallback Luftlinie: ${luftlinie}m"
            ))

            Toast.makeText(
                this,
                "⚠️ Route: $errorReason - Luftlinie: ${luftlinie}m",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Erstellt einen Kreis als Polygon für Toleranz-Zonen
     */
    private fun createCircle(center: GeoPoint, radiusMeters: Double, fillColor: Int, strokeColor: Int): Polygon {
        val points = ArrayList<GeoPoint>()
        val earthRadius = 6371000.0 // Meter

        for (i in 0..360 step 10) {
            val angle = Math.toRadians(i.toDouble())
            val latOffset = (radiusMeters / earthRadius) * Math.cos(angle) * (180.0 / Math.PI)
            val lngOffset = (radiusMeters / earthRadius) * Math.sin(angle) * (180.0 / Math.PI) / Math.cos(Math.toRadians(center.latitude))
            points.add(GeoPoint(center.latitude + latOffset, center.longitude + lngOffset))
        }

        return Polygon().apply {
            this.points = points
            fillPaint.color = fillColor
            outlinePaint.color = strokeColor
            outlinePaint.strokeWidth = 3f
        }
    }

    /**
     * Zeigt Haus (GPS) und Snap-Punkt (Straße) auf der Karte
     * Optional mit Trigger-Linie entlang der Straße
     */
    private fun updateMapWithWifiHome(
        houseLat: Double, houseLng: Double,
        snapLat: Double, snapLng: Double,
        lineStart: Pair<Double, Double>? = null,
        lineEnd: Pair<Double, Double>? = null
    ) {
        // WICHTIG: Speichere Koordinaten für Route-Berechnung!
        wifiHouseLat = houseLat
        wifiHouseLng = houseLng
        wifiSnapLat = snapLat
        wifiSnapLng = snapLng

        android.util.Log.d("Route", "HOME SAVED: house=$houseLat,$houseLng snap=$snapLat,$snapLng")

        // Entferne alte WLAN-Marker
        houseMarker?.let { mapView.overlays.remove(it) }
        snapMarker?.let { mapView.overlays.remove(it) }
        houseToSnapLine?.let { mapView.overlays.remove(it) }
        snapToleranceCircle?.let { mapView.overlays.remove(it) }
        triggerLine?.let { mapView.overlays.remove(it) }

        val housePos = GeoPoint(houseLat, houseLng)
        val snapPos = GeoPoint(snapLat, snapLng)

        // Speichere Trigger-Linie für späteren Zugriff
        triggerLineStart = lineStart
        triggerLineEnd = lineEnd

        // NEUE Trigger-LINIE entlang der Straße (statt Kreis)
        if (lineStart != null && lineEnd != null) {
            triggerLine = Polyline().apply {
                addPoint(GeoPoint(lineStart.first, lineStart.second))
                addPoint(GeoPoint(lineEnd.first, lineEnd.second))
                outlinePaint.color = Color.parseColor("#22c55e")  // Grün
                outlinePaint.strokeWidth = 12f  // Dick für Sichtbarkeit
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            }
            mapView.overlays.add(triggerLine)

            logAdapter.addEntry(LogEntry(
                timestamp = System.currentTimeMillis(),
                distance = 0f,
                accuracy = 0f,
                speedKmh = 0f,
                category = SpeedCategory.STILL,
                mode = PrecisionMode.LOW_POWER,
                nextCheckSec = 0f,
                event = "🛣️ Trigger-Linie: ±${RoadSnapService.TRIGGER_LINE_HALF_LENGTH.toInt()}m entlang Straße"
            ))
        } else {
            // Fallback: Alter Kreis wenn keine Linie verfügbar
            snapToleranceCircle = createCircle(
                snapPos,
                triggerTolerance.toDouble(),
                Color.parseColor("#40f97316"),
                Color.parseColor("#f97316")
            )
            mapView.overlays.add(snapToleranceCircle)
        }

        // Linie vom Haus zur Straße (orange gestrichelt)
        houseToSnapLine = Polyline().apply {
            addPoint(housePos)
            addPoint(snapPos)
            outlinePaint.color = Color.parseColor("#f97316")  // Orange
            outlinePaint.strokeWidth = 4f
            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }
        mapView.overlays.add(houseToSnapLine)

        // Haus-Marker (blau) - GPS Position
        houseMarker = Marker(mapView).apply {
            position = housePos
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "🏠 Haus (GPS)"
            icon = resources.getDrawable(android.R.drawable.ic_menu_myplaces, null).apply {
                setTint(Color.parseColor("#3b82f6"))  // Blau
            }
        }
        mapView.overlays.add(houseMarker)

        // Snap-Marker (grün) - Straßenpunkt = Trigger-Zone Mitte
        snapMarker = Marker(mapView).apply {
            position = snapPos
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "🛣️ Trigger-Zone Mitte"
            icon = resources.getDrawable(android.R.drawable.ic_menu_compass, null).apply {
                setTint(Color.parseColor("#22c55e"))  // Grün
            }
        }
        mapView.overlays.add(snapMarker)

        // Karte auf Snap-Punkt zentrieren
        mapView.controller.setCenter(snapPos)
        mapView.controller.setZoom(18.0)

        mapView.invalidate()
    }

    private fun setupListeners() {
        seekRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvRadius.text = "${progress}m"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Toleranz Slider
        seekTolerance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                triggerTolerance = progress.toFloat()
                tvTolerance.text = "\u00B1${progress}m"
                // Sync mit WLAN Manager
                WifiLocationManager.TRIGGER_TOLERANCE = triggerTolerance
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnSearch).setOnClickListener {
            searchAddress()
        }

        btnStart.setOnClickListener { startTracking() }
        btnStop.setOnClickListener { stopTracking() }

        btnSetWifiHome.setOnClickListener {
            setWifiHomeManually()
        }
    }

    private fun setWifiHomeManually() {
        // Aktuelle Position als Home-Location setzen
        if (wifiManager != null) {
            wifiManager?.setHomeLocationManually()
            Toast.makeText(this, "\uD83C\uDFE0 Aktueller Standort als Home gespeichert!", Toast.LENGTH_SHORT).show()
        } else {
            // Wenn kein Tracking aktiv, hole kurz GPS und speichere
            Toast.makeText(this, "\u26A0\uFE0F Tracking muss aktiv sein", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun searchAddress() {
        val address = etAddress.text.toString()
        if (address.isBlank()) return

        // Disable button während Suche
        val btnSearch = findViewById<Button>(R.id.btnSearch)
        btnSearch.isEnabled = false
        btnSearch.text = "..."

        // Toast zur Bestätigung
        Toast.makeText(this, "🔎 Suche: $address", Toast.LENGTH_SHORT).show()

        // KLARER SEPARATOR im Log
        logAdapter.addEntry(LogEntry(
            timestamp = System.currentTimeMillis(),
            distance = 0f, accuracy = 0f, speedKmh = 0f,
            category = SpeedCategory.STILL, mode = PrecisionMode.LOW_POWER, nextCheckSec = 0f,
            event = "━━━━━━━━━━━━━━━━━━━━"
        ))
        logAdapter.addEntry(LogEntry(
            timestamp = System.currentTimeMillis(),
            distance = 0f, accuracy = 0f, speedKmh = 0f,
            category = SpeedCategory.STILL, mode = PrecisionMode.LOW_POWER, nextCheckSec = 0f,
            event = "🔎 SUCHE: '$address'"
        ))

        // RecyclerView nach oben scrollen
        rvLog.scrollToPosition(0)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 1. Geocoding (Hausmitte)
                val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
                val results = withContext(Dispatchers.IO) {
                    geocoder.getFromLocationName(address, 1)
                }

                if (results.isNullOrEmpty()) {
                    Toast.makeText(this@MainActivity, "Adresse nicht gefunden", Toast.LENGTH_SHORT).show()
                    logAdapter.addEntry(LogEntry(
                        timestamp = System.currentTimeMillis(),
                        distance = 0f, accuracy = 0f, speedKmh = 0f,
                        category = SpeedCategory.STILL, mode = PrecisionMode.LOW_POWER, nextCheckSec = 0f,
                        event = "❌ Geocoding: '$address' nicht gefunden!"
                    ))
                    snapInfoSection.visibility = View.GONE
                    return@launch
                }

                val location = results[0]
                val houseLat = location.latitude
                val houseLng = location.longitude
                val foundAddress = location.getAddressLine(0) ?: "?"
                val city = location.locality ?: location.subAdminArea ?: "?"

                // Log gefundene Adresse
                logAdapter.addEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = 0f, accuracy = 0f, speedKmh = 0f,
                    category = SpeedCategory.STILL, mode = PrecisionMode.LOW_POWER, nextCheckSec = 0f,
                    event = "🔍 Gefunden: $city - ${"%.4f".format(houseLat)},${"%.4f".format(houseLng)}"
                ))

                // Zeige Snap Info Section
                snapInfoSection.visibility = View.VISIBLE

                // Zeige Hausmitte
                tvOriginalCoords.text = "\uD83C\uDFE0 ${houseLat.format(6)}, ${houseLng.format(6)}"

                // 2. Snap to Road (Straßenpunkt)
                tvSnappedCoords.text = "Suche Straße..."
                tvSnappedCoords.setTextColor(0xFF06b6d4.toInt())

                val snapResult = RoadSnapService.snapToRoad(houseLat, houseLng)

                if (snapResult != null && snapResult.distanceToRoad > 0.5f) {
                    // Nutze Straßenpunkt als Trigger (nur wenn > 0.5m Unterschied)
                    etLat.setText(snapResult.snappedLat.toString())
                    etLng.setText(snapResult.snappedLng.toString())

                    // Aktualisiere Ziel-Koordinaten für Karte
                    targetLat = snapResult.snappedLat
                    targetLng = snapResult.snappedLng

                    tvSnappedCoords.text = "\uD83D\uDEE3\uFE0F ${snapResult.snappedLat.format(6)}, ${snapResult.snappedLng.format(6)}"
                    tvSnappedCoords.setTextColor(0xFF22c55e.toInt())
                    tvRoadDistance.text = "\u2194\uFE0F ${snapResult.distanceToRoad.toInt()}m Korrektur"
                    tvRoadName.text = snapResult.roadName ?: "Unbenannte Straße"

                    // Log
                    logAdapter.addEntry(LogEntry(
                        timestamp = System.currentTimeMillis(),
                        distance = snapResult.distanceToRoad,
                        accuracy = 0f,
                        speedKmh = 0f,
                        category = SpeedCategory.STILL,
                        mode = PrecisionMode.LOW_POWER,
                        nextCheckSec = 0f,
                        event = "\uD83D\uDEE3\uFE0F Snap to Road: ${snapResult.distanceToRoad.toInt()}m Korrektur"
                    ))

                    // Setze beide Punkte für Direction Detection (WLAN Hybrid)
                    wifiManager?.setHomeLocations(
                        houseLat = houseLat,
                        houseLng = houseLng,
                        snapLat = snapResult.snappedLat,
                        snapLng = snapResult.snappedLng
                    )

                    // Speichere Ziel-Trigger-Linie
                    targetTriggerLineStart = snapResult.triggerLineStart
                    targetTriggerLineEnd = snapResult.triggerLineEnd

                    // Zeige Ziel auf Karte (mit Trigger-Linie)
                    showTargetOnMap(snapResult.snappedLat, snapResult.snappedLng)

                    Toast.makeText(
                        this@MainActivity,
                        "\uD83D\uDCCD Straßenpunkt: ${snapResult.distanceToRoad.toInt()}m von Hausmitte",
                        Toast.LENGTH_LONG
                    ).show()

                } else {
                    // Kein Snap nötig oder Fehler - nutze Hausmitte
                    etLat.setText(houseLat.toString())
                    etLng.setText(houseLng.toString())

                    // Aktualisiere Ziel-Koordinaten für Karte
                    targetLat = houseLat
                    targetLng = houseLng

                    // Keine Trigger-Linie wenn bereits auf Straße oder Fehler
                    targetTriggerLineStart = null
                    targetTriggerLineEnd = null

                    // Zeige Ziel auf Karte
                    showTargetOnMap(houseLat, houseLng)

                    if (snapResult != null && snapResult.distanceToRoad <= 0.5f) {
                        tvSnappedCoords.text = "\u2705 Bereits auf Straße"
                        tvSnappedCoords.setTextColor(0xFF22c55e.toInt())
                        tvRoadDistance.text = "< 1m"
                        tvRoadName.text = snapResult.roadName ?: "—"
                    } else {
                        tvSnappedCoords.text = "\u26A0\uFE0F Snap fehlgeschlagen"
                        tvSnappedCoords.setTextColor(0xFFeab308.toInt())
                        tvRoadDistance.text = "—"
                        tvRoadName.text = "—"
                    }

                    Toast.makeText(this@MainActivity, "\uD83D\uDCCD ${location.getAddressLine(0)}", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                logAdapter.addEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = 0f, accuracy = 0f, speedKmh = 0f,
                    category = SpeedCategory.STILL, mode = PrecisionMode.LOW_POWER, nextCheckSec = 0f,
                    event = "❌ FEHLER: ${e.javaClass.simpleName}: ${e.message}"
                ))
                Toast.makeText(this@MainActivity, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
                snapInfoSection.visibility = View.GONE
            } finally {
                btnSearch.isEnabled = true
                btnSearch.text = "S"
            }
        }
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)

    private fun startTracking() {
        val lat = etLat.text.toString().toDoubleOrNull()
        val lng = etLng.text.toString().toDoubleOrNull()
        val radius = seekRadius.progress.toFloat()

        if (lat == null || lng == null) {
            Toast.makeText(this, "Ungültige Koordinaten", Toast.LENGTH_SHORT).show()
            return
        }

        // Ziel-Koordinaten speichern für Map
        targetLat = lat
        targetLng = lng
        startDistanceValue = 0f

        // Generiere Reminder ID
        currentReminderId = "reminder_${System.currentTimeMillis()}"

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        logAdapter.clear()

        // Hole aktuelle Position um Entfernung zu berechnen
        getCurrentLocation { currentLocation ->
            val distanceToTarget = if (currentLocation != null) {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(
                    currentLocation.latitude, currentLocation.longitude,
                    lat, lng, results
                )
                results[0]
            } else {
                null
            }

            android.util.Log.d("MainActivity", "Distance to target: ${distanceToTarget}m")

            // Entscheidung: Geofence oder direkt Tracking?
            if (distanceToTarget != null && distanceToTarget > GeofenceWakeupManager.MIN_DISTANCE_FOR_GEOFENCE) {
                // WEIT WEG -> Geofence registrieren und schlafen
                isInSleepMode = true
                showSleepMode(distanceToTarget)

                geofenceManager?.registerWakeUpGeofence(
                    reminderId = currentReminderId,
                    targetLat = lat,
                    targetLng = lng,
                    currentDistanceMeters = distanceToTarget,
                    onWakeUp = {
                        runOnUiThread {
                            isInSleepMode = false
                            showWakeUpMode()
                            startPredictiveTracking(lat, lng, radius)
                        }
                    }
                )

                logAdapter.addEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceToTarget,
                    accuracy = 0f,
                    speedKmh = 0f,
                    category = SpeedCategory.STILL,
                    mode = PrecisionMode.LOW_POWER,
                    nextCheckSec = 0f,
                    event = "😴 SCHLAFMODUS - Ziel ${(distanceToTarget/1000).toInt()}km entfernt"
                ))

                logAdapter.addEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = 500f,
                    accuracy = 0f,
                    speedKmh = 0f,
                    category = SpeedCategory.STILL,
                    mode = PrecisionMode.LOW_POWER,
                    nextCheckSec = 0f,
                    event = "📍 Android Geofence registriert (500m Radius)"
                ))

                Toast.makeText(this, "😴 Schlafmodus - Warte auf 500m Nähe", Toast.LENGTH_LONG).show()

            } else {
                // NAH GENUG -> Direkt Predictive Tracking starten
                isInSleepMode = false
                showWakeUpMode()
                startPredictiveTracking(lat, lng, radius)

                logAdapter.addEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceToTarget ?: 0f,
                    accuracy = 0f,
                    speedKmh = 0f,
                    category = SpeedCategory.STILL,
                    mode = PrecisionMode.HIGH_ACCURACY,
                    nextCheckSec = 0f,
                    event = "🎯 Ziel nah genug - starte Predictive Tracking"
                ))

                Toast.makeText(this, "🚀 Predictive Tracking gestartet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Hilfsmethode: Aktuelle Position holen
     */
    @SuppressLint("MissingPermission")
    private fun getCurrentLocation(callback: (android.location.Location?) -> Unit) {
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)

        fusedClient.lastLocation
            .addOnSuccessListener { location ->
                callback(location)
            }
            .addOnFailureListener {
                callback(null)
            }
    }

    /**
     * Startet das Predictive Tracking (bisherige Logik)
     */
    private fun startPredictiveTracking(lat: Double, lng: Double, radius: Float) {
        // Akku-Level am Start messen
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        // Start foreground service
        val serviceIntent = Intent(this, ForegroundLocationService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        // Create location manager (BESTEHENDES SYSTEM)
        locationManager = PredictiveLocationManager(
            context = this,
            targetLat = lat,
            targetLng = lng,
            triggerRadius = radius,
            onStateUpdate = { state -> runOnUiThread { updateUI(state) } },
            onLogEntry = { entry -> runOnUiThread { logAdapter.addEntry(entry) } },
            onTrigger = { stats -> runOnUiThread { onTrigger(stats) } }
        )

        // Akku-Level übergeben
        locationManager?.setBatteryStart(batteryLevel)
        locationManager?.startTracking()

        // WLAN Hybrid auch starten wenn aktiviert
        if (switchWifiHybrid.isChecked) {
            wifiManager = WifiLocationManager(
                context = this,
                onStateUpdate = { state -> runOnUiThread { updateWifiUI(state) } },
                onLogEntry = { entry -> runOnUiThread { logAdapter.addEntry(entry) } },
                onTrigger = { runOnUiThread { onWifiTrigger() } }
            )
            wifiManager?.startMonitoring()
        }
    }

    /**
     * UI Methode: Zeigt Schlafmodus an
     */
    private fun showSleepMode(distanceMeters: Float) {
        sleepStatusSection.visibility = View.VISIBLE
        tvSleepIcon.text = "😴"
        tvSleepStatus.text = "SCHLAFMODUS"
        tvSleepStatus.setTextColor(0xFFa855f7.toInt())  // Lila
        tvSleepDetail.text = "Ziel ${(distanceMeters/1000).toInt()}km entfernt - Warte auf 500m Nähe"
        tvSleepBattery.text = "🔋 0%"
        tvSleepBattery.setTextColor(0xFF22c55e.toInt())  // Grün
    }

    /**
     * UI Methode: Zeigt aktives Tracking an
     */
    private fun showWakeUpMode() {
        sleepStatusSection.visibility = View.VISIBLE
        tvSleepIcon.text = "☀️"
        tvSleepStatus.text = "AKTIV"
        tvSleepStatus.setTextColor(0xFF22c55e.toInt())  // Grün
        tvSleepDetail.text = "Predictive Tracking läuft"
        tvSleepBattery.text = "🔋 Tracking"
        tvSleepBattery.setTextColor(0xFFf97316.toInt())  // Orange
    }

    private fun stopTracking() {
        // Geofence entfernen (falls im Schlafmodus)
        if (currentReminderId.isNotEmpty()) {
            geofenceManager?.unregisterGeofence(currentReminderId)
        }
        isInSleepMode = false

        // Predictive Tracking stoppen (BESTEHENDES)
        locationManager?.stopTracking()
        locationManager = null

        // WLAN stoppen (BESTEHENDES)
        wifiManager?.stopMonitoring()
        wifiManager = null

        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        stopService(Intent(this, ForegroundLocationService::class.java))

        // UI Reset
        sleepStatusSection.visibility = View.GONE
        btnStart.isEnabled = true
        btnStop.isEnabled = false

        Toast.makeText(this, "⏹️ Tracking gestoppt", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI(state: PredictiveState) {
        tvDistance.text = Utils.formatDistance(state.distanceToTarget)
        tvAccuracy.text = "\u00B1${state.accuracy.toInt()}m"
        tvSpeed.text = "${state.speedKmh.toInt()} km/h"
        tvMode.text = state.precisionMode.label
        tvNextCheck.text = Utils.formatDuration(state.nextCheckSeconds)
        tvCheckCount.text = state.checkCount.toString()

        if (state.etaSeconds < Float.MAX_VALUE) {
            tvETA.text = Utils.formatDuration(state.etaSeconds)
        } else {
            tvETA.text = "\u221E"
        }

        // Start-Distanz (nur beim ersten Update setzen)
        if (startDistanceValue == 0f && state.distanceToTarget > 0) {
            startDistanceValue = state.distanceToTarget
        }
        tvStartDistance.text = Utils.formatDistance(startDistanceValue)

        // Map aktualisieren
        if (state.latitude != 0.0 && state.longitude != 0.0) {
            updateMap(state.latitude, state.longitude)
        }

        // Mode farbe
        val modeColor = when (state.precisionMode) {
            PrecisionMode.LOW_POWER -> 0xFF22c55e.toInt()
            PrecisionMode.BALANCED -> 0xFFeab308.toInt()
            PrecisionMode.HIGH_ACCURACY -> 0xFFf97316.toInt()
            PrecisionMode.MAXIMUM_PRECISION -> 0xFFef4444.toInt()
        }
        tvMode.setTextColor(modeColor)

        // Accuracy farbe
        val accColor = when {
            state.accuracy <= 5 -> 0xFF22c55e.toInt()
            state.accuracy <= 10 -> 0xFF84cc16.toInt()
            state.accuracy <= 20 -> 0xFFeab308.toInt()
            else -> 0xFFef4444.toInt()
        }
        tvAccuracy.setTextColor(accColor)

        // Update notification
        service?.updateNotification(
            state.distanceToTarget,
            state.nextCheckSeconds,
            state.precisionMode.label
        )
    }

    private fun onTrigger(stats: TrackingStats) {
        // Geofence aufräumen
        if (currentReminderId.isNotEmpty()) {
            geofenceManager?.unregisterGeofence(currentReminderId)
        }

        // Vibration
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 400), -1))

        // Akku-Level am Ende messen
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryEnd = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryUsed = stats.batteryStart - batteryEnd

        // Dauer formatieren
        val durationMin = stats.totalDurationMs / 60000
        val durationSec = (stats.totalDurationMs % 60000) / 1000
        val durationStr = if (durationMin > 0) "${durationMin}m ${durationSec}s" else "${durationSec}s"

        // Bewertung der Genauigkeit
        val (rating, ratingColor) = when {
            stats.triggerDistance <= 5 -> "PERFEKT!" to "#22c55e"
            stats.triggerDistance <= 10 -> "Sehr gut" to "#84cc16"
            stats.triggerDistance <= 15 -> "Gut" to "#eab308"
            else -> "OK" to "#f97316"
        }

        // ScrollView für langes Pop-up
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1c1c26"))
        }

        // Custom Dialog Layout
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }
        scrollView.addView(layout)

        // Emoji + Titel
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val emojiView = TextView(this).apply {
            text = "\uD83C\uDFAF"
            textSize = 40f
        }
        val titleView = TextView(this).apply {
            text = " TRIGGER!"
            textSize = 28f
            setTextColor(Color.parseColor("#22c55e"))
            setTypeface(null, Typeface.BOLD)
        }
        headerLayout.addView(emojiView)
        headerLayout.addView(titleView)
        layout.addView(headerLayout)

        // Distanz zum Ziel-Zentrum (Hauptinfo)
        val distanceLabel = TextView(this).apply {
            text = "Distanz zum Ziel-Zentrum:"
            textSize = 13f
            setTextColor(Color.parseColor("#9898a8"))
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 0)
        }
        layout.addView(distanceLabel)

        val distanceValue = TextView(this).apply {
            text = "${stats.triggerDistance.toInt()} Meter"
            textSize = 42f
            setTextColor(Color.parseColor("#3b82f6"))
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        layout.addView(distanceValue)

        val ratingView = TextView(this).apply {
            text = rating
            textSize = 18f
            setTextColor(Color.parseColor(ratingColor))
            gravity = Gravity.CENTER
            setPadding(0, 5, 0, 25)
        }
        layout.addView(ratingView)

        // === VERGLEICHS-STATISTIKEN ===
        val statsHeader = TextView(this).apply {
            text = "\uD83D\uDCCA GPS-ANFRAGEN VERGLEICH"
            textSize = 12f
            setTextColor(Color.parseColor("#5c5c6c"))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 10)
        }
        layout.addView(statsHeader)

        val statsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#22222e"))
            setPadding(40, 30, 40, 30)
        }

        // Predictive (unsere Methode) - GRÜN hervorgehoben
        val predictiveRow = createStatsRow(
            "\u2705 Predictive (unsere Methode)",
            "${stats.predictiveChecks} Checks",
            "#22c55e",
            true
        )
        statsBox.addView(predictiveRow)

        // Konstant alle 2 Sek
        val highRow = createStatsRow(
            "\u274C Konstant (alle 2s)",
            "${stats.constantHighChecks} Checks",
            "#ef4444"
        )
        statsBox.addView(highRow)

        // Konstant alle 10 Sek
        val mediumRow = createStatsRow(
            "\u26A0\uFE0F Standard Geofence (10s)",
            "${stats.constantMediumChecks} Checks",
            "#eab308"
        )
        statsBox.addView(mediumRow)

        layout.addView(statsBox)

        // === ERSPARNIS ===
        if (stats.savingsVsConstantHigh > 0) {
            val savingsBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#14532d"))
                setPadding(40, 25, 40, 25)
            }

            val savingsTitle = TextView(this).apply {
                text = "\uD83D\uDD0B ERSPARNIS"
                textSize = 12f
                setTextColor(Color.parseColor("#4ade80"))
                gravity = Gravity.CENTER
            }
            savingsBox.addView(savingsTitle)

            val savingsValue = TextView(this).apply {
                text = "${stats.savingsVsConstantHigh.toInt()}% weniger GPS-Anfragen"
                textSize = 18f
                setTextColor(Color.parseColor("#22c55e"))
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 5, 0, 0)
            }
            savingsBox.addView(savingsValue)

            val savingsDetail = TextView(this).apply {
                text = "vs. konstantes High-Accuracy GPS"
                textSize = 11f
                setTextColor(Color.parseColor("#4ade80"))
                gravity = Gravity.CENTER
            }
            savingsBox.addView(savingsDetail)

            layout.addView(savingsBox)
        }

        // === WEITERE DETAILS ===
        val detailsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#22222e"))
            setPadding(40, 25, 40, 25)
        }

        val details = listOf(
            "Tracking-Dauer" to durationStr,
            "Start-Distanz" to "${stats.startDistance.toInt()}m",
            "GPS-Accuracy" to "\u00B1${stats.triggerAccuracy.toInt()}m",
            "Akku verbraucht" to "${if (batteryUsed > 0) batteryUsed else "<1"}%"
        )

        details.forEach { (label, value) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            val labelView = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.parseColor("#9898a8"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valueView = TextView(this).apply {
                text = value
                textSize = 13f
                setTextColor(Color.parseColor("#ffffff"))
                setTypeface(null, Typeface.BOLD)
            }
            row.addView(labelView)
            row.addView(valueView)
            detailsBox.addView(row)
        }

        layout.addView(detailsBox)

        // Dialog anzeigen
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(scrollView)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun createStatsRow(label: String, value: String, color: String, highlight: Boolean = false): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 12)
            if (highlight) {
                setBackgroundColor(Color.parseColor("#1a3a1a"))
            }

            val labelView = TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor(if (highlight) "#4ade80" else "#9898a8"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valueView = TextView(this@MainActivity).apply {
                text = value
                textSize = 14f
                setTextColor(Color.parseColor(color))
                setTypeface(null, Typeface.BOLD)
            }
            addView(labelView)
            addView(valueView)
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val needed = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        }
    }

    private fun updateWifiUI(state: WifiState) {
        // Debug: Was kommt an?
        android.util.Log.d("WifiUI", "updateWifiUI called:")
        android.util.Log.d("WifiUI", "  status=${state.status}")
        android.util.Log.d("WifiUI", "  homeLocation=${state.homeLocation?.latitude}, ${state.homeLocation?.longitude}")
        android.util.Log.d("WifiUI", "  snapLocation=${state.snapLocation?.latitude}, ${state.snapLocation?.longitude}")
        android.util.Log.d("WifiUI", "  houseToSnapDistance=${state.houseToSnapDistance}")

        tvWifiStatus.text = "${state.status.emoji} ${state.status.label}"
        tvWifiSSID.text = state.ssid ?: "—"

        // Status Farbe
        val statusColor = when (state.status) {
            WifiStatus.CONNECTED -> 0xFF22c55e.toInt()       // Grün
            WifiStatus.WIFI_OUTAGE -> 0xFFeab308.toInt()     // Gelb
            WifiStatus.DEBOUNCING,
            WifiStatus.VERIFYING -> 0xFF06b6d4.toInt()       // Cyan
            WifiStatus.TRACKING_MOVEMENT -> 0xFFf97316.toInt() // Orange
            WifiStatus.TRIGGERED -> 0xFFef4444.toInt()       // Rot
            else -> 0xFF9898a8.toInt()                        // Grau
        }
        tvWifiStatus.setTextColor(statusColor)

        // Home Location anzeigen - bevorzuge Snap-Point wenn vorhanden
        if (state.snapLocation != null) {
            // Snap-Point vorhanden = Direction Detection aktiv
            tvHomeDistance.text = "\uD83D\uDEE3\uFE0F ${state.snapLocation.latitude.format(4)}, ${state.snapLocation.longitude.format(4)}"
            tvHomeDistance.setTextColor(0xFF22c55e.toInt())  // Grün

            // Debug im Event Log - nur bei CONNECTED Status
            if (state.status == WifiStatus.CONNECTED) {
                logAdapter.addEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = state.houseToSnapDistance,
                    accuracy = 0f,
                    speedKmh = 0f,
                    category = SpeedCategory.STILL,
                    mode = PrecisionMode.LOW_POWER,
                    nextCheckSec = 0f,
                    event = "✅ UI FINAL: Snap=${state.snapLocation.latitude.format(4)}, Dist=${state.houseToSnapDistance.toInt()}m"
                ))

                // Karte mit Haus, Snap-Punkt und Trigger-Linie aktualisieren
                if (state.homeLocation != null) {
                    updateMapWithWifiHome(
                        houseLat = state.homeLocation.latitude,
                        houseLng = state.homeLocation.longitude,
                        snapLat = state.snapLocation.latitude,
                        snapLng = state.snapLocation.longitude,
                        lineStart = state.triggerLineStart,
                        lineEnd = state.triggerLineEnd
                    )
                }
            }
        } else if (state.homeLocation != null) {
            // Nur GPS-Position (kein Snap)
            tvHomeDistance.text = "\uD83D\uDCCD ${state.homeLocation.latitude.format(4)}, ${state.homeLocation.longitude.format(4)}"
            tvHomeDistance.setTextColor(0xFF3b82f6.toInt())  // Blau

            // Debug im Event Log - nur bei CONNECTED Status (nicht während VERIFYING)
            if (state.status == WifiStatus.CONNECTED) {
                logAdapter.addEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = 0f,
                    accuracy = 0f,
                    speedKmh = 0f,
                    category = SpeedCategory.STILL,
                    mode = PrecisionMode.LOW_POWER,
                    nextCheckSec = 0f,
                    event = "⚠️ UI FINAL: Nur GPS, kein Snap!"
                ))
            }
        } else {
            tvHomeDistance.text = "—"
            tvHomeDistance.setTextColor(0xFF9898a8.toInt())  // Grau
        }

        // Direction Detection anzeigen
        if (state.houseToSnapDistance > 0) {
            directionSection.visibility = View.VISIBLE
            tvHouseToSnapDist.text = "${state.houseToSnapDistance.toInt()}m"

            state.isOnStreetSide?.let { onStreet ->
                if (onStreet) {
                    tvDirectionStatus.text = "\uD83D\uDEE3\uFE0F Straßenseite"
                    tvDirectionStatus.setTextColor(0xFFf97316.toInt())  // Orange
                } else {
                    tvDirectionStatus.text = "\uD83C\uDFE0 Hausseite/Garten"
                    tvDirectionStatus.setTextColor(0xFF22c55e.toInt())  // Grün
                }
            } ?: run {
                tvDirectionStatus.text = "\u2705 Snap OK"
                tvDirectionStatus.setTextColor(0xFF22c55e.toInt())  // Grün
            }
        } else {
            directionSection.visibility = View.GONE
        }
    }

    private fun onWifiTrigger() {
        // Vibration
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 600), -1))

        Toast.makeText(this, "\uD83D\uDCF6\uD83C\uDFAF WLAN TRIGGER! Du hast Zuhause verlassen!", Toast.LENGTH_LONG).show()

        // Custom Dialog
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
            setBackgroundColor(Color.parseColor("#1c1c26"))
        }

        val emojiView = TextView(this).apply {
            text = "\uD83C\uDFE0\u27A1\uFE0F\uD83D\uDEB6"
            textSize = 40f
            gravity = Gravity.CENTER
        }
        layout.addView(emojiView)

        val titleView = TextView(this).apply {
            text = "WLAN TRIGGER!"
            textSize = 24f
            setTextColor(Color.parseColor("#22c55e"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 10)
        }
        layout.addView(titleView)

        val descView = TextView(this).apply {
            text = "Du hast dein Zuhause verlassen!\n\nWLAN getrennt + GPS bestätigt Entfernung."
            textSize = 14f
            setTextColor(Color.parseColor("#9898a8"))
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 20)
        }
        layout.addView(descView)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(layout)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        geofenceManager?.cleanup()
        stopTracking()
    }
}

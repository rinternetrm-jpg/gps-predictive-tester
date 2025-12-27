package com.exitreminder.gpstester

import android.Manifest
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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var etAddress: EditText
    private lateinit var seekRadius: SeekBar
    private lateinit var tvRadius: TextView
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
    private lateinit var snapInfoSection: LinearLayout
    private lateinit var tvOriginalCoords: TextView
    private lateinit var tvSnappedCoords: TextView
    private lateinit var tvRoadDistance: TextView
    private lateinit var tvRoadName: TextView

    private val logAdapter = LogAdapter()

    // Map Marker
    private var currentPosMarker: Marker? = null
    private var targetMarker: Marker? = null
    private var routeLine: Polyline? = null
    private var targetLat: Double = 0.0
    private var targetLng: Double = 0.0
    private var startDistanceValue: Float = 0f
    private var locationManager: PredictiveLocationManager? = null
    private var wifiManager: WifiLocationManager? = null
    private var service: ForegroundLocationService? = null
    private var isBound = false

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
    }

    private fun initViews() {
        etLat = findViewById(R.id.etLat)
        etLng = findViewById(R.id.etLng)
        etAddress = findViewById(R.id.etAddress)
        seekRadius = findViewById(R.id.seekRadius)
        tvRadius = findViewById(R.id.tvRadius)
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
        snapInfoSection = findViewById(R.id.snapInfoSection)
        tvOriginalCoords = findViewById(R.id.tvOriginalCoords)
        tvSnappedCoords = findViewById(R.id.tvSnappedCoords)
        tvRoadDistance = findViewById(R.id.tvRoadDistance)
        tvRoadName = findViewById(R.id.tvRoadName)

        rvLog.layoutManager = LinearLayoutManager(this)
        rvLog.adapter = logAdapter

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

        val currentPos = GeoPoint(currentLat, currentLng)
        val targetPos = GeoPoint(targetLat, targetLng)

        // Route-Linie (direkte Verbindung)
        routeLine = Polyline().apply {
            addPoint(currentPos)
            addPoint(targetPos)
            outlinePaint.color = Color.parseColor("#3b82f6")
            outlinePaint.strokeWidth = 6f
        }
        mapView.overlays.add(routeLine)

        // Aktueller Standort (blau)
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

    private fun setupListeners() {
        seekRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvRadius.text = "${progress}m"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnSearch).setOnClickListener {
            searchAddress()
        }

        btnStart.setOnClickListener { startTracking() }
        btnStop.setOnClickListener { stopTracking() }
    }

    @Suppress("DEPRECATION")
    private fun searchAddress() {
        val address = etAddress.text.toString()
        if (address.isBlank()) return

        // Disable button während Suche
        val btnSearch = findViewById<Button>(R.id.btnSearch)
        btnSearch.isEnabled = false
        btnSearch.text = "..."

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 1. Geocoding (Hausmitte)
                val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
                val results = withContext(Dispatchers.IO) {
                    geocoder.getFromLocationName(address, 1)
                }

                if (results.isNullOrEmpty()) {
                    Toast.makeText(this@MainActivity, "Adresse nicht gefunden", Toast.LENGTH_SHORT).show()
                    snapInfoSection.visibility = View.GONE
                    return@launch
                }

                val location = results[0]
                val houseLat = location.latitude
                val houseLng = location.longitude

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

                    Toast.makeText(
                        this@MainActivity,
                        "\uD83D\uDCCD Straßenpunkt: ${snapResult.distanceToRoad.toInt()}m von Hausmitte",
                        Toast.LENGTH_LONG
                    ).show()

                } else {
                    // Kein Snap nötig oder Fehler - nutze Hausmitte
                    etLat.setText(houseLat.toString())
                    etLng.setText(houseLng.toString())

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

        // Akku-Level am Start messen
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        // Start foreground service
        val serviceIntent = Intent(this, ForegroundLocationService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        // Create location manager
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

        // WLAN Hybrid Modus starten wenn aktiviert
        if (switchWifiHybrid.isChecked) {
            wifiManager = WifiLocationManager(
                context = this,
                onStateUpdate = { state -> runOnUiThread { updateWifiUI(state) } },
                onLogEntry = { entry -> runOnUiThread { logAdapter.addEntry(entry) } },
                onTrigger = { runOnUiThread { onWifiTrigger() } }
            )
            wifiManager?.startMonitoring()
        }

        btnStart.isEnabled = false
        btnStop.isEnabled = true

        logAdapter.clear()
        Toast.makeText(this, "\uD83D\uDE80 Tracking gestartet", Toast.LENGTH_SHORT).show()
    }

    private fun stopTracking() {
        locationManager?.stopTracking()
        locationManager = null

        wifiManager?.stopMonitoring()
        wifiManager = null

        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        stopService(Intent(this, ForegroundLocationService::class.java))

        btnStart.isEnabled = true
        btnStop.isEnabled = false

        Toast.makeText(this, "\u23F9\uFE0F Tracking gestoppt", Toast.LENGTH_SHORT).show()
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

        // Home Location anzeigen
        state.homeLocation?.let { home ->
            tvHomeDistance.text = "${home.latitude.format(4)}, ${home.longitude.format(4)}"
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
        stopTracking()
    }
}

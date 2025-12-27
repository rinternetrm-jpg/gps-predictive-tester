package com.exitreminder.gpstester

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WifiLocationManager(
    private val context: Context,
    private val onStateUpdate: (WifiState) -> Unit,
    private val onLogEntry: (LogEntry) -> Unit,
    private val onTrigger: () -> Unit
) {
    companion object {
        private const val TAG = "WifiLocation"

        // Debounce: Wie lange warten nach WLAN-Verlust
        const val DEBOUNCE_MS = 10_000L  // 10 Sekunden

        // Distanz-Schwellen
        const val DISTANCE_STILL_HOME = 30f      // <30m = noch zuhause
        const val DISTANCE_MAYBE_LEAVING = 100f  // 30-100m = vielleicht am gehen
        const val DISTANCE_DEFINITELY_LEFT = 100f // >100m = definitiv weg

        // Movement Tracking
        const val MOVEMENT_CHECK_INTERVAL = 15_000L  // Alle 15 Sek
        const val MOVEMENT_CHECKS_NEEDED = 3         // 3x checken bevor Trigger

        // Trigger Toleranz - kann von außen gesetzt werden
        @JvmStatic
        var TRIGGER_TOLERANCE: Float = 15f  // Default 15m
    }

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())

    // State
    private var isMonitoring = false
    private var homeLocation: Location? = null
    private var homeWifiSSID: String? = null
    private var isWifiConnected = false
    private var disconnectTime: Long? = null

    // Direction Detection - NEU
    private var houseCenter: Location? = null      // Gebäudemitte (von Geocoding)
    private var snapPoint: Location? = null        // Straßenpunkt (von Snap to Road)
    private var houseToSnapDistance: Float = 0f    // Distanz Haus → Straße
    private var currentIsOnStreetSide: Boolean? = null

    // Trigger-Linie entlang der Straße (statt Kreis)
    private var triggerLineStart: Pair<Double, Double>? = null
    private var triggerLineEnd: Pair<Double, Double>? = null

    // Movement tracking
    private var movementCheckCount = 0
    private var lastMovementLocation: Location? = null
    private var isTrackingMovement = false

    // Network callback
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        Log.d(TAG, "Starting WLAN monitoring")

        // Check current WLAN state
        checkCurrentWifiState()

        // Register for WLAN changes
        registerNetworkCallback()

        onLogEntry(LogEntry(
            timestamp = System.currentTimeMillis(),
            distance = 0f,
            accuracy = 0f,
            speedKmh = 0f,
            category = SpeedCategory.STILL,
            mode = PrecisionMode.LOW_POWER,
            nextCheckSec = 0f,
            event = "\uD83D\uDCF6 WLAN Monitoring gestartet"
        ))
    }

    fun stopMonitoring() {
        isMonitoring = false
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        isTrackingMovement = false
        Log.d(TAG, "WLAN monitoring stopped")
    }

    /**
     * Manuell aktuelle Position als Home setzen (über Button)
     * Ruft automatisch Snap-to-Road auf für Direction Detection
     */
    fun setHomeLocationManually() {
        Log.d(TAG, "Manually setting home location with Snap-to-Road...")

        onLogEntry(LogEntry(
            timestamp = System.currentTimeMillis(),
            distance = 0f,
            accuracy = 0f,
            speedKmh = 0f,
            category = SpeedCategory.STILL,
            mode = PrecisionMode.LOW_POWER,
            nextCheckSec = 0f,
            event = "\uD83D\uDCCD Manuell: Hole GPS + Snap-to-Road..."
        ))

        // saveHomeLocation() macht jetzt GPS + Snap-to-Road
        saveHomeLocation()
    }

    /**
     * Setze beide Punkte für Direction Detection
     * @param houseLat Gebäudemitte Latitude (von Geocoding)
     * @param houseLng Gebäudemitte Longitude
     * @param snapLat Straßenpunkt Latitude (von Snap to Road)
     * @param snapLng Straßenpunkt Longitude
     */
    fun setHomeLocations(houseLat: Double, houseLng: Double, snapLat: Double, snapLng: Double) {
        houseCenter = Location("geocoding").apply {
            latitude = houseLat
            longitude = houseLng
        }

        snapPoint = Location("snap").apply {
            latitude = snapLat
            longitude = snapLng
        }

        // Berechne Distanz Haus → Snap
        houseToSnapDistance = houseCenter!!.distanceTo(snapPoint!!)

        // Setze auch homeLocation für Fallback
        homeLocation = snapPoint

        Log.d(TAG, "Home locations set:")
        Log.d(TAG, "  House: $houseLat, $houseLng")
        Log.d(TAG, "  Snap:  $snapLat, $snapLng")
        Log.d(TAG, "  Distance: ${houseToSnapDistance}m")

        onLogEntry(LogEntry(
            timestamp = System.currentTimeMillis(),
            distance = houseToSnapDistance,
            accuracy = 0f,
            speedKmh = 0f,
            category = SpeedCategory.STILL,
            mode = PrecisionMode.LOW_POWER,
            nextCheckSec = 0f,
            event = "\uD83C\uDFE0\u27A1\uFE0F\uD83D\uDEE3\uFE0F Direction Detection: ${houseToSnapDistance.toInt()}m Haus→Straße"
        ))

        updateState(WifiStatus.CONNECTED)
    }

    @SuppressLint("MissingPermission")
    private fun checkCurrentWifiState() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        isWifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        if (isWifiConnected) {
            val wifiInfo = wifiManager.connectionInfo
            homeWifiSSID = wifiInfo.ssid?.replace("\"", "") ?: "Unknown"

            Log.d(TAG, "Currently connected to: $homeWifiSSID")

            // Speichere Home Location nur wenn noch keine gesetzt
            if (homeLocation == null) {
                // saveHomeLocation() ruft selbst updateState() auf wenn fertig!
                saveHomeLocation()
            } else {
                // Nur updaten wenn schon eine Home-Position existiert
                updateState(WifiStatus.CONNECTED)
            }
        } else {
            updateState(WifiStatus.DISCONNECTED)
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "WLAN connected")
                handler.post { onWifiConnected() }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "WLAN lost")
                handler.post { onWifiDisconnected() }
            }
        }

        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering callback", e)
            }
        }
        networkCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun onWifiConnected() {
        isWifiConnected = true
        disconnectTime = null
        isTrackingMovement = false
        currentIsOnStreetSide = null
        handler.removeCallbacksAndMessages(null)

        val wifiInfo = wifiManager.connectionInfo
        val ssid = wifiInfo.ssid?.replace("\"", "") ?: "Unknown"

        Log.d(TAG, "Connected to: $ssid")

        onLogEntry(LogEntry(
            timestamp = System.currentTimeMillis(),
            distance = 0f,
            accuracy = 0f,
            speedKmh = 0f,
            category = SpeedCategory.STILL,
            mode = PrecisionMode.LOW_POWER,
            nextCheckSec = 0f,
            event = "\uD83D\uDCF6 WLAN verbunden: $ssid"
        ))

        // Wenn neues WLAN oder erstes Mal (und keine Direction Detection aktiv)
        if ((homeWifiSSID == null || homeWifiSSID != ssid) && houseCenter == null) {
            homeWifiSSID = ssid
            // saveHomeLocation() ruft selbst updateState() auf wenn Snap-to-Road fertig!
            saveHomeLocation()
        } else {
            homeWifiSSID = ssid
            // Nur updaten wenn NICHT gerade saveHomeLocation() läuft
            updateState(WifiStatus.CONNECTED)
        }
    }

    private fun onWifiDisconnected() {
        if (!isWifiConnected) return // Schon disconnected

        isWifiConnected = false
        disconnectTime = System.currentTimeMillis()

        Log.d(TAG, "WLAN disconnected - starting debounce timer (${DEBOUNCE_MS}ms)")

        onLogEntry(LogEntry(
            timestamp = System.currentTimeMillis(),
            distance = 0f,
            accuracy = 0f,
            speedKmh = 0f,
            category = SpeedCategory.STILL,
            mode = PrecisionMode.BALANCED,
            nextCheckSec = DEBOUNCE_MS / 1000f,
            event = "\uD83D\uDCF6 WLAN getrennt - warte ${DEBOUNCE_MS/1000}s..."
        ))

        updateState(WifiStatus.DEBOUNCING)

        // Debounce Timer
        handler.postDelayed({
            if (!isWifiConnected) {
                // Immer noch getrennt nach Debounce -> GPS Check
                performVerificationCheck()
            } else {
                Log.d(TAG, "WLAN reconnected during debounce - cancelled")
            }
        }, DEBOUNCE_MS)
    }

    @SuppressLint("MissingPermission")
    private fun saveHomeLocation() {
        Log.d(TAG, "Saving home location with Snap-to-Road...")

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMaxUpdates(1)
            .setWaitForAccurateLocation(true)
            .build()

        fusedClient.requestLocationUpdates(request, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    // GPS-Position als Hausmitte speichern
                    homeLocation = location

                    Log.d(TAG, "GPS position: ${location.latitude}, ${location.longitude} (±${location.accuracy}m)")

                    onLogEntry(LogEntry(
                        timestamp = System.currentTimeMillis(),
                        distance = 0f,
                        accuracy = location.accuracy,
                        speedKmh = 0f,
                        category = SpeedCategory.STILL,
                        mode = PrecisionMode.LOW_POWER,
                        nextCheckSec = 0f,
                        event = "\uD83D\uDCCD GPS Position (\u00B1${location.accuracy.toInt()}m) - Suche Straße..."
                    ))

                    // Zeige Zwischen-Status während Snap läuft
                    updateState(WifiStatus.VERIFYING)

                    // Log für UI
                    onLogEntry(LogEntry(
                        timestamp = System.currentTimeMillis(),
                        distance = 0f,
                        accuracy = location.accuracy,
                        speedKmh = 0f,
                        category = SpeedCategory.STILL,
                        mode = PrecisionMode.LOW_POWER,
                        nextCheckSec = 0f,
                        event = "🌐 Starte OSRM API-Aufruf..."
                    ))

                    // Snap-to-Road aufrufen für Direction Detection
                    CoroutineScope(Dispatchers.Main).launch {
                        Log.d(TAG, "Calling Snap-to-Road API for ${location.latitude}, ${location.longitude}...")

                        onLogEntry(LogEntry(
                            timestamp = System.currentTimeMillis(),
                            distance = 0f,
                            accuracy = location.accuracy,
                            speedKmh = 0f,
                            category = SpeedCategory.STILL,
                            mode = PrecisionMode.LOW_POWER,
                            nextCheckSec = 0f,
                            event = "⏳ Coroutine gestartet, rufe API..."
                        ))

                        try {
                            val snapResult = RoadSnapService.snapToRoad(location.latitude, location.longitude)

                            onLogEntry(LogEntry(
                                timestamp = System.currentTimeMillis(),
                                distance = 0f,
                                accuracy = location.accuracy,
                                speedKmh = 0f,
                                category = SpeedCategory.STILL,
                                mode = PrecisionMode.LOW_POWER,
                                nextCheckSec = 0f,
                                event = "📡 API Antwort: ${if (snapResult != null) "OK ${snapResult.distanceToRoad}m" else "NULL!"}"
                            ))

                            Log.d(TAG, "Snap-to-Road result: $snapResult")

                            if (snapResult != null) {
                                Log.d(TAG, "Snap distance: ${snapResult.distanceToRoad}m")

                                // Setze beide Punkte für Direction Detection (auch bei kleiner Distanz!)
                                houseCenter = Location("gps").apply {
                                    latitude = location.latitude
                                    longitude = location.longitude
                                }

                                snapPoint = Location("snap").apply {
                                    latitude = snapResult.snappedLat
                                    longitude = snapResult.snappedLng
                                }

                                houseToSnapDistance = snapResult.distanceToRoad

                                // Speichere Trigger-Linie (entlang der Straße)
                                triggerLineStart = snapResult.triggerLineStart
                                triggerLineEnd = snapResult.triggerLineEnd

                                Log.d(TAG, "Snap-to-Road SUCCESS:")
                                Log.d(TAG, "  House: ${location.latitude}, ${location.longitude}")
                                Log.d(TAG, "  Snap:  ${snapResult.snappedLat}, ${snapResult.snappedLng}")
                                Log.d(TAG, "  Distance: ${houseToSnapDistance}m")
                                Log.d(TAG, "  Road: ${snapResult.roadName}")
                                Log.d(TAG, "  TriggerLine: ${triggerLineStart} -> ${triggerLineEnd}")

                                val roadInfo = if (snapResult.roadName != null) " (${snapResult.roadName})" else ""
                                val snapLatStr = "%.5f".format(snapResult.snappedLat)
                                val snapLngStr = "%.5f".format(snapResult.snappedLng)

                                onLogEntry(LogEntry(
                                    timestamp = System.currentTimeMillis(),
                                    distance = houseToSnapDistance,
                                    accuracy = location.accuracy,
                                    speedKmh = 0f,
                                    category = SpeedCategory.STILL,
                                    mode = PrecisionMode.LOW_POWER,
                                    nextCheckSec = 0f,
                                    event = "\uD83D\uDEE3\uFE0F SNAP: $snapLatStr, $snapLngStr$roadInfo"
                                ))

                                onLogEntry(LogEntry(
                                    timestamp = System.currentTimeMillis(),
                                    distance = houseToSnapDistance,
                                    accuracy = location.accuracy,
                                    speedKmh = 0f,
                                    category = SpeedCategory.STILL,
                                    mode = PrecisionMode.LOW_POWER,
                                    nextCheckSec = 0f,
                                    event = "\u2705 Direction Detection: ${houseToSnapDistance.toInt()}m Haus\u2192Straße"
                                ))
                            } else {
                                // Snap fehlgeschlagen - nur GPS verwenden
                                Log.e(TAG, "Snap-to-Road FAILED - API returned null")

                                onLogEntry(LogEntry(
                                    timestamp = System.currentTimeMillis(),
                                    distance = 0f,
                                    accuracy = location.accuracy,
                                    speedKmh = 0f,
                                    category = SpeedCategory.STILL,
                                    mode = PrecisionMode.LOW_POWER,
                                    nextCheckSec = 0f,
                                    event = "\u274C Snap-to-Road FEHLER! Nur GPS."
                                ))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Snap-to-Road EXCEPTION: ${e.message}", e)

                            onLogEntry(LogEntry(
                                timestamp = System.currentTimeMillis(),
                                distance = 0f,
                                accuracy = location.accuracy,
                                speedKmh = 0f,
                                category = SpeedCategory.STILL,
                                mode = PrecisionMode.LOW_POWER,
                                nextCheckSec = 0f,
                                event = "\u274C Snap Exception: ${e.message}"
                            ))
                        }

                        // WICHTIG: Jetzt erst updateState() aufrufen, wenn Snap fertig!
                        Log.d(TAG, "Snap complete - updating state. snapPoint=${snapPoint?.latitude}, ${snapPoint?.longitude}")

                        // Log für UI - zeige finalen Status
                        if (snapPoint != null) {
                            onLogEntry(LogEntry(
                                timestamp = System.currentTimeMillis(),
                                distance = houseToSnapDistance,
                                accuracy = 0f,
                                speedKmh = 0f,
                                category = SpeedCategory.STILL,
                                mode = PrecisionMode.LOW_POWER,
                                nextCheckSec = 0f,
                                event = "📍 HOME GESETZT: Snap=${snapPoint?.latitude?.let { "%.4f".format(it) }}, ${snapPoint?.longitude?.let { "%.4f".format(it) }}"
                            ))
                        }

                        updateState(WifiStatus.CONNECTED)
                    }
                }
                fusedClient.removeLocationUpdates(this)
            }
        }, Looper.getMainLooper())
    }

    @SuppressLint("MissingPermission")
    private fun performVerificationCheck() {
        Log.d(TAG, "Performing GPS verification check...")

        updateState(WifiStatus.VERIFYING)

        onLogEntry(LogEntry(
            timestamp = System.currentTimeMillis(),
            distance = 0f,
            accuracy = 0f,
            speedKmh = 0f,
            category = SpeedCategory.STILL,
            mode = PrecisionMode.HIGH_ACCURACY,
            nextCheckSec = 0f,
            event = "\uD83D\uDD0D GPS Verification Check..."
        ))

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMaxUpdates(1)
            .setWaitForAccurateLocation(true)
            .build()

        fusedClient.requestLocationUpdates(request, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { currentLocation ->
                    evaluateLocation(currentLocation)
                }
                fusedClient.removeLocationUpdates(this)
            }
        }, Looper.getMainLooper())
    }

    private fun evaluateLocation(currentLocation: Location) {
        val house = houseCenter
        val snap = snapPoint

        // Direction Detection aktiv?
        if (house != null && snap != null) {
            evaluateWithDirectionDetection(currentLocation, house, snap)
        } else {
            // Fallback: Einfache Distanz-Prüfung
            evaluateSimpleDistance(currentLocation)
        }
    }

    /**
     * Erweiterte Evaluation mit Direction Detection
     * Nutzt jetzt Trigger-LINIE statt Kreis für präzisere Erkennung
     */
    private fun evaluateWithDirectionDetection(currentLocation: Location, house: Location, snap: Location) {
        val distanceFromHouse = currentLocation.distanceTo(house)
        val distanceFromSnap = currentLocation.distanceTo(snap)

        // Berechne Distanz zur Trigger-LINIE (wenn vorhanden)
        val distanceToTriggerLine = if (triggerLineStart != null && triggerLineEnd != null) {
            RoadSnapService.distanceToLineSegment(
                currentLocation.latitude, currentLocation.longitude,
                triggerLineStart!!.first, triggerLineStart!!.second,
                triggerLineEnd!!.first, triggerLineEnd!!.second
            )
        } else {
            distanceFromSnap  // Fallback auf Punkt-Distanz
        }

        // Ist User auf der Straßenseite? (weiter vom Haus als der Snap Point)
        val isOnStreetSide = distanceFromHouse > (houseToSnapDistance - 5f)  // 5m Toleranz
        currentIsOnStreetSide = isOnStreetSide

        Log.d(TAG, "Direction check:")
        Log.d(TAG, "  Distance from house: ${distanceFromHouse}m")
        Log.d(TAG, "  Distance from snap: ${distanceFromSnap}m")
        Log.d(TAG, "  Distance to trigger LINE: ${distanceToTriggerLine}m")
        Log.d(TAG, "  House to snap: ${houseToSnapDistance}m")
        Log.d(TAG, "  On street side: $isOnStreetSide")
        Log.d(TAG, "  Trigger tolerance: ${TRIGGER_TOLERANCE}m")

        when {
            // SOFORT TRIGGER: Nah an Trigger-LINIE UND auf Straßenseite
            isOnStreetSide && distanceToTriggerLine <= TRIGGER_TOLERANCE -> {
                Log.d(TAG, "Near trigger LINE on street side - TRIGGER!")

                onLogEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceToTriggerLine,
                    accuracy = currentLocation.accuracy,
                    speedKmh = 0f,
                    category = SpeedCategory.WALKING,
                    mode = PrecisionMode.HIGH_ACCURACY,
                    nextCheckSec = 0f,
                    event = "\uD83C\uDFAF An Trigger-Linie! (${distanceToTriggerLine.toInt()}m, Toleranz: ${TRIGGER_TOLERANCE.toInt()}m)"
                ))

                updateState(WifiStatus.TRIGGERED)
                onTrigger()
                return
            }

            // Noch sehr nah am Haus (nicht auf Straßenseite)
            !isOnStreetSide && distanceFromHouse < houseToSnapDistance -> {
                Log.d(TAG, "At home or at boundary - no trigger")

                onLogEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceFromHouse,
                    accuracy = currentLocation.accuracy,
                    speedKmh = 0f,
                    category = SpeedCategory.STILL,
                    mode = PrecisionMode.LOW_POWER,
                    nextCheckSec = 30f,
                    event = "\uD83C\uDFE0 Noch zuhause (${distanceFromHouse.toInt()}m vom Haus)"
                ))

                updateState(WifiStatus.WIFI_OUTAGE)
                scheduleRecheck()
            }

            // Im Garten/Hinterhof (weit vom Snap, aber nicht auf Straßenseite)
            !isOnStreetSide && distanceFromSnap > TRIGGER_TOLERANCE -> {
                Log.d(TAG, "In garden/backyard - no trigger")

                onLogEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceFromHouse,
                    accuracy = currentLocation.accuracy,
                    speedKmh = 0f,
                    category = SpeedCategory.STILL,
                    mode = PrecisionMode.LOW_POWER,
                    nextCheckSec = 60f,
                    event = "\uD83C\uDF33 Im Garten (${distanceFromHouse.toInt()}m) - kein Exit"
                ))

                updateState(WifiStatus.WIFI_OUTAGE)
                scheduleRecheck()
            }

            // Auf Straßenseite aber außerhalb Toleranz - beobachten
            isOnStreetSide && distanceFromSnap > TRIGGER_TOLERANCE && distanceFromSnap < DISTANCE_MAYBE_LEAVING -> {
                Log.d(TAG, "On street side, outside tolerance - tracking movement")

                onLogEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceFromSnap,
                    accuracy = currentLocation.accuracy,
                    speedKmh = 0f,
                    category = SpeedCategory.WALKING,
                    mode = PrecisionMode.HIGH_ACCURACY,
                    nextCheckSec = MOVEMENT_CHECK_INTERVAL / 1000f,
                    event = "\uD83D\uDEB6 Auf Straße (${distanceFromSnap.toInt()}m) - beobachte..."
                ))

                updateState(WifiStatus.TRACKING_MOVEMENT)
                startMovementTracking(currentLocation)
            }

            // Definitiv auf Straßenseite und weit genug weg
            isOnStreetSide && distanceFromSnap >= DISTANCE_MAYBE_LEAVING -> {
                Log.d(TAG, "Definitely on street side and moving away - TRIGGER!")

                onLogEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceFromSnap,
                    accuracy = currentLocation.accuracy,
                    speedKmh = 0f,
                    category = SpeedCategory.WALKING,
                    mode = PrecisionMode.HIGH_ACCURACY,
                    nextCheckSec = 0f,
                    event = "\uD83C\uDFAF EXIT! Auf Straße, ${distanceFromSnap.toInt()}m vom Snap Point"
                ))

                updateState(WifiStatus.TRIGGERED)
                onTrigger()
            }

            else -> {
                // Fallback: Weiter beobachten
                Log.d(TAG, "Uncertain state - continue monitoring")
                scheduleRecheck()
            }
        }

        // State aktualisieren für UI
        updateState(if (isWifiConnected) WifiStatus.CONNECTED else WifiStatus.WIFI_OUTAGE)
    }

    /**
     * Fallback: Einfache Distanz-Prüfung (ohne Direction Detection)
     */
    private fun evaluateSimpleDistance(currentLocation: Location) {
        val home = homeLocation

        if (home == null) {
            Log.w(TAG, "No home location saved - cannot verify")
            onLogEntry(LogEntry(
                timestamp = System.currentTimeMillis(),
                distance = 0f,
                accuracy = currentLocation.accuracy,
                speedKmh = 0f,
                category = SpeedCategory.STILL,
                mode = PrecisionMode.HIGH_ACCURACY,
                nextCheckSec = 0f,
                event = "\u26A0\uFE0F Keine Home-Position - kann nicht verifizieren"
            ))
            return
        }

        val distanceFromHome = currentLocation.distanceTo(home)

        Log.d(TAG, "Simple distance check: ${distanceFromHome}m from home (accuracy: ${currentLocation.accuracy}m)")

        when {
            distanceFromHome < DISTANCE_STILL_HOME -> {
                // Noch zuhause - WLAN nur ausgefallen
                Log.d(TAG, "Still at home (${distanceFromHome}m) - WLAN outage, no trigger")

                onLogEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceFromHome,
                    accuracy = currentLocation.accuracy,
                    speedKmh = 0f,
                    category = SpeedCategory.STILL,
                    mode = PrecisionMode.LOW_POWER,
                    nextCheckSec = 30f,
                    event = "\uD83C\uDFE0 Noch zuhause (${distanceFromHome.toInt()}m) - WLAN Ausfall"
                ))

                updateState(WifiStatus.WIFI_OUTAGE)
                scheduleRecheck()
            }

            distanceFromHome < DISTANCE_MAYBE_LEAVING -> {
                // Vielleicht am Gehen - Movement Tracking starten
                Log.d(TAG, "Maybe leaving (${distanceFromHome}m) - starting movement tracking")

                onLogEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceFromHome,
                    accuracy = currentLocation.accuracy,
                    speedKmh = 0f,
                    category = SpeedCategory.WALKING,
                    mode = PrecisionMode.HIGH_ACCURACY,
                    nextCheckSec = MOVEMENT_CHECK_INTERVAL / 1000f,
                    event = "\uD83D\uDEB6 Bewegung erkannt (${distanceFromHome.toInt()}m) - beobachte..."
                ))

                updateState(WifiStatus.TRACKING_MOVEMENT)
                startMovementTracking(currentLocation)
            }

            else -> {
                // Definitiv weg!
                Log.d(TAG, "Definitely left (${distanceFromHome}m) - TRIGGER!")

                onLogEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceFromHome,
                    accuracy = currentLocation.accuracy,
                    speedKmh = 0f,
                    category = SpeedCategory.WALKING,
                    mode = PrecisionMode.HIGH_ACCURACY,
                    nextCheckSec = 0f,
                    event = "\uD83C\uDFAF TRIGGER! Definitiv unterwegs (${distanceFromHome.toInt()}m)"
                ))

                updateState(WifiStatus.TRIGGERED)
                onTrigger()
            }
        }
    }

    private fun startMovementTracking(initialLocation: Location) {
        isTrackingMovement = true
        movementCheckCount = 0
        lastMovementLocation = initialLocation

        scheduleMovementCheck()
    }

    private fun scheduleMovementCheck() {
        handler.postDelayed({
            if (isTrackingMovement && !isWifiConnected) {
                performMovementCheck()
            }
        }, MOVEMENT_CHECK_INTERVAL)
    }

    @SuppressLint("MissingPermission")
    private fun performMovementCheck() {
        movementCheckCount++

        Log.d(TAG, "Movement check #$movementCheckCount")

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMaxUpdates(1)
            .build()

        fusedClient.requestLocationUpdates(request, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    evaluateMovement(location)
                }
                fusedClient.removeLocationUpdates(this)
            }
        }, Looper.getMainLooper())
    }

    private fun evaluateMovement(currentLocation: Location) {
        val house = houseCenter
        val snap = snapPoint

        // Direction Detection aktiv?
        if (house != null && snap != null) {
            evaluateMovementWithDirection(currentLocation, house, snap)
        } else {
            evaluateMovementSimple(currentLocation)
        }
    }

    /**
     * Movement Evaluation mit Direction Detection
     * Nutzt jetzt Trigger-LINIE statt Kreis
     */
    private fun evaluateMovementWithDirection(currentLocation: Location, house: Location, snap: Location) {
        val distanceFromHouse = currentLocation.distanceTo(house)
        val distanceFromSnap = currentLocation.distanceTo(snap)

        // Berechne Distanz zur Trigger-LINIE (wenn vorhanden)
        val distanceToTriggerLine = if (triggerLineStart != null && triggerLineEnd != null) {
            RoadSnapService.distanceToLineSegment(
                currentLocation.latitude, currentLocation.longitude,
                triggerLineStart!!.first, triggerLineStart!!.second,
                triggerLineEnd!!.first, triggerLineEnd!!.second
            )
        } else {
            distanceFromSnap  // Fallback
        }

        val isOnStreetSide = distanceFromHouse > (houseToSnapDistance - 5f)
        currentIsOnStreetSide = isOnStreetSide

        // Bewegung seit letztem Check
        val lastPos = lastMovementLocation ?: return
        val distanceMoved = currentLocation.distanceTo(lastPos)
        val speedMps = distanceMoved / (MOVEMENT_CHECK_INTERVAL / 1000f)
        val speedKmh = speedMps * 3.6f

        // Richtung: Entfernt sich vom Haus?
        val lastDistanceFromHouse = lastPos.distanceTo(house)
        val movingAway = distanceFromHouse > lastDistanceFromHouse

        Log.d(TAG, "Movement check #$movementCheckCount:")
        Log.d(TAG, "  Distance from house: ${distanceFromHouse}m (was ${lastDistanceFromHouse}m)")
        Log.d(TAG, "  Distance to trigger LINE: ${distanceToTriggerLine}m")
        Log.d(TAG, "  On street side: $isOnStreetSide")
        Log.d(TAG, "  Moving away: $movingAway")
        Log.d(TAG, "  Speed: ${speedKmh} km/h")

        onLogEntry(LogEntry(
            timestamp = System.currentTimeMillis(),
            distance = distanceFromHouse,
            accuracy = currentLocation.accuracy,
            speedKmh = speedKmh,
            category = if (speedKmh > 2) SpeedCategory.WALKING else SpeedCategory.STILL,
            mode = PrecisionMode.HIGH_ACCURACY,
            nextCheckSec = MOVEMENT_CHECK_INTERVAL / 1000f,
            event = "\uD83D\uDCCD Check #$movementCheckCount: ${if (movingAway) "\u2197\uFE0F entfernt sich" else "\u2199\uFE0F nähert sich"}"
        ))

        when {
            // SOFORT TRIGGER: Nah an Trigger-LINIE UND auf Straßenseite
            isOnStreetSide && distanceToTriggerLine <= TRIGGER_TOLERANCE -> {
                Log.d(TAG, "Near trigger LINE during movement - TRIGGER!")

                onLogEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceToTriggerLine,
                    accuracy = currentLocation.accuracy,
                    speedKmh = speedKmh,
                    category = SpeedCategory.WALKING,
                    mode = PrecisionMode.HIGH_ACCURACY,
                    nextCheckSec = 0f,
                    event = "\uD83C\uDFAF An Trigger-Linie! (${distanceToTriggerLine.toInt()}m, Toleranz: ${TRIGGER_TOLERANCE.toInt()}m)"
                ))

                isTrackingMovement = false
                updateState(WifiStatus.TRIGGERED)
                onTrigger()
            }

            // Zurück ins Haus/Garten (nicht mehr auf Straßenseite)
            !isOnStreetSide -> {
                Log.d(TAG, "Returned to house side - cancelling")

                onLogEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceFromHouse,
                    accuracy = currentLocation.accuracy,
                    speedKmh = speedKmh,
                    category = SpeedCategory.STILL,
                    mode = PrecisionMode.LOW_POWER,
                    nextCheckSec = 0f,
                    event = "\uD83C\uDFE0 Zurück (nicht mehr auf Straßenseite)"
                ))

                isTrackingMovement = false
                updateState(WifiStatus.WIFI_OUTAGE)
                scheduleRecheck()
            }

            // Auf Straßenseite + entfernt sich + genug Checks = EXIT!
            isOnStreetSide && movingAway && movementCheckCount >= MOVEMENT_CHECKS_NEEDED -> {
                Log.d(TAG, "Confirmed exit - TRIGGER!")

                onLogEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceFromHouse,
                    accuracy = currentLocation.accuracy,
                    speedKmh = speedKmh,
                    category = SpeedCategory.WALKING,
                    mode = PrecisionMode.HIGH_ACCURACY,
                    nextCheckSec = 0f,
                    event = "\uD83C\uDFAF EXIT bestätigt! ${movementCheckCount}x auf Straßenseite + entfernt sich"
                ))

                isTrackingMovement = false
                updateState(WifiStatus.TRIGGERED)
                onTrigger()
            }

            // Auf Straßenseite + sehr weit weg = sofort EXIT
            isOnStreetSide && distanceFromSnap > DISTANCE_DEFINITELY_LEFT -> {
                Log.d(TAG, "Far from snap point - TRIGGER!")

                onLogEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceFromSnap,
                    accuracy = currentLocation.accuracy,
                    speedKmh = speedKmh,
                    category = SpeedCategory.WALKING,
                    mode = PrecisionMode.HIGH_ACCURACY,
                    nextCheckSec = 0f,
                    event = "\uD83C\uDFAF EXIT! ${distanceFromSnap.toInt()}m von Straße entfernt"
                ))

                isTrackingMovement = false
                updateState(WifiStatus.TRIGGERED)
                onTrigger()
            }

            // Weiter beobachten
            else -> {
                lastMovementLocation = currentLocation
                scheduleMovementCheck()
            }
        }
    }

    /**
     * Fallback Movement Evaluation (ohne Direction Detection)
     */
    private fun evaluateMovementSimple(currentLocation: Location) {
        val home = homeLocation ?: return
        val lastPos = lastMovementLocation ?: return

        val distanceFromHome = currentLocation.distanceTo(home)
        val distanceMoved = currentLocation.distanceTo(lastPos)

        Log.d(TAG, "Movement check: ${distanceFromHome}m from home, moved ${distanceMoved}m since last check")

        // Berechne Geschwindigkeit
        val speedMps = distanceMoved / (MOVEMENT_CHECK_INTERVAL / 1000f)
        val speedKmh = speedMps * 3.6f

        onLogEntry(LogEntry(
            timestamp = System.currentTimeMillis(),
            distance = distanceFromHome,
            accuracy = currentLocation.accuracy,
            speedKmh = speedKmh,
            category = if (speedKmh > 2) SpeedCategory.WALKING else SpeedCategory.STILL,
            mode = PrecisionMode.HIGH_ACCURACY,
            nextCheckSec = MOVEMENT_CHECK_INTERVAL / 1000f,
            event = "\uD83D\uDCCD Check #$movementCheckCount: ${distanceFromHome.toInt()}m von Home"
        ))

        when {
            // Wieder näher an Home -> wahrscheinlich zurückgekehrt
            distanceFromHome < DISTANCE_STILL_HOME -> {
                Log.d(TAG, "Returned home - cancelling movement tracking")

                onLogEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceFromHome,
                    accuracy = currentLocation.accuracy,
                    speedKmh = speedKmh,
                    category = SpeedCategory.STILL,
                    mode = PrecisionMode.LOW_POWER,
                    nextCheckSec = 0f,
                    event = "\uD83C\uDFE0 Zurückgekehrt (${distanceFromHome.toInt()}m) - kein Trigger"
                ))

                isTrackingMovement = false
                updateState(WifiStatus.WIFI_OUTAGE)
                scheduleRecheck()
            }

            // Weit genug weg ODER genug Checks gemacht -> Trigger
            distanceFromHome > DISTANCE_DEFINITELY_LEFT || movementCheckCount >= MOVEMENT_CHECKS_NEEDED -> {
                Log.d(TAG, "Confirmed leaving - TRIGGER!")

                onLogEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    distance = distanceFromHome,
                    accuracy = currentLocation.accuracy,
                    speedKmh = speedKmh,
                    category = SpeedCategory.WALKING,
                    mode = PrecisionMode.HIGH_ACCURACY,
                    nextCheckSec = 0f,
                    event = "\uD83C\uDFAF TRIGGER! Verlassen bestätigt (${distanceFromHome.toInt()}m, $movementCheckCount checks)"
                ))

                isTrackingMovement = false
                updateState(WifiStatus.TRIGGERED)
                onTrigger()
            }

            // Noch nicht sicher -> weiter beobachten
            else -> {
                lastMovementLocation = currentLocation
                scheduleMovementCheck()
            }
        }
    }

    private fun scheduleRecheck() {
        // Falls WLAN nicht wiederkommt, nochmal checken
        handler.postDelayed({
            if (!isWifiConnected && isMonitoring) {
                Log.d(TAG, "Recheck: WLAN still disconnected")
                performVerificationCheck()
            }
        }, 30_000) // 30 Sekunden
    }

    private fun updateState(status: WifiStatus) {
        val state = WifiState(
            status = status,
            ssid = homeWifiSSID,
            homeLocation = homeLocation,
            snapLocation = snapPoint,
            houseToSnapDistance = houseToSnapDistance,
            isOnStreetSide = currentIsOnStreetSide,
            isConnected = isWifiConnected,
            disconnectTime = disconnectTime,
            movementCheckCount = movementCheckCount,
            triggerLineStart = triggerLineStart,
            triggerLineEnd = triggerLineEnd
        )
        onStateUpdate(state)
    }
}

// State Enums und Data Classes
enum class WifiStatus(val label: String, val emoji: String) {
    CONNECTED("Verbunden", "\uD83D\uDCF6"),
    DISCONNECTED("Getrennt", "\uD83D\uDCF4"),
    DEBOUNCING("Warte...", "\u23F3"),
    VERIFYING("Verifiziere...", "\uD83D\uDD0D"),
    WIFI_OUTAGE("WLAN Ausfall", "\u26A0\uFE0F"),
    TRACKING_MOVEMENT("Beobachte...", "\uD83D\uDC40"),
    TRIGGERED("Getriggert!", "\uD83C\uDFAF")
}

data class WifiState(
    val status: WifiStatus = WifiStatus.DISCONNECTED,
    val ssid: String? = null,
    val homeLocation: Location? = null,
    val snapLocation: Location? = null,
    val houseToSnapDistance: Float = 0f,
    val isOnStreetSide: Boolean? = null,
    val isConnected: Boolean = false,
    val disconnectTime: Long? = null,
    val movementCheckCount: Int = 0,
    // Trigger-Linie entlang der Straße (statt Kreis)
    val triggerLineStart: Pair<Double, Double>? = null,
    val triggerLineEnd: Pair<Double, Double>? = null
)

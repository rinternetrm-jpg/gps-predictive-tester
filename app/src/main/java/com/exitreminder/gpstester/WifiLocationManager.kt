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

            // Speichere Home Location
            saveHomeLocation()

            updateState(WifiStatus.CONNECTED)
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

        // Wenn neues WLAN oder erstes Mal
        if (homeWifiSSID == null || homeWifiSSID != ssid) {
            homeWifiSSID = ssid
            saveHomeLocation()
        }

        updateState(WifiStatus.CONNECTED)
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
        Log.d(TAG, "Saving home location...")

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMaxUpdates(1)
            .setWaitForAccurateLocation(true)
            .build()

        fusedClient.requestLocationUpdates(request, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    homeLocation = location

                    Log.d(TAG, "Home location saved: ${location.latitude}, ${location.longitude} (±${location.accuracy}m)")

                    onLogEntry(LogEntry(
                        timestamp = System.currentTimeMillis(),
                        distance = 0f,
                        accuracy = location.accuracy,
                        speedKmh = 0f,
                        category = SpeedCategory.STILL,
                        mode = PrecisionMode.LOW_POWER,
                        nextCheckSec = 0f,
                        event = "\uD83C\uDFE0 Home Position gespeichert (\u00B1${location.accuracy.toInt()}m)"
                    ))

                    updateState(WifiStatus.CONNECTED)
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

        Log.d(TAG, "Distance from home: ${distanceFromHome}m (accuracy: ${currentLocation.accuracy}m)")

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

                // Recheck in 30 Sek falls WLAN nicht wiederkommt
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
            isConnected = isWifiConnected,
            disconnectTime = disconnectTime,
            movementCheckCount = movementCheckCount
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
    val isConnected: Boolean = false,
    val disconnectTime: Long? = null,
    val movementCheckCount: Int = 0
)

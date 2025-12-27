package com.exitreminder.gpstester

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*

class PredictiveLocationManager(
    private val context: Context,
    private val targetLat: Double,
    private val targetLng: Double,
    private val triggerRadius: Float,  // z.B. 5 Meter!
    private val onStateUpdate: (PredictiveState) -> Unit,
    private val onLogEntry: (LogEntry) -> Unit,
    private val onTrigger: (distanceToCenter: Float, accuracy: Float) -> Unit
) {
    companion object {
        private const val TAG = "PredictiveGPS"

        // Für Maximum Precision Mode
        private const val PRECISION_ZONE_RADIUS = 50f  // Ab 50m → Maximum Precision
        private const val MINIMUM_ACCURACY_FOR_TRIGGER = 10f  // Nur triggern wenn Accuracy < 10m
    }

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val handler = Handler(Looper.getMainLooper())

    // State
    private var isTracking = false
    private var lastLocation: Location? = null
    private var lastCheckTime: Long = 0
    private var estimatedSpeedMps: Float = 1.4f  // Default: Fussgänger
    private var checkCount = 0
    private var currentMode = PrecisionMode.LOW_POWER
    private var hasTriggered = false

    // Speed history für smoothing
    private val speedHistory = mutableListOf<Float>()
    private val maxSpeedHistory = 5

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        isTracking = true
        hasTriggered = false
        checkCount = 0
        speedHistory.clear()
        lastLocation = null

        Log.d(TAG, "Starting tracking to target: $targetLat, $targetLng (radius: ${triggerRadius}m)")

        // Erster Check sofort
        requestLocationUpdate()
    }

    fun stopTracking() {
        isTracking = false
        fusedClient.removeLocationUpdates(locationCallback)
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Tracking stopped")
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdate() {
        if (!isTracking) return

        // Request basierend auf aktuellem Mode
        val request = LocationRequest.Builder(currentMode.priority, currentMode.intervalMs)
            .setMinUpdateIntervalMillis(1000)  // Nicht öfter als 1 Sek
            .setMaxUpdates(1)  // Nur ein Update, dann neu planen
            .setWaitForAccurateLocation(currentMode == PrecisionMode.MAXIMUM_PRECISION)
            .build()

        Log.d(TAG, "Requesting location update (Mode: ${currentMode.label})")

        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                processLocation(location)
            }
        }
    }

    private fun processLocation(location: Location) {
        val now = System.currentTimeMillis()
        checkCount++

        // === 1. DISTANZ BERECHNEN ===
        val results = FloatArray(1)
        Location.distanceBetween(
            location.latitude, location.longitude,
            targetLat, targetLng,
            results
        )
        val distanceToTarget = results[0]
        val distanceToTrigger = (distanceToTarget - triggerRadius).coerceAtLeast(0f)

        // === 2. GESCHWINDIGKEIT BERECHNEN ===
        lastLocation?.let { last ->
            val timeDelta = (now - lastCheckTime) / 1000f
            if (timeDelta > 1f) {  // Mindestens 1 Sekunde
                val movedResults = FloatArray(1)
                Location.distanceBetween(
                    last.latitude, last.longitude,
                    location.latitude, location.longitude,
                    movedResults
                )
                val distanceMoved = movedResults[0]
                val instantSpeed = distanceMoved / timeDelta

                // Sanity check: Ignoriere unrealistische Werte (GPS-Sprünge)
                if (instantSpeed < 60f) {  // Max 216 km/h
                    speedHistory.add(instantSpeed)
                    if (speedHistory.size > maxSpeedHistory) {
                        speedHistory.removeAt(0)
                    }

                    // Durchschnitt der letzten Messungen
                    estimatedSpeedMps = speedHistory.average().toFloat()
                }
            }
        }

        val speedKmh = estimatedSpeedMps * 3.6f

        // === 3. SPEED CATEGORY ===
        val speedCategory = when {
            speedKmh < 2f -> SpeedCategory.STILL
            speedKmh < 7f -> SpeedCategory.WALKING
            speedKmh < 12f -> SpeedCategory.RUNNING
            speedKmh < 28f -> SpeedCategory.CYCLING
            else -> SpeedCategory.DRIVING
        }

        // === 4. PRECISION MODE BESTIMMEN ===
        val newMode = when {
            distanceToTarget < PRECISION_ZONE_RADIUS -> PrecisionMode.MAXIMUM_PRECISION
            distanceToTarget < 200f -> PrecisionMode.HIGH_ACCURACY
            distanceToTarget < 500f -> PrecisionMode.BALANCED
            else -> PrecisionMode.LOW_POWER
        }

        if (newMode != currentMode) {
            Log.d(TAG, "Mode change: ${currentMode.label} -> ${newMode.label}")
            onLogEntry(LogEntry(
                timestamp = now,
                distance = distanceToTarget,
                accuracy = location.accuracy,
                speedKmh = speedKmh,
                category = speedCategory,
                mode = newMode,
                nextCheckSec = 0f,
                event = "\uD83D\uDCE1 Mode: ${newMode.label}"
            ))
        }
        currentMode = newMode

        // === 5. ETA BERECHNEN ===
        val etaSeconds = if (estimatedSpeedMps > 0.1f) {
            distanceToTrigger / estimatedSpeedMps
        } else {
            Float.MAX_VALUE
        }

        // === 6. NÄCHSTEN CHECK PLANEN ===
        val nextCheckSeconds = when (currentMode) {
            PrecisionMode.MAXIMUM_PRECISION -> {
                // Im Precision-Modus: Alle 2 Sekunden, aber...
                // Wenn wir uns vom Ziel entfernen, können wir entspannen
                val lastDist = lastLocation?.let {
                    val r = FloatArray(1)
                    Location.distanceBetween(it.latitude, it.longitude, targetLat, targetLng, r)
                    r[0]
                } ?: 0f
                if (lastLocation != null && distanceToTarget > lastDist) {
                    // Entfernen uns -> etwas länger warten
                    5f
                } else {
                    2f
                }
            }
            else -> {
                // Predictive: Basierend auf ETA mit Safety Factor
                (etaSeconds * speedCategory.safetyFactor)
                    .coerceIn(currentMode.intervalMs / 1000f * 0.8f, currentMode.intervalMs / 1000f)
            }
        }

        // === 7. TRIGGER CHECK ===
        val inTriggerZone = distanceToTrigger <= 0

        // Nur triggern wenn:
        // - Wir sind in der Trigger-Zone
        // - GPS Accuracy ist gut genug
        // - Noch nicht getriggert
        if (inTriggerZone && location.accuracy <= MINIMUM_ACCURACY_FOR_TRIGGER && !hasTriggered) {
            hasTriggered = true
            Log.d(TAG, "\uD83C\uDFAF TRIGGER! Distance to center: ${distanceToTarget}m, Accuracy: ${location.accuracy}m")

            onLogEntry(LogEntry(
                timestamp = now,
                distance = distanceToTarget,
                accuracy = location.accuracy,
                speedKmh = speedKmh,
                category = speedCategory,
                mode = currentMode,
                nextCheckSec = nextCheckSeconds,
                event = "\uD83C\uDFAF TRIGGER! ${distanceToTarget.toInt()}m vom Ziel (GPS: \u00B1${location.accuracy.toInt()}m)"
            ))

            onTrigger(distanceToTarget, location.accuracy)
        } else if (inTriggerZone && location.accuracy > MINIMUM_ACCURACY_FOR_TRIGGER) {
            // In Zone aber Accuracy zu schlecht -> weiter tracken
            Log.d(TAG, "In trigger zone but accuracy too low: ${location.accuracy}m")

            onLogEntry(LogEntry(
                timestamp = now,
                distance = distanceToTarget,
                accuracy = location.accuracy,
                speedKmh = speedKmh,
                category = speedCategory,
                mode = currentMode,
                nextCheckSec = nextCheckSeconds,
                event = "\u26A0\uFE0F In Zone, aber Accuracy ${location.accuracy.toInt()}m > ${MINIMUM_ACCURACY_FOR_TRIGGER.toInt()}m"
            ))
        }

        // === 8. STATE UPDATE ===
        val state = PredictiveState(
            timestamp = now,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            distanceToTarget = distanceToTarget,
            distanceToTrigger = distanceToTrigger,
            speedMps = estimatedSpeedMps,
            speedKmh = speedKmh,
            speedCategory = speedCategory,
            etaSeconds = etaSeconds,
            nextCheckSeconds = nextCheckSeconds,
            checkCount = checkCount,
            inTriggerZone = inTriggerZone,
            precisionMode = currentMode,
            provider = location.provider ?: "unknown"
        )

        onStateUpdate(state)

        // Log entry
        onLogEntry(LogEntry(
            timestamp = now,
            distance = distanceToTarget,
            accuracy = location.accuracy,
            speedKmh = speedKmh,
            category = speedCategory,
            mode = currentMode,
            nextCheckSec = nextCheckSeconds
        ))

        // === 9. SAVE & SCHEDULE NEXT ===
        lastLocation = location
        lastCheckTime = now

        // Nächsten Check planen
        handler.postDelayed({
            requestLocationUpdate()
        }, (nextCheckSeconds * 1000).toLong())

        Log.d(TAG, "Check #$checkCount: ${distanceToTarget.toInt()}m | " +
                "Speed: ${speedKmh.toInt()} km/h | " +
                "Accuracy: ${location.accuracy.toInt()}m | " +
                "Next: ${nextCheckSeconds.toInt()}s | " +
                "Mode: ${currentMode.label}")
    }
}

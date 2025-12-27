package com.exitreminder.gpstester

data class PredictiveState(
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,                    // GPS Accuracy in Metern
    val distanceToTarget: Float = 0f,            // Distanz zum Ziel-Zentrum
    val distanceToTrigger: Float = 0f,           // Distanz bis Trigger-Radius erreicht
    val speedMps: Float = 0f,                    // Geschwindigkeit m/s
    val speedKmh: Float = 0f,                    // Geschwindigkeit km/h
    val speedCategory: SpeedCategory = SpeedCategory.STILL,
    val etaSeconds: Float = 0f,                  // Geschätzte Zeit bis Trigger
    val nextCheckSeconds: Float = 0f,            // Wann nächster GPS Check
    val checkCount: Int = 0,                     // Anzahl GPS Checks
    val inTriggerZone: Boolean = false,          // Sind wir im Trigger-Bereich?
    val precisionMode: PrecisionMode = PrecisionMode.LOW_POWER,
    val provider: String = ""                    // GPS, FUSED, etc.
)

enum class SpeedCategory(val label: String, val emoji: String, val safetyFactor: Float) {
    STILL("Stillstand", "\uD83E\uDDCD", 0.95f),           // Kaum Bewegung
    WALKING("Zu Fuss", "\uD83D\uDEB6", 0.70f),             // ~5 km/h
    RUNNING("Joggen", "\uD83C\uDFC3", 0.55f),             // ~10 km/h
    CYCLING("Fahrrad", "\uD83D\uDEB4", 0.45f),            // ~20 km/h
    DRIVING("Auto", "\uD83D\uDE97", 0.25f)                // >30 km/h
}

enum class PrecisionMode(
    val label: String,
    val intervalMs: Long,
    val priority: Int,
    val distanceThreshold: Float  // Ab welcher Distanz zum Trigger
) {
    LOW_POWER("Low Power", 300_000, 104, 500f),           // >500m: alle 5 Min
    BALANCED("Balanced", 60_000, 102, 200f),              // 200-500m: alle 60 Sek
    HIGH_ACCURACY("High Accuracy", 10_000, 100, 50f),     // 50-200m: alle 10 Sek
    MAXIMUM_PRECISION("Maximum Precision", 2_000, 100, 0f) // <50m: alle 2 Sek!
}

data class LogEntry(
    val timestamp: Long,
    val distance: Float,
    val accuracy: Float,
    val speedKmh: Float,
    val category: SpeedCategory,
    val mode: PrecisionMode,
    val nextCheckSec: Float,
    val event: String = ""
)

/**
 * Tracking-Statistiken für den Vergleich verschiedener Methoden
 */
data class TrackingStats(
    val startTime: Long = 0,
    val endTime: Long = 0,
    val totalDurationMs: Long = 0,
    val startDistance: Float = 0f,           // Start-Distanz zum Ziel

    // Unsere Predictive Methode
    val predictiveChecks: Int = 0,

    // Vergleichswerte (hochgerechnet)
    val constantHighChecks: Int = 0,         // Alle 2 Sek (wie Maximum Precision durchgehend)
    val constantMediumChecks: Int = 0,       // Alle 10 Sek (Standard Geofencing)
    val constantLowChecks: Int = 0,          // Alle 60 Sek (Battery Saver)

    // Akku
    val batteryStart: Int = 0,               // Akku-Level am Start (%)
    val batteryEnd: Int = 0,                 // Akku-Level am Ende (%)
    val batteryUsed: Int = 0,                // Verbrauchter Akku (%)

    // Effizienz
    val savingsVsConstantHigh: Float = 0f,   // % Ersparnis vs alle 2 Sek
    val savingsVsConstantMedium: Float = 0f, // % Ersparnis vs alle 10 Sek

    // Genauigkeit
    val triggerDistance: Float = 0f,         // Bei welcher Distanz getriggert
    val triggerAccuracy: Float = 0f          // GPS-Accuracy beim Trigger
)

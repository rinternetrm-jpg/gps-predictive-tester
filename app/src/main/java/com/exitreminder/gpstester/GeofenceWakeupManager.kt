package com.exitreminder.gpstester

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

class GeofenceWakeupManager(private val context: Context) {

    companion object {
        private const val TAG = "GeofenceWakeup"
        const val ACTION_GEOFENCE_EVENT = "com.exitreminder.gpstester.GEOFENCE_EVENT"

        // Wake-Up Radius - Android Geofencing ist ungenau, daher großzügig
        const val WAKEUP_RADIUS_METERS = 500f

        // Mindest-Entfernung für Geofence (näher = direkt Predictive starten)
        const val MIN_DISTANCE_FOR_GEOFENCE = 600f
    }

    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)

    // Callbacks für Wake-Up Events
    private val wakeUpCallbacks = mutableMapOf<String, () -> Unit>()

    // Registrierte Geofences
    private val registeredGeofences = mutableSetOf<String>()

    // Broadcast Receiver für Geofence Events
    private val geofenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_GEOFENCE_EVENT) {
                val reminderId = intent.getStringExtra("reminder_id") ?: return
                val transitionType = intent.getIntExtra("transition_type", -1)

                Log.d(TAG, "Geofence event received: $reminderId, transition: $transitionType")

                if (transitionType == Geofence.GEOFENCE_TRANSITION_ENTER) {
                    onGeofenceEnter(reminderId)
                }
            }
        }
    }

    init {
        // Receiver registrieren
        val filter = IntentFilter(ACTION_GEOFENCE_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(geofenceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(geofenceReceiver, filter)
        }
    }

    /**
     * Registriert einen Wake-Up Geofence.
     * Android meldet sich wenn User in WAKEUP_RADIUS_METERS Nähe kommt.
     *
     * @param reminderId Eindeutige ID für den Reminder
     * @param targetLat Ziel Latitude
     * @param targetLng Ziel Longitude
     * @param currentDistanceMeters Aktuelle Entfernung zum Ziel (optional, für Optimierung)
     * @param onWakeUp Callback wenn User in der Nähe ist
     * @return true wenn Geofence registriert, false wenn direkt Tracking starten soll
     */
    fun registerWakeUpGeofence(
        reminderId: String,
        targetLat: Double,
        targetLng: Double,
        currentDistanceMeters: Float? = null,
        onWakeUp: () -> Unit
    ): Boolean {

        // Wenn User schon nah genug ist, direkt Tracking starten
        if (currentDistanceMeters != null && currentDistanceMeters < MIN_DISTANCE_FOR_GEOFENCE) {
            Log.d(TAG, "User already within ${currentDistanceMeters}m - skipping geofence, start tracking directly")
            onWakeUp()
            return false
        }

        // Callback speichern
        wakeUpCallbacks[reminderId] = onWakeUp

        // Geofence erstellen
        val geofence = Geofence.Builder()
            .setRequestId(reminderId)
            .setCircularRegion(targetLat, targetLng, WAKEUP_RADIUS_METERS)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .setLoiteringDelay(0)  // Sofort triggern
            .build()

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)  // Auch wenn schon drin
            .addGeofence(geofence)
            .build()

        val pendingIntent = createGeofencePendingIntent(reminderId)

        // Permissions check
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing location permission")
            return false
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing background location permission")
            // Fallback: Trotzdem versuchen, Geofence zu registrieren
        }

        geofencingClient.addGeofences(geofencingRequest, pendingIntent)
            .addOnSuccessListener {
                registeredGeofences.add(reminderId)
                Log.d(TAG, "Geofence registered: $reminderId at $targetLat, $targetLng (${WAKEUP_RADIUS_METERS}m radius)")
                Log.d(TAG, "System is now SLEEPING - waiting for Android to wake us up")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to register geofence: ${e.message}")
                // Fallback: Direkt Tracking starten
                wakeUpCallbacks.remove(reminderId)
                onWakeUp()
            }

        return true
    }

    /**
     * Entfernt einen Geofence
     */
    fun unregisterGeofence(reminderId: String) {
        if (registeredGeofences.contains(reminderId)) {
            geofencingClient.removeGeofences(listOf(reminderId))
                .addOnSuccessListener {
                    registeredGeofences.remove(reminderId)
                    wakeUpCallbacks.remove(reminderId)
                    Log.d(TAG, "Geofence unregistered: $reminderId")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to unregister geofence: ${e.message}")
                }
        }
    }

    /**
     * Entfernt alle Geofences
     */
    fun unregisterAll() {
        if (registeredGeofences.isNotEmpty()) {
            geofencingClient.removeGeofences(registeredGeofences.toList())
                .addOnSuccessListener {
                    Log.d(TAG, "All geofences unregistered: ${registeredGeofences.size}")
                    registeredGeofences.clear()
                    wakeUpCallbacks.clear()
                }
        }
    }

    /**
     * Wird aufgerufen wenn Android einen Geofence Enter meldet
     */
    private fun onGeofenceEnter(reminderId: String) {
        Log.d(TAG, "WAKE UP! Geofence entered: $reminderId")
        Log.d(TAG, "Starting Predictive Tracking...")

        // Callback ausführen (startet PredictiveLocationManager)
        wakeUpCallbacks[reminderId]?.invoke()

        // Geofence entfernen (wir übernehmen jetzt mit Predictive Tracking)
        unregisterGeofence(reminderId)
    }

    private fun createGeofencePendingIntent(reminderId: String): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_GEOFENCE_EVENT
            putExtra("reminder_id", reminderId)
        }

        return PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    /**
     * Gibt Status aller registrierten Geofences zurück
     */
    fun getStatus(): GeofenceStatus {
        return GeofenceStatus(
            registeredCount = registeredGeofences.size,
            registeredIds = registeredGeofences.toList(),
            isSleeping = registeredGeofences.isNotEmpty()
        )
    }

    fun cleanup() {
        try {
            context.unregisterReceiver(geofenceReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        unregisterAll()
    }
}

data class GeofenceStatus(
    val registeredCount: Int,
    val registeredIds: List<String>,
    val isSleeping: Boolean
)

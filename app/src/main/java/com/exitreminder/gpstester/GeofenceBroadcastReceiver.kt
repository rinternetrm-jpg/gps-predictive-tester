package com.exitreminder.gpstester

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GeofenceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Geofence broadcast received")

        val geofencingEvent = GeofencingEvent.fromIntent(intent)

        if (geofencingEvent == null) {
            Log.e(TAG, "GeofencingEvent is null")
            return
        }

        if (geofencingEvent.hasError()) {
            Log.e(TAG, "Geofencing error: ${geofencingEvent.errorCode}")
            return
        }

        val transitionType = geofencingEvent.geofenceTransition

        if (transitionType == Geofence.GEOFENCE_TRANSITION_ENTER) {
            val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return

            for (geofence in triggeringGeofences) {
                val reminderId = geofence.requestId
                Log.d(TAG, "GEOFENCE ENTER: $reminderId")

                // Broadcast an GeofenceWakeupManager
                val wakeUpIntent = Intent(GeofenceWakeupManager.ACTION_GEOFENCE_EVENT).apply {
                    putExtra("reminder_id", reminderId)
                    putExtra("transition_type", transitionType)
                    setPackage(context.packageName)
                }
                context.sendBroadcast(wakeUpIntent)
            }
        }
    }
}

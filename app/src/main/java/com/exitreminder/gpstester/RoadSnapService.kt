package com.exitreminder.gpstester

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

object RoadSnapService {

    private const val TAG = "RoadSnap"
    private const val OSRM_NEAREST_URL = "https://router.project-osrm.org/nearest/v1/driving/"

    data class SnapResult(
        val originalLat: Double,
        val originalLng: Double,
        val snappedLat: Double,
        val snappedLng: Double,
        val distanceToRoad: Float,  // Meter
        val roadName: String?
    )

    /**
     * Findet den nächsten Punkt auf einer Straße
     * @param lat Latitude (Hausmitte)
     * @param lng Longitude (Hausmitte)
     * @return SnapResult mit Straßenpunkt oder null bei Fehler
     */
    suspend fun snapToRoad(lat: Double, lng: Double): SnapResult? {
        return withContext(Dispatchers.IO) {
            try {
                // OSRM erwartet: longitude,latitude (umgekehrt!)
                val url = "$OSRM_NEAREST_URL$lng,$lat?number=1"

                Log.d(TAG, "Requesting: $url")

                val response = URL(url).readText()
                val json = JSONObject(response)

                if (json.getString("code") != "Ok") {
                    Log.e(TAG, "OSRM error: ${json.getString("code")}")
                    return@withContext null
                }

                val waypoints = json.getJSONArray("waypoints")
                if (waypoints.length() == 0) {
                    Log.e(TAG, "No waypoints found")
                    return@withContext null
                }

                val waypoint = waypoints.getJSONObject(0)
                val location = waypoint.getJSONArray("location")
                val snappedLng = location.getDouble(0)
                val snappedLat = location.getDouble(1)
                val distance = waypoint.getDouble("distance").toFloat()
                val name = waypoint.optString("name", null)

                Log.d(TAG, "Snapped: $lat,$lng -> $snappedLat,$snappedLng (${distance}m to road)")

                SnapResult(
                    originalLat = lat,
                    originalLng = lng,
                    snappedLat = snappedLat,
                    snappedLng = snappedLng,
                    distanceToRoad = distance,
                    roadName = if (name.isNullOrBlank()) null else name
                )

            } catch (e: Exception) {
                Log.e(TAG, "Snap to road failed", e)
                null
            }
        }
    }
}

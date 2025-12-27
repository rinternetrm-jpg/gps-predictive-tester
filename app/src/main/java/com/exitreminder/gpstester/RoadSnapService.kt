package com.exitreminder.gpstester

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object RoadSnapService {

    private const val TAG = "RoadSnap"
    private const val OSRM_NEAREST_URL = "https://router.project-osrm.org/nearest/v1/driving/"
    private const val OSRM_ROUTE_URL = "https://router.project-osrm.org/route/v1/driving/"
    private const val TIMEOUT_MS = 10_000  // 10 Sekunden Timeout

    /**
     * HTTP GET mit Timeout
     */
    private fun httpGet(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.requestMethod = "GET"

        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    data class SnapResult(
        val originalLat: Double,
        val originalLng: Double,
        val snappedLat: Double,
        val snappedLng: Double,
        val distanceToRoad: Float,  // Meter
        val roadName: String?,
        // NEU: Trigger-Linie entlang der Straße
        val triggerLineStart: Pair<Double, Double>?,  // Linker Endpunkt
        val triggerLineEnd: Pair<Double, Double>?      // Rechter Endpunkt
    )

    data class RouteResult(
        val distanceMeters: Float,      // Straßen-Distanz in Metern
        val durationSeconds: Float,     // Geschätzte Fahrzeit
        val routePoints: List<Pair<Double, Double>>  // Koordinaten für Karten-Linie
    )

    // Toleranz-Distanz für Trigger-Linie (links und rechts vom Snap-Punkt)
    const val TRIGGER_LINE_HALF_LENGTH = 10.0  // 10 Meter in jede Richtung = 20m Linie

    /**
     * Findet den nächsten Punkt auf einer Straße und berechnet Trigger-Linie
     * @param lat Latitude (Hausmitte)
     * @param lng Longitude (Hausmitte)
     * @return SnapResult mit Straßenpunkt und Trigger-Linie oder null bei Fehler
     */
    suspend fun snapToRoad(lat: Double, lng: Double): SnapResult? {
        return withContext(Dispatchers.IO) {
            try {
                // OSRM erwartet: longitude,latitude (umgekehrt!)
                val url = "$OSRM_NEAREST_URL$lng,$lat"

                Log.d(TAG, "Requesting: $url")

                val response = httpGet(url)
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

                // Hauptpunkt (nächster zur Anfrage)
                val waypoint = waypoints.getJSONObject(0)
                val location = waypoint.getJSONArray("location")
                val snappedLng = location.getDouble(0)
                val snappedLat = location.getDouble(1)
                val distance = waypoint.getDouble("distance").toFloat()
                val name = waypoint.optString("name", null)

                Log.d(TAG, "Snapped: $lat,$lng -> $snappedLat,$snappedLng (${distance}m to road)")

                // Berechne Trigger-Linie:
                // Die Linie verläuft SENKRECHT zur Richtung Haus→Snap
                // (also entlang der Straße)
                var triggerLineStart: Pair<Double, Double>? = null
                var triggerLineEnd: Pair<Double, Double>? = null

                if (distance > 1f) {  // Nur wenn Snap-Distanz > 1m
                    // Bearing vom Haus zur Straße
                    val bearingToRoad = calculateBearing(lat, lng, snappedLat, snappedLng)

                    // Straße verläuft 90° dazu (senkrecht)
                    val roadBearing = bearingToRoad + 90.0

                    // Trigger-Linie entlang der Straße (±10m)
                    triggerLineStart = calculateDestination(snappedLat, snappedLng, roadBearing, TRIGGER_LINE_HALF_LENGTH)
                    triggerLineEnd = calculateDestination(snappedLat, snappedLng, roadBearing + 180.0, TRIGGER_LINE_HALF_LENGTH)

                    Log.d(TAG, "Trigger line: $triggerLineStart to $triggerLineEnd (road bearing: $roadBearing)")
                }

                SnapResult(
                    originalLat = lat,
                    originalLng = lng,
                    snappedLat = snappedLat,
                    snappedLng = snappedLng,
                    distanceToRoad = distance,
                    roadName = if (name.isNullOrBlank()) null else name,
                    triggerLineStart = triggerLineStart,
                    triggerLineEnd = triggerLineEnd
                )

            } catch (e: Exception) {
                Log.e(TAG, "Snap to road failed", e)
                null
            }
        }
    }

    /**
     * Dekodiert Polyline-Format (Google/OSRM Standard)
     * https://developers.google.com/maps/documentation/utilities/polylinealgorithm
     */
    private fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
        val poly = mutableListOf<Pair<Double, Double>>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            poly.add(Pair(lat / 1E5, lng / 1E5))
        }
        return poly
    }

    /**
     * Berechnet den Bearing (Richtungswinkel) zwischen zwei Punkten
     */
    private fun calculateBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)

        val y = Math.sin(dLng) * Math.cos(lat2Rad)
        val x = Math.cos(lat1Rad) * Math.sin(lat2Rad) - Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(dLng)

        val bearing = Math.toDegrees(Math.atan2(y, x))
        return (bearing + 360) % 360  // Normalisieren auf 0-360
    }

    /**
     * Berechnet einen Zielpunkt in einer bestimmten Richtung und Distanz
     */
    private fun calculateDestination(lat: Double, lng: Double, bearing: Double, distanceMeters: Double): Pair<Double, Double> {
        val earthRadius = 6371000.0  // Meter
        val latRad = Math.toRadians(lat)
        val lngRad = Math.toRadians(lng)
        val bearingRad = Math.toRadians(bearing)
        val angularDist = distanceMeters / earthRadius

        val destLatRad = Math.asin(
            Math.sin(latRad) * Math.cos(angularDist) +
            Math.cos(latRad) * Math.sin(angularDist) * Math.cos(bearingRad)
        )

        val destLngRad = lngRad + Math.atan2(
            Math.sin(bearingRad) * Math.sin(angularDist) * Math.cos(latRad),
            Math.cos(angularDist) - Math.sin(latRad) * Math.sin(destLatRad)
        )

        return Pair(Math.toDegrees(destLatRad), Math.toDegrees(destLngRad))
    }

    /**
     * Berechnet die kürzeste Distanz von einem Punkt zu einer Linie (zwischen zwei Punkten)
     */
    fun distanceToLineSegment(
        pointLat: Double, pointLng: Double,
        lineStartLat: Double, lineStartLng: Double,
        lineEndLat: Double, lineEndLng: Double
    ): Float {
        val results = FloatArray(1)

        // Distanz zum Startpunkt
        android.location.Location.distanceBetween(pointLat, pointLng, lineStartLat, lineStartLng, results)
        val distToStart = results[0]

        // Distanz zum Endpunkt
        android.location.Location.distanceBetween(pointLat, pointLng, lineEndLat, lineEndLng, results)
        val distToEnd = results[0]

        // Länge der Linie
        android.location.Location.distanceBetween(lineStartLat, lineStartLng, lineEndLat, lineEndLng, results)
        val lineLength = results[0]

        if (lineLength == 0f) {
            return distToStart
        }

        // Projektion berechnen (wie weit ist der Punkt auf der Linie)
        // Vereinfachte Berechnung für kurze Distanzen
        val t = maxOf(0.0, minOf(1.0,
            ((pointLat - lineStartLat) * (lineEndLat - lineStartLat) +
             (pointLng - lineStartLng) * (lineEndLng - lineStartLng)) /
            ((lineEndLat - lineStartLat) * (lineEndLat - lineStartLat) +
             (lineEndLng - lineStartLng) * (lineEndLng - lineStartLng))
        ))

        // Nächster Punkt auf der Linie
        val nearestLat = lineStartLat + t * (lineEndLat - lineStartLat)
        val nearestLng = lineStartLng + t * (lineEndLng - lineStartLng)

        // Distanz zum nächsten Punkt
        android.location.Location.distanceBetween(pointLat, pointLng, nearestLat, nearestLng, results)
        return results[0]
    }

    // Speichert den letzten Fehler für UI-Anzeige
    var lastRouteError: String? = null
        private set

    /**
     * Berechnet die Route zwischen zwei Punkten entlang der Straßen
     * @param fromLat Start Latitude
     * @param fromLng Start Longitude
     * @param toLat Ziel Latitude
     * @param toLng Ziel Longitude
     * @return RouteResult mit Straßen-Distanz und Route oder null bei Fehler
     */
    suspend fun getRoute(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): RouteResult? {
        lastRouteError = null
        return withContext(Dispatchers.IO) {
            try {
                // OSRM erwartet: longitude,latitude (umgekehrt!)
                // Format: /route/v1/driving/lng1,lat1;lng2,lat2
                // WICHTIG: Keine Query-Parameter - OSRM Server blockiert sonst!
                val url = "$OSRM_ROUTE_URL$fromLng,$fromLat;$toLng,$toLat"

                Log.d(TAG, "Route request: $url")
                Log.d(TAG, "FROM: $fromLat, $fromLng -> TO: $toLat, $toLng")

                Log.d(TAG, "Fetching route from API...")
                val response = httpGet(url)
                Log.d(TAG, "Got response: ${response.take(200)}...")

                val json = JSONObject(response)
                val code = json.getString("code")

                if (code != "Ok") {
                    val message = json.optString("message", "Unbekannter Fehler")
                    lastRouteError = "OSRM: $code - $message"
                    Log.e(TAG, "OSRM route error: $code - $message")
                    return@withContext null
                }

                val routes = json.getJSONArray("routes")
                if (routes.length() == 0) {
                    lastRouteError = "Keine Route gefunden (0 routes)"
                    Log.e(TAG, "No routes found")
                    return@withContext null
                }

                val route = routes.getJSONObject(0)
                val distance = route.getDouble("distance").toFloat()  // Meter
                val duration = route.getDouble("duration").toFloat()  // Sekunden
                Log.d(TAG, "Route distance: ${distance}m, duration: ${duration}s")

                // Route-Geometrie dekodieren (Polyline Format)
                val geometry = route.getString("geometry")
                Log.d(TAG, "Geometry (first 50 chars): ${geometry.take(50)}")

                val routePoints = decodePolyline(geometry)
                Log.d(TAG, "Decoded ${routePoints.size} points")

                Log.d(TAG, "Route SUCCESS: ${distance}m, ${duration}s, ${routePoints.size} points")

                RouteResult(
                    distanceMeters = distance,
                    durationSeconds = duration,
                    routePoints = routePoints
                )

            } catch (e: java.net.SocketTimeoutException) {
                lastRouteError = "Timeout (${TIMEOUT_MS/1000}s)"
                Log.e(TAG, "Route TIMEOUT after ${TIMEOUT_MS}ms")
                null
            } catch (e: java.net.UnknownHostException) {
                lastRouteError = "Kein Internet"
                Log.e(TAG, "No internet connection")
                null
            } catch (e: Exception) {
                lastRouteError = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "Route calculation FAILED: ${e.message}", e)
                Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
                null
            }
        }
    }
}

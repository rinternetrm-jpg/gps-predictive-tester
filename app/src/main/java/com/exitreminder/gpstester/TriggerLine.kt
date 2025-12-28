package com.exitreminder.gpstester

import android.location.Location
import org.osmdroid.util.GeoPoint
import kotlin.math.*

data class TriggerLine(
    var id: Int = 0,
    var centerLat: Double,
    var centerLng: Double,
    var rotationDegrees: Float = 0f,
    var lengthMeters: Float = 30f,
    var isActive: Boolean = true
) {
    fun getEndpoints(): Pair<GeoPoint, GeoPoint> {
        val halfLength = lengthMeters / 2.0
        val rotationRad = Math.toRadians(rotationDegrees.toDouble())

        val metersPerDegreeLat = 111320.0
        val metersPerDegreeLng = 111320.0 * cos(Math.toRadians(centerLat))

        val deltaLat = halfLength * cos(rotationRad) / metersPerDegreeLat
        val deltaLng = halfLength * sin(rotationRad) / metersPerDegreeLng

        return Pair(
            GeoPoint(centerLat + deltaLat, centerLng + deltaLng),
            GeoPoint(centerLat - deltaLat, centerLng - deltaLng)
        )
    }

    fun distanceToLine(lat: Double, lng: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat, lng, centerLat, centerLng, results)
        return results[0]
    }

    fun hasCrossedLine(prevLat: Double, prevLng: Double, curLat: Double, curLng: Double): Boolean {
        if (!isActive) return false
        val (p1, p2) = getEndpoints()

        fun side(lat: Double, lng: Double): Float {
            return ((p2.longitude - p1.longitude) * (lat - p1.latitude) -
                    (p2.latitude - p1.latitude) * (lng - p1.longitude)).toFloat()
        }

        val prevSide = side(prevLat, prevLng)
        val curSide = side(curLat, curLng)
        return prevSide * curSide < 0
    }

    fun moveTo(lat: Double, lng: Double) {
        centerLat = lat
        centerLng = lng
    }

    fun rotateFromPoint(center: GeoPoint, point: GeoPoint): Float {
        val deltaLat = point.latitude - center.latitude
        val deltaLng = point.longitude - center.longitude
        return Math.toDegrees(atan2(deltaLng, deltaLat)).toFloat()
    }
}

data class TriggerLineSet(
    var lines: MutableList<TriggerLine> = mutableListOf(),
    var targetLat: Double = 0.0,
    var targetLng: Double = 0.0
) {
    companion object {
        const val MAX_LINES = 5
    }

    fun addLine(lat: Double, lng: Double, rotation: Float = 0f, length: Float = 30f): TriggerLine? {
        if (lines.size >= MAX_LINES) return null
        val line = TriggerLine(
            id = lines.size + 1,
            centerLat = lat,
            centerLng = lng,
            rotationDegrees = rotation,
            lengthMeters = length
        )
        lines.add(line)
        return line
    }

    fun removeLine(id: Int) = lines.removeIf { it.id == id }

    fun hasAnyCrossed(prevLat: Double, prevLng: Double, curLat: Double, curLng: Double): TriggerLine? {
        return lines.firstOrNull { it.isActive && it.hasCrossedLine(prevLat, prevLng, curLat, curLng) }
    }
}

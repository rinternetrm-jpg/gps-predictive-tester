package com.exitreminder.gpstester

import java.text.SimpleDateFormat
import java.util.*

object Utils {
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun formatTime(timestamp: Long): String = timeFormat.format(Date(timestamp))

    fun formatDuration(seconds: Float): String {
        return when {
            seconds < 60 -> "${seconds.toInt()}s"
            seconds < 3600 -> "${(seconds / 60).toInt()}m ${(seconds % 60).toInt()}s"
            else -> "${(seconds / 3600).toInt()}h ${((seconds % 3600) / 60).toInt()}m"
        }
    }

    fun formatDistance(meters: Float): String {
        return when {
            meters < 1000 -> "${meters.toInt()}m"
            else -> String.format("%.1fkm", meters / 1000)
        }
    }
}

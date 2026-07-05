package com.example.phantomtrail.utils

import org.osmdroid.util.GeoPoint
import java.time.ZonedDateTime
import kotlin.math.*

// gps and geospatial calculation utilities
object GeoUtils {

    // distance between two points using haversine formula, in km
    fun calculateHaversineDistance(
        lon1: Double, lat1: Double,
        lon2: Double, lat2: Double
    ): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    // convert decimal degrees to exif gps format
    fun convertToExifFormat(decimalDegrees: Double): String {
        val degrees = decimalDegrees.toInt()
        val minutesDecimal = (decimalDegrees - degrees) * 60
        val minutes = minutesDecimal.toInt()
        val seconds = ((minutesDecimal - minutes) * 60 * 1000).toInt()

        return "$degrees/1,$minutes/1,$seconds/1000"
    }

    // find interpolated location on trail based on timestamp
    fun findLocationForTime(
        photoTime: ZonedDateTime,
        points: List<GeoPoint>,
        startTime: ZonedDateTime,
        endTime: ZonedDateTime
    ): GeoPoint {
        if (photoTime.isBefore(startTime)) {
            return points.first()
        }

        if (photoTime.isAfter(endTime)) {
            return points.last()
        }

        // interpolate based on time progress
        val totalDuration = java.time.Duration.between(startTime, endTime).toMillis().toDouble()
        val photoOffset = java.time.Duration.between(startTime, photoTime).toMillis().toDouble()
        val progress = photoOffset / totalDuration

        val pointIndex = (progress * (points.size - 1)).toInt().coerceIn(0, points.size - 1)
        return points[pointIndex]
    }
}
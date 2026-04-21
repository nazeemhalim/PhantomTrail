package com.example.phantomtrail.utils

import org.osmdroid.util.GeoPoint
import java.time.ZonedDateTime
import kotlin.math.*

/**
 * Utilities for GPS and geospatial calculations
 */
object GeoUtils {

    /**
     * Calculate distance between two points using Haversine formula
     * @return Distance in kilometers
     */
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

    /**
     * Convert decimal degrees to EXIF GPS format
     */
    fun convertToExifFormat(decimalDegrees: Double): String {
        val degrees = decimalDegrees.toInt()
        val minutesDecimal = (decimalDegrees - degrees) * 60
        val minutes = minutesDecimal.toInt()
        val seconds = ((minutesDecimal - minutes) * 60 * 1000).toInt()

        return "$degrees/1,$minutes/1,$seconds/1000"
    }

    /**
     * Find interpolated location on trail based on timestamp
     */
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

        // Interpolate based on time progress
        val totalDuration = java.time.Duration.between(startTime, endTime).toMillis().toDouble()
        val photoOffset = java.time.Duration.between(startTime, photoTime).toMillis().toDouble()
        val progress = photoOffset / totalDuration

        val pointIndex = (progress * (points.size - 1)).toInt().coerceIn(0, points.size - 1)
        return points[pointIndex]
    }
}

/**
 * Generates trail points - EXACT logic from original updateTrailPoints()
 */
class TrailGenerator(
    private val stepLengthMeters: Double,
    private val startLat: Double,
    private val startLon: Double
) {
    companion object {
        private const val SCALE = 0.0001
        private val ANGLE_VARIABILITY = Math.PI / 7
    }

    // State variables - must persist across calls like in original
    var currentAngle = 0.0
    var accumulatedDistance = 0.0

    /**
     * Generate trail from scratch - mirrors the initialization in original
     */
    fun generateTrail(totalSteps: Int, currentStartLat: Double, currentStartLon: Double): List<GeoPoint> {
        if (totalSteps == 0) {
            return listOf(GeoPoint(currentStartLat, currentStartLon))
        }

        // Reset state
        currentAngle = 0.0
        accumulatedDistance = 0.0

        val existingPoints = mutableListOf<GeoPoint>()
        existingPoints.add(GeoPoint(currentStartLat, currentStartLon))

        // Use updateTrail logic to generate all points
        val result = updateTrail(existingPoints, totalSteps, 0, currentStartLat, currentStartLon)
        return result.points
    }

    /**
     * Add new points to existing trail - EXACT original updateTrailPoints logic
     */
    fun updateTrail(
        existingPoints: MutableList<GeoPoint>,
        newSteps: Int,
        lastProcessedSteps: Int,
        currentStartLat: Double,
        currentStartLon: Double
    ): UpdateResult {
        if (newSteps <= 0) {
            return UpdateResult(existingPoints, 0, lastProcessedSteps + newSteps)
        }

        // Initialize if empty
        if (existingPoints.isEmpty()) {
            existingPoints.add(GeoPoint(currentStartLat, currentStartLon))
            currentAngle = 0.0
            accumulatedDistance = 0.0
        }

        // Add new distance to accumulated distance
        val newDistance = newSteps * stepLengthMeters / 1000.0
        accumulatedDistance += newDistance

        // Calculate distance between points using CURRENT start location (like original)
        val distanceBetweenPoints = GeoUtils.calculateHaversineDistance(
            currentStartLon, currentStartLat,
            currentStartLon + SCALE, currentStartLat
        )

        // Calculate points based on accumulated distance
        val newPointsToAdd = (accumulatedDistance / distanceBetweenPoints).toInt()

        if (newPointsToAdd > 0) {
            // Subtract the distance we're about to use
            accumulatedDistance -= (newPointsToAdd * distanceBetweenPoints)

            // Get the last point as starting position
            val lastPoint = existingPoints.last()
            var lat = lastPoint.latitude
            var lon = lastPoint.longitude

            // Add new points - EXACT original logic
            repeat(newPointsToAdd) {
                lat += cos(currentAngle) * SCALE
                lon += sin(currentAngle) * SCALE
                existingPoints.add(GeoPoint(lat, lon))
                currentAngle += (Math.random() * ANGLE_VARIABILITY) - (ANGLE_VARIABILITY / 2.0)
            }
        }

        return UpdateResult(
            existingPoints,
            newPointsToAdd,
            lastProcessedSteps + newSteps
        )
    }

    data class UpdateResult(
        val points: List<GeoPoint>,
        val pointsAdded: Int,
        val newProcessedSteps: Int
    )
}
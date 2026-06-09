package com.example.phantomtrail.utils

import android.util.Log
import org.osmdroid.util.GeoPoint
import java.io.File
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * GPX exporter - matches original logic but with improved pace data
 */
class GpxExporter {
    companion object {
        private const val TAG = "GpxExporter"
        private const val TIME_GAP_THRESHOLD_SECONDS = 300L // 5 minutes
    }

    /**
     * Generate GPX file from trail points and timestamps
     */
    fun generateGpxFile(
        outputDir: File,
        points: List<GeoPoint>,
        timestamps: List<ZonedDateTime>,
        totalSteps: Int
    ): File {
        val effectiveTimestamps = if (timestamps.size < 2) {
            val now = ZonedDateTime.now()
            val startTime = now.minusSeconds(totalSteps.toLong()) // ~1 step per second
            (0 until totalSteps).map { startTime.plusSeconds(it.toLong()) }
        } else {
            timestamps
        }

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val trackpoints = if (points.isEmpty()) {
            generateFallbackTrackpoint(timestamps)
        } else {
            generateTrackpointsWithRealTiming(points, timestamps, totalSteps)
        }

        val startTime = timestamps.firstOrNull()?.format(DateTimeFormatter.ISO_INSTANT)
            ?: ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT)

        val gpxContent = createGpxContent(trackpoints, startTime)

        val file = File(outputDir, "phantom_trail_${System.currentTimeMillis()}.gpx")
        file.writeText(gpxContent)

        Log.d(TAG, "Generated GPX with ${points.size} points from $totalSteps steps")
        return file
    }


    /**
     * Build a (timestamp, GeoPoint) list spread along the trail by step index.
     * Used for EXIF tagging so photo positions match the GPX export exactly.
     */
    fun generateTimedTrackpoints(
        points: List<GeoPoint>,
        timestamps: List<ZonedDateTime>,
        totalSteps: Int
    ): List<Pair<ZonedDateTime, GeoPoint>> {
        if (points.isEmpty() || timestamps.isEmpty()) return emptyList()

        val distances = mutableListOf(0.0)
        for (i in 1 until points.size) {
            distances.add(distances.last() + GeoUtils.calculateHaversineDistance(
                points[i - 1].longitude, points[i - 1].latitude,
                points[i].longitude, points[i].latitude
            ))
        }
        val trailDistance = distances.last()

        if (timestamps.size < 2 || trailDistance < 0.000001 || totalSteps <= 0) {
            return timestamps.map { it to points.first() }
        }

        // Spread timestamps evenly from trail start (0%) to current position (100%)
        // so the first trackpoint is always at the original start point.
        val lastIndex = (timestamps.size - 1).coerceAtLeast(1)
        return timestamps.mapIndexed { i, ts ->
            val progress = (i.toDouble() / lastIndex).coerceIn(0.0, 1.0)
            ts to interpolateLocation(points, distances, progress * trailDistance)
        }
    }

    private fun generateTrackpointsWithRealTiming(
        points: List<GeoPoint>,
        timestamps: List<ZonedDateTime>,
        totalSteps: Int
    ): String {
        val distances = mutableListOf(0.0)
        for (i in 1 until points.size) {
            val dist = GeoUtils.calculateHaversineDistance(
                points[i-1].longitude, points[i-1].latitude,
                points[i].longitude, points[i].latitude
            )
            distances.add(distances.last() + dist)
        }
        val trailDistance = distances.last()

        if (timestamps.size < 2 || trailDistance < 0.000001 || totalSteps <= 0) {
            // Not enough data to spread trackpoints — emit single point at start
            return createTrackpoint(
                points.first().latitude,
                points.first().longitude,
                timestamps.first().format(DateTimeFormatter.ISO_INSTANT)
            )
        }

        // Spread timestamps evenly from the trail start (0%) to current position (100%).
        // This ensures the GPX always starts at the original start point regardless of how
        // many sessions the walk took.
        val lastIndex = (timestamps.size - 1).coerceAtLeast(1)

        val trackpoints = StringBuilder()
        val stepsPerTrackpoint = 10
        var lastTime = timestamps.first()
        var lastAddedIndex = 0

        for (i in 0 until timestamps.size step stepsPerTrackpoint) {
            val timestampIndex = i.coerceAtMost(timestamps.size - 1)
            val currentTime = timestamps[timestampIndex]

            // Skip if time gap is too large (pause detection), but advance lastTime
            // so subsequent steps after the pause are not also discarded
            val gap = Duration.between(lastTime, currentTime).seconds
            if (gap > TIME_GAP_THRESHOLD_SECONDS) {
                lastTime = currentTime
                continue
            }

            val progress = (i.toDouble() / lastIndex).coerceIn(0.0, 1.0)
            val targetDistance = progress * trailDistance
            val location = interpolateLocation(points, distances, targetDistance)

            trackpoints.append(createTrackpoint(
                location.latitude,
                location.longitude,
                currentTime.format(DateTimeFormatter.ISO_INSTANT)
            ))

            lastTime = currentTime
            lastAddedIndex = timestampIndex
        }

        // Add last point ONLY if it's close in time to the previous point
        val lastGap = Duration.between(lastTime, timestamps.last()).seconds
        if (lastGap <= 300 && lastAddedIndex < timestamps.size - 1) {
            trackpoints.append(createTrackpoint(
                points.last().latitude,
                points.last().longitude,
                timestamps.last().format(DateTimeFormatter.ISO_INSTANT)
            ))
        }

        Log.d(TAG, "Generated trackpoints with real pace variations")
        return trackpoints.toString()
    }

    private fun interpolateLocation(
        points: List<GeoPoint>,
        distances: List<Double>,
        targetDistance: Double
    ): GeoPoint {
        for (i in 1 until distances.size) {
            if (targetDistance <= distances[i]) {
                val segStart = distances[i-1]
                val segEnd = distances[i]
                val segLen = segEnd - segStart
                if (segLen < 0.000001) return points[i-1]

                val progress = (targetDistance - segStart) / segLen
                val lat = points[i-1].latitude + (points[i].latitude - points[i-1].latitude) * progress
                val lon = points[i-1].longitude + (points[i].longitude - points[i-1].longitude) * progress
                return GeoPoint(lat, lon)
            }
        }
        return points.last()
    }

    private fun generateFallbackTrackpoint(timestamps: List<ZonedDateTime>): String {
        val timeStr = timestamps.firstOrNull()?.format(DateTimeFormatter.ISO_INSTANT)
            ?: ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT)
        return createTrackpoint(0.0, 0.0, timeStr)
    }

    private fun createTrackpoint(lat: Double, lon: Double, time: String): String {
        return """   <trkpt lat="${"%.7f".format(lat)}" lon="${"%.7f".format(lon)}">
    <time>$time</time>
   </trkpt>
"""
    }

    private fun createGpxContent(trackpoints: String, startTime: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<gpx xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd" creator="PhantomTrail" version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
 <metadata>
  <time>$startTime</time>
 </metadata>
 <trk>
  <name>Phantom Trail</name>
  <type>running</type>
  <trkseg>
$trackpoints </trkseg>
 </trk>
</gpx>"""
    }
}
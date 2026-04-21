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


    private fun generateTrackpointsWithRealTiming(
        points: List<GeoPoint>,
        timestamps: List<ZonedDateTime>,
        totalSteps: Int
    ): String {
        val trackpoints = StringBuilder()
        val stepLength = 0.75 // meters - should come from settings

        // Create straight-line distance based on step count
        val theoreticalDistance = (totalSteps * stepLength) / 1000.0 // km

        val distances = mutableListOf(0.0)
        for (i in 1 until points.size) {
            val dist = GeoUtils.calculateHaversineDistance(
                points[i-1].longitude, points[i-1].latitude,
                points[i].longitude, points[i].latitude
            )
            distances.add(distances.last() + dist)
        }

        // Scale trail to match theoretical distance
        val trailDistance = distances.last()
        val scaleFactor = theoreticalDistance / trailDistance

        for (i in timestamps.indices) {
            val progress = if (timestamps.size > 1) {
                i.toDouble() / (timestamps.size - 1)
            } else 0.0

            val targetDistance = progress * trailDistance * scaleFactor
            val location = interpolateLocation(points, distances, targetDistance)

            trackpoints.append(createTrackpoint(
                location.latitude,
                location.longitude,
                timestamps[i].format(DateTimeFormatter.ISO_INSTANT)
            ))
        }

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
        return """"<?xml version="1.0" encoding="UTF-8"?>
<gpx xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd" creator="PhantomTrail" version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
 <metadata>
  <time>$startTime</time>
 </metadata>
 <trk>
  <name>Phantom Trail</name>
  <type>1</type>
  <trkseg>
$trackpoints </trkseg>
 </trk>
</gpx>"""

    }
}
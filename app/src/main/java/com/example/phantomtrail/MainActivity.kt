package com.example.phantomtrail

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.phantomtrail.ui.theme.PhantomTrailTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

private val Context.dataStore by preferencesDataStore(name = "step_counter")

class MainActivity : ComponentActivity(), SensorEventListener {

    private var running = false
    private lateinit var sensorManager: SensorManager
    private val stepsFlow = MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var initialStepCount = -1
    private var isInitialized = false

    // Track step timestamps and positions
    private val stepTimestamps = mutableListOf<ZonedDateTime>()
    private var sessionStartTime: ZonedDateTime? = null

    companion object {
        private val STEPS_KEY = intPreferencesKey("saved_steps")
        private val INITIAL_SENSOR_COUNT_KEY = intPreferencesKey("initial_sensor_count")
        private val TIMESTAMPS_KEY = stringPreferencesKey("step_timestamps")
        private val SESSION_START_KEY = stringPreferencesKey("session_start")

        // Simulated walk parameters (adjust for realism)
        private const val METERS_PER_STEP = 0.75 // Average step length
        private const val START_LAT = 2.9279088973999023 // MMU starting point
        private const val START_LON = 101.64179229736328
        private const val BASE_ELEVATION = 35.0 // Base elevation in meters
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Initialize session start time immediately
        if (sessionStartTime == null) {
            sessionStartTime = ZonedDateTime.now()
        }

        // Load data asynchronously
        scope.launch {
            try {
                val prefs = dataStore.data.first()
                val savedSteps = prefs[STEPS_KEY] ?: 0
                initialStepCount = prefs[INITIAL_SENSOR_COUNT_KEY] ?: -1
                stepsFlow.value = savedSteps

                // Load timestamps
                prefs[TIMESTAMPS_KEY]?.let { timestampsStr ->
                    stepTimestamps.clear()
                    timestampsStr.split(",").forEach { ts ->
                        if (ts.isNotBlank()) {
                            try {
                                stepTimestamps.add(ZonedDateTime.parse(ts))
                            } catch (e: Exception) {
                                // Skip invalid timestamps
                            }
                        }
                    }
                }

                prefs[SESSION_START_KEY]?.let {
                    try {
                        sessionStartTime = ZonedDateTime.parse(it)
                    } catch (e: Exception) {
                        sessionStartTime = ZonedDateTime.now()
                    }
                } ?: run {
                    sessionStartTime = ZonedDateTime.now()
                }

                isInitialized = true
            } catch (e: Exception) {
                // If DataStore fails, just initialize with defaults
                isInitialized = true
                sessionStartTime = ZonedDateTime.now()
            }
        }

        if (checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION), 1)
        }

        setContent {
            val steps by stepsFlow.collectAsState()

            PhantomTrailTheme {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    Text(
                        text = "steps",
                        color = Color(0xFF7B9E87),
                        fontSize = 30.sp
                    )
                    Text(
                        text = "$steps",
                        color = Color.White,
                        fontSize = 50.sp
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = { exportStepBasedGPX() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4A7C59)
                        )
                    ) {
                        Text(
                            text = "Export Step Trail",
                            color = Color.White,
                            fontSize = 18.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { exportRandomTrailGPX() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF5D8AA8) // Different color for random trail
                        )
                    ) {
                        Text(
                            text = "Generate Random Trail",
                            color = Color.White,
                            fontSize = 18.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { resetSteps() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF7B9E87)
                        )
                    ) {
                        Text(
                            text = "Reset Steps",
                            color = Color.White,
                            fontSize = 18.sp
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        running = true

        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        if (stepSensor == null) {
            Toast.makeText(this, "No Step Sensor Available!", Toast.LENGTH_SHORT).show()
        } else {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)

            if (stepSensor.type == Sensor.TYPE_STEP_DETECTOR) {
                Toast.makeText(this, "Using Step Detector", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        running = false
        sensorManager.unregisterListener(this)
        saveSteps()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || !isInitialized) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val totalSteps = event.values[0].toInt()

                if (initialStepCount == -1) {
                    initialStepCount = totalSteps - stepsFlow.value
                    scope.launch {
                        dataStore.edit { prefs ->
                            prefs[INITIAL_SENSOR_COUNT_KEY] = initialStepCount
                        }
                    }
                }

                val newStepCount = totalSteps - initialStepCount

                // Add timestamps for new steps
                while (stepsFlow.value < newStepCount) {
                    stepTimestamps.add(ZonedDateTime.now())
                    stepsFlow.value++
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                stepTimestamps.add(ZonedDateTime.now())
                stepsFlow.value++
            }
        }
    }

    private fun saveSteps() {
        scope.launch {
            try {
                dataStore.edit { prefs ->
                    prefs[STEPS_KEY] = stepsFlow.value
                    prefs[INITIAL_SENSOR_COUNT_KEY] = initialStepCount
                    prefs[TIMESTAMPS_KEY] = stepTimestamps.joinToString(",")
                    sessionStartTime?.let { prefs[SESSION_START_KEY] = it.toString() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to save data: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun resetSteps() {
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepSensor != null) {
            sensorManager.unregisterListener(this)
            initialStepCount = -1
            stepsFlow.value = 0
            stepTimestamps.clear()
            sessionStartTime = ZonedDateTime.now()

            scope.launch {
                dataStore.edit { prefs ->
                    prefs[STEPS_KEY] = 0
                    prefs[INITIAL_SENSOR_COUNT_KEY] = -1
                    prefs[TIMESTAMPS_KEY] = ""
                    prefs[SESSION_START_KEY] = sessionStartTime.toString()
                }
            }

            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            stepsFlow.value = 0
            stepTimestamps.clear()
            sessionStartTime = ZonedDateTime.now()

            scope.launch {
                dataStore.edit { prefs ->
                    prefs[STEPS_KEY] = 0
                    prefs[TIMESTAMPS_KEY] = ""
                    prefs[SESSION_START_KEY] = sessionStartTime.toString()
                }
            }
        }
    }

    private fun exportStepBasedGPX() {
        val timestampCount = stepTimestamps.size
        val stepCount = stepsFlow.value

        if (stepCount == 0 || timestampCount < 2) {
            Toast.makeText(
                this,
                "Not enough step data! Walk around first. Steps: $stepCount",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        scope.launch {
            try {
                val gpxFile = generateStepBasedGPX()
                withContext(Dispatchers.Main) {
                    shareGPXFile(gpxFile, "Step Trail")
                    Toast.makeText(
                        this@MainActivity,
                        "Step trail exported with $stepCount steps",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Error exporting step trail: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun exportRandomTrailGPX() {
        scope.launch {
            try {
                val gpxFile = generateRandomTrailGPX()
                withContext(Dispatchers.Main) {
                    shareGPXFile(gpxFile, "Random Trail")
                    Toast.makeText(
                        this@MainActivity,
                        "Random trail generated successfully",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Error generating random trail: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // Haversine distance calculation function
    private fun calculateHaversineDistance(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val R = 6371.0 // Earth's radius in km

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    // Generate realistic elevation variation
    private fun generateElevation(index: Int, baseElevation: Double): Double {
        // Add some variation to make it look realistic
        val variation = sin(index * 0.1) * 5.0 + (Math.random() * 4.0 - 2.0)
        return baseElevation + variation
    }

    private fun generateStepBasedGPX(): File {
        try {
            // Use the random trail generation algorithm
            val SCALE = 0.0001
            var angle = 0.0
            val angleVariability = Math.PI / 7

            // Calculate parameters based on step data
            val totalSteps = stepsFlow.value
            val totalDistance = totalSteps * METERS_PER_STEP / 1000.0 // Convert to km

            // Use step timestamps to calculate duration
            val sessionDuration = if (stepTimestamps.size > 1) {
                val start = stepTimestamps.first()
                val end = stepTimestamps.last()
                java.time.Duration.between(start, end).toMinutes().toDouble()
            } else {
                // Fallback: estimate based on step count
                totalSteps * 0.05 // Assuming ~0.05 minutes per step
            }

            val startLat = START_LAT
            val startLon = START_LON

            // Calculate distance between points for scaling
            val distanceBetweenPoints = calculateHaversineDistance(
                startLon, startLat,
                startLon + SCALE, startLat
            )

            val speed = (sessionDuration * 60) / (totalDistance / distanceBetweenPoints)

            // Initialize coordinates using circular buffer
            val lat = doubleArrayOf(startLat, 0.0)
            val lon = doubleArrayOf(startLon, 0.0)

            val trackpoints = StringBuilder()
            var currentTime = stepTimestamps.lastOrNull() ?: ZonedDateTime.now()
            var distance = 0.0
            var i = 1
            var pointIndex = 0

            // Generate points until we cover the total distance
            while (distance < totalDistance && i < 10000) {
                lat[i % 2] = lat[(i + 1) % 2] + cos(angle) * SCALE
                lon[i % 2] = lon[(i + 1) % 2] + sin(angle) * SCALE

                distance += calculateHaversineDistance(
                    lon[i % 2], lat[i % 2],
                    lon[(i + 1) % 2], lat[(i + 1) % 2]
                )

                i++

                // Move backwards in time for the trail
                currentTime = currentTime.minusSeconds(speed.toLong())

                val timeStr = currentTime.format(DateTimeFormatter.ISO_INSTANT)
                val elevation = generateElevation(pointIndex, BASE_ELEVATION)

                trackpoints.insert(0, """   <trkpt lat="${"%.7f".format(lat[i % 2])}" lon="${"%.7f".format(lon[i % 2])}">
    <ele>${"%.1f".format(elevation)}</ele>
    <time>$timeStr</time>
   </trkpt>
""")

                pointIndex++

                // Add random angle variation
                angle += (Math.random() * angleVariability) - (angleVariability / 2.0)
            }

            val startTime = currentTime.format(DateTimeFormatter.ISO_INSTANT)

            val gpxContent = """<?xml version="1.0" encoding="UTF-8"?>
<gpx xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd http://www.garmin.com/xmlschemas/GpxExtensions/v3 http://www.garmin.com/xmlschemas/GpxExtensionsv3.xsd http://www.garmin.com/xmlschemas/TrackPointExtension/v1 http://www.garmin.com/xmlschemas/TrackPointExtensionv1.xsd" creator="StravaGPX" version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1" xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3">
 <metadata>
  <time>$startTime</time>
 </metadata>
 <trk>
  <name>Step Trail</name>
  <type>running</type>
  <trkseg>
$trackpoints </trkseg>
 </trk>
</gpx>"""

            // Write to file
            val dir = externalCacheDir ?: cacheDir
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val file = File(dir, "step_trail_${System.currentTimeMillis()}.gpx")
            file.writeText(gpxContent)

            return file
        } catch (e: Exception) {
            e.printStackTrace()
            throw Exception("Failed to generate step trail GPX: ${e.message}", e)
        }
    }

    private fun generateRandomTrailGPX(): File {
        // Default parameters (you can make these configurable)
        val totalDistance = 1.932782 // km
        val startLat = 2.9279088973999023
        val startLon = 101.64179229736328
        val durationMinutes = 49.7238
        val endTime = ZonedDateTime.now()

        // Random trail generation algorithm
        val SCALE = 0.0001
        var angle = 0.0
        val angleVariability = Math.PI / 7

        val distanceBetweenPoints = calculateHaversineDistance(
            startLon, startLat,
            startLon + SCALE, startLat
        )

        val durationSeconds = 60 * durationMinutes
        val speed = durationSeconds / (totalDistance / distanceBetweenPoints)

        val lat = doubleArrayOf(startLat, 0.0)
        val lon = doubleArrayOf(startLon, 0.0)

        val trackpoints = StringBuilder()
        var currentTime = endTime
        var distance = 0.0
        var i = 1
        var pointIndex = 0

        while (distance < totalDistance && i < 10000) {
            lat[i % 2] = lat[(i + 1) % 2] + cos(angle) * SCALE
            lon[i % 2] = lon[(i + 1) % 2] + sin(angle) * SCALE

            distance += calculateHaversineDistance(
                lon[i % 2], lat[i % 2],
                lon[(i + 1) % 2], lat[(i + 1) % 2]
            )

            i++
            currentTime = currentTime.minusSeconds(speed.toLong())

            val timeStr = currentTime.format(DateTimeFormatter.ISO_INSTANT)
            val elevation = generateElevation(pointIndex, BASE_ELEVATION)

            trackpoints.insert(0, """   <trkpt lat="${"%.7f".format(lat[i % 2])}" lon="${"%.7f".format(lon[i % 2])}">
    <ele>${"%.1f".format(elevation)}</ele>
    <time>$timeStr</time>
   </trkpt>
""")

            pointIndex++
            angle += (Math.random() * angleVariability) - (angleVariability / 2.0)
        }

        val startTime = currentTime.format(DateTimeFormatter.ISO_INSTANT)

        val gpxContent = """<?xml version="1.0" encoding="UTF-8"?>
<gpx xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd http://www.garmin.com/xmlschemas/GpxExtensions/v3 http://www.garmin.com/xmlschemas/GpxExtensionsv3.xsd http://www.garmin.com/xmlschemas/TrackPointExtension/v1 http://www.garmin.com/xmlschemas/TrackPointExtensionv1.xsd" creator="StravaGPX" version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1" xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3">
 <metadata>
  <time>$startTime</time>
 </metadata>
 <trk>
  <name>Random Phantom Trail</name>
  <type>running</type>
  <trkseg>
$trackpoints </trkseg>
 </trk>
</gpx>"""

        val dir = externalCacheDir ?: cacheDir
        val file = File(dir, "random_trail_${System.currentTimeMillis()}.gpx")
        file.writeText(gpxContent)

        return file
    }

    private fun shareGPXFile(file: File, trailType: String) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$trailType GPX File")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Share $trailType GPX"))
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback: show file location
            Toast.makeText(this, "$trailType saved to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }
}
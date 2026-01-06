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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    private var isTracking = false
    private lateinit var sensorManager: SensorManager
    private val stepsFlow = MutableStateFlow(0)
    private val isTrackingFlow = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var initialStepCount = -1
    private var isInitialized = false

    // Track step timestamps - only when actively tracking
    private val stepTimestamps = mutableListOf<ZonedDateTime>()
    private var sessionStartTime: ZonedDateTime? = null

    companion object {
        private val STEPS_KEY = intPreferencesKey("saved_steps")
        private val INITIAL_SENSOR_COUNT_KEY = intPreferencesKey("initial_sensor_count")
        private val TIMESTAMPS_KEY = stringPreferencesKey("step_timestamps")
        private val SESSION_START_KEY = stringPreferencesKey("session_start")
        private val IS_TRACKING_KEY = booleanPreferencesKey("is_tracking")

        // Simulated walk parameters
        private const val START_LAT = 2.9279088973999023
        private const val START_LON = 101.64179229736328

        // Step length in meters (realistic average)
        private const val STEP_LENGTH_METERS = 0.75
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Load data asynchronously
        scope.launch {
            try {
                val prefs = dataStore.data.first()
                val savedSteps = prefs[STEPS_KEY] ?: 0
                initialStepCount = prefs[INITIAL_SENSOR_COUNT_KEY] ?: -1
                isTracking = prefs[IS_TRACKING_KEY] ?: false
                stepsFlow.value = savedSteps
                isTrackingFlow.value = isTracking

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
            val tracking by isTrackingFlow.collectAsState()

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

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (tracking) "Recording..." else "Stopped",
                        color = if (tracking) Color(0xFF4A7C59) else Color.Gray,
                        fontSize = 24.sp
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // Start/Stop Row
                    Row {
                        Button(
                            onClick = { startTracking() },
                            enabled = !tracking,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4A7C59),
                                disabledContainerColor = Color.Gray
                            )
                        ) {
                            Text(
                                text = "Start",
                                color = Color.White,
                                fontSize = 18.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Button(
                            onClick = { stopTracking() },
                            enabled = tracking,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD32F2F),
                                disabledContainerColor = Color.Gray
                            )
                        ) {
                            Text(
                                text = "Stop",
                                color = Color.White,
                                fontSize = 18.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = { exportStepBasedGPX() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF5D8AA8)
                        )
                    ) {
                        Text(
                            text = "Export GPX Trail",
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

    private fun startTracking() {
        if (!isTracking) {
            isTracking = true
            isTrackingFlow.value = true

            scope.launch {
                dataStore.edit { prefs ->
                    prefs[IS_TRACKING_KEY] = true
                }
            }

            Toast.makeText(this, "Started tracking", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopTracking() {
        if (isTracking) {
            isTracking = false
            isTrackingFlow.value = false

            scope.launch {
                dataStore.edit { prefs ->
                    prefs[IS_TRACKING_KEY] = false
                }
            }

            Toast.makeText(this, "Stopped tracking", Toast.LENGTH_SHORT).show()
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
        if (!running || !isInitialized || !isTracking) return

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

                // Add timestamps for new steps ONLY when tracking
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
                    prefs[IS_TRACKING_KEY] = isTracking
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
            isTracking = false
            isTrackingFlow.value = false

            scope.launch {
                dataStore.edit { prefs ->
                    prefs[STEPS_KEY] = 0
                    prefs[INITIAL_SENSOR_COUNT_KEY] = -1
                    prefs[TIMESTAMPS_KEY] = ""
                    prefs[IS_TRACKING_KEY] = false
                    prefs[SESSION_START_KEY] = sessionStartTime.toString()
                }
            }

            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            stepsFlow.value = 0
            stepTimestamps.clear()
            sessionStartTime = ZonedDateTime.now()
            isTracking = false
            isTrackingFlow.value = false

            scope.launch {
                dataStore.edit { prefs ->
                    prefs[STEPS_KEY] = 0
                    prefs[TIMESTAMPS_KEY] = ""
                    prefs[IS_TRACKING_KEY] = false
                    prefs[SESSION_START_KEY] = sessionStartTime.toString()
                }
            }
        }

        Toast.makeText(this, "Steps reset", Toast.LENGTH_SHORT).show()
    }

    private fun exportStepBasedGPX() {
        val timestampCount = stepTimestamps.size
        val stepCount = stepsFlow.value

        if (stepCount == 0 || timestampCount < 2) {
            Toast.makeText(
                this,
                "Not enough step data! Start tracking and walk around first.",
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
                        "GPX trail exported with $stepCount steps",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Error exporting trail: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // Haversine distance calculation
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

    private fun generateStepBasedGPX(): File {
        try {
            val SCALE = 0.0001
            var angle = 0.0
            val angleVariability = Math.PI / 7

            val totalSteps = stepsFlow.value

            // Calculate total distance based on step count
            val totalDistance = totalSteps * STEP_LENGTH_METERS / 1000.0 // Convert to km

            val startLat = START_LAT
            val startLon = START_LON

            // Calculate distance between coordinate points
            val distanceBetweenPoints = calculateHaversineDistance(
                startLon, startLat,
                startLon + SCALE, startLat
            )

            // Calculate how many coordinate points we need
            val numberOfPoints = (totalDistance / distanceBetweenPoints).toInt()

            // Calculate ACTIVE tracking time (only time between consecutive timestamps)
            var totalActiveSeconds = 0L
            for (i in 1 until stepTimestamps.size) {
                val prevTime = stepTimestamps[i - 1]
                val currTime = stepTimestamps[i]
                val gap = java.time.Duration.between(prevTime, currTime).seconds

                // Only count gaps less than 5 minutes as active time
                // Anything longer is considered a pause
                if (gap < 300) {
                    totalActiveSeconds += gap
                }
            }

            // Time interval between trackpoints (using only active time)
            val secondsPerPoint = if (numberOfPoints > 1 && totalActiveSeconds > 0) {
                totalActiveSeconds.toDouble() / numberOfPoints
            } else {
                1.0
            }

            val lat = doubleArrayOf(startLat, 0.0)
            val lon = doubleArrayOf(startLon, 0.0)

            val trackpoints = StringBuilder()

            // Start from the first recorded step timestamp
            var currentTime = stepTimestamps.first()
            var i = 1

            // Generate exactly the number of points needed for the distance
            repeat(numberOfPoints) {
                lat[i % 2] = lat[(i + 1) % 2] + cos(angle) * SCALE
                lon[i % 2] = lon[(i + 1) % 2] + sin(angle) * SCALE

                i++

                val timeStr = currentTime.format(DateTimeFormatter.ISO_INSTANT)

                trackpoints.append("""   <trkpt lat="${"%.7f".format(lat[i % 2])}" lon="${"%.7f".format(lon[i % 2])}">
    <time>$timeStr</time>
   </trkpt>
""")

                // Move forward in time by the calculated interval
                currentTime = currentTime.plusSeconds(secondsPerPoint.toLong())

                // Add angle variation for natural path
                angle += (Math.random() * angleVariability) - (angleVariability / 2.0)
            }

            val startTime = stepTimestamps.first().format(DateTimeFormatter.ISO_INSTANT)

            val gpxContent = """<?xml version="1.0" encoding="UTF-8"?>
<gpx xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd http://www.garmin.com/xmlschemas/GpxExtensions/v3 http://www.garmin.com/xmlschemas/GpxExtensionsv3.xsd http://www.garmin.com/xmlschemas/TrackPointExtension/v1 http://www.garmin.com/xmlschemas/TrackPointExtensionv1.xsd" creator="StravaGPX" version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1" xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3">
 <metadata>
  <time>$startTime</time>
 </metadata>
 <trk>
  <n>Phantom Trail</n>
  <type>9</type>
  <trkseg>
$trackpoints </trkseg>
 </trk>
</gpx>"""

            val dir = externalCacheDir ?: cacheDir
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val file = File(dir, "phantom_trail_${System.currentTimeMillis()}.gpx")
            file.writeText(gpxContent)

            return file
        } catch (e: Exception) {
            e.printStackTrace()
            throw Exception("Failed to generate GPX: ${e.message}", e)
        }
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
            Toast.makeText(this, "$trailType saved to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }
}
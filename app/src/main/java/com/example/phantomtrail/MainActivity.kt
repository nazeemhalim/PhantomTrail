package com.example.phantomtrail

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
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

// Foreground Service for Step Counting
class StepCounterService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var initialStepCount = -1
    private var currentSteps = 0
    private var isInitialized = false
    private val stepTimestamps = mutableListOf<ZonedDateTime>()

    private var notificationManager: NotificationManager? = null

    companion object {
        const val CHANNEL_ID = "StepCounterChannel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "StepCounterService"

        private val STEPS_KEY = intPreferencesKey("saved_steps")
        private val INITIAL_SENSOR_COUNT_KEY = intPreferencesKey("initial_sensor_count")
        private val TIMESTAMPS_KEY = stringPreferencesKey("step_timestamps")

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"

        val isRunning = MutableStateFlow(false)
        val currentStepCount = MutableStateFlow(0)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()

        // Load saved data
        scope.launch {
            try {
                val prefs = dataStore.data.first()
                currentSteps = prefs[STEPS_KEY] ?: 0
                currentStepCount.value = currentSteps
                initialStepCount = prefs[INITIAL_SENSOR_COUNT_KEY] ?: -1

                // Load timestamps
                prefs[TIMESTAMPS_KEY]?.let { timestampsStr ->
                    stepTimestamps.clear()
                    timestampsStr.split(",").forEach { ts ->
                        if (ts.isNotBlank()) {
                            try {
                                stepTimestamps.add(ZonedDateTime.parse(ts))
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing timestamp: ${e.message}")
                            }
                        }
                    }
                }

                isInitialized = true
                Log.d(TAG, "Data loaded: $currentSteps steps")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading data: ${e.message}")
                isInitialized = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
        }

        return START_STICKY
    }

    private fun startTracking() {
        try {
            Log.d(TAG, "Starting tracking")

            // Start foreground service with notification
            val notification = createNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            isRunning.value = true

            // Register sensor listener
            stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

            stepSensor?.let {
                val registered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                Log.d(TAG, "Sensor registered: $registered, Type: ${it.type}")
            } ?: run {
                Log.e(TAG, "No step sensor available!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting tracking: ${e.message}", e)
            stopSelf()
        }
    }

    private fun stopTracking() {
        try {
            Log.d(TAG, "Stopping tracking")

            sensorManager.unregisterListener(this)
            saveSteps()

            isRunning.value = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tracking: ${e.message}", e)
            stopSelf()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isInitialized) return

        try {
            when (event.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> {
                    val totalSteps = event.values[0].toInt()

                    if (initialStepCount == -1) {
                        initialStepCount = totalSteps - currentSteps
                        scope.launch {
                            dataStore.edit { prefs ->
                                prefs[INITIAL_SENSOR_COUNT_KEY] = initialStepCount
                            }
                        }
                    }

                    val newStepCount = totalSteps - initialStepCount

                    while (currentSteps < newStepCount) {
                        stepTimestamps.add(ZonedDateTime.now())
                        currentSteps++
                        currentStepCount.value = currentSteps
                    }

                    saveSteps()
                    updateNotification()
                }
                Sensor.TYPE_STEP_DETECTOR -> {
                    stepTimestamps.add(ZonedDateTime.now())
                    currentSteps++
                    currentStepCount.value = currentSteps
                    saveSteps()
                    updateNotification()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onSensorChanged: ${e.message}", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Step Counter",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracks your steps in the background"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, StepCounterService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phantom Trail")
            .setContentText("Steps: $currentSteps")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        try {
            notificationManager?.notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification: ${e.message}")
        }
    }

    private fun saveSteps() {
        scope.launch {
            try {
                dataStore.edit { prefs ->
                    prefs[STEPS_KEY] = currentSteps
                    prefs[INITIAL_SENSOR_COUNT_KEY] = initialStepCount
                    prefs[TIMESTAMPS_KEY] = stepTimestamps.joinToString(",")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving steps: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        saveSteps()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// Main Activity
class MainActivity : ComponentActivity() {

    private val stepsFlow = MutableStateFlow(0)
    private val isTrackingFlow = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val stepTimestamps = mutableListOf<ZonedDateTime>()

    companion object {
        private const val TAG = "MainActivity"
        private val STEPS_KEY = intPreferencesKey("saved_steps")
        private val INITIAL_SENSOR_COUNT_KEY = intPreferencesKey("initial_sensor_count")
        private val TIMESTAMPS_KEY = stringPreferencesKey("step_timestamps")
        private val SESSION_START_KEY = stringPreferencesKey("session_start")

        private const val START_LAT = 2.9279088973999023
        private const val START_LON = 101.64179229736328
        private const val STEP_LENGTH_METERS = 0.75
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }

        if (checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION), 1)
        }

        // Load saved steps into the service flow immediately
        scope.launch {
            try {
                val prefs = dataStore.data.first()
                val savedSteps = prefs[STEPS_KEY] ?: 0
                StepCounterService.currentStepCount.value = savedSteps
                Log.d(TAG, "Loaded saved steps on startup: $savedSteps")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading steps on startup: ${e.message}")
            }
        }

        loadData()

        setContent {
            // Observe service state flows
            val serviceSteps by StepCounterService.currentStepCount.collectAsState()
            val serviceTracking by StepCounterService.isRunning.collectAsState()

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
                        text = "$serviceSteps",
                        color = Color.White,
                        fontSize = 50.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (serviceTracking) "Recording..." else "Stopped",
                        color = if (serviceTracking) Color(0xFF4A7C59) else Color.Gray,
                        fontSize = 24.sp
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // Start/Pause Button
                    Button(
                        onClick = {
                            if (serviceTracking) {
                                pauseTracking()
                            } else {
                                startTracking()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (serviceTracking) Color(0xFFFFA500) else Color(0xFF4A7C59)
                        )
                    ) {
                        Text(
                            text = if (serviceTracking) "Pause" else "Start",
                            color = Color.White,
                            fontSize = 18.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Stop Button (resets steps)
                    Button(
                        onClick = { stopAndResetSteps() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD32F2F)
                        )
                    ) {
                        Text(
                            text = "Stop & Reset",
                            color = Color.White,
                            fontSize = 18.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = { exportStepBasedGPX() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4A7C59)
                        )
                    ) {
                        Text(
                            text = "Export GPX",
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

        // Reload saved steps into the service flow
        scope.launch {
            try {
                val prefs = dataStore.data.first()
                val savedSteps = prefs[STEPS_KEY] ?: 0
                StepCounterService.currentStepCount.value = savedSteps
                Log.d(TAG, "Reloaded saved steps on resume: $savedSteps")
            } catch (e: Exception) {
                Log.e(TAG, "Error reloading steps on resume: ${e.message}")
            }
        }

        loadData()
    }

    private fun loadData() {
        scope.launch {
            try {
                val prefs = dataStore.data.first()
                val savedSteps = prefs[STEPS_KEY] ?: 0

                stepsFlow.value = savedSteps

                stepTimestamps.clear()
                prefs[TIMESTAMPS_KEY]?.let { timestampsStr ->
                    timestampsStr.split(",").forEach { ts ->
                        if (ts.isNotBlank()) {
                            try {
                                stepTimestamps.add(ZonedDateTime.parse(ts))
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing timestamp: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading data: ${e.message}")
            }
        }
    }

    private fun startTracking() {
        val serviceIntent = Intent(this, StepCounterService::class.java).apply {
            action = StepCounterService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Toast.makeText(this, "Started tracking", Toast.LENGTH_SHORT).show()
    }

    private fun pauseTracking() {
        val serviceIntent = Intent(this, StepCounterService::class.java).apply {
            action = StepCounterService.ACTION_STOP
        }
        startService(serviceIntent)

        scope.launch {
            kotlinx.coroutines.delay(500)
            loadData()
        }

        Toast.makeText(this, "Paused tracking", Toast.LENGTH_SHORT).show()
    }

    private fun stopAndResetSteps() {
        // Stop service if running
        if (StepCounterService.isRunning.value) {
            val serviceIntent = Intent(this, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_STOP
            }
            startService(serviceIntent)
        }

        // Reset all data
        StepCounterService.currentStepCount.value = 0
        stepTimestamps.clear()

        scope.launch {
            dataStore.edit { prefs ->
                prefs[STEPS_KEY] = 0
                prefs[INITIAL_SENSOR_COUNT_KEY] = -1
                prefs[TIMESTAMPS_KEY] = ""
                prefs[SESSION_START_KEY] = ZonedDateTime.now().toString()
            }
        }

        Toast.makeText(this, "Stopped and reset steps", Toast.LENGTH_SHORT).show()
    }

    private fun exportStepBasedGPX() {
        val stepCount = StepCounterService.currentStepCount.value
        val timestampCount = stepTimestamps.size

        if (stepCount == 0 || timestampCount < 2) {
            AlertDialog.Builder(this)
                .setMessage("Start counting and export again")
                .setTitle("Not Enough Steps")
                .create()
                .show()
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
                Log.e(TAG, "Error exporting GPX: ${e.message}", e)
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

    private fun calculateHaversineDistance(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun generateStepBasedGPX(): File {
        val SCALE = 0.0001
        var angle = 0.0
        val angleVariability = Math.PI / 7
        val totalSteps = StepCounterService.currentStepCount.value
        val totalDistance = totalSteps * STEP_LENGTH_METERS / 1000.0
        val startLat = START_LAT
        val startLon = START_LON

        val distanceBetweenPoints = calculateHaversineDistance(
            startLon, startLat,
            startLon + SCALE, startLat
        )

        val numberOfPoints = (totalDistance / distanceBetweenPoints).toInt()

        var totalActiveSeconds = 0L
        for (i in 1 until stepTimestamps.size) {
            val prevTime = stepTimestamps[i - 1]
            val currTime = stepTimestamps[i]
            val gap = java.time.Duration.between(prevTime, currTime).seconds
            if (gap < 300) {
                totalActiveSeconds += gap
            }
        }

        val secondsPerPoint = if (numberOfPoints > 1 && totalActiveSeconds > 0) {
            totalActiveSeconds.toDouble() / numberOfPoints
        } else {
            1.0
        }

        val lat = doubleArrayOf(startLat, 0.0)
        val lon = doubleArrayOf(startLon, 0.0)
        val trackpoints = StringBuilder()
        var currentTime = stepTimestamps.first()
        var i = 1

        repeat(numberOfPoints) {
            lat[i % 2] = lat[(i + 1) % 2] + cos(angle) * SCALE
            lon[i % 2] = lon[(i + 1) % 2] + sin(angle) * SCALE
            i++

            val timeStr = currentTime.format(DateTimeFormatter.ISO_INSTANT)
            trackpoints.append("""   <trkpt lat="${"%.7f".format(lat[i % 2])}" lon="${"%.7f".format(lon[i % 2])}">
    <time>$timeStr</time>
   </trkpt>
""")
            currentTime = currentTime.plusSeconds(secondsPerPoint.toLong())
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
            Log.e(TAG, "Error sharing GPX: ${e.message}")
            Toast.makeText(this, "$trailType saved to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }
}
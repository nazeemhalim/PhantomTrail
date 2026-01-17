package com.example.phantomtrail

import android.Manifest
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.text.InputType
import android.util.Log
import android.widget.EditText
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
import androidx.compose.runtime.MutableState
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
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.phantomtrail.ui.theme.PhantomTrailTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
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
    private var wakeLock: PowerManager.WakeLock? = null

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

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()

        // Load saved data
        scope.launch {
            try {
                val prefs = dataStore.data.first()
                currentSteps = prefs[STEPS_KEY] ?: 0
                currentStepCount.value = currentSteps

                // IMPORTANT: Always reset initialStepCount on service creation
                // This handles cases where phone rebooted while tracking
                // The sensor counter has reset, so we need to recalculate the baseline
                initialStepCount = -1

                Log.d(TAG, "Service onCreate - Steps: $currentSteps, Reset initialStepCount to -1")

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
                Log.d(TAG, "Service initialized with $currentSteps steps")
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
            Log.d(TAG, "Starting tracking - Current steps: $currentSteps, Initial sensor count: $initialStepCount")

            // Acquire partial wake lock to ensure sensor updates
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PhantomTrail::StepCounterWakeLock"
            ).apply {
                acquire(10*60*60*1000L /*10 hours*/)
            }
            Log.d(TAG, "Wake lock acquired")

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
                // Use SENSOR_DELAY_NORMAL for step counter (best balance)
                // SENSOR_DELAY_FASTEST can drain battery and step counter batches anyway
                val delay = if (it.type == Sensor.TYPE_STEP_COUNTER) {
                    SensorManager.SENSOR_DELAY_NORMAL
                } else {
                    SensorManager.SENSOR_DELAY_FASTEST
                }
                val registered = sensorManager.registerListener(this, it, delay)
                Log.d(TAG, "Sensor registered: $registered, Type: ${it.type}, Delay: $delay")
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

            // Release wake lock
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Wake lock released")
                }
            }
            wakeLock = null

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
                    Log.d(TAG, "Sensor total steps: $totalSteps, Initial: $initialStepCount, Current saved: $currentSteps")

                    // Check if sensor has reset (happens after reboot)
                    // If sensor value is less than our saved initial count, sensor must have reset
                    if (initialStepCount != -1 && totalSteps < initialStepCount) {
                        Log.d(TAG, "Sensor reset detected! Resetting initialStepCount. SensorValue: $totalSteps < InitialCount: $initialStepCount")
                        initialStepCount = -1
                    }

                    if (initialStepCount == -1) {
                        // First time starting OR after sensor reset
                        // Set initial count so that sensor value - initial = current saved steps
                        initialStepCount = totalSteps - currentSteps
                        Log.d(TAG, "Set initial sensor count: $initialStepCount (totalSteps: $totalSteps - currentSteps: $currentSteps)")

                        scope.launch {
                            dataStore.edit { prefs ->
                                prefs[INITIAL_SENSOR_COUNT_KEY] = initialStepCount
                            }
                        }
                    }

                    val newStepCount = totalSteps - initialStepCount
                    Log.d(TAG, "Calculated new step count: $newStepCount (totalSteps: $totalSteps - initialStepCount: $initialStepCount)")

                    // Add timestamps for new steps
                    while (currentSteps < newStepCount) {
                        stepTimestamps.add(ZonedDateTime.now())
                        currentSteps++
                        currentStepCount.value = currentSteps
                        Log.d(TAG, "Step incremented to: $currentSteps")
                    }

                    saveSteps()
                    updateNotification()
                }
                Sensor.TYPE_STEP_DETECTOR -> {
                    stepTimestamps.add(ZonedDateTime.now())
                    currentSteps++
                    currentStepCount.value = currentSteps
                    Log.d(TAG, "Step detector - incremented to: $currentSteps")
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
                Log.d(TAG, "Saved steps: $currentSteps, initial sensor: $initialStepCount")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving steps: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)

        // Release wake lock if held
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released in onDestroy")
            }
        }

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

        private val STEP_LENGTH_KEY = doublePreferencesKey("step_length_meters")
        private const val START_LAT = 2.9279088973999023
        private const val START_LON = 101.64179229736328

        private val stepLengthMeters = MutableStateFlow<Double> (0.75)

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }

        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
        }

        // Load saved steps into the service flow immediately
        scope.launch {
            try {
                val prefs = dataStore.data.first()
                val savedSteps = prefs[STEPS_KEY] ?: 0

                stepLengthMeters.value = prefs[STEP_LENGTH_KEY] ?: 0.75
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
            val stepLength by stepLengthMeters.collectAsState()

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

                    Text(
                        text = String.format("%.2f km", serviceSteps * stepLength / 1000.0),
                        color = Color.Gray,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (serviceTracking) "recording..." else "stopped",
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
                            text = if (serviceTracking) "pause" else "start",
                            color = Color.White,
                            fontSize = 18.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Stop Button (resets steps)
                    Button(
                        onClick = {confirmStop()},
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD32F2F)
                        )
                    ) {
                        Text(
                            text = "stop",
                            color = Color.White,
                            fontSize = 18.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    // Set Step Length Button
                    Button(
                        onClick = {setStepLength()},
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF606060)
                        )
                    ) {
                        Text(
                            text = "step length",
                            color = Color.White,
                            fontSize = 18.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { exportStepBasedGPX() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF606060)
                        )
                    ) {
                        Text(
                            text = "export",
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
                val stepLengthMeters = prefs[STEP_LENGTH_KEY] ?: 0.75

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
            delay(500)
            loadData()
        }

        Toast.makeText(this, "Paused tracking", Toast.LENGTH_SHORT).show()
    }

    private fun confirmStop(){
        AlertDialog.Builder(this@MainActivity)
            .setMessage("Do you want to stop and reset steps?")
            .setTitle("Stop & Reset")
            .setPositiveButton("Yes") { dialog, which ->
                stopAndResetSteps()
            }
            .setNegativeButton("No") { dialog, which ->
            }
            .create()
            .show()
    }

     private fun setStepLength(){
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        AlertDialog.Builder(this@MainActivity)
            .setTitle("Step Length")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val value = input.text.toString()

                if (value.isNotEmpty()) {
                    val number = value.toDouble()
                    stepLengthMeters.value = number

                    scope.launch {
                        dataStore.edit { prefs ->
                            prefs[STEP_LENGTH_KEY] = number
                        }
                    }

                    Toast.makeText(this, "Value: $number", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Input cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL") { dialog, which ->
            }
            .create()
            .show()
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
        scope.launch {
            try {
                // Reload timestamps from DataStore
                val prefs = dataStore.data.first()
                val stepCount = prefs[STEPS_KEY] ?: 0

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

                Log.d(TAG, "Loaded ${stepTimestamps.size} timestamps for GPX export")

                val timestampCount = stepTimestamps.size

                if (stepCount == 0 || timestampCount < 2) {
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage("Start counting and export again")
                            .setTitle("Not Enough Steps")
                            .create()
                            .show()
                    }
                    return@launch
                }

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
        val totalDistance = totalSteps * stepLengthMeters.value / 1000.0
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
            val gap = Duration.between(prevTime, currTime).seconds
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
  <type>1</type>
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
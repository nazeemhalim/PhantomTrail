package com.example.phantomtrail

import android.Manifest
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import org.osmdroid.views.overlay.Polyline
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.exifinterface.media.ExifInterface
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import org.osmdroid.views.overlay.Marker
import java.time.ZoneId
import android.graphics.Color as AndroidColor
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

    private val pickMultipleMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            processPhotosWithGPS(uris)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private val STEPS_KEY = intPreferencesKey("saved_steps")
        private val INITIAL_SENSOR_COUNT_KEY = intPreferencesKey("initial_sensor_count")
        private val TIMESTAMPS_KEY = stringPreferencesKey("step_timestamps")
        private val SESSION_START_KEY = stringPreferencesKey("session_start")
        private val STEP_LENGTH_KEY = doublePreferencesKey("step_length_meters")

        private val TRAIL_POINTS_KEY = stringPreferencesKey("trail_points")
        private val SHOW_MAP_KEY = booleanPreferencesKey("map_state")

        private val CUSTOM_START_LAT_KEY = doublePreferencesKey("custom_start_lat")

        private val CUSTOM_START_LON_KEY = doublePreferencesKey("custom_start_lon")

        private const val START_LAT = 2.9279088973999023
        private const val START_LON = 101.64179229736328
        private val stepLengthMeters = MutableStateFlow<Double> (0.75)

        private val showMapFlow = MutableStateFlow (false)
        private var lastProcessedSteps = 0
        private var currentAngle = 0.0

        private var accumulatedDistance = 0.0

        val trailPoints = MutableStateFlow<List<GeoPoint>>(emptyList())

        val customStartLat = MutableStateFlow(START_LAT)
        val customStartLon = MutableStateFlow(START_LON)

        val isSelectingStartLocation = MutableStateFlow(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            load(this@MainActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
        }


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
                showMapFlow.value = false
                StepCounterService.currentStepCount.value = savedSteps

                Log.d(TAG, "Loaded saved steps on startup: $savedSteps")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading steps on startup: ${e.message}")
            }
        }

        scope.launch(Dispatchers.Main) {
            StepCounterService.currentStepCount.collect { steps ->
                Log.d(TAG, "Step count changed to: $steps, showMap: ${showMapFlow.value}")
                if (steps > 0) {
                    updateTrailPoints()
                }
            }
        }


        loadData()

        scope.launch {
            try {
                val prefs = dataStore.data.first()
                val savedSteps = prefs[STEPS_KEY] ?: 0

                stepLengthMeters.value = prefs[STEP_LENGTH_KEY] ?: 0.75
                showMapFlow.value = false
                StepCounterService.currentStepCount.value = savedSteps

                customStartLat.value = prefs[CUSTOM_START_LAT_KEY] ?: START_LAT
                customStartLon.value = prefs[CUSTOM_START_LON_KEY] ?: START_LON
                // Load trail points
                val savedTrailStr = prefs[TRAIL_POINTS_KEY]
                if (savedTrailStr != null && savedTrailStr.isNotEmpty()) {
                    val points = mutableListOf<GeoPoint>()
                    savedTrailStr.split(";").forEach { pointStr ->
                        val parts = pointStr.split(",")
                        if (parts.size == 2) {
                            points.add(GeoPoint(parts[0].toDouble(), parts[1].toDouble()))
                        }
                    }
                    trailPoints.value = points
                    lastProcessedSteps = savedSteps
                    Log.d(TAG, "Loaded ${points.size} trail points")
                }

                Log.d(TAG, "Loaded saved steps on startup: $savedSteps")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading steps on startup: ${e.message}")
            }
        }

        setContent {
            // Observe service state flows
            val serviceSteps by StepCounterService.currentStepCount.collectAsState()
            val serviceTracking by StepCounterService.isRunning.collectAsState()
            val stepLength by stepLengthMeters.collectAsState()
            val showMap by showMapFlow.collectAsState()

            PhantomTrailTheme {
                if (showMap) {
                    // Show Map View
                    Box(modifier = Modifier.fillMaxSize()) {
                        OSMMapView()
                        val isSelecting by isSelectingStartLocation.collectAsState()

                        // Close button overlay
                        Button(
                            onClick = {
                                showMapFlow.value =  false
                                isSelectingStartLocation.value = false // Reset selection mode
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF606060)
                            )
                        ) {
                            Text("close", color = Color.White)
                        }

                        // Set Start Location button (only show when not selecting)
                        if (!isSelecting) {
                            Button(
                                onClick = { isSelectingStartLocation.value = true },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4A7C59)
                                )
                            ) {
                                Text("set start", color = Color.White)
                            }
                        } else {
                            // Show instruction when selecting
                            Text(
                                text = "Tap on map to set start location",
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(16.dp)
                                    .background(Color.Black.copy(alpha = 0.7f))
                                    .padding(8.dp)
                            )
                        }
                        Button(
                            onClick = {},
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(128.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF606060)
                            )
                        ){
                            Text(
                                text = "$serviceSteps",
                                color = Color.White
                            )
                        }

                        // Start/Pause — bottom center
                        Button(
                            onClick = { if (serviceTracking) pauseTracking() else startButton() },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 64.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (serviceTracking) Color(0xFFFFA500) else Color(
                                    0xFF4A7C59
                                )
                            )

                        ) {
                            Text(
                                text = if (serviceTracking) "pause" else "start",
                                color = Color.White
                            )
                        }
                    }

                } else {
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
                                    startButton()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (serviceTracking) Color(0xFFFFA500) else Color(
                                    0xFF4A7C59
                                )
                            )
                        ) {
                            Text(
                                text = if (serviceTracking) "pause" else "start",
                                color = Color.White,
                                fontSize = 18.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Set Step Length Button
                        Button(
                            onClick = { setStepLength() },
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

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { selectPhotosForGPS() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF606060)
                            )
                        ) {
                            Text(
                                text = "exif",
                                color = Color.White,
                                fontSize = 18.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                showMapFlow.value = true
                                updateTrailPoints() // Initialize trail points
                                },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF606060)
                            )
                        ) {
                            Text(
                                text = "map",
                                color = Color.White,
                                fontSize = 18.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(64.dp))

                        Text(
                            text = "V1.4.3",
                            color = Color.DarkGray,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }

    // MAP
    @Composable
    fun OSMMapView() {
        val context = LocalContext.current
        val points by trailPoints.collectAsState()
        val serviceSteps by StepCounterService.currentStepCount.collectAsState()
        val isSelecting by isSelectingStartLocation.collectAsState()
        val startLat by customStartLat.collectAsState()
        val startLon by customStartLon.collectAsState()

        val mapView = remember {
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(18.0)
                controller.setCenter(GeoPoint(startLat, startLon))
            }
        }

        // Handle map tap for location selection
        DisposableEffect(isSelecting) {
            if (isSelecting) {
                val overlay = object : org.osmdroid.views.overlay.Overlay() {
                    override fun onSingleTapConfirmed(e: android.view.MotionEvent?, mapView: MapView?): Boolean {
                        if (isSelectingStartLocation.value && mapView != null && e != null) {
                            val projection = mapView.projection
                            val geoPoint = projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint

                            // Save the selected location
                            customStartLat.value = geoPoint.latitude
                            customStartLon.value = geoPoint.longitude

                            scope.launch {
                                dataStore.edit { prefs ->
                                    prefs[CUSTOM_START_LAT_KEY] = geoPoint.latitude
                                    prefs[CUSTOM_START_LON_KEY] = geoPoint.longitude
                                }

                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "Updating trail...",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    isSelectingStartLocation.value = false

                                    // Regenerate the trail with the new start location
                                    regenerateTrailWithNewStart()
                                }
                            }

                            return true
                        }
                        return false
                    }
                }

                mapView.overlays.add(0, overlay)

                onDispose {
                    mapView.overlays.remove(overlay)
                }
            } else {
                onDispose { }
            }
        }

        // Update map overlays
        LaunchedEffect(points.size, points.lastOrNull(), isSelecting, startLat, startLon) {
            Log.d("OSMMapView", "LaunchedEffect triggered - points size: ${points.size}, isSelecting: $isSelecting")

            // Clear existing overlays
            val tapOverlay = if (isSelecting) mapView.overlays.firstOrNull() else null
            mapView.overlays.clear()
            if (tapOverlay != null) {
                mapView.overlays.add(tapOverlay)
            }

            if (isSelecting) {
                // Show selection marker
                val selectionMarker = Marker(mapView).apply {
                    position = GeoPoint(startLat, startLon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Start Location"
                    snippet = "Tap anywhere to change"
                    icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
                    icon?.setTint(AndroidColor.rgb(255, 0, 0)) // Red
                }
                mapView.overlays.add(selectionMarker)
                mapView.controller.setCenter(GeoPoint(startLat, startLon))
            } else if (points.isNotEmpty()) {
                // Normal trail display
                if (points.size > 1) {
                    val polyline = Polyline().apply {
                        setPoints(points)
                        outlinePaint.color = AndroidColor.rgb(123, 158, 135)
                        outlinePaint.strokeWidth = 12f
                        outlinePaint.isAntiAlias = true
                    }
                    mapView.overlays.add(polyline)
                }

                val startMarker = Marker(mapView).apply {
                    position = points.first()
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Start"
                    snippet = "Trail beginning"
                    icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
                    icon?.setTint(AndroidColor.rgb(74, 124, 89))
                }
                mapView.overlays.add(startMarker)

                if (points.size > 1) {
                    val currentMarker = Marker(mapView).apply {
                        position = points.last()
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = "Current Position"
                        snippet = "$serviceSteps steps"
                        icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
                        icon?.setTint(AndroidColor.rgb(255, 165, 0))
                    }
                    mapView.overlays.add(currentMarker)
                    mapView.controller.animateTo(points.last())
                }
            }

            mapView.invalidate()
        }

        DisposableEffect(Unit) {
            mapView.onResume()
            onDispose { mapView.onPause() }
        }

        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )
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

                // Update trail points if map is showing
                if (showMapFlow.value) {
                    updateTrailPoints()
                }
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
            .setMessage("Current step length is ${stepLengthMeters.value}m\n" +
                    "\nPlease input step length in metres")
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
        trailPoints.value = listOf(GeoPoint(customStartLat.value, customStartLon.value))
        lastProcessedSteps = 0
        currentAngle = 0.0
        accumulatedDistance = 0.0 // ADD THIS LINE

        scope.launch {
            dataStore.edit { prefs ->
                prefs[STEPS_KEY] = 0
                prefs[INITIAL_SENSOR_COUNT_KEY] = -1
                prefs[TIMESTAMPS_KEY] = ""
                prefs[SESSION_START_KEY] = ZonedDateTime.now().toString()
                prefs[TRAIL_POINTS_KEY] = "${customStartLat.value},${customStartLon.value}"
            }
        }

        Toast.makeText(this, "Stopped and reset steps", Toast.LENGTH_SHORT).show()
    }

    private fun startButton() {
        val items = arrayOf(
            "Start New Trail",
            "Continue Existing",
        )
        var checkedItems = -1

        if (StepCounterService.currentStepCount.value <= 0) {
            startTracking()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Tracking options")
                .setSingleChoiceItems(items, checkedItems) { _, which,->
                    checkedItems = which
                }
                .setPositiveButton("OK") { dialog, which ->
                    // Apply actions when OK / Positive is pressed
                    when(checkedItems){
                        0 -> {
                            stopAndResetSteps()
                            startTracking()
                        }

                        1 -> {
                            startTracking()
                        }
                    }

                    dialog.dismiss()
                }
                .setNegativeButton("CANCEL") { dialog, which ->
                    dialog.dismiss()
                }
                .show()
        }
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

    /**
     * Interpolate a GPS location at a specific distance along the trail
     */
    private fun interpolateLocationAtDistance(
        points: List<GeoPoint>,
        distances: List<Double>,
        targetDistance: Double
    ): GeoPoint {
        // Find which segment the target distance falls on
        for (i in 1 until distances.size) {
            if (targetDistance <= distances[i]) {
                val segmentStart = distances[i - 1]
                val segmentEnd = distances[i]
                val segmentLength = segmentEnd - segmentStart

                if (segmentLength < 0.000001) {
                    return points[i - 1]
                }

                val segmentProgress = (targetDistance - segmentStart) / segmentLength

                // Linear interpolation between points[i-1] and points[i]
                val lat = points[i - 1].latitude + (points[i].latitude - points[i - 1].latitude) * segmentProgress
                val lon = points[i - 1].longitude + (points[i].longitude - points[i - 1].longitude) * segmentProgress

                return GeoPoint(lat, lon)
            }
        }

        // If target distance is beyond the trail, return last point
        return points.last()
    }

    private fun generateStepBasedGPX(): File {
        val totalSteps = StepCounterService.currentStepCount.value
        val points = trailPoints.value
        val timestamps = stepTimestamps

        if (points.isEmpty() || timestamps.isEmpty()) {
            // Fallback: create a single point at start location
            val startLat = customStartLat.value
            val startLon = customStartLon.value
            val trackpoint = """   <trkpt lat="${"%.7f".format(startLat)}" lon="${"%.7f".format(startLon)}">
    <time>${ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT)}</time>
   </trkpt>
"""

            val startTime = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT)
            val gpxContent = """<?xml version="1.0" encoding="UTF-8"?>
<gpx xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd http://www.garmin.com/xmlschemas/GpxExtensions/v3 http://www.garmin.com/xmlschemas/GpxExtensionsv3.xsd http://www.garmin.com/xmlschemas/TrackPointExtension/v1 http://www.garmin.com/xmlschemas/TrackPointExtensionv1.xsd" creator="PhantomTrail" version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1" xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3">
 <metadata>
  <time>$startTime</time>
 </metadata>
 <trk>
  <n>Phantom Trail</n>
  <type>1</type>
  <trkseg>
$trackpoint </trkseg>
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

        // Calculate cumulative distances along the trail
        val distances = mutableListOf(0.0)
        for (i in 1 until points.size) {
            val segmentDist = calculateHaversineDistance(
                points[i-1].longitude,
                points[i-1].latitude,
                points[i].longitude,
                points[i].latitude
            )
            distances.add(distances.last() + segmentDist)
        }
        val totalDistance = distances.last()

        Log.d(TAG, "Generating GPX: ${timestamps.size} steps, ${points.size} trail points, ${totalDistance * 1000}m total distance")

        // Create one trackpoint per step timestamp
        val trackpoints = StringBuilder()

        for (i in timestamps.indices) {
            // Calculate progress through the walk (0.0 to 1.0)
            val progress = if (timestamps.size > 1) {
                i.toDouble() / (timestamps.size - 1)
            } else {
                0.0
            }

            // Find location at this progress along the trail
            val targetDistance = progress * totalDistance
            val location = interpolateLocationAtDistance(points, distances, targetDistance)

            val timeStr = timestamps[i].format(DateTimeFormatter.ISO_INSTANT)
            trackpoints.append("""   <trkpt lat="${"%.7f".format(location.latitude)}" lon="${"%.7f".format(location.longitude)}">
    <time>$timeStr</time>
   </trkpt>
""")
        }

        val startTime = timestamps.first().format(DateTimeFormatter.ISO_INSTANT)
        val gpxContent = """<?xml version="1.0" encoding="UTF-8"?>
<gpx xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd http://www.garmin.com/xmlschemas/GpxExtensions/v3 http://www.garmin.com/xmlschemas/GpxExtensionsv3.xsd http://www.garmin.com/xmlschemas/TrackPointExtension/v1 http://www.garmin.com/xmlschemas/TrackPointExtensionv1.xsd" creator="PhantomTrail" version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1" xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3">
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

        Log.d(TAG, "Generated ${timestamps.size} trackpoints (1 per step)")
        return file
    }

    private fun processPhotosWithGPS(uris: List<android.net.Uri>) {
        scope.launch {
            try {
                var successCount = 0
                var errorCount = 0

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Processing ${uris.size} photos...",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Load trail points and timestamps
                val prefs = dataStore.data.first()
                val savedTrailStr = prefs[TRAIL_POINTS_KEY]
                val points = mutableListOf<GeoPoint>()

                if (savedTrailStr != null && savedTrailStr.isNotEmpty()) {
                    savedTrailStr.split(";").forEach { pointStr ->
                        val parts = pointStr.split(",")
                        if (parts.size == 2) {
                            points.add(GeoPoint(parts[0].toDouble(), parts[1].toDouble()))
                        }
                    }
                }

                // Load step timestamps
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

                if (points.isEmpty() || stepTimestamps.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "No trail data available. Start tracking first!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                val startTime = stepTimestamps.first()
                val endTime = stepTimestamps.last()
                val savedPhotoUris = mutableListOf<android.net.Uri>()

                for (uri in uris) {
                    try {
                        // Copy URI to a temporary file we can modify
                        val inputStream = contentResolver.openInputStream(uri)
                        val tempFile = File(cacheDir, "temp_${System.currentTimeMillis()}.jpg")

                        inputStream?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }

                        // Read EXIF data
                        val exif = ExifInterface(tempFile.absolutePath)

                        // Get photo timestamp
                        val photoTimeStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)

                        val photoTime = if (photoTimeStr == null) {
                            Log.w(TAG, "No timestamp in photo, using end of trail")
                            endTime
                        } else {
                            parseExifDateTime(photoTimeStr)
                        }

                        // Find corresponding location on trail
                        val location = findLocationForTime(photoTime, points, startTime, endTime)

                        // Update GPS coordinates
                        updatePhotoGPS(exif, location, tempFile)

                        // Save to same directory as original
                        val savedUri = savePhotoToSameDirectory(uri, tempFile)
                        if (savedUri != null) {
                            savedPhotoUris.add(savedUri)
                            successCount++
                            Log.d(TAG, "Updated photo GPS: ${location.latitude}, ${location.longitude}")
                        } else {
                            errorCount++
                        }

                        // Clean up temp file
                        tempFile.delete()

                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing photo: ${e.message}", e)
                        errorCount++
                    }
                }

                // Show results
                withContext(Dispatchers.Main) {
                    if (successCount > 0) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Photos Updated")
                            .setMessage(
                                        "Location updated and saved as a new copy in gallery."
                            )
                            .setPositiveButton("Share") { _, _ ->
                                sharePhotos(savedPhotoUris)
                            }
                            .setNeutralButton("OK", null)
                            .show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Failed to process photos",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in processPhotosWithGPS: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Error processing photos: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    private fun savePhotoToSameDirectory(originalUri: android.net.Uri, modifiedFile: File): android.net.Uri? {
        return try {
            // Get original photo metadata
            val originalFileName = getFileName(originalUri) ?: "photo_${System.currentTimeMillis()}.jpg"
            var relativePath = Environment.DIRECTORY_PICTURES

            contentResolver.query(originalUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                        if (pathIndex >= 0) {
                            val path = cursor.getString(pathIndex)
                            if (path != null) {
                                relativePath = path
                            }
                        }
                    }
                }
            }

            // Create new filename with GPS prefix
            val baseName = originalFileName.substringBeforeLast(".")
            val extension = originalFileName.substringAfterLast(".", "jpg")
            val newFileName = "${baseName}_GPS.${extension}"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - Use MediaStore with same relative path
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, newFileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                }

                val newUri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                )

                newUri?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use { output ->
                        modifiedFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Saved GPS photo to same directory: $relativePath$newFileName")
                    uri
                }
            } else {
                // Android 9 and below - Get the actual file path
                val filePath = getRealPathFromURI(originalUri)
                if (filePath != null) {
                    val originalFile = File(filePath)
                    val parentDir = originalFile.parentFile

                    if (parentDir != null && parentDir.exists()) {
                        val newFile = File(parentDir, newFileName)
                        modifiedFile.copyTo(newFile, overwrite = true)

                        // Scan file to add to MediaStore
                        MediaScannerConnection.scanFile(
                            this@MainActivity,
                            arrayOf(newFile.absolutePath),
                            arrayOf("image/jpeg")
                        ) { path, uri ->
                            Log.d(TAG, "Scanned file: $path -> $uri")
                        }

                        Log.d(TAG, "Saved GPS photo to: ${newFile.absolutePath}")
                        android.net.Uri.fromFile(newFile)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save photo to same directory: ${e.message}")
            null
        }
    }
    private fun getRealPathFromURI(uri: android.net.Uri): String? {
        var realPath: String? = null

        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                    if (columnIndex >= 0) {
                        realPath = cursor.getString(columnIndex)
                    }
                }
            }
        } else if (uri.scheme == "file") {
            realPath = uri.path
        }

        return realPath
    }
    private fun parseExifDateTime(exifDateTime: String): ZonedDateTime {
        // EXIF format: "2024:01:27 14:30:45"
        val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val date = sdf.parse(exifDateTime)
        return ZonedDateTime.ofInstant(date?.toInstant() ?: java.time.Instant.now(), ZoneId.systemDefault())
    }

    private fun findLocationForTime(
        photoTime: ZonedDateTime,
        points: List<GeoPoint>,
        startTime: ZonedDateTime,
        endTime: ZonedDateTime
    ): GeoPoint {
        // If photo is before trail start, return start location
        if (photoTime.isBefore(startTime)) {
            Log.d(TAG, "Photo before trail start, using start location")
            return points.first()
        }

        // If photo is after trail end, return end location
        if (photoTime.isAfter(endTime)) {
            Log.d(TAG, "Photo after trail end, using end location")
            return points.last()
        }

        // Photo is during the trail - interpolate location
        val totalDuration = Duration.between(startTime, endTime).toMillis().toDouble()
        val photoOffset = Duration.between(startTime, photoTime).toMillis().toDouble()
        val progress = photoOffset / totalDuration

        // Find the point index based on progress
        val pointIndex = (progress * (points.size - 1)).toInt().coerceIn(0, points.size - 1)

        Log.d(TAG, "Photo during trail - progress: ${progress * 100}%, point index: $pointIndex")
        return points[pointIndex]
    }

    private fun updatePhotoGPS(exif: ExifInterface, location: GeoPoint, file: File) {
        // Convert decimal degrees to GPS format (degrees, minutes, seconds)
        val lat = location.latitude
        val lon = location.longitude

        // Set latitude
        val latRef = if (lat >= 0) "N" else "S"
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, convertToExifFormat(abs(lat)))
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, latRef)

        // Set longitude
        val lonRef = if (lon >= 0) "E" else "W"
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, convertToExifFormat(abs(lon)))
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, lonRef)

        // Save changes
        exif.saveAttributes()

        Log.d(TAG, "Updated GPS: $lat,$lon ($latRef,$lonRef)")
    }

    private fun convertToExifFormat(decimalDegrees: Double): String {
        val degrees = decimalDegrees.toInt()
        val minutesDecimal = (decimalDegrees - degrees) * 60
        val minutes = minutesDecimal.toInt()
        val seconds = ((minutesDecimal - minutes) * 60 * 1000).toInt()

        return "$degrees/1,$minutes/1,$seconds/1000"
    }

    private fun getFileName(uri: android.net.Uri): String? {
        var fileName: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }
        return fileName
    }

    private fun sharePhotos(uris: List<android.net.Uri>) {
        try {
            if (uris.isEmpty()) {
                Toast.makeText(this, "No photos to share", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Share GPS-tagged photos"))
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing photos: ${e.message}")
            Toast.makeText(this, "Error sharing photos: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    // Add button click handler
    private fun selectPhotosForGPS() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 3)
                return
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 3)
                return
            }
        }

        pickMultipleMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    private fun updateTrailPoints() {
        scope.launch {
            try {
                val SCALE = 0.0001
                val angleVariability = Math.PI / 7
                val totalSteps = StepCounterService.currentStepCount.value

                Log.d(TAG, "updateTrailPoints called - totalSteps: $totalSteps, lastProcessedSteps: $lastProcessedSteps")

                // Load existing trail points
                val prefs = dataStore.data.first()
                val savedTrailStr = prefs[TRAIL_POINTS_KEY]
                val existingPoints = mutableListOf<GeoPoint>()

                if (savedTrailStr != null && savedTrailStr.isNotEmpty()) {
                    savedTrailStr.split(";").forEach { pointStr ->
                        val parts = pointStr.split(",")
                        if (parts.size == 2) {
                            existingPoints.add(GeoPoint(parts[0].toDouble(), parts[1].toDouble()))
                        }
                    }
                }

                // If no existing points, create start point
                if (existingPoints.isEmpty()) {
                    existingPoints.add(GeoPoint(START_LAT, START_LON))
                    lastProcessedSteps = 0
                    currentAngle = 0.0
                    accumulatedDistance = 0.0
                }

                if (totalSteps == 0) {
                    withContext(Dispatchers.Main) {
                        trailPoints.value = existingPoints
                    }
                    return@launch
                }

                // Calculate how many new steps to add
                val newSteps = totalSteps - lastProcessedSteps
                Log.d(TAG, "newSteps to process: $newSteps")

                if (newSteps <= 0) {
                    withContext(Dispatchers.Main) {
                        trailPoints.value = existingPoints
                    }
                    return@launch
                }

                // Add new distance to accumulated distance
                val newDistance = newSteps * stepLengthMeters.value / 1000.0
                accumulatedDistance += newDistance

                val startLat = customStartLat.value
                val startLon = customStartLon.value

                val distanceBetweenPoints = calculateHaversineDistance(
                    startLon, startLat,
                    startLon + SCALE, startLat
                )

                // Calculate points based on accumulated distance
                val newPointsToAdd = (accumulatedDistance / distanceBetweenPoints).toInt()

                Log.d(TAG, "newDistance: $newDistance km, accumulated: $accumulatedDistance km, newPointsToAdd: $newPointsToAdd")

                if (newPointsToAdd > 0) {
                    // Subtract the distance we're about to use
                    accumulatedDistance -= (newPointsToAdd * distanceBetweenPoints)

                    // Get the last point as starting position
                    val lastPoint = existingPoints.last()
                    var lat = lastPoint.latitude
                    var lon = lastPoint.longitude

                    // Add new points
                    repeat(newPointsToAdd) {
                        lat += cos(currentAngle) * SCALE
                        lon += sin(currentAngle) * SCALE
                        existingPoints.add(GeoPoint(lat, lon))
                        currentAngle += (Math.random() * angleVariability) - (angleVariability / 2.0)
                    }

                    // Save updated trail points
                    val trailStr = existingPoints.joinToString(";") { "${it.latitude},${it.longitude}" }
                    dataStore.edit { prefs ->
                        prefs[TRAIL_POINTS_KEY] = trailStr
                    }

                    // Update on Main thread to ensure UI updates
                    withContext(Dispatchers.Main) {
                        trailPoints.value = existingPoints.toList() // Create new list instance to trigger recomposition
                        Log.d(TAG, "Trail points updated: ${existingPoints.size} points, last point: ${existingPoints.lastOrNull()}")
                    }
                }

                lastProcessedSteps = totalSteps

                Log.d(TAG, "Updated trail: ${existingPoints.size} points for $totalSteps steps (added $newPointsToAdd points), remaining accumulated: $accumulatedDistance km")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating trail points: ${e.message}", e)
            }
        }
    }

    private fun regenerateTrailWithNewStart() {
        scope.launch {
            try {
                val totalSteps = StepCounterService.currentStepCount.value
                if (totalSteps == 0) {
                    // Just set the new start point
                    trailPoints.value = listOf(GeoPoint(customStartLat.value, customStartLon.value))
                    dataStore.edit { prefs ->
                        prefs[TRAIL_POINTS_KEY] = "${customStartLat.value},${customStartLon.value}"
                    }
                    return@launch
                }

                // Regenerate the entire trail from scratch with new start location
                val SCALE = 0.0001
                val angleVariability = Math.PI / 7
                val startLat = customStartLat.value
                val startLon = customStartLon.value

                val totalDistance = totalSteps * stepLengthMeters.value / 1000.0

                val distanceBetweenPoints = calculateHaversineDistance(
                    startLon, startLat,
                    startLon + SCALE, startLat
                )

                val numberOfPoints = (totalDistance / distanceBetweenPoints).toInt().coerceAtLeast(0)

                val newPoints = mutableListOf<GeoPoint>()
                newPoints.add(GeoPoint(startLat, startLon))

                var lat = startLat
                var lon = startLon
                var angle = 0.0

                repeat(numberOfPoints) {
                    lat += cos(angle) * SCALE
                    lon += sin(angle) * SCALE
                    newPoints.add(GeoPoint(lat, lon))
                    angle += (Math.random() * angleVariability) - (angleVariability / 2.0)
                }

                // Save the new trail
                val trailStr = newPoints.joinToString(";") { "${it.latitude},${it.longitude}" }
                dataStore.edit { prefs ->
                    prefs[TRAIL_POINTS_KEY] = trailStr
                }

                // Update state
                withContext(Dispatchers.Main) {
                    trailPoints.value = newPoints
                    currentAngle = angle
                    accumulatedDistance = totalDistance - (numberOfPoints * distanceBetweenPoints)
                    lastProcessedSteps = totalSteps

                    Log.d(TAG, "Regenerated trail with ${newPoints.size} points from new start location")
                    Toast.makeText(
                        this@MainActivity,
                        "Trail updated with new start location",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error regenerating trail: ${e.message}", e)
            }
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
            Log.e(TAG, "Error sharing GPX: ${e.message}")
            Toast.makeText(this, "$trailType saved to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }
}
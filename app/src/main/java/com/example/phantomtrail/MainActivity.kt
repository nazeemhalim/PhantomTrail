package com.example.phantomtrail

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.phantomtrail.data.StepRepository
import com.example.phantomtrail.service.StepCounterService
import com.example.phantomtrail.ui.theme.PhantomTrailTheme
import com.example.phantomtrail.utils.GpxExporter
import com.example.phantomtrail.utils.PhotoGpsProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.time.ZonedDateTime
import kotlin.math.*
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Refactored components - KEEP THESE
    private lateinit var repository: StepRepository
    private lateinit var photoProcessor: PhotoGpsProcessor
    private lateinit var gpxExporter: GpxExporter

    private val pickMultipleMedia = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not take persistent permission: ${e.message}")
                }
            }
            processPhotosWithGPS(uris)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val START_LAT = 2.9279088973999023
        private const val START_LON = 101.64179229736328

        private val TRAIL_POINTS_KEY = stringPreferencesKey("trail_points")

        private val stepLengthMeters = MutableStateFlow(0.75)
        private val showMapFlow = MutableStateFlow(false)

        // Trail generation state
        private var lastProcessedSteps = 0
        private var currentAngle = 0.0
        private var accumulatedDistance = 0.0

        // Slider
        private val selectedTrackpointIndex = MutableStateFlow(0)

        private val photosNeedingManualLocation = MutableStateFlow<List<Uri>>(emptyList())

        val trailPoints = MutableStateFlow<List<GeoPoint>>(emptyList())
        val customStartLat = MutableStateFlow(START_LAT)
        val customStartLon = MutableStateFlow(START_LON)
        val isSelectingStartLocation = MutableStateFlow(false)


    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "=== MainActivity onCreate ===")

        Configuration.getInstance().apply {
            userAgentValue = packageName
            load(this@MainActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
        }

        // Initialize refactored components
        repository = StepRepository(this)
        photoProcessor = PhotoGpsProcessor(this, contentResolver)
        gpxExporter = GpxExporter()

        Log.d(TAG, "Components initialized")

        requestNecessaryPermissions()
        loadInitialData()

        // Observe step count changes
        scope.launch(Dispatchers.Main) {
            StepCounterService.currentStepCount.collect { steps ->
                Log.d(TAG, "Step count changed to: $steps")
                if (steps > 0) {
                    updateTrailPoints()
                }
            }
        }

        setContent {
            val serviceSteps by StepCounterService.currentStepCount.collectAsState()
            val serviceTracking by StepCounterService.isRunning.collectAsState()
            val stepLength by stepLengthMeters.collectAsState()
            val showMap by showMapFlow.collectAsState()

            PhantomTrailTheme {
                if (showMap) {
                    MapScreen(serviceSteps, serviceTracking)
                } else {
                    MainScreen(serviceSteps, serviceTracking, stepLength)
                }
            }
        }
    }

    private fun requestNecessaryPermissions() {
        Log.d(TAG, "Checking permissions...")

        val permissionsToRequest = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), 100)
        }
    }

    private fun loadInitialData() {
        scope.launch {
            try {
                val stepData = repository.loadStepData()

                StepCounterService.currentStepCount.value = stepData.steps
                stepLengthMeters.value = stepData.stepLength
                customStartLat.value = stepData.customStartLat
                customStartLon.value = stepData.customStartLon

                // Load trail points
                val points = repository.loadTrailPoints()
                if (points.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        trailPoints.value = points
                    }
                    lastProcessedSteps = stepData.steps
                    Log.d(TAG, "Loaded ${points.size} trail points")
                }

                Log.d(TAG, "Loaded data: ${stepData.steps} steps")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading initial data: ${e.message}", e)
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

    private fun updateTrailPoints() {
        scope.launch {
            try {
                val SCALE = 0.0001
                val angleVariability = Math.PI / 7
                val totalSteps = StepCounterService.currentStepCount.value

                Log.d(TAG, "updateTrailPoints - totalSteps: $totalSteps, lastProcessed: $lastProcessedSteps")

                // Load existing trail points
                val points = repository.loadTrailPoints()
                val existingPoints = repository.loadTrailPoints().toMutableList()


                // If no existing points, create start point
                if (existingPoints.isEmpty()) {
                    existingPoints.add(GeoPoint(customStartLat.value, customStartLon.value))
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

                val newSteps = totalSteps - lastProcessedSteps

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
                Log.d(TAG, "distanceBetweenPoints: $distanceBetweenPoints km = ${distanceBetweenPoints * 1000} meters")

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

                    // Save to DataStore
                    val trailStr = existingPoints.joinToString(";") { "${it.latitude},${it.longitude}" }
                    repository.saveTrailPoints(existingPoints)


                    withContext(Dispatchers.Main) {
                        trailPoints.value = existingPoints.toList()
                        Log.d(TAG, "Trail updated: ${existingPoints.size} points")
                    }
                }

                lastProcessedSteps = totalSteps
            } catch (e: Exception) {
                Log.e(TAG, "Error updating trail: ${e.message}", e)
            }
        }
    }

    private fun regenerateTrail() {
        scope.launch {
            try {
                val totalSteps = StepCounterService.currentStepCount.value

                if (totalSteps == 0) {
                    val startPoint = listOf(GeoPoint(customStartLat.value, customStartLon.value))
                    val trailStr = "${customStartLat.value},${customStartLon.value}"
                    repository.saveTrailPoints(startPoint)

                    withContext(Dispatchers.Main) {
                        trailPoints.value = startPoint
                    }
                    return@launch
                }

                // Regenerate entire trail from scratch
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

                // Save
                val trailStr = newPoints.joinToString(";") { "${it.latitude},${it.longitude}" }
                repository.saveTrailPoints(newPoints)

                // Update state
                withContext(Dispatchers.Main) {
                    trailPoints.value = newPoints
                    currentAngle = angle
                    accumulatedDistance = totalDistance - (numberOfPoints * distanceBetweenPoints)
                    lastProcessedSteps = totalSteps

                    Log.d(TAG, "Regenerated trail: ${newPoints.size} points")
                    Toast.makeText(this@MainActivity, "Trail updated", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error regenerating trail: ${e.message}", e)
            }
        }
    }

    // ========== UI COMPOSABLES ==========

    @Composable
    fun MainScreen(serviceSteps: Int, serviceTracking: Boolean, stepLength: Double) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Text("steps", color = Color(0xFF7B9E87), fontSize = 30.sp)
            Text("$serviceSteps", color = Color.White, fontSize = 50.sp)
            Text(
                String.format("%.2f km", serviceSteps * stepLength / 1000.0),
                color = Color.Gray,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                if (serviceTracking) "recording..." else "stopped",
                color = if (serviceTracking) Color(0xFF4A7C59) else Color.Gray,
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { if (serviceTracking) pauseTracking() else startButton() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (serviceTracking) Color(0xFFFFA500) else Color(0xFF4A7C59)
                )
            ) {
                Text(if (serviceTracking) "pause" else "start", color = Color.White, fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { setStepLength() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF606060))
            ) {
                Text("step length", color = Color.White, fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { exportGPX() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF606060))
            ) {
                Text("export gpx", color = Color.White, fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { selectPhotosForGPS() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF606060))
            ) {
                Text("exif", color = Color.White, fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {streetView()},
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF606060))
            ) {
                Text("show location", color = Color.White, fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    showMapFlow.value = true
                    updateTrailPoints()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF606060))
            ) {
                Text("map", color = Color.White, fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(64.dp))
            Text("V1.5.1", color = Color.DarkGray, fontSize = 12.sp)
        }
    }

    @Composable
    fun MapScreen(serviceSteps: Int, serviceTracking: Boolean) {
        val photosNeedingLocation by photosNeedingManualLocation.collectAsState()
        val sliderPosition by selectedTrackpointIndex.collectAsState()
        val points by trailPoints.collectAsState()

        Box(modifier = Modifier.fillMaxSize()) {
            OSMMapView()
            val isSelecting by isSelectingStartLocation.collectAsState()

            Button(
                onClick = {
                    showMapFlow.value = false
                    isSelectingStartLocation.value = false
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF606060))
            ) {
                Text("close", color = Color.White)
            }

            if (!isSelecting) {
                Button(
                    onClick = { isSelectingStartLocation.value = true },
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A7C59))
                ) {
                    Text("set start", color = Color.White)
                }
            } else {
                Text(
                    "Tap on map to set start location",
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
                modifier = Modifier.align(Alignment.BottomCenter).padding(128.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF606060))
            ) {
                Text("$serviceSteps", color = Color.White)
            }

            Button(
                onClick = { if (serviceTracking) pauseTracking() else startButton() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (serviceTracking) Color(0xFFFFA500) else Color(0xFF4A7C59)
                )
            ) {
                Text(if (serviceTracking) "pause" else "start", color = Color.White)
            }

            // Slider for manual photo location
            if (photosNeedingLocation.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.8f))
                        .padding(16.dp)
                ) {
                    Text("Select location for ${photosNeedingLocation.size} photos", color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))

                    androidx.compose.material3.Slider(
                        value = sliderPosition.toFloat(),
                        onValueChange = { selectedTrackpointIndex.value = it.toInt() },
                        valueRange = 0f..(points.size - 1).toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Trackpoint ${sliderPosition + 1} of ${points.size}", color = Color.Gray, fontSize = 12.sp)

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { applyManualLocationToPhotos() }) {
                        Text("Apply Location")
                    }
                }
            }
        }
    }

    @Composable
    fun OSMMapView() {
        val context = LocalContext.current
        val points by trailPoints.collectAsState()
        val serviceSteps by StepCounterService.currentStepCount.collectAsState()
        val isSelecting by isSelectingStartLocation.collectAsState()
        val startLat by customStartLat.collectAsState()
        val startLon by customStartLon.collectAsState()
        val photosNeedingLocation by photosNeedingManualLocation.collectAsState()
        val sliderPosition by selectedTrackpointIndex.collectAsState()

        val mapView = remember {
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(18.0)
                controller.setCenter(GeoPoint(startLat, startLon))
            }
        }

        DisposableEffect(isSelecting) {
            if (isSelecting) {
                val overlay = object : org.osmdroid.views.overlay.Overlay() {
                    override fun onSingleTapConfirmed(e: android.view.MotionEvent?, mapView: MapView?): Boolean {
                        if (isSelectingStartLocation.value && mapView != null && e != null) {
                            val projection = mapView.projection
                            val geoPoint = projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint

                            customStartLat.value = geoPoint.latitude
                            customStartLon.value = geoPoint.longitude

                            scope.launch {
                                repository.saveStartLocation(geoPoint.latitude, geoPoint.longitude)

                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Start location updated", Toast.LENGTH_SHORT).show()
                                    isSelectingStartLocation.value = false
                                    regenerateTrail()
                                }
                            }
                            return true
                        }
                        return false
                    }
                }
                mapView.overlays.add(0, overlay)
                onDispose { mapView.overlays.remove(overlay) }
            } else {
                onDispose { }
            }
        }

        LaunchedEffect(points.size, points.lastOrNull(), isSelecting, startLat, startLon, sliderPosition, photosNeedingLocation.isNotEmpty()) {
            val tapOverlay = if (isSelecting) mapView.overlays.firstOrNull() else null
            mapView.overlays.clear()
            if (tapOverlay != null) mapView.overlays.add(tapOverlay)

            if (isSelecting) {
                val marker = Marker(mapView).apply {
                    position = GeoPoint(startLat, startLon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Start Location"
                    icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
                    icon?.setTint(AndroidColor.rgb(255, 0, 0))
                }
                mapView.overlays.add(marker)
                mapView.controller.setCenter(GeoPoint(startLat, startLon))
            } else if (points.isNotEmpty()) {
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
                    icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
                    icon?.setTint(AndroidColor.rgb(74, 124, 89))
                }
                mapView.overlays.add(startMarker)

                // Add selected trackpoint marker for photo location
                if (photosNeedingLocation.isNotEmpty() && sliderPosition < points.size) {
                    val selectedMarker = Marker(mapView).apply {
                        position = points[sliderPosition]
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = "Photo Location"
                        snippet = "Trackpoint ${sliderPosition + 1}"
                        icon = context.getDrawable(android.R.drawable.ic_menu_camera)
                        icon?.setTint(AndroidColor.rgb(255, 0, 255)) // Purple
                    }
                    mapView.overlays.add(selectedMarker)
                    mapView.controller.animateTo(points[sliderPosition])
                } else if (points.size > 1) {
                    val currentMarker = Marker(mapView).apply {
                        position = points.last()
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = "Current"
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

        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
    }

    // ========== HELPER FUNCTIONS ==========

    override fun onResume() {
        super.onResume()
        scope.launch {
            try {
                val stepData = repository.loadStepData()
                StepCounterService.currentStepCount.value = stepData.steps
                if (showMapFlow.value) updateTrailPoints()
            } catch (e: Exception) {
                Log.e(TAG, "Error on resume: ${e.message}")
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
        Toast.makeText(this, "Paused tracking", Toast.LENGTH_SHORT).show()
    }

    private fun setStepLength() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        AlertDialog.Builder(this)
            .setTitle("Step Length")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val value = input.text.toString()
                if (value.isNotEmpty()) {
                    val number = value.toDouble()
                    stepLengthMeters.value = number
                    scope.launch { repository.saveStepLength(number) }
                    Toast.makeText(this, "Step length: $number m", Toast.LENGTH_SHORT).show()
                }
            }
            .setMessage("Current: ${stepLengthMeters.value}m\n\nEnter step length in meters")
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun stopAndResetSteps() {
        if (StepCounterService.isRunning.value) {
            val serviceIntent = Intent(this, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_STOP
            }
            startService(serviceIntent)
        }

        StepCounterService.currentStepCount.value = 0
        trailPoints.value = listOf(GeoPoint(customStartLat.value, customStartLon.value))
        lastProcessedSteps = 0
        currentAngle = 0.0
        accumulatedDistance = 0.0

        scope.launch {
            scope.launch {
                repository.resetAllData()
                repository.saveStartLocation(customStartLat.value, customStartLon.value)
                repository.saveTrailPoints(trailPoints.value)
            }
        }

        Toast.makeText(this, "Reset", Toast.LENGTH_SHORT).show()
    }

    private fun startButton() {
        val items = arrayOf("Start New Trail", "Continue Existing")
        var checkedItems = -1

        if (StepCounterService.currentStepCount.value <= 0) {
            startTracking()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Tracking options")
                .setSingleChoiceItems(items, checkedItems) { _, which -> checkedItems = which }
                .setPositiveButton("OK") { dialog, _ ->
                    when (checkedItems) {
                        0 -> {
                            stopAndResetSteps()
                            startTracking()
                        }
                        1 -> startTracking()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("CANCEL") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    private fun exportGPX() {
        scope.launch {
            try {
                val stepData = repository.loadStepData()
                val points = repository.loadTrailPoints()

                if (stepData.steps < 2 || stepData.timestamps.size < 2) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Not enough steps", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val dir = externalCacheDir ?: cacheDir
                val gpxFile = gpxExporter.generateGpxFile(dir, points, stepData.timestamps, stepData.steps)

                withContext(Dispatchers.Main) {
                    shareGPXFile(gpxFile)
                    Toast.makeText(
                        this@MainActivity,
                        "Exported ${stepData.steps} steps",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Export failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareGPXFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share GPX"))
        } catch (e: Exception) {
            Log.e(TAG, "Share error: ${e.message}")
            Toast.makeText(this, "Saved to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    private fun selectPhotosForGPS() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 3)
                return
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 3)
                return
            }
        }
        pickMultipleMedia.launch(arrayOf("image/*"))
    }

    private fun streetView() {
        val currentPoint = trailPoints.value.lastOrNull()
        if (currentPoint == null) {
            Toast.makeText(this, "No location available", Toast.LENGTH_SHORT).show()
            return
        }

        val lat = currentPoint.latitude
        val lon = currentPoint.longitude

        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
        val intent = Intent(Intent.ACTION_VIEW, uri)

        try {
            startActivity(Intent.createChooser(intent, "Open location in..."))
        } catch (e: Exception) {
            Toast.makeText(this, "No map apps available", Toast.LENGTH_SHORT).show()
        }
    }



    private fun getPhotoTimestamp(uri: Uri): ZonedDateTime? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val tempFile = File(cacheDir, "temp_check.jpg")
                tempFile.outputStream().use { output -> input.copyTo(output) }
                val exif = androidx.exifinterface.media.ExifInterface(tempFile.absolutePath)
                val timeStr = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                tempFile.delete()

                if (timeStr != null) {
                    val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                    val date = sdf.parse(timeStr)
                    ZonedDateTime.ofInstant(date?.toInstant(), java.time.ZoneId.systemDefault())
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun processPhotosWithGPS(uris: List<android.net.Uri>) {
        scope.launch {
            try {
                val stepData = repository.loadStepData()
                val points = repository.loadTrailPoints()

                if (points.isEmpty() || stepData.timestamps.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "No trail data", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val startTime = stepData.timestamps.first()
                val endTime = stepData.timestamps.last()

                // Separate photos by timestamp
                val photosInRange = mutableListOf<Uri>()
                val photosOutOfRange = mutableListOf<Uri>()

                for (uri in uris) {
                    val photoTime = getPhotoTimestamp(uri)
                    if (photoTime != null && (photoTime.isBefore(startTime) || photoTime.isAfter(endTime))) {
                        photosOutOfRange.add(uri)
                    } else {
                        photosInRange.add(uri)
                    }
                }

                // Process in-range photos normally
                if (photosInRange.isNotEmpty()) {
                    val result = photoProcessor.processPhotos(photosInRange, points, stepData.timestamps)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Updated ${result.successCount} photos", Toast.LENGTH_SHORT).show()
                    }
                }

                // Handle out-of-range photos with manual selection
                if (photosOutOfRange.isNotEmpty()) {
                    photosNeedingManualLocation.value = photosOutOfRange
                    selectedTrackpointIndex.value = points.size / 2 // Start at middle
                    withContext(Dispatchers.Main) {
                        showMapFlow.value = true
                        Toast.makeText(this@MainActivity, "${photosOutOfRange.size} photos need manual location", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Photo error: ${e.message}", e)
            }
        }
    }

    private fun applyManualLocationToPhotos() {
        scope.launch {
            try {
                val points = repository.loadTrailPoints()
                val index = selectedTrackpointIndex.value.coerceIn(0, points.size - 1)
                val location = points[index]

                val uris = photosNeedingManualLocation.value
                val result = photoProcessor.processPhotosWithFixedLocation(uris, location)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Tagged ${result.successCount} photos", Toast.LENGTH_SHORT).show()
                    photosNeedingManualLocation.value = emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Manual tagging error: ${e.message}", e)
            }
        }
    }
}
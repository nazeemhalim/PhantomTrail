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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.phantomtrail.data.AppPreferences
import com.example.phantomtrail.data.Constants
import com.example.phantomtrail.data.PreviousTrail
import com.example.phantomtrail.data.StepRepository
import com.example.phantomtrail.utils.UnitUtils
import com.example.phantomtrail.service.StepCounterService

import com.example.phantomtrail.ui.theme.HandjetFontFamily
import com.example.phantomtrail.ui.theme.PhantomTrailTheme
import com.example.phantomtrail.utils.GpxExporter
import com.example.phantomtrail.utils.PhotoGpsProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import com.example.phantomtrail.utils.CartoVoyagerTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaScannerConnection
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isActive = false

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

        val activityBaselineSteps = MutableStateFlow(0)
        private val stepLengthMeters = MutableStateFlow(0.75)
        private val pathWavinessDegrees = MutableStateFlow(Constants.DEFAULT_PATH_WAVINESS_DEGREES)
        private val useImperial = MutableStateFlow(false)
        private val walkedTrailColor = MutableStateFlow(AppPreferences.DEFAULT_WALKED_TRAIL_COLOR)
        val selectedTab = MutableStateFlow(0) // 0=Map, 1=Actions, 2=Settings

        // trail generation state
        private var lastProcessedSteps = 0
        private var currentAngle = 0.0
        private var accumulatedDistance = 0.0

        // slider
        private val photosNeedingManualLocation = MutableStateFlow<List<PhotoToTag>>(emptyList())

        private val selectedPhotoIndex = MutableStateFlow(0)
        private val selectedTrackpointIndex = MutableStateFlow(0)

        val trailPoints = MutableStateFlow<List<GeoPoint>>(emptyList())
        val customStartLat = MutableStateFlow(START_LAT)
        val customStartLon = MutableStateFlow(START_LON)
        val isSelectingStartLocation = MutableStateFlow(false)


    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "=== MainActivity onCreate ===")

        repository = StepRepository(this)
        photoProcessor = PhotoGpsProcessor(this, contentResolver)
        gpxExporter = GpxExporter()

        Log.d(TAG, "Components initialized")

        requestNecessaryPermissions()
        loadInitialData()

        // observe step count changes
        scope.launch(Dispatchers.Main) {
            StepCounterService.currentStepCount.collect { totalSteps ->
                if (StepCounterService.activeActivity.value != com.example.phantomtrail.service.ActiveActivity.MAIN) return@collect
                scope.launch {
                    repository.saveSteps(totalSteps)
                    // save timestamps from service
                    val timestamps = StepCounterService.getTimestamps()
                    repository.saveTimestamps(timestamps)
                }
                if (totalSteps > 0) updateTrailPoints(totalSteps)
            }
        }

        setContent {
            val serviceSteps by StepCounterService.currentStepCount.collectAsState()
            val serviceTracking by StepCounterService.isMainRunning.collectAsState()
            val tab by selectedTab.collectAsState()

            PhantomTrailTheme {
                Scaffold(
                    containerColor = Color.Black,
                    bottomBar = {
                        BottomNavBar(tab) { newTab ->
                            if (newTab == 0) updateTrailPoints(StepCounterService.currentStepCount.value)
                            selectedTab.value = newTab
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                if (targetState > initialState) {
                                    (slideInHorizontally(tween(250)) { it } + fadeIn(tween(250))) togetherWith
                                        (slideOutHorizontally(tween(250)) { -it } + fadeOut(tween(250)))
                                } else {
                                    (slideInHorizontally(tween(250)) { -it } + fadeIn(tween(250))) togetherWith
                                        (slideOutHorizontally(tween(250)) { it } + fadeOut(tween(250)))
                                }
                            },
                            label = "tabContent"
                        ) { targetTab ->
                            when (targetTab) {
                                0 -> MapScreen(serviceSteps, serviceTracking)
                                1 -> ActionsScreen()
                                2 -> SettingsScreen()
                            }
                        }
                    }
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
                pathWavinessDegrees.value = stepData.pathWavinessDegrees
                customStartLat.value = stepData.customStartLat
                customStartLon.value = stepData.customStartLon
                useImperial.value = AppPreferences.isImperial(this@MainActivity)
                walkedTrailColor.value = AppPreferences.getWalkedTrailColor(this@MainActivity)

                // load trail points
                val points = repository.loadTrailPoints()
                if (points.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        trailPoints.value = points
                    }
                    lastProcessedSteps = stepData.steps
                    Log.d(TAG, "Loaded ${points.size} trail points")
                }

                // if tracking was active when the app/process was killed, resume it instead of
                // silently landing back on the idle screen
                if (repository.wasTracking()) {
                    withContext(Dispatchers.Main) {
                        startTracking()
                    }
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

    private fun updateTrailPoints(totalSteps: Int) {
        scope.launch {
            try {
                val SCALE = 0.0001
                val angleVariability = Math.toRadians(pathWavinessDegrees.value)
                val totalSteps = StepCounterService.currentStepCount.value

                Log.d(TAG, "updateTrailPoints - totalSteps: $totalSteps, lastProcessed: $lastProcessedSteps")

                // load existing trail points
                val points = repository.loadTrailPoints()
                val existingPoints = repository.loadTrailPoints().toMutableList()

                // if no existing points, create start point
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

                // add new distance to accumulated distance
                val newDistance = newSteps * stepLengthMeters.value / 1000.0
                accumulatedDistance += newDistance

                val startLat = customStartLat.value
                val startLon = customStartLon.value

                val distanceBetweenPoints = calculateHaversineDistance(
                    startLon, startLat,
                    startLon + SCALE, startLat
                )

                // calculate points based on accumulated distance
                val newPointsToAdd = (accumulatedDistance / distanceBetweenPoints).toInt()

                Log.d(TAG, "newDistance: $newDistance km, accumulated: $accumulatedDistance km, newPointsToAdd: $newPointsToAdd")
                Log.d(TAG, "distanceBetweenPoints: $distanceBetweenPoints km = ${distanceBetweenPoints * 1000} meters")

                if (newPointsToAdd > 0) {
                    // subtract the distance we're about to use
                    accumulatedDistance -= (newPointsToAdd * distanceBetweenPoints)

                    // get the last point as starting position
                    val lastPoint = existingPoints.last()
                    var lat = lastPoint.latitude
                    var lon = lastPoint.longitude

                    // add new points
                    repeat(newPointsToAdd) {
                        lat += cos(currentAngle) * SCALE
                        lon += sin(currentAngle) * SCALE
                        existingPoints.add(GeoPoint(lat, lon))
                        currentAngle += (Math.random() * angleVariability) - (angleVariability / 2.0)
                    }

                    // save to datastore
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

                // regenerate entire trail from scratch
                val SCALE = 0.0001
                val angleVariability = Math.toRadians(pathWavinessDegrees.value)
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

                // save
                val trailStr = newPoints.joinToString(";") { "${it.latitude},${it.longitude}" }
                repository.saveTrailPoints(newPoints)

                // update state
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

    // ui composables

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ActionGrid(items: List<ActionItem>) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().background(Color.Black)
        ) {
            items(items.size) { i ->
                val item = items[i]
                Card(
                    onClick = item.action,
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = item.color),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize().padding(12.dp)
                    ) {
                        if (item.icon != null) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            item.label,
                            color = Color.White,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ActionsScreen() {
        ActionGrid(listOf(
            ActionItem("Previous Trails", Color(0xFF3A3A3A), Icons.Default.History) { showPreviousTrailsDialog() },
            ActionItem("Export GPX",      Color(0xFF3A3A3A), Icons.Default.Share) { exportGPX() },
            ActionItem("EXIF Tag Photos", Color(0xFF3A3A3A), Icons.Default.PhotoCamera) { selectPhotosForGPS() },
            ActionItem("View Location",   Color(0xFF3A3A3A), Icons.Default.LocationOn) { openInMapsApp() },
            ActionItem("Upload to Strava",Color(0xFF3A3A3A), Icons.Default.CloudUpload) { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.strava.com/upload/select"))
            )}
        ))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsScreen() {
        ActionGrid(listOf(
            ActionItem("Step Length",   Color(0xFF3A3A3A), Icons.Default.Straighten) { setStepLength() },
            ActionItem("Path Deviation", Color(0xFF3A3A3A), Icons.Default.Timeline) { setPathWaviness() },
            ActionItem("Units",         Color(0xFF3A3A3A), Icons.Default.Public) { setUnits() },
            ActionItem("Trail Color",   Color(0xFF3A3A3A), Icons.Default.Palette) { setTrailColor() }
        ))
    }

    @Composable
    fun BottomNavBar(currentTab: Int, onTabSelected: (Int) -> Unit) {
        NavigationBar(containerColor = Color(0xFF1A1A1A), tonalElevation = 0.dp) {
            NavigationBarItem(
                selected = currentTab == 0,
                onClick = { onTabSelected(0) },
                icon = { Icon(Icons.Default.LocationOn, contentDescription = "Map") },
                label = { Text("Map") },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF4A7C59),
                    selectedTextColor = Color(0xFF4A7C59),
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray,
                    indicatorColor = Color(0xFF1A1A1A)
                )
            )
            NavigationBarItem(
                selected = currentTab == 1,
                onClick = { onTabSelected(1) },
                icon = { Icon(Icons.Default.List, contentDescription = "Actions") },
                label = { Text("Actions") },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF4A7C59),
                    selectedTextColor = Color(0xFF4A7C59),
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray,
                    indicatorColor = Color(0xFF1A1A1A)
                )
            )
            NavigationBarItem(
                selected = currentTab == 2,
                onClick = { onTabSelected(2) },
                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                label = { Text("Settings") },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF4A7C59),
                    selectedTextColor = Color(0xFF4A7C59),
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray,
                    indicatorColor = Color(0xFF1A1A1A)
                )
            )
        }
    }

    @Composable
    fun MapScreen(serviceSteps: Int, serviceTracking: Boolean) {
        val photosNeedingLocation by photosNeedingManualLocation.collectAsState()
        val currentPhotoIndex by selectedPhotoIndex.collectAsState()
        val sliderPosition by selectedTrackpointIndex.collectAsState()
        val points by trailPoints.collectAsState()
        val isSelecting by isSelectingStartLocation.collectAsState()

        Box(modifier = Modifier.fillMaxSize()) {
            OSMMapView()

            // hide buttons when photo tagging is active
            val showButtons = photosNeedingLocation.isEmpty()

            if (showButtons) {
                if (isSelecting) {
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

                val stepLen by stepLengthMeters.collectAsState()
                val imperial by useImperial.collectAsState()
                val distanceKm = serviceSteps * stepLen / 1000.0
                val distanceValue = UnitUtils.distanceValue(distanceKm, imperial)
                val distanceUnitLabel = UnitUtils.distanceUnitLabel(imperial)

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp, start = 24.dp, end = 24.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xCC1A1A1A)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Random Trail", color = Color(0xFF7B9E87), fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "$serviceSteps",
                                    color = Color.White,
                                    fontSize = 36.sp,
                                    fontFamily = HandjetFontFamily
                                )
                                Text("steps", color = Color.LightGray, fontSize = 11.sp)
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "%.2f".format(distanceValue),
                                    color = Color.White,
                                    fontSize = 36.sp,
                                    fontFamily = HandjetFontFamily
                                )
                                Text(distanceUnitLabel, color = Color.LightGray, fontSize = 11.sp)
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = { if (serviceTracking) pauseTracking() else startButton() }
                            ) {
                                Icon(
                                    imageVector = if (serviceTracking) Icons.Default.Refresh else Icons.Default.PlayArrow,
                                    contentDescription = if (serviceTracking) "Pause" else "Play",
                                    tint = if (serviceTracking) Color(0xFFFFA500) else Color(0xFF4A7C59),
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }
                }
            }

            // photo tagging ui
            if (photosNeedingLocation.isNotEmpty() && currentPhotoIndex < photosNeedingLocation.size) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.9f))
                        .padding(16.dp)
                ) {
                    Text(
                        "Photo ${currentPhotoIndex + 1} of ${photosNeedingLocation.size}",
                        color = Color.White,
                        fontSize = 16.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // thumbnail with margin, smaller size
                    val currentPhoto = photosNeedingLocation[currentPhotoIndex]
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .padding(horizontal = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                android.widget.ImageView(ctx).apply {
                                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                    adjustViewBounds = true
                                }
                            },
                            update = { imageView ->
                                try {
                                    val bitmap = contentResolver.openInputStream(currentPhoto.uri)?.use {
                                        android.graphics.BitmapFactory.decodeStream(it)
                                    }
                                    imageView.setImageBitmap(bitmap)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error loading thumbnail: ${e.message}")
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    currentPhoto.timestamp?.let {
                        Text(
                            "Photo taken: ${it.format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))}",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Select location on trail", color = Color.White, fontSize = 14.sp)

                    androidx.compose.material3.Slider(
                        value = sliderPosition.toFloat(),
                        onValueChange = { selectedTrackpointIndex.value = it.toInt() },
                        valueRange = 0f..(points.size - 1).toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        "Trackpoint ${sliderPosition + 1} of ${points.size}",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(
                            onClick = {
                                val remaining = photosNeedingLocation.filterIndexed { i, _ -> i != currentPhotoIndex }
                                photosNeedingManualLocation.value = remaining
                                if (remaining.isEmpty()) {
                                    Toast.makeText(this@MainActivity, "Skipped all photos", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA500))
                        ) {
                            Text("Skip",
                                color = Color.White)
                        }

                        Button(onClick = { applyManualLocationToCurrentPhoto() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A7C59))) {
                            Text("Apply to This Photo",
                                color = Color.White)
                        }
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
        val trailColor by walkedTrailColor.collectAsState()

        val mapView = remember {
            MapView(context).apply {
                setTileSource(CartoVoyagerTileSource)
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

                            this@MainActivity.startNewTrailFromLocation(geoPoint)
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

        LaunchedEffect(points.size, points.lastOrNull(), isSelecting, startLat, startLon, sliderPosition, photosNeedingLocation.isNotEmpty(), trailColor) {
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
                        outlinePaint.color = trailColor
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

                // add selected trackpoint marker for photo location
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

    // helper functions

    override fun onResume() {
        super.onResume()
        StepCounterService.activeActivity.value = com.example.phantomtrail.service.ActiveActivity.MAIN
        isActive = true

        scope.launch {
            val stepData = repository.loadStepData()
            withContext(Dispatchers.Main) {
                StepCounterService.currentStepCount.value = stepData.steps
            }
        }
    }


    override fun onPause() {
        super.onPause()
        isActive = false
    }
    override fun onStop() {
        super.onStop()
        if (!isFinishing) {
            return
        }
        StepCounterService.activeActivity.value = com.example.phantomtrail.service.ActiveActivity.NONE
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    private fun startTracking() {
        StepCounterService.isMainRunning.value = true
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
        StepCounterService.isMainRunning.value = false
        val serviceIntent = Intent(this, StepCounterService::class.java).apply {
            action = StepCounterService.ACTION_STOP
        }
        startService(serviceIntent)
        Toast.makeText(this, "Paused tracking", Toast.LENGTH_SHORT).show()
    }

    private fun setStepLength() {
        val input = EditText(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.LTGRAY)
        }
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

    private fun setPathWaviness() {
        val input = EditText(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.LTGRAY)
        }
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        AlertDialog.Builder(this)
            .setTitle("Path Waviness")
            .setView(input)
            .setMessage("Current: ${pathWavinessDegrees.value}°\n\nHow much the walking direction randomly drifts, in degrees (0-90)\n\nHigher = wigglier path, lower = straighter path")
            .setPositiveButton("OK") { _, _ ->
                val value = input.text.toString()
                if (value.isNotEmpty()) {
                    val number = value.toDoubleOrNull()
                    if (number == null || number <= 0 || number > 90) {
                        Toast.makeText(this, "Enter a value between 0 and 90", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    pathWavinessDegrees.value = number
                    scope.launch { repository.savePathWaviness(number) }
                    Toast.makeText(this, "Path waviness: $number°", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun setUnits() {
        val options = arrayOf("Metric (km)", "Imperial (mi)")
        var selected = if (useImperial.value) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Units")
            .setSingleChoiceItems(options, selected) { _, which -> selected = which }
            .setPositiveButton("OK") { _, _ ->
                val imperial = selected == 1
                useImperial.value = imperial
                scope.launch { AppPreferences.setImperial(this@MainActivity, imperial) }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun setTrailColor() {
        val presets = listOf(
            "Green" to AndroidColor.rgb(74, 124, 89),
            "Blue" to AndroidColor.rgb(66, 133, 244),
            "Red" to AndroidColor.rgb(219, 68, 55),
            "Orange" to AndroidColor.rgb(255, 152, 0),
            "Purple" to AndroidColor.rgb(156, 39, 176),
            "Cyan" to AndroidColor.rgb(0, 188, 212),
            "Pink" to AndroidColor.rgb(233, 30, 99),
            "Yellow" to AndroidColor.rgb(255, 235, 59),
            "White" to AndroidColor.rgb(255, 255, 255)
        )
        val labels = presets.map { it.first }.toTypedArray()
        var selected = presets.indexOfFirst { it.second == walkedTrailColor.value }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Trail Color")
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton("OK") { _, _ ->
                val color = presets[selected].second
                walkedTrailColor.value = color
                scope.launch { AppPreferences.setWalkedTrailColor(this@MainActivity, color) }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun saveCurrentTrailToHistory() {
        val points = trailPoints.value
        if (points.size < 2) return
        val steps = StepCounterService.currentStepCount.value
        scope.launch {
            val timestamps = repository.loadStepData().timestamps
            repository.appendToPreviousTrails(
                PreviousTrail(
                    savedAt = java.time.ZonedDateTime.now(),
                    steps = steps,
                    trailPoints = points,
                    stepTimestamps = timestamps
                )
            )
        }
    }

    private fun showPreviousTrailsDialog() {
        scope.launch {
            val trails = repository.loadPreviousTrails()
            withContext(Dispatchers.Main) {
                if (trails.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No previous trails saved", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
                val labels = trails.map { it.savedAt.format(fmt) }.toTypedArray()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Previous Trails")
                    .setItems(labels) { _, which -> loadPreviousTrail(trails[which]) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun loadPreviousTrail(trail: PreviousTrail) {
        saveCurrentTrailToHistory()

        val firstPoint = trail.trailPoints.first()
        customStartLat.value = firstPoint.latitude
        customStartLon.value = firstPoint.longitude
        trailPoints.value = trail.trailPoints
        StepCounterService.currentStepCount.value = trail.steps
        lastProcessedSteps = trail.steps
        currentAngle = 0.0
        accumulatedDistance = 0.0

        scope.launch {
            repository.saveStartLocation(firstPoint.latitude, firstPoint.longitude)
            repository.saveTrailPoints(trail.trailPoints)
            repository.saveSteps(trail.steps)
            if (trail.stepTimestamps.isNotEmpty()) repository.saveTimestamps(trail.stepTimestamps)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Trail loaded", Toast.LENGTH_SHORT).show()
                selectedTab.value = 0
            }
        }
    }

    private fun stopAndResetSteps() {
        saveCurrentTrailToHistory()
        // stop service if running
        if (StepCounterService.isRunning.value) {
            val serviceIntent = Intent(this, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_STOP
            }
            startService(serviceIntent)
        }

        // reset all state
        StepCounterService.currentStepCount.value = 0
        trailPoints.value = listOf(GeoPoint(customStartLat.value, customStartLon.value))
        lastProcessedSteps = 0
        currentAngle = 0.0
        accumulatedDistance = 0.0

        scope.launch {
            repository.resetAllData()
            repository.saveStartLocation(customStartLat.value, customStartLon.value)
            repository.saveTrailPoints(trailPoints.value)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Reset complete", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startButton() {
        if (StepCounterService.currentStepCount.value <= 0) {
            showNewTrailDialog()
            return
        }
        val items = arrayOf("Start New Trail", "Continue Existing", "Reset to Current")
        var checkedItem = -1
        AlertDialog.Builder(this)
            .setTitle("Tracking options")
            .setSingleChoiceItems(items, checkedItem) { _, which -> checkedItem = which }
            .setPositiveButton("OK") { dialog, _ ->
                when (checkedItem) {
                    0 -> showNewTrailDialog()
                    1 -> startTracking()
                    2 -> resetToCurrentLocation()
                }
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showNewTrailDialog() {
        AlertDialog.Builder(this)
            .setTitle("Start location")
            .setItems(arrayOf("Select Start Location", "Keep Current Location")) { _, which ->
                when (which) {
                    0 -> {
                        isSelectingStartLocation.value = true
                        selectedTab.value = 0
                    }
                    1 -> scope.launch {
                        stopAndResetSteps()
                        delay(500)
                        withContext(Dispatchers.Main) { startTracking() }
                    }
                }
            }
            .show()
    }

    private fun startNewTrailFromLocation(geoPoint: GeoPoint) {
        saveCurrentTrailToHistory()
        if (StepCounterService.isRunning.value) {
            startService(Intent(this, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_STOP
            })
        }
        StepCounterService.isMainRunning.value = false
        StepCounterService.currentStepCount.value = 0
        customStartLat.value = geoPoint.latitude
        customStartLon.value = geoPoint.longitude
        trailPoints.value = listOf(geoPoint)
        lastProcessedSteps = 0
        currentAngle = 0.0
        accumulatedDistance = 0.0
        isSelectingStartLocation.value = false

        scope.launch {
            repository.resetAllData()
            repository.saveStartLocation(geoPoint.latitude, geoPoint.longitude)
            repository.saveTrailPoints(listOf(geoPoint))
            withContext(Dispatchers.Main) { startTracking() }
        }
    }

    private fun resetToCurrentLocation() {
        saveCurrentTrailToHistory()
        val currentPoint = trailPoints.value.lastOrNull() ?: run {
            Toast.makeText(this, "No current location", Toast.LENGTH_SHORT).show()
            return
        }

        if (StepCounterService.isRunning.value) {
            startService(Intent(this, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_STOP
            })
        }

        StepCounterService.currentStepCount.value = 0
        customStartLat.value = currentPoint.latitude
        customStartLon.value = currentPoint.longitude
        trailPoints.value = listOf(currentPoint)
        lastProcessedSteps = 0
        currentAngle = 0.0
        accumulatedDistance = 0.0

        scope.launch {
            repository.resetAllData()
            repository.saveStartLocation(currentPoint.latitude, currentPoint.longitude)
            repository.saveTrailPoints(listOf(currentPoint))
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Reset to current location", Toast.LENGTH_SHORT).show()
                startTracking()
            }
        }
    }


    private fun exportGPX() {
        scope.launch {
            val stepData = repository.loadStepData()
            Log.d(TAG, "Export check - saved steps: ${stepData.steps}, service steps: ${StepCounterService.currentStepCount.value}")

            val currentSteps = stepData.steps

            withContext(Dispatchers.Main) {
                if (currentSteps <= 0) {
                    Toast.makeText(this@MainActivity, "No steps recorded", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                val options = arrayOf("Share GPX", "Save to Downloads")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Export GPX")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> shareGPX()
                            1 -> saveGPXLocally()
                        }
                    }
                    .show()
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

    private fun shareGPX() {
        scope.launch {
            try {
                val stepData = repository.loadStepData()
                val points = repository.loadTrailPoints()

                Log.d(TAG, "shareGPX - steps: ${stepData.steps}, timestamps: ${stepData.timestamps.size}, points: ${points.size}")
                Log.d(TAG, "steps: ${stepData.steps}, timestamps: ${stepData.timestamps.size}")

                if (stepData.steps < 2) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Not enough steps", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val gpxFile = gpxExporter.generateGpxFile(
                    cacheDir,
                    points,
                    stepData.timestamps,
                    stepData.steps
                )

                withContext(Dispatchers.Main) {
                    shareGPXFile(gpxFile)  // This calls the existing function with File parameter
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Export failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveGPXLocally() {
        scope.launch {
            try {
                val stepData = repository.loadStepData()
                val points = repository.loadTrailPoints()
                scope.launch(Dispatchers.Main) {
                    StepCounterService.currentStepCount.collect { totalSteps ->
                        if (StepCounterService.activeActivity.value != com.example.phantomtrail.service.ActiveActivity.MAIN) return@collect
                        scope.launch {
                            repository.saveSteps(totalSteps)
                            // save timestamps from service
                            val timestamps = StepCounterService.getTimestamps()
                            repository.saveTimestamps(timestamps)
                        }
                        if (totalSteps > 0) updateTrailPoints(totalSteps)
                    }
                }
                if (stepData.steps < 2 || stepData.timestamps.size < 2) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Not enough steps", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // generate in cache first
                val tempFile = gpxExporter.generateGpxFile(
                    cacheDir,
                    points,
                    stepData.timestamps,
                    stepData.steps
                )

                // save to downloads
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, tempFile.name)
                        put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }

                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let {
                        contentResolver.openOutputStream(it)?.use { output ->
                            tempFile.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Saved to Downloads", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val savedFile = File(downloadsDir, tempFile.name)
                    tempFile.copyTo(savedFile, overwrite = true)

                    MediaScannerConnection.scanFile(
                        this@MainActivity,
                        arrayOf(savedFile.absolutePath),
                        arrayOf("application/gpx+xml"),
                        null
                    )

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Saved to Downloads/${savedFile.name}", Toast.LENGTH_LONG).show()
                    }
                }

                tempFile.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Save error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
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

    private fun openInMapsApp() {
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

                val photosInRange = mutableListOf<Uri>()
                val photosOutOfRange = mutableListOf<PhotoToTag>()

                for (uri in uris) {
                    val photoTime = getPhotoTimestamp(uri)
                    if (photoTime != null && (photoTime.isBefore(startTime) || photoTime.isAfter(endTime))) {
                        photosOutOfRange.add(PhotoToTag(uri, photoTime))
                    } else {
                        photosInRange.add(uri)
                    }
                }

                // process in-range photos
                if (photosInRange.isNotEmpty()) {
                    val result = photoProcessor.processPhotos(photosInRange, points, stepData.timestamps)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Auto-tagged ${result.successCount} photos", Toast.LENGTH_SHORT).show()
                    }
                }

                // show slider for out-of-range photos
                if (photosOutOfRange.isNotEmpty()) {
                    photosNeedingManualLocation.value = photosOutOfRange
                    selectedPhotoIndex.value = 0
                    selectedTrackpointIndex.value = points.size / 2
                    withContext(Dispatchers.Main) {
                        selectedTab.value = 0
                        Toast.makeText(this@MainActivity, "${photosOutOfRange.size} photos need manual location", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Photo error: ${e.message}", e)
            }
        }
    }

    private fun applyManualLocationToCurrentPhoto() {
        scope.launch {
            try {
                val photos = photosNeedingManualLocation.value
                val photoIndex = selectedPhotoIndex.value
                val points = repository.loadTrailPoints()
                val trackpointIndex = selectedTrackpointIndex.value.coerceIn(0, points.size - 1)

                if (photoIndex >= photos.size) return@launch

                val location = points[trackpointIndex]
                val result = photoProcessor.processPhotosWithFixedLocation(
                    listOf(photos[photoIndex].uri),
                    location
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Tagged photo ${photoIndex + 1}",
                        Toast.LENGTH_SHORT
                    ).show()

                    // move to next photo or close
                    val remaining = photos.filterIndexed { i, _ -> i != photoIndex }
                    if (remaining.isNotEmpty()) {
                        photosNeedingManualLocation.value = remaining
                        selectedPhotoIndex.value = 0
                        selectedTrackpointIndex.value = points.size / 2
                    } else {
                        photosNeedingManualLocation.value = emptyList()
                        Toast.makeText(this@MainActivity, "All photos tagged", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Manual tagging error: ${e.message}", e)
            }
        }
    }
    }
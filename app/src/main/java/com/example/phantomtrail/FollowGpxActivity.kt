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
import com.example.phantomtrail.data.StepRepository
import com.example.phantomtrail.data.FollowGpxStepRepository
import com.example.phantomtrail.service.StepCounterService
import com.example.phantomtrail.ui.theme.PhantomTrailTheme
import com.example.phantomtrail.utils.GpxExporter
import com.example.phantomtrail.utils.PhotoGpsProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.w3c.dom.Element
import android.os.Vibrator
import android.os.VibrationEffect
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import android.graphics.Color as AndroidColor
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import com.example.phantomtrail.utils.GeoUtils
import com.example.phantomtrail.PhotoToTag

class FollowGpxActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var baselineSet = false
    private var isActive = false
    private var accumulatedExtendedDistance = 0.0
    private lateinit var followGpxRepository: FollowGpxStepRepository
    private lateinit var gpxExporter: GpxExporter

    private lateinit var photoProcessor: PhotoGpsProcessor

    val photosNeedingManualLocation = MutableStateFlow<List<PhotoToTag>>(emptyList())
    val selectedPhotoIndex = MutableStateFlow(0)
    val selectedTrackpointIndex = MutableStateFlow(0)


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
        private const val TAG = "FollowGpxActivity"
        val followGpxSteps = MutableStateFlow(0)
        private var lastRawSensorValue = -1
        private val stepLengthMeters = MutableStateFlow(0.75)
        private val showMapFlow = MutableStateFlow(false)

        val importedTrailPoints = MutableStateFlow<List<GeoPoint>>(emptyList())
        val currentPosition = MutableStateFlow(0) // Index along trail
        val gpxImported = MutableStateFlow(false)
        val startStepCount = MutableStateFlow(0) // Steps when we started following GPX
        private val reachedEnd = MutableStateFlow(false)
        private val userChoseToContinue = MutableStateFlow(false)

        val extendedTrailPoints = MutableStateFlow<List<GeoPoint>>(emptyList())
        private var lastGeneratedSteps = 0
        private var continueAngle = 0.0
    }

    private val pickGpxFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not take persistent permission: ${e.message}")
            }
            importGpxFile(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            userAgentValue = packageName
            load(this@FollowGpxActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
        }

        followGpxRepository = FollowGpxStepRepository(this)
        photoProcessor = PhotoGpsProcessor(this, contentResolver)
        gpxExporter = GpxExporter()

        requestNecessaryPermissions()

        // Load Saved Data
        scope.launch {
            val stepData = followGpxRepository.loadStepData()
            val savedTrail = followGpxRepository.loadImportedTrail()
            val wasImported = followGpxRepository.isGpxImported()
            val savedExtended = followGpxRepository.loadExtendedTrail()
            val wasContinuing = followGpxRepository.wasUserContinuing()

            withContext(Dispatchers.Main) {
                if (savedTrail.isNotEmpty() && wasImported) {
                    importedTrailPoints.value = savedTrail
                    gpxImported.value = true
                }
                if (savedExtended.isNotEmpty() && wasContinuing) {
                    extendedTrailPoints.value = savedExtended
                    userChoseToContinue.value = true
                    reachedEnd.value = true
                    lastGeneratedSteps = stepData.steps
                }
                startStepCount.value = stepData.startStepCount
                stepLengthMeters.value = stepData.stepLength
                followGpxSteps.value = stepData.steps
                lastRawSensorValue = -1
            }
        }

        // Save steps when they update
        scope.launch(Dispatchers.Main) {
            StepCounterService.currentStepCount.collect { totalSteps ->
                if (StepCounterService.activeActivity.value != com.example.phantomtrail.service.ActiveActivity.FOLLOW_GPX) return@collect

                if (lastRawSensorValue == -1) {
                    lastRawSensorValue = totalSteps
                    return@collect
                }

                val delta = totalSteps - lastRawSensorValue
                if (delta > 0) {
                    followGpxSteps.value += delta
                    lastRawSensorValue = totalSteps
                    Log.d(TAG, "Raw: $totalSteps, Delta: $delta, FollowGpxSteps: ${followGpxSteps.value}")


                    val timestamps = StepCounterService.getTimestamps()
                    scope.launch {
                        followGpxRepository.saveSteps(followGpxSteps.value)
                        followGpxRepository.saveTimestamps(timestamps)
                    }
                }
                updatePositionOnTrail(followGpxSteps.value)
            }
        }

        setContent {
            val gpxSteps by followGpxSteps.collectAsState()
            val serviceTracking by StepCounterService.isFollowGpxRunning.collectAsState()
            val stepLength by stepLengthMeters.collectAsState()
            val showMap by showMapFlow.collectAsState()
            val hasGpx by gpxImported.collectAsState()

            PhantomTrailTheme {
                if (showMap && hasGpx) {
                    MapScreen(gpxSteps, serviceTracking)
                } else {
                    MainScreen(gpxSteps, serviceTracking, stepLength, hasGpx)
                }
            }
        }
    }



    override fun onResume() {
        super.onResume()
        StepCounterService.activeActivity.value = com.example.phantomtrail.service.ActiveActivity.FOLLOW_GPX
        isActive = true
        showMapFlow.value = false
        scope.launch {
            val stepData = followGpxRepository.loadStepData()
            withContext(Dispatchers.Main) {
                followGpxSteps.value = stepData.steps
                startStepCount.value = stepData.startStepCount
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isActive = false
    }

    override fun onStop() {
        super.onStop()
        // Only clear if not finishing (i.e. going to another activity)
        if (!isFinishing) {
            // Keep activeActivity as FOLLOW_GPX when minimized
            return
        }
        StepCounterService.activeActivity.value = com.example.phantomtrail.service.ActiveActivity.NONE
    }
    private fun requestNecessaryPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), 100)
        }
    }

    private fun importGpxFile(uri: Uri) {
        scope.launch {
            try {
                val resetIntent = Intent(this@FollowGpxActivity, StepCounterService::class.java).apply {
                    action = StepCounterService.ACTION_RESET
                }
                startService(resetIntent)

                Log.d(TAG, "Importing GPX from: $uri")
                val points = parseGpxFile(uri)
                Log.d(TAG, "Parsed ${points.size} points")

                if (points.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FollowGpxActivity, "No trail points in GPX", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Reset all state
                followGpxRepository.resetAllData()
                followGpxRepository.saveStartStepCount(0)

                withContext(Dispatchers.Main) {
                    importedTrailPoints.value = points
                    accumulatedExtendedDistance = 0.0
                    extendedTrailPoints.value = emptyList()
                    lastGeneratedSteps = 0
                    continueAngle = 0.0
                    followGpxSteps.value = 0
                    lastRawSensorValue = -1
                    importedTrailPoints.value = points
                    startStepCount.value = 0
                    StepCounterService.currentStepCount.value = 0
                    currentPosition.value = 0
                    reachedEnd.value = false
                    userChoseToContinue.value = false
                    gpxImported.value = true
                    showMapFlow.value = true
                    followGpxRepository.saveExtendedTrail(emptyList(), false)
                    extendedTrailPoints.value = emptyList()
                    userChoseToContinue.value = false
                    Toast.makeText(this@FollowGpxActivity, "Loaded ${points.size} points", Toast.LENGTH_SHORT).show()
                }
                followGpxRepository.saveImportedTrail(points)
            } catch (e: Exception) {
                Log.e(TAG, "Error importing GPX: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FollowGpxActivity, "Failed to import GPX", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parseGpxFile(uri: Uri): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()

        contentResolver.openInputStream(uri)?.use { input ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
            doc.documentElement.normalize()

            val trackpoints = doc.getElementsByTagName("trkpt")
            for (i in 0 until trackpoints.length) {
                val node = trackpoints.item(i) as Element
                val lat = node.getAttribute("lat").toDouble()
                val lon = node.getAttribute("lon").toDouble()
                points.add(GeoPoint(lat, lon))
            }
        }

        return points
    }

    private fun updatePositionOnTrail(totalSteps: Int) {
        val points = importedTrailPoints.value
        if (points.isEmpty()) return

        val walkedDistance = totalSteps * stepLengthMeters.value / 1000.0

        var totalDistance = 0.0
        for (i in 1 until points.size) {
            totalDistance += calculateDistance(points[i-1], points[i])
        }

        // If user chose to continue beyond trail
        if (userChoseToContinue.value) {
            currentPosition.value = points.size - 1
            generateExtendedTrail(totalSteps)
            return
        }

        // Find position on trail
        var accumulatedDistance = 0.0
        for (i in 1 until points.size) {
            val segmentDist = calculateDistance(points[i-1], points[i])
            if (accumulatedDistance + segmentDist >= walkedDistance) {
                currentPosition.value = i
                return
            }
            accumulatedDistance += segmentDist
        }

        currentPosition.value = points.size - 1

        if (!reachedEnd.value && !userChoseToContinue.value && walkedDistance >= totalDistance) {
            reachedEnd.value = true
            scope.launch(Dispatchers.Main) {
                vibratePhone()
                showEndDialog()
            }
        }
    }

    private fun generateExtendedTrail(totalSteps: Int) {
        scope.launch {
            val SCALE = 0.0001
            val angleVariability = Math.PI / 7

            val newSteps = totalSteps - lastGeneratedSteps
            if (newSteps <= 0) return@launch

            val extended = extendedTrailPoints.value.toMutableList()

            val newDistance = newSteps * stepLengthMeters.value // meters
            accumulatedExtendedDistance += newDistance

            // Each SCALE step = ~11 meters
            val metersPerPoint = 11.0
            val newPointsToAdd = (accumulatedExtendedDistance / metersPerPoint).toInt()

            if (newPointsToAdd > 0) {
                accumulatedExtendedDistance -= newPointsToAdd * metersPerPoint

                var lat = extended.last().latitude
                var lon = extended.last().longitude

                repeat(newPointsToAdd) {
                    lat += kotlin.math.cos(continueAngle) * SCALE
                    lon += kotlin.math.sin(continueAngle) * SCALE
                    extended.add(GeoPoint(lat, lon))
                    continueAngle += (Math.random() * angleVariability) - (angleVariability / 2.0)
                }

                lastGeneratedSteps = totalSteps

                withContext(Dispatchers.Main) {
                    extendedTrailPoints.value = extended.toList()
                }
            } else {
                lastGeneratedSteps = totalSteps
            }
            withContext(Dispatchers.Main) {
                extendedTrailPoints.value = extended.toList()
                // Save extended trail
                scope.launch {
                    followGpxRepository.saveExtendedTrail(extended, true)
                }
            }
        }
    }

    private fun vibratePhone() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Vibrate permission denied")
        }
    }

    private fun showEndDialog() {
        AlertDialog.Builder(this)
            .setTitle("Trail Complete!")
            .setMessage("You've reached the end of the imported trail. Continue tracking or stop here?")
            .setPositiveButton("Continue") { dialog, _ ->
                userChoseToContinue.value = true
                extendedTrailPoints.value = listOf(importedTrailPoints.value.last()) // Start from last point
                lastGeneratedSteps = followGpxSteps.value
                continueAngle = 0.0
                Toast.makeText(this, "Continuing beyond trail", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Stop") { dialog, _ ->
                pauseTracking()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun calculateDistance(p1: GeoPoint, p2: GeoPoint): Double {
        val R = 6371.0
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(p1.latitude)) * kotlin.math.cos(Math.toRadians(p2.latitude)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }

    @Composable
    fun MainScreen(serviceSteps: Int, serviceTracking: Boolean, stepLength: Double, hasGpx: Boolean) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().background(Color.Black)
        ) {
            Text("Follow GPX", color = Color(0xFF7B9E87), fontSize = 30.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text("steps", color = Color(0xFF7B9E87), fontSize = 24.sp)
            Text("$serviceSteps", color = Color.White, fontSize = 50.sp)
            Text(
                String.format("%.2f km", serviceSteps * stepLength / 1000.0),
                color = Color.Gray,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                if (serviceTracking) "tracking..." else "stopped",
                color = if (serviceTracking) Color(0xFF4A7C59) else Color.Gray,
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.height(32.dp))

            if (!hasGpx) {
                Button(
                    onClick = {
                        pickGpxFile.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A7C59))
                ) {
                    Text("Import GPX", color = Color.White, fontSize = 18.sp)
                }
            } else {
                Button(
                    onClick = { if (serviceTracking) pauseTracking() else startTracking() },
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
                    Text("export", color = Color.White, fontSize = 18.sp)
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
                    onClick = { openInMapsApp() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF606060))
                ) {
                    Text("location", color = Color.White, fontSize = 18.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { showMapFlow.value = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF606060))
                ) {
                    Text("map", color = Color.White, fontSize = 18.sp)
                }
            }
        }
    }

    @Composable
    fun MapScreen(serviceSteps: Int, serviceTracking: Boolean) {
        val photosNeedingLocation by photosNeedingManualLocation.collectAsState()
        val currentPhotoIndex by selectedPhotoIndex.collectAsState()
        val sliderPosition by selectedTrackpointIndex.collectAsState()
        val points by importedTrailPoints.collectAsState()
        val extendedPoints by extendedTrailPoints.collectAsState()

        val combinedPoints = remember(points, extendedPoints) {
            if (extendedPoints.isNotEmpty()) points + extendedPoints else points
        }

        Box(modifier = Modifier.fillMaxSize()) {
            OSMMapView()

            val showButtons = photosNeedingLocation.isEmpty()

            if (showButtons) {
                Button(
                    onClick = { showMapFlow.value = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF606060))
                ) {
                    Text("close", color = Color.White)
                }

                Button(
                    onClick = {},
                    modifier = Modifier.align(Alignment.BottomCenter).padding(128.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF606060))
                ) {
                    Text("$serviceSteps", color = Color.White)
                }

                Button(
                    onClick = { if (serviceTracking) pauseTracking() else startTracking() },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (serviceTracking) Color(0xFFFFA500) else Color(0xFF4A7C59)
                    )
                ) {
                    Text(if (serviceTracking) "pause" else "start", color = Color.White)
                }
            }

            // Photo tagging slider
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
                            modifier = Modifier.fillMaxWidth().height(100.dp)
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
                        valueRange = 0f..(combinedPoints.size - 1).coerceAtLeast(1).toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        "Trackpoint ${sliderPosition + 1} of ${combinedPoints.size}",
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
                                val remaining = photosNeedingLocation.toMutableList().also { it.removeAt(currentPhotoIndex) }
                                photosNeedingManualLocation.value = remaining
                                if (remaining.isEmpty()) showMapFlow.value = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF606060))
                        ) {
                            Text("Skip")
                        }

                        Button(onClick = { applyManualLocationToCurrentPhoto() }) {
                            Text("Apply to This Photo")
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun OSMMapView() {
        val context = LocalContext.current
        val points by importedTrailPoints.collectAsState()
        val extendedPoints by extendedTrailPoints.collectAsState()
        val position by currentPosition.collectAsState()
        val gpxSteps by followGpxSteps.collectAsState()
        val isContinuing by userChoseToContinue.collectAsState()
        val photosNeedingLocation by photosNeedingManualLocation.collectAsState()
        val sliderPosition by selectedTrackpointIndex.collectAsState()

        // Combine imported + extended for full trail
        val combinedPoints = remember(points, extendedPoints) {
            if (extendedPoints.isNotEmpty()) points + extendedPoints else points
        }

        val mapView = remember {
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(16.0)
            }
        }

        LaunchedEffect(points.size, position, gpxSteps, extendedPoints.size, sliderPosition, photosNeedingLocation.isNotEmpty()) {
            mapView.overlays.clear()

            if (points.isNotEmpty()) {
                // Draw imported trail
                val polyline = Polyline().apply {
                    setPoints(points)
                    outlinePaint.color = AndroidColor.rgb(123, 158, 135)
                    outlinePaint.strokeWidth = 12f
                }
                mapView.overlays.add(polyline)

                // Draw extended trail
                if (isContinuing && extendedPoints.size > 1) {
                    val extendedPolyline = Polyline().apply {
                        setPoints(extendedPoints)
                        outlinePaint.color = AndroidColor.rgb(255, 165, 0)
                        outlinePaint.strokeWidth = 12f
                    }
                    mapView.overlays.add(extendedPolyline)
                }

                // Start marker
                val startMarker = Marker(mapView).apply {
                    this.position = points.first()
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Start"
                    icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
                    icon?.setTint(AndroidColor.rgb(0, 255, 0))
                }
                mapView.overlays.add(startMarker)

                // Photo slider marker - use combined trail
                if (photosNeedingLocation.isNotEmpty() && sliderPosition < combinedPoints.size) {
                    val sliderMarker = Marker(mapView).apply {
                        this.position = combinedPoints[sliderPosition]
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = "Photo Location"
                        icon = context.getDrawable(android.R.drawable.ic_menu_camera)
                        icon?.setTint(AndroidColor.rgb(255, 0, 255))
                    }
                    mapView.overlays.add(sliderMarker)
                    mapView.controller.animateTo(combinedPoints[sliderPosition])
                } else {
                    // Current position marker
                    val currentPoint = if (isContinuing && extendedPoints.isNotEmpty()) {
                        extendedPoints.last()
                    } else {
                        val safePosition = position.coerceIn(0, points.size - 1)
                        points[safePosition]
                    }

                    val currentMarker = Marker(mapView).apply {
                        this.position = currentPoint
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = "You"
                        snippet = "$gpxSteps steps"
                        icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
                        icon?.setTint(AndroidColor.rgb(255, 165, 0))
                    }
                    mapView.overlays.add(currentMarker)
                    mapView.controller.animateTo(currentPoint)

                    // End marker
                    if (!isContinuing) {
                        val endMarker = Marker(mapView).apply {
                            this.position = points.last()
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "End"
                            icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
                            icon?.setTint(AndroidColor.rgb(255, 0, 0))
                        }
                        mapView.overlays.add(endMarker)
                    }
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

    private fun startTracking() {
        if (gpxImported.value && StepCounterService.currentStepCount.value > startStepCount.value) {
            // Already tracking - show options
            val items = arrayOf("Import New GPX", "Continue Current")
            var checkedItems = -1

            AlertDialog.Builder(this)
                .setTitle("Tracking options")
                .setSingleChoiceItems(items, checkedItems) { _, which -> checkedItems = which }
                .setPositiveButton("OK") { dialog, _ ->
                    when (checkedItems) {
                        0 -> {
                            // Import new GPX - reset everything
                            scope.launch {
                                stopAndReset()
                                withContext(Dispatchers.Main) {
                                    pickGpxFile.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*"))
                                }
                            }
                        }
                        1 -> startService()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("CANCEL") { dialog, _ -> dialog.dismiss() }
                .show()
        } else {
            startService()
        }
    }

    private fun startService() {
        val savedSteps = followGpxSteps.value
        lastRawSensorValue = -1
        StepCounterService.isFollowGpxRunning.value = true
        val serviceIntent = Intent(this, StepCounterService::class.java).apply {
            action = StepCounterService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        scope.launch {
            delay(600) // Wait for service to initialize
            withContext(Dispatchers.Main) {
                followGpxSteps.value = savedSteps
                Log.d(TAG, "Restored followGpxSteps to $savedSteps after service restart")
            }
        }
        Toast.makeText(this, "Started tracking", Toast.LENGTH_SHORT).show()
    }
    private fun stopAndReset() {
        if (StepCounterService.isFollowGpxRunning.value) {
            val serviceIntent = Intent(this, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_STOP
            }
            startService(serviceIntent)
            StepCounterService.isFollowGpxRunning.value = false
        }

        importedTrailPoints.value = emptyList()
        gpxImported.value = false
        currentPosition.value = 0
        startStepCount.value = 0
        reachedEnd.value = false
        userChoseToContinue.value = false
        extendedTrailPoints.value = emptyList()
        showMapFlow.value = false
        lastGeneratedSteps = 0
        accumulatedExtendedDistance = 0.0

        scope.launch {
            followGpxRepository.resetAllData()
            followGpxRepository.saveExtendedTrail(emptyList(), false)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@FollowGpxActivity, "Reset - select new GPX", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pauseTracking() {
        StepCounterService.isFollowGpxRunning.value = false
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
                    stepLengthMeters.value = value.toDouble()
                    Toast.makeText(this, "Step length: $value m", Toast.LENGTH_SHORT).show()
                }
            }
            .setMessage("Current: ${stepLengthMeters.value}m")
            .setNegativeButton("CANCEL", null)
            .show()
    }
    private fun exportGPX() {
        val currentSteps = followGpxSteps.value
        if (currentSteps <= 0) {
            Toast.makeText(this, "No steps recorded", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf("Share GPX", "Save to Downloads")
        AlertDialog.Builder(this)
            .setTitle("Export GPX")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> shareGPX()
                    1 -> saveGPXLocally()
                }
            }
            .show()
    }

    private fun shareGPX() {
        scope.launch {
            try {
                val stepData = followGpxRepository.loadStepData()
                val importedPoints = importedTrailPoints.value
                val extendedPoints = extendedTrailPoints.value

                Log.d(TAG, "Export - steps: ${stepData.steps}, timestamps: ${stepData.timestamps.size}, imported: ${importedPoints.size}, extended: ${extendedPoints.size}")

                if (stepData.steps < 2 || stepData.timestamps.size < 2) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FollowGpxActivity, "Not enough steps", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Combine imported trail up to walked distance + extended trail
                val walkedDistance = followGpxSteps.value * stepLengthMeters.value / 1000.0

                // Calculate total imported trail distance
                var importedDistance = 0.0
                for (i in 1 until importedPoints.size) {
                    importedDistance += calculateDistance(importedPoints[i-1], importedPoints[i])
                }

                // Build points that represent actual walked route
                val walkedPoints = if (walkedDistance <= importedDistance) {
                    // Still on imported trail - trim to walked distance
                    val trimmedPoints = mutableListOf<GeoPoint>()
                    var accumulated = 0.0
                    trimmedPoints.add(importedPoints.first())
                    for (i in 1 until importedPoints.size) {
                        val segDist = calculateDistance(importedPoints[i-1], importedPoints[i])
                        if (accumulated + segDist >= walkedDistance) break
                        accumulated += segDist
                        trimmedPoints.add(importedPoints[i])
                    }
                    trimmedPoints
                } else {
                    // Went beyond - use all imported + extended
                    importedPoints + extendedPoints
                }

                val gpxFile = gpxExporter.generateGpxFile(
                    cacheDir,
                    walkedPoints,
                    stepData.timestamps,
                    stepData.steps
                )

                withContext(Dispatchers.Main) {
                    shareGPXFile(gpxFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FollowGpxActivity, "Export failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveGPXLocally() {
        scope.launch {
            try {
                val stepData = followGpxRepository.loadStepData()
                val importedPoints = importedTrailPoints.value
                val extendedPoints = extendedTrailPoints.value


                if (stepData.steps < 2 || stepData.timestamps.size < 2) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FollowGpxActivity, "Not enough steps", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val tempFile = gpxExporter.generateGpxFile(
                    cacheDir,
                    importedPoints,
                    stepData.timestamps,
                    stepData.steps
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, tempFile.name)
                        put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let {
                        contentResolver.openOutputStream(it)?.use { output ->
                            tempFile.inputStream().use { input -> input.copyTo(output) }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FollowGpxActivity, "Saved to Downloads", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val savedFile = File(downloadsDir, tempFile.name)
                    tempFile.copyTo(savedFile, overwrite = true)
                    MediaScannerConnection.scanFile(this@FollowGpxActivity, arrayOf(savedFile.absolutePath), arrayOf("application/gpx+xml"), null)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FollowGpxActivity, "Saved to Downloads/${savedFile.name}", Toast.LENGTH_LONG).show()
                    }
                }
                tempFile.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Save error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FollowGpxActivity, "Save failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareGPXFile(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
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

    private fun processPhotosWithGPS(uris: List<Uri>) {
        scope.launch {
            try {
                val stepData = followGpxRepository.loadStepData()
                val points = importedTrailPoints.value

                if (points.isEmpty() || stepData.timestamps.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FollowGpxActivity, "No trail data", Toast.LENGTH_LONG).show()
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

                if (photosInRange.isNotEmpty()) {
                    val result = photoProcessor.processPhotos(photosInRange, points, stepData.timestamps)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FollowGpxActivity, "Auto-tagged ${result.successCount} photos", Toast.LENGTH_SHORT).show()
                    }
                }

                if (photosOutOfRange.isNotEmpty()) {
                    photosNeedingManualLocation.value = photosOutOfRange
                    selectedPhotoIndex.value = 0
                    selectedTrackpointIndex.value = points.size / 2
                    withContext(Dispatchers.Main) {
                        showMapFlow.value = true  // ADD THIS - open map
                        Toast.makeText(this@FollowGpxActivity, "${photosOutOfRange.size} photos need manual location", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Photo error: ${e.message}", e)
            }
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

    private fun applyManualLocationToCurrentPhoto() {
        scope.launch {
            try {
                val photos = photosNeedingManualLocation.value
                val photoIndex = selectedPhotoIndex.value

                // Use combined trail instead of just imported
                val importedPoints = importedTrailPoints.value
                val extended = extendedTrailPoints.value
                val combinedPoints = if (extended.isNotEmpty()) importedPoints + extended else importedPoints

                val trackpointIndex = selectedTrackpointIndex.value.coerceIn(0, combinedPoints.size - 1)

                if (photoIndex >= photos.size) return@launch

                val location = combinedPoints[trackpointIndex]  // Use combinedPoints
                val photoUri = photos[photoIndex].uri
                val result = photoProcessor.processPhotosWithFixedLocation(listOf(photoUri), location)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FollowGpxActivity, "Tagged photo ${photoIndex + 1}", Toast.LENGTH_SHORT).show()
                    val remaining = photos.toMutableList().also { it.removeAt(photoIndex) }
                    if (remaining.isNotEmpty()) {
                        photosNeedingManualLocation.value = remaining
                        selectedPhotoIndex.value = 0
                        selectedTrackpointIndex.value = combinedPoints.size / 2
                    } else {
                        photosNeedingManualLocation.value = emptyList<PhotoToTag>()
                        Toast.makeText(this@FollowGpxActivity, "All photos tagged", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Manual tagging error: ${e.message}", e)
            }
        }
    }

    private fun openInMapsApp() {
        val currentPoint = if (userChoseToContinue.value && extendedTrailPoints.value.isNotEmpty()) {
            extendedTrailPoints.value.last()
        } else {
            importedTrailPoints.value.getOrNull(currentPosition.value)
        }

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

}


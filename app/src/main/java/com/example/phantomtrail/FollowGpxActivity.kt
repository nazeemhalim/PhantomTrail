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

class FollowGpxActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var repository: StepRepository
    private lateinit var photoProcessor: PhotoGpsProcessor
    private lateinit var gpxExporter: GpxExporter

    companion object {
        private const val TAG = "FollowGpxActivity"

        private val stepLengthMeters = MutableStateFlow(0.75)
        private val showMapFlow = MutableStateFlow(false)

        val importedTrailPoints = MutableStateFlow<List<GeoPoint>>(emptyList())
        val currentPosition = MutableStateFlow(0) // Index along trail
        val gpxImported = MutableStateFlow(false)
        val startStepCount = MutableStateFlow(0) // Steps when we started following GPX
        private val reachedEnd = MutableStateFlow(false)
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

        repository = StepRepository(this)
        photoProcessor = PhotoGpsProcessor(this, contentResolver)
        gpxExporter = GpxExporter()

        requestNecessaryPermissions()

        // Observe step count to update position along trail
        scope.launch(Dispatchers.Main) {
            StepCounterService.currentStepCount.collect { totalSteps ->
                updatePositionOnTrail(totalSteps)
            }
        }

        setContent {
            val serviceSteps by StepCounterService.currentStepCount.collectAsState()
            val serviceTracking by StepCounterService.isRunning.collectAsState()
            val stepLength by stepLengthMeters.collectAsState()
            val showMap by showMapFlow.collectAsState()
            val hasGpx by gpxImported.collectAsState()
            val startSteps by startStepCount.collectAsState()

            // Calculate steps since starting GPX follow
            val gpxSteps = (serviceSteps - startSteps).coerceAtLeast(0)

            PhantomTrailTheme {
                if (showMap && hasGpx) {
                    MapScreen(gpxSteps, serviceTracking)
                } else {
                    MainScreen(gpxSteps, serviceTracking, stepLength, hasGpx)
                }
            }
        }
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
                Log.d(TAG, "Importing GPX from: $uri")
                val points = parseGpxFile(uri)
                Log.d(TAG, "Parsed ${points.size} points")

                if (points.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FollowGpxActivity, "No trail points in GPX", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                importedTrailPoints.value = points
                gpxImported.value = true
                currentPosition.value = 0
                startStepCount.value = StepCounterService.currentStepCount.value // Record current steps

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FollowGpxActivity, "Loaded ${points.size} points", Toast.LENGTH_SHORT).show()
                    showMapFlow.value = true
                }
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

        val gpxSteps = (totalSteps - startStepCount.value).coerceAtLeast(0)

        // Calculate total trail distance
        var totalDistance = 0.0
        for (i in 1 until points.size) {
            totalDistance += calculateDistance(points[i-1], points[i])
        }

        val walkedDistance = gpxSteps * stepLengthMeters.value / 1000.0

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

        // Reached or passed the end
        currentPosition.value = points.size - 1

        // Show end dialog only once
        if (!reachedEnd.value && walkedDistance >= totalDistance) {
            reachedEnd.value = true
            scope.launch(Dispatchers.Main) {
                vibratePhone()
                showEndDialog()
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
                // Keep tracking, just dismiss
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
        Box(modifier = Modifier.fillMaxSize()) {
            OSMMapView()

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
    }

    @Composable
    fun OSMMapView() {
        val context = LocalContext.current
        val points by importedTrailPoints.collectAsState()
        val position by currentPosition.collectAsState()
        val serviceSteps by StepCounterService.currentStepCount.collectAsState()
        val startSteps by startStepCount.collectAsState()

        val gpxSteps = (serviceSteps - startSteps).coerceAtLeast(0)

        val mapView = remember {
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(16.0)
            }
        }

        LaunchedEffect(points.size, position, gpxSteps) { // Added gpxSteps to trigger updates
            mapView.overlays.clear()

            if (points.isNotEmpty()) {
                // Draw full trail
                val polyline = Polyline().apply {
                    setPoints(points)
                    outlinePaint.color = AndroidColor.rgb(123, 158, 135)
                    outlinePaint.strokeWidth = 12f
                }
                mapView.overlays.add(polyline)

                // Start marker
                val startMarker = Marker(mapView).apply {
                    this.position = points.first()
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Start"
                    icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
                    icon?.setTint(AndroidColor.rgb(0, 255, 0))
                }
                mapView.overlays.add(startMarker)

                // Current position marker
                val safePosition = position.coerceIn(0, points.size - 1)
                val currentMarker = Marker(mapView).apply {
                    this.position = points[safePosition]
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "You"
                    snippet = "$gpxSteps steps"
                    icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
                    icon?.setTint(AndroidColor.rgb(255, 165, 0))
                }
                mapView.overlays.add(currentMarker)
                mapView.controller.animateTo(points[safePosition])

                // End marker
                val endMarker = Marker(mapView).apply {
                    this.position = points.last()
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "End"
                    icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
                    icon?.setTint(AndroidColor.rgb(255, 0, 0))
                }
                mapView.overlays.add(endMarker)
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

    private fun stopAndReset() {
        // Stop service if running
        if (StepCounterService.isRunning.value) {
            val serviceIntent = Intent(this, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_STOP
            }
            startService(serviceIntent)
        }

        // Reset GPX state
        importedTrailPoints.value = emptyList()
        gpxImported.value = false
        currentPosition.value = 0
        startStepCount.value = 0
        reachedEnd.value = false // Reset this too
        showMapFlow.value = false

        scope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@FollowGpxActivity, "Reset - select new GPX", Toast.LENGTH_SHORT).show()
            }
        }


        // Reset GPX state
        importedTrailPoints.value = emptyList()
        gpxImported.value = false
        currentPosition.value = 0
        startStepCount.value = 0
        showMapFlow.value = false

        scope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@FollowGpxActivity, "Reset - select new GPX", Toast.LENGTH_SHORT).show()
            }
        }
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
                    stepLengthMeters.value = value.toDouble()
                    Toast.makeText(this, "Step length: $value m", Toast.LENGTH_SHORT).show()
                }
            }
            .setMessage("Current: ${stepLengthMeters.value}m")
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
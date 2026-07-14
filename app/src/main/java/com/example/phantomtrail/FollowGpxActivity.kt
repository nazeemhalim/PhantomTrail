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
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TravelExplore
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
import com.example.phantomtrail.data.AppPreferences
import com.example.phantomtrail.data.StepRepository
import com.example.phantomtrail.data.FollowGpxStepRepository
import com.example.phantomtrail.data.PreviousTrail
import com.example.phantomtrail.utils.UnitUtils
import com.example.phantomtrail.service.StepCounterService
import com.example.phantomtrail.ui.theme.HandjetFontFamily
import com.example.phantomtrail.ui.theme.PhantomTrailTheme
import com.example.phantomtrail.utils.GpxExporter
import com.example.phantomtrail.utils.PhotoGpsProcessor
import com.example.phantomtrail.utils.RandomRoadGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.w3c.dom.Element
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import android.graphics.Color as AndroidColor
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import com.example.phantomtrail.FollowRandomRoad.Companion.trailPoints
import com.example.phantomtrail.utils.GeoUtils
import com.example.phantomtrail.PhotoToTag
import kotlin.Triple

class FollowGpxActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var baselineSet = false
    private var isActive = false
    private var accumulatedExtendedDistance = 0.0
    private val roadGenerator = RandomRoadGenerator()
    private var roadFetchInProgress = false
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
        private val loopClosingThresholdKm = MutableStateFlow(0.01) // default 10 metres
        val followGpxSteps = MutableStateFlow(0)
        private var lastRawSensorValue = -1
        private val stepLengthMeters = MutableStateFlow(0.75)
        private val searchRadiusMeters = MutableStateFlow(1000)
        const val MAX_SEARCH_RADIUS_METERS = 10000
        private val pathWavinessMeters = MutableStateFlow(2.0)
        private val useImperial = MutableStateFlow(false)
        private val walkedTrailColor = MutableStateFlow(AppPreferences.DEFAULT_WALKED_TRAIL_COLOR)
        val selectedTab = MutableStateFlow(0) // 0=Map, 1=Actions, 2=Settings

        val importedTrailPoints = MutableStateFlow<List<GeoPoint>>(emptyList())
        val currentPosition = MutableStateFlow(0)
        val gpxImported = MutableStateFlow(false)
        val startStepCount = MutableStateFlow(0)
        private val reachedEnd = MutableStateFlow(false)
        private val userChoseToContinue = MutableStateFlow(false)
        val isLoopTrail = MutableStateFlow(false)

        // reversing
        private val isReversing = MutableStateFlow(false)
        private val reverseStartSteps = MutableStateFlow(0)
        private val pendingReverse = MutableStateFlow(false) // walk to end first, then reverse
        private val isReversingFromExtended = MutableStateFlow(false) // reversing through off-trail extension
        private var extendedReverseStartSteps = 0
        private var segmentBaselineSteps = 0
        private var trailResetIndex = 0
        val trailResetIndexState = MutableStateFlow(0)
        private var exportStartIndex = 0  // preserved across loop laps, trailResetIndex clears to 0 after first lap
        private var completedLaps = 0
        val extendedStartTrailPoints = MutableStateFlow<List<GeoPoint>>(emptyList())
        private val userChoseToContinueFromStart = MutableStateFlow(false)
        private var lastStartExtendedSteps = 0
        private var startExtendedAngle = 0.0
        private var accumulatedStartExtendedDistance = 0.0

        val extendedTrailPoints = MutableStateFlow<List<GeoPoint>>(emptyList())
        private var lastGeneratedSteps = 0
        private var continueAngle = 0.0
        // marker position during extended-trail reverse
        val extendedReverseMarker = MutableStateFlow<GeoPoint?>(null)
        // reversing back through the start extension
        private val isReversingFromStartExtended = MutableStateFlow(false)
        private var startExtendedReverseStartSteps = 0
        val startExtendedReverseMarker = MutableStateFlow<GeoPoint?>(null)

        // road-following extension state
        val continueAsRoad = MutableStateFlow(false)
        private val fullRoadPath = MutableStateFlow<List<GeoPoint>>(emptyList())
        private var roadPathConsumedIdx = 0
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

        // load saved data
        scope.launch {
            val stepData = followGpxRepository.loadStepData()
            val savedTrail = followGpxRepository.loadImportedTrail()
            val wasImported = followGpxRepository.isGpxImported()
            val savedExtended = followGpxRepository.loadExtendedTrail()
            val wasContinuing = followGpxRepository.wasUserContinuing()
            val savedExtendedStart = followGpxRepository.loadExtendedStartTrail()
            val wasContinuingFromStart = followGpxRepository.wasUserContinuingFromStart()
            val wasRoad = followGpxRepository.wasContinueAsRoad()
            val wasTracking = followGpxRepository.wasTracking()
            val imperial = AppPreferences.isImperial(this@FollowGpxActivity)
            val trailColor = AppPreferences.getWalkedTrailColor(this@FollowGpxActivity)

            withContext(Dispatchers.Main) {
                if (savedTrail.isNotEmpty() && wasImported) {
                    importedTrailPoints.value = savedTrail
                    gpxImported.value = true
                }
                continueAsRoad.value = wasRoad
                if (savedExtended.isNotEmpty() && wasContinuing) {
                    extendedTrailPoints.value = savedExtended
                    userChoseToContinue.value = true
                    reachedEnd.value = true
                    lastGeneratedSteps = stepData.steps
                    // drip-feed road path is regenerated on demand from the last point
                    roadPathConsumedIdx = 0
                    fullRoadPath.value = emptyList()
                }
                if (savedExtendedStart.isNotEmpty() && wasContinuingFromStart) {
                    extendedStartTrailPoints.value = savedExtendedStart
                    userChoseToContinueFromStart.value = true
                    lastStartExtendedSteps = stepData.steps
                }
                startStepCount.value = stepData.startStepCount
                stepLengthMeters.value = stepData.stepLength
                searchRadiusMeters.value = stepData.searchRadiusMeters
                loopClosingThresholdKm.value = stepData.loopClosingThresholdMeters / 1000.0
                pathWavinessMeters.value = stepData.pathWavinessMeters
                useImperial.value = imperial
                walkedTrailColor.value = trailColor
                followGpxSteps.value = stepData.steps
                lastRawSensorValue = -1

                // if tracking was active when the app/process was killed, resume it instead of
                // silently landing back on the idle screen
                if (wasTracking) {
                    startService()
                }
            }
        }

        // save steps when they update
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
            val tab by selectedTab.collectAsState()
            val hasGpx by gpxImported.collectAsState()

            LaunchedEffect(hasGpx) {
                if (!hasGpx) selectedTab.value = 0
            }

            PhantomTrailTheme {
                Scaffold(
                    containerColor = Color.Black,
                    bottomBar = {
                        BottomNavBar(tab, hasGpx) { newTab ->
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
                                0 -> MapScreen(gpxSteps, serviceTracking, hasGpx)
                                1 -> if (hasGpx) ActionsScreen()
                                2 -> if (hasGpx) SettingsScreen()
                            }
                        }
                    }
                }
            }
        }
    }



    override fun onResume() {
        super.onResume()
        StepCounterService.activeActivity.value = com.example.phantomtrail.service.ActiveActivity.FOLLOW_GPX
        isActive = true
        selectedTab.value = 0
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
        // only clear if not finishing (i.e. going to another activity)
        if (!isFinishing) {
            // keep activeActivity as FOLLOW_GPX when minimized
            return
        }
        StepCounterService.activeActivity.value = com.example.phantomtrail.service.ActiveActivity.NONE
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
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

                // archive the trail being replaced before wiping session state
                saveCurrentTrailToHistory()

                // reset all state
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
                    selectedTab.value = 0
                    followGpxRepository.saveExtendedTrail(emptyList(), false)
                    extendedTrailPoints.value = emptyList()
                    userChoseToContinue.value = false

                    isReversing.value = false
                    reverseStartSteps.value = 0
                    segmentBaselineSteps = 0
                    trailResetIndex = 0; trailResetIndexState.value = 0
                    exportStartIndex = 0
                    extendedStartTrailPoints.value = emptyList()
                    userChoseToContinueFromStart.value = false
                    lastStartExtendedSteps = 0
                    startExtendedAngle = 0.0
                    accumulatedStartExtendedDistance = 0.0
                    isLoopTrail.value = false
                    completedLaps = 0
                    pendingReverse.value = false
                    isReversingFromExtended.value = false
                    extendedReverseStartSteps = 0
                    extendedReverseMarker.value = null
                    isReversingFromStartExtended.value = false
                    startExtendedReverseStartSteps = 0
                    startExtendedReverseMarker.value = null
                    Toast.makeText(this@FollowGpxActivity, "Loaded ${points.size} points", Toast.LENGTH_SHORT).show()

                    // loop availability is surfaced in the start dialog, not at import time
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

        var totalDistance = 0.0
        for (i in 1 until points.size) {
            totalDistance += calculateDistance(points[i-1], points[i])
        }

        // reversing back through the start extension
        if (isReversingFromStartExtended.value) {
            val ext = extendedStartTrailPoints.value
            val stepsInReverse = totalSteps - startExtendedReverseStartSteps
            val reverseDistance = stepsInReverse * stepLengthMeters.value / 1000.0

            var extTotalDist = 0.0
            for (i in 1 until ext.size) extTotalDist += calculateDistance(ext[i - 1], ext[i])

            val remainingExtDist = extTotalDist - reverseDistance

            if (remainingExtDist <= 0 || ext.size < 2) {
                // finished reversing start extension, resume forward walk from A
                isReversingFromStartExtended.value = false
                startExtendedReverseMarker.value = null
                userChoseToContinueFromStart.value = false
                segmentBaselineSteps = totalSteps
                currentPosition.value = 0
                return
            }

            var acc = 0.0
            var markerPoint = ext.last()
            for (i in 1 until ext.size) {
                val seg = calculateDistance(ext[i - 1], ext[i])
                if (acc + seg >= remainingExtDist) {
                    val t = (remainingExtDist - acc) / seg
                    val lat = ext[i - 1].latitude + (ext[i].latitude - ext[i - 1].latitude) * t
                    val lon = ext[i - 1].longitude + (ext[i].longitude - ext[i - 1].longitude) * t
                    markerPoint = GeoPoint(lat, lon)
                    break
                }
                acc += seg
            }
            startExtendedReverseMarker.value = markerPoint
            currentPosition.value = 0
            return
        }

        // continuing from start with random trail
        if (userChoseToContinueFromStart.value) {
            currentPosition.value = 0
            generateExtendedTrailFromStart(totalSteps)
            return
        }

        // handle reversing
        if (isReversing.value) {
            val stepsInReverse = totalSteps - reverseStartSteps.value
            val reverseDistance = stepsInReverse * stepLengthMeters.value / 1000.0
            val distanceFromEnd = totalDistance - reverseDistance

            if (distanceFromEnd <= 0) {
                // auto-continue in random direction from trail start
                currentPosition.value = 0
                isReversing.value = false
                reachedEnd.value = false
                segmentBaselineSteps = totalSteps
                userChoseToContinueFromStart.value = true
                extendedStartTrailPoints.value = listOf(importedTrailPoints.value.first())
                lastStartExtendedSteps = totalSteps
                startExtendedAngle = Math.PI
                accumulatedStartExtendedDistance = 0.0
                return
            }

            var accumulatedDistance = 0.0
            for (i in 1 until points.size) {
                val segmentDist = calculateDistance(points[i-1], points[i])
                if (accumulatedDistance + segmentDist >= distanceFromEnd) {
                    currentPosition.value = i - 1
                    return
                }
                accumulatedDistance += segmentDist
            }
            currentPosition.value = 0
            return
        }

        // reverse through off-trail extension before reversing the imported trail
        if (isReversingFromExtended.value) {
            val ext = extendedTrailPoints.value
            val stepsInReverse = totalSteps - extendedReverseStartSteps
            val reverseDistance = stepsInReverse * stepLengthMeters.value / 1000.0

            var extTotalDist = 0.0
            for (i in 1 until ext.size) extTotalDist += calculateDistance(ext[i - 1], ext[i])

            val remainingExtDist = extTotalDist - reverseDistance

            if (remainingExtDist <= 0 || ext.size < 2) {
                // finished reversing the extended trail, transition to imported-trail reverse
                isReversingFromExtended.value = false
                extendedReverseMarker.value = null
                userChoseToContinue.value = false
                isReversing.value = true
                reverseStartSteps.value = totalSteps
                currentPosition.value = points.size - 1
                return
            }

            // compute the marker point at remainingExtDist
            var acc = 0.0
            var markerPoint = ext.last()
            for (i in 1 until ext.size) {
                val seg = calculateDistance(ext[i - 1], ext[i])
                if (acc + seg >= remainingExtDist) {
                    val t = (remainingExtDist - acc) / seg
                    val lat = ext[i - 1].latitude + (ext[i].latitude - ext[i - 1].latitude) * t
                    val lon = ext[i - 1].longitude + (ext[i].longitude - ext[i - 1].longitude) * t
                    markerPoint = GeoPoint(lat, lon)
                    break
                }
                acc += seg
            }
            extendedReverseMarker.value = markerPoint
            currentPosition.value = points.size - 1
            return
        }

        // handle continuing beyond end
        if (userChoseToContinue.value) {
            currentPosition.value = points.size - 1
            generateExtendedTrail(totalSteps)
            return
        }

        // normal forward movement, starts from trailResetIndex
        val effectiveSteps = totalSteps - segmentBaselineSteps
        val walkedDistance = effectiveSteps * stepLengthMeters.value / 1000.0

        var accumulatedDistance = 0.0
        for (i in (trailResetIndex + 1) until points.size) {
            val segmentDist = calculateDistance(points[i-1], points[i])
            if (accumulatedDistance + segmentDist >= walkedDistance) {
                currentPosition.value = i
                return
            }
            accumulatedDistance += segmentDist
        }

        currentPosition.value = points.size - 1

        var remainingDistance = 0.0
        for (i in (trailResetIndex + 1) until points.size) remainingDistance += calculateDistance(points[i-1], points[i])

        if (!reachedEnd.value && !userChoseToContinue.value && !isReversing.value
            && !userChoseToContinueFromStart.value && walkedDistance >= remainingDistance) {
            when {
                isLoopTrail.value -> {
                    completedLaps++
                    segmentBaselineSteps = totalSteps
                    trailResetIndex = 0; trailResetIndexState.value = 0
                    currentPosition.value = 0
                }
                pendingReverse.value -> {
                    // reached the end, now reverse back to start
                    pendingReverse.value = false
                    isReversing.value = true
                    reverseStartSteps.value = totalSteps
                    currentPosition.value = points.size - 1
                }
                else -> {
                    // auto-continue past the end using the type chosen up-front (trail vs road)
                    userChoseToContinue.value = true
                    reachedEnd.value = true
                    val startPt = importedTrailPoints.value.last()
                    extendedTrailPoints.value = listOf(startPt)
                    lastGeneratedSteps = totalSteps
                    accumulatedExtendedDistance = 0.0
                    if (!continueAsRoad.value) continueAngle = 0.0
                }
            }
        }
    }

    private fun generateExtendedTrail(totalSteps: Int) {
        if (continueAsRoad.value) { generateRoadExtension(totalSteps); return }
        scope.launch {
            val SCALE = 0.0001
            val angleVariability = (Math.PI / 7) * (pathWavinessMeters.value / 2.0)

            val newSteps = totalSteps - lastGeneratedSteps
            if (newSteps <= 0) return@launch

            val extended = extendedTrailPoints.value.toMutableList()

            val newDistance = newSteps * stepLengthMeters.value // meters
            accumulatedExtendedDistance += newDistance

            // each SCALE step is ~11 meters
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
                // save extended trail
                scope.launch {
                    followGpxRepository.saveExtendedTrail(extended, true)
                }
            }
        }
    }

    // drip-feeds pre-generated road points into extendedTrailPoints as the user walks
    private fun generateRoadExtension(totalSteps: Int) {
        val path = fullRoadPath.value
        // no path yet, fetch a fresh segment and resume next tick
        if (path.isEmpty() || roadPathConsumedIdx >= path.size) {
            val from = extendedTrailPoints.value.lastOrNull()
                ?: importedTrailPoints.value.lastOrNull()
            if (from != null) beginRoadFetch(from)
            lastGeneratedSteps = totalSteps
            return
        }

        val newSteps = totalSteps - lastGeneratedSteps
        if (newSteps <= 0) return

        val newDistance = newSteps * stepLengthMeters.value
        accumulatedExtendedDistance += newDistance

        val newPointsToAdd = (accumulatedExtendedDistance / RandomRoadGenerator.METERS_PER_POINT).toInt()
        if (newPointsToAdd > 0) {
            val ext = extendedTrailPoints.value.toMutableList()
            val endIdx = (roadPathConsumedIdx + newPointsToAdd).coerceAtMost(path.size)
            ext.addAll(path.subList(roadPathConsumedIdx, endIdx))
            roadPathConsumedIdx = endIdx
            accumulatedExtendedDistance -= newPointsToAdd * RandomRoadGenerator.METERS_PER_POINT
            lastGeneratedSteps = totalSteps

            // path exhausted, queue a fresh segment from this new end point
            if (roadPathConsumedIdx >= path.size) {
                fullRoadPath.value = emptyList()
                roadPathConsumedIdx = 0
            }

            extendedTrailPoints.value = ext
            scope.launch { followGpxRepository.saveExtendedTrail(ext, true) }
        } else {
            lastGeneratedSteps = totalSteps
        }
    }

    // mid-walk continuation fetch, falls back quietly to random trail if no road is reachable
    private fun beginRoadFetch(from: GeoPoint) {
        if (roadFetchInProgress) return
        roadFetchInProgress = true
        scope.launch {
            val path = roadGenerator.generateRoadPath(from, searchRadiusMeters = searchRadiusMeters.value, maxDeviationMeters = pathWavinessMeters.value)
            withContext(Dispatchers.Main) {
                roadFetchInProgress = false
                if (path.isNotEmpty()) {
                    fullRoadPath.value = path
                    roadPathConsumedIdx = 0
                } else {
                    continueAsRoad.value = false
                    continueAngle = 0.0
                    scope.launch { followGpxRepository.saveContinueAsRoad(false) }
                    Toast.makeText(this@FollowGpxActivity,
                        "No roads found nearby, using random trail", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // validates road availability at choose time, then starts tracking in road mode
    private fun tryStartRoadMode() {
        val startPt = importedTrailPoints.value.lastOrNull() ?: run { startService(); return }
        Toast.makeText(this, "Checking for roads nearby…", Toast.LENGTH_SHORT).show()
        roadFetchInProgress = true
        scope.launch {
            val path = roadGenerator.generateRoadPath(startPt, searchRadiusMeters = searchRadiusMeters.value, maxDeviationMeters = pathWavinessMeters.value)
            withContext(Dispatchers.Main) {
                roadFetchInProgress = false
                if (path.isNotEmpty()) {
                    fullRoadPath.value = path
                    roadPathConsumedIdx = 0
                    continueAsRoad.value = true
                    isReversing.value = false
                    scope.launch { followGpxRepository.saveContinueAsRoad(true) }
                    startService()
                } else {
                    showNoRoadsDialog()
                }
            }
        }
    }

    // shown at choose time when no road is reachable within the search radius
    private fun showNoRoadsDialog() {
        val options = arrayOf("Continue Random Trail", "Cancel")
        var selected = 0
        AlertDialog.Builder(this)
            .setTitle("No road within reach")
            .setSingleChoiceItems(options, selected) { _, which -> selected = which }
            .setPositiveButton("OK") { _, _ ->
                when (options[selected]) {
                    "Continue Random Trail" -> {
                        continueAsRoad.value = false
                        continueAngle = 0.0
                        isReversing.value = false
                        scope.launch { followGpxRepository.saveContinueAsRoad(false) }
                        startService()
                    }
                    "Cancel" -> showModeDialog()
                }
            }
            .setCancelable(false)
            .show()
    }

    // sub-dialog from "Continue": choose how the walk extends once the imported trail ends
    private fun showContinueModeDialog() {
        val options = arrayOf("Continue Random Trail", "Continue Random Road")
        var selected = if (continueAsRoad.value) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("When the trail ends, continue as…")
            .setSingleChoiceItems(options, selected) { _, which -> selected = which }
            .setPositiveButton("OK") { _, _ ->
                if (selected == 1) {
                    // validate roads now, may branch to the no-roads dialog
                    tryStartRoadMode()
                } else {
                    continueAsRoad.value = false
                    isReversing.value = false
                    scope.launch { followGpxRepository.saveContinueAsRoad(false) }
                    startService()
                }
            }
            .setNegativeButton("Back") { _, _ -> showModeDialog() }
            .show()
    }

    private fun generateExtendedTrailFromStart(totalSteps: Int) {
        scope.launch {
            val SCALE = 0.0001
            val angleVariability = (Math.PI / 7) * (pathWavinessMeters.value / 2.0)

            val newSteps = totalSteps - lastStartExtendedSteps
            if (newSteps <= 0) return@launch

            val extended = extendedStartTrailPoints.value.toMutableList()

            val newDistance = newSteps * stepLengthMeters.value
            accumulatedStartExtendedDistance += newDistance

            val metersPerPoint = 11.0
            val newPointsToAdd = (accumulatedStartExtendedDistance / metersPerPoint).toInt()

            if (newPointsToAdd > 0) {
                accumulatedStartExtendedDistance -= newPointsToAdd * metersPerPoint

                var lat = extended.last().latitude
                var lon = extended.last().longitude

                repeat(newPointsToAdd) {
                    lat += kotlin.math.cos(startExtendedAngle) * SCALE
                    lon += kotlin.math.sin(startExtendedAngle) * SCALE
                    extended.add(GeoPoint(lat, lon))
                    startExtendedAngle += (Math.random() * angleVariability) - (angleVariability / 2.0)
                }

                lastStartExtendedSteps = totalSteps

                withContext(Dispatchers.Main) {
                    extendedStartTrailPoints.value = extended.toList()
                    scope.launch {
                        followGpxRepository.saveExtendedStartTrail(extended, true)
                    }
                }
            } else {
                lastStartExtendedSteps = totalSteps
            }
        }
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

    @Composable
    fun ActionsScreen() {
        ActionGrid(listOf(
            ActionItem("Previous Trails", Color(0xFF3A3A3A), Icons.Default.History) { showPreviousTrailsDialog() },
            ActionItem("Export GPX",      Color(0xFF3A3A3A), Icons.Default.Share) { exportGPX() },
            ActionItem("EXIF Tag Photos", Color(0xFF3A3A3A), Icons.Default.PhotoCamera) { selectPhotosForGPS() },
            ActionItem("View Location",   Color(0xFF3A3A3A), Icons.Default.LocationOn) { openInMapsApp() },
            ActionItem("Upload to Strava",Color(0xFF3A3A3A), Icons.Default.CloudUpload) { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.strava.com/upload/select"))) }
        ))
    }

    @Composable
    fun SettingsScreen() {
        ActionGrid(listOf(
            ActionItem("Step Length",   Color(0xFF3A3A3A), Icons.Default.Straighten) { setStepLength() },
            ActionItem("Search Radius", Color(0xFF3A3A3A), Icons.Default.TravelExplore) { setSearchRadius() },
            ActionItem("Loop Threshold",Color(0xFF3A3A3A), Icons.Default.Loop) { setLoopClosingThreshold() },
            ActionItem("Path Deviation", Color(0xFF3A3A3A), Icons.Default.Timeline) { setPathWaviness() },
            ActionItem("Units",         Color(0xFF3A3A3A), Icons.Default.Public) { setUnits() },
            ActionItem("Trail Color",   Color(0xFF3A3A3A), Icons.Default.Palette) { setTrailColor() }
        ))
    }

    @Composable
    fun BottomNavBar(currentTab: Int, hasGpx: Boolean, onTabSelected: (Int) -> Unit) {
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
                enabled = hasGpx,
                onClick = { onTabSelected(1) },
                icon = { Icon(Icons.Default.List, contentDescription = "Actions") },
                label = { Text("Actions") },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF4A7C59),
                    selectedTextColor = Color(0xFF4A7C59),
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray,
                    disabledIconColor = Color.DarkGray,
                    disabledTextColor = Color.DarkGray,
                    indicatorColor = Color(0xFF1A1A1A)
                )
            )
            NavigationBarItem(
                selected = currentTab == 2,
                enabled = hasGpx,
                onClick = { onTabSelected(2) },
                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                label = { Text("Settings") },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF4A7C59),
                    selectedTextColor = Color(0xFF4A7C59),
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray,
                    disabledIconColor = Color.DarkGray,
                    disabledTextColor = Color.DarkGray,
                    indicatorColor = Color(0xFF1A1A1A)
                )
            )
        }
    }

    @Composable
    fun MapScreen(serviceSteps: Int, serviceTracking: Boolean, hasGpx: Boolean) {
        if (!hasGpx) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize().background(Color.Black)
            ) {
                Text("Follow GPX", color = Color(0xFF7B9E87), fontSize = 30.sp)
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        pickGpxFile.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A7C59))
                ) {
                    Text("Import GPX", color = Color.White, fontSize = 18.sp)
                }
            }
            return
        }

        val photosNeedingLocation by photosNeedingManualLocation.collectAsState()
        val currentPhotoIndex by selectedPhotoIndex.collectAsState()
        val sliderPosition by selectedTrackpointIndex.collectAsState()
        val points by importedTrailPoints.collectAsState()
        val extendedPoints by extendedTrailPoints.collectAsState()
        val extendedStartPoints by extendedStartTrailPoints.collectAsState()
        val isContinuing by userChoseToContinue.collectAsState()
        val isContinuingFromStart by userChoseToContinueFromStart.collectAsState()
        val loopTrail by isLoopTrail.collectAsState()
        val resetIdx by trailResetIndexState.collectAsState()

        val combinedPoints = remember(points, extendedPoints, extendedStartPoints, isContinuing, isContinuingFromStart, resetIdx) {
            val base = if (resetIdx > 0 && resetIdx < points.size) points.subList(resetIdx, points.size) else points
            when {
                isContinuingFromStart -> base + extendedStartPoints
                isContinuing -> base + extendedPoints
                else -> base
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            OSMMapView()

            val showButtons = photosNeedingLocation.isEmpty()

            if (showButtons) {
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
                        Text("Follow GPX", color = Color(0xFF7B9E87), fontSize = 13.sp)
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
                                onClick = { if (serviceTracking) pauseTracking() else startTracking() }
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
                                val remaining = photosNeedingLocation.toMutableList()
                                    .also { it.removeAt(currentPhotoIndex) }
                                photosNeedingManualLocation.value = remaining
                                if (remaining.isEmpty()) selectedTab.value = 0
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
        val isReversing by isReversing.collectAsState()
        val extendedStartPoints by extendedStartTrailPoints.collectAsState()
        val isContinuingFromStart by userChoseToContinueFromStart.collectAsState()
        val loopTrail by isLoopTrail.collectAsState()
        val isRevExt by isReversingFromExtended.collectAsState()
        val extRevMarker by extendedReverseMarker.collectAsState()
        val isRevStartExt by isReversingFromStartExtended.collectAsState()
        val startExtRevMarker by startExtendedReverseMarker.collectAsState()
        val resetIdx by trailResetIndexState.collectAsState()
        val trailColor by walkedTrailColor.collectAsState()

        // combined trail for the photo slider, starts from trailResetIndex to match export
        val combinedPoints = remember(points, extendedPoints, extendedStartPoints, isContinuing, isContinuingFromStart, resetIdx) {
            val base = if (resetIdx > 0 && resetIdx < points.size) points.subList(resetIdx, points.size) else points
            when {
                isContinuingFromStart -> base + extendedStartPoints
                isContinuing -> base + extendedPoints
                else -> base
            }
        }

        val mapView = remember {
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(16.0)
            }
        }

        LaunchedEffect(points.size, position, gpxSteps, extendedPoints.size, extendedStartPoints.size, sliderPosition, photosNeedingLocation.isNotEmpty(), loopTrail, isRevExt, extRevMarker, isReversing, isRevStartExt, startExtRevMarker, resetIdx, trailColor) {
            mapView.overlays.clear()

            if (points.isNotEmpty()) {
                val safePos = position.coerceIn(0, points.size - 1)
                val gray = AndroidColor.rgb(150, 150, 150)
                val green = trailColor

                // full imported trail as gray base layer
                val polyline = Polyline().apply {
                    setPoints(points)
                    outlinePaint.color = gray
                    outlinePaint.strokeWidth = 12f
                }
                mapView.overlays.add(polyline)

                // walked forward portion on top in green
                val walkedEndPos = when {
                    isReversing || isContinuingFromStart || isRevExt || isRevStartExt -> points.size - 1
                    else -> safePos
                }
                if (walkedEndPos > resetIdx && points.size > 1) {
                    val walkedPolyline = Polyline().apply {
                        setPoints(deviateTrailPoints(points, resetIdx, walkedEndPos))
                        outlinePaint.color = green
                        outlinePaint.strokeWidth = 12f
                    }
                    mapView.overlays.add(walkedPolyline)
                }

                // extended end trail, walked so green
                if ((isContinuing || isRevExt || isReversing || isContinuingFromStart) && extendedPoints.size > 1) {
                    val extendedPolyline = Polyline().apply {
                        setPoints(extendedPoints)
                        outlinePaint.color = green
                        outlinePaint.strokeWidth = 12f
                    }
                    mapView.overlays.add(extendedPolyline)
                }

                // reverse path walked back along imported trail, green
                if (isReversing && safePos < points.size - 1) {
                    val reversePolyline = Polyline().apply {
                        setPoints(points.subList(safePos, points.size))
                        outlinePaint.color = green
                        outlinePaint.strokeWidth = 12f
                    }
                    mapView.overlays.add(reversePolyline)
                }

                // full imported trail walked in reverse, green
                if (isContinuingFromStart && extendedPoints.isNotEmpty() && points.size > 1) {
                    val fullReversePolyline = Polyline().apply {
                        setPoints(points)
                        outlinePaint.color = green
                        outlinePaint.strokeWidth = 12f
                    }
                    mapView.overlays.add(fullReversePolyline)
                }

                // extended start trail, walked so green
                if ((isContinuingFromStart || isRevStartExt) && extendedStartPoints.size > 1) {
                    val startExtPolyline = Polyline().apply {
                        setPoints(extendedStartPoints)
                        outlinePaint.color = green
                        outlinePaint.strokeWidth = 12f
                    }
                    mapView.overlays.add(startExtPolyline)
                }

                // start marker
                val startMarker = Marker(mapView).apply {
                    this.position = points.first()
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Start"
                    icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
                    icon?.setTint(AndroidColor.rgb(0, 255, 0))
                }
                mapView.overlays.add(startMarker)

                // photo slider marker, use full combinedPoints
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
                    val currentPoint = when {
                        isRevStartExt && startExtRevMarker != null -> startExtRevMarker
                        isRevExt && extRevMarker != null -> extRevMarker
                        isContinuingFromStart && extendedStartPoints.isNotEmpty() -> extendedStartPoints.last()
                        isContinuing && extendedPoints.isNotEmpty() -> extendedPoints.last()
                        else -> {
                            val safePosition = position.coerceIn(0, points.size - 1)
                            points[safePosition]
                        }
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

                    if (!isContinuing && !isContinuingFromStart) {
                        val endMarker = Marker(mapView).apply {
                            this.position = points.last()
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "End"
                            icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
                            icon?.setTint(AndroidColor.rgb(255, 0, 0))
                        }
                        mapView.overlays.add(endMarker)
                    }

                    // draw dashed loop-closing line from trail end back to start
                    if (loopTrail && points.size > 1) {
                        val closingLine = Polyline().apply {
                            setPoints(listOf(points.last(), points.first()))
                            outlinePaint.color = gray
                            outlinePaint.strokeWidth = 10f
                            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 15f), 0f)
                        }
                        mapView.overlays.add(closingLine)
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


    private fun deviateTrailPoints(
        allPoints: List<GeoPoint>,
        fromIdx: Int = 0,
        toIdx: Int = allPoints.size - 1,
        maxMeters: Double = 1.0
    ): List<GeoPoint> {
        if (allPoints.size < 2) return allPoints.subList(fromIdx, (toIdx + 1).coerceAtMost(allPoints.size))
        val seed = (allPoints.first().latitude * 1e8 + allPoints.first().longitude * 1e8).toLong()
        val rngLat = java.util.Random(seed)
        val rngLon = java.util.Random(seed xor 0x5DEECE66DL)

        val controlStep = 12
        val numControls = (allPoints.size / controlStep) + 2
        val cosLat = Math.cos(Math.toRadians(allPoints.first().latitude))
        val latControls = DoubleArray(numControls) {
            (rngLat.nextDouble() - 0.5) * 2.0 * maxMeters / 111320.0
        }
        val lonControls = DoubleArray(numControls) {
            (rngLon.nextDouble() - 0.5) * 2.0 * maxMeters / (111320.0 * cosLat)
        }

        val safeFrom = fromIdx.coerceIn(0, allPoints.size - 1)
        val safeTo = (toIdx + 1).coerceIn(safeFrom + 1, allPoints.size)
        return allPoints.subList(safeFrom, safeTo).mapIndexed { i, pt ->
            val t = (safeFrom + i).toDouble() / controlStep
            val ci = t.toInt().coerceIn(0, numControls - 2)
            val frac = t - ci
            val smooth = frac * frac * (3.0 - 2.0 * frac) // smoothstep
            val latOff = latControls[ci] * (1.0 - smooth) + latControls[ci + 1] * smooth
            val lonOff = lonControls[ci] * (1.0 - smooth) + lonControls[ci + 1] * smooth
            GeoPoint(pt.latitude + latOff, pt.longitude + lonOff)
        }
    }

    private fun startTracking() {
        if (!gpxImported.value) {
            startService()
            return
        }
        if (followGpxSteps.value > 0) showResumeDialog() else showModeDialog()
    }

    private fun showResumeDialog() {
        val options = arrayOf("Continue", "Reset", "Import New GPX")
        var selected = 0
        AlertDialog.Builder(this)
            .setTitle("Resume tracking?")
            .setSingleChoiceItems(options, selected) { _, which -> selected = which }
            .setPositiveButton("OK") { _, _ ->
                when (options[selected]) {
                    "Continue" -> startService()
                    "Reset" -> showResetDialog()
                    "Import New GPX" -> scope.launch {
                        stopAndReset()
                        withContext(Dispatchers.Main) {
                            pickGpxFile.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*"))
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showResetDialog() {
        val options = arrayOf("Reset from start", "Reset from current")
        var selected = 0
        AlertDialog.Builder(this)
            .setTitle("Reset")
            .setSingleChoiceItems(options, selected) { _, which -> selected = which }
            .setPositiveButton("OK") { _, _ ->
                when (options[selected]) {
                    "Reset from start" -> { resetTrailState(resetPosition = true); showModeDialog() }
                    "Reset from current" -> { resetTrailState(resetPosition = false); showModeDialog() }
                }
            }
            .setNegativeButton("Back") { _, _ -> showResumeDialog() }
            .show()
    }

    private fun resetTrailState(resetPosition: Boolean) {
        if (StepCounterService.isFollowGpxRunning.value) {
            val serviceIntent = Intent(this, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_STOP
            }
            startService(serviceIntent)
            StepCounterService.isFollowGpxRunning.value = false
        }

        if (resetPosition) {
            trailResetIndex = 0; trailResetIndexState.value = 0
            exportStartIndex = 0
            followGpxSteps.value = 0
            currentPosition.value = 0
            segmentBaselineSteps = 0
        } else {
            val pts = importedTrailPoints.value
            val pos = currentPosition.value.coerceIn(0, pts.size - 1)
            trailResetIndex = pos; trailResetIndexState.value = pos
            exportStartIndex = pos
            followGpxSteps.value = 0
            currentPosition.value = pos
            segmentBaselineSteps = 0
        }

        reachedEnd.value = false
        isReversing.value = false
        reverseStartSteps.value = 0
        pendingReverse.value = false
        isReversingFromExtended.value = false
        extendedReverseStartSteps = 0
        extendedReverseMarker.value = null
        isReversingFromStartExtended.value = false
        startExtendedReverseStartSteps = 0
        startExtendedReverseMarker.value = null
        isLoopTrail.value = false
        completedLaps = 0
        userChoseToContinue.value = false
        extendedTrailPoints.value = emptyList()
        userChoseToContinueFromStart.value = false
        extendedStartTrailPoints.value = emptyList()
        lastGeneratedSteps = 0
        accumulatedExtendedDistance = 0.0
        accumulatedStartExtendedDistance = 0.0
        continueAngle = 0.0
        lastStartExtendedSteps = 0
        startExtendedAngle = 0.0
        continueAsRoad.value = false
        fullRoadPath.value = emptyList()
        roadPathConsumedIdx = 0

        scope.launch {
            followGpxRepository.saveSteps(0)
            followGpxRepository.saveExtendedTrail(emptyList(), false)
            // clear timing history so the reset session starts a fresh clock
            followGpxRepository.saveTimestamps(emptyList())
        }
    }

    private fun showModeDialog() {
        val pts = importedTrailPoints.value
        val trailCanLoop = pts.size > 2 &&
                calculateDistance(pts.last(), pts.first()) <= loopClosingThresholdKm.value

        val options = mutableListOf<String>()
        options.add("Continue")
        options.add("Reverse")
        val offTrail = userChoseToContinue.value || userChoseToContinueFromStart.value
        if (!offTrail && (isLoopTrail.value || trailCanLoop)) options.add("Loop")
        options.add("Import New GPX")

        var selected = 0
        AlertDialog.Builder(this)
            .setTitle("How do you want to walk?")
            .setSingleChoiceItems(options.toTypedArray(), selected) { _, which -> selected = which }
            .setPositiveButton("OK") { dialog, _ ->
                when (options[selected]) {
                    "Continue" -> {
                        if (userChoseToContinue.value || userChoseToContinueFromStart.value) {
                            isReversing.value = false
                            startService()
                        } else {
                            // choose how the walk extends once the trail ends
                            showContinueModeDialog()
                        }
                    }
                    "Reverse" -> {
                        when {
                            userChoseToContinue.value -> {
                                isReversingFromExtended.value = true
                                extendedReverseStartSteps = followGpxSteps.value
                                pendingReverse.value = false
                            }
                            userChoseToContinueFromStart.value -> {
                                isReversingFromStartExtended.value = true
                                startExtendedReverseStartSteps = followGpxSteps.value
                                pendingReverse.value = false
                            }
                            else -> {
                                pendingReverse.value = true
                                isReversing.value = false
                            }
                        }
                        startService()
                    }
                    "Loop" -> {
                        isLoopTrail.value = true
                        isReversing.value = false
                        reachedEnd.value = false
                        val loopPts = importedTrailPoints.value
                        val pos = currentPosition.value.coerceIn(0, loopPts.size - 1)
                        var distToPos = 0.0
                        for (i in 1..pos) distToPos += calculateDistance(loopPts[i - 1], loopPts[i])
                        val stepsToPos = (distToPos * 1000.0 / stepLengthMeters.value).toInt()
                        segmentBaselineSteps = (followGpxSteps.value - stepsToPos).coerceAtLeast(0)
                        startService()
                    }
                    "Import New GPX" -> {
                        scope.launch {
                            stopAndReset()
                            withContext(Dispatchers.Main) {
                                pickGpxFile.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*"))
                            }
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
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

        scope.launch {
            // archive the current trail before wiping it, otherwise it's gone by the time
            // the GPX picker returns and importGpxFile() tries to save history
            saveCurrentTrailToHistory()

            withContext(Dispatchers.Main) {
                importedTrailPoints.value = emptyList()
                gpxImported.value = false
                currentPosition.value = 0
                startStepCount.value = 0
                reachedEnd.value = false
                userChoseToContinue.value = false
                extendedTrailPoints.value = emptyList()
                selectedTab.value = 0
                lastGeneratedSteps = 0
                accumulatedExtendedDistance = 0.0

                isReversing.value = false
                reverseStartSteps.value = 0
                segmentBaselineSteps = 0
                trailResetIndex = 0; trailResetIndexState.value = 0
                exportStartIndex = 0
                extendedStartTrailPoints.value = emptyList()
                userChoseToContinueFromStart.value = false
                lastStartExtendedSteps = 0
                startExtendedAngle = 0.0
                accumulatedStartExtendedDistance = 0.0
                isLoopTrail.value = false
                completedLaps = 0
                pendingReverse.value = false
                isReversingFromExtended.value = false
                extendedReverseStartSteps = 0
                extendedReverseMarker.value = null
                isReversingFromStartExtended.value = false
                startExtendedReverseStartSteps = 0
                startExtendedReverseMarker.value = null
            }

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
                    val number = value.toDouble()
                    stepLengthMeters.value = number
                    scope.launch { followGpxRepository.saveStepLength(number) }
                    Toast.makeText(this, "Step length: $value m", Toast.LENGTH_SHORT).show()
                }
            }
            .setMessage("Current: ${stepLengthMeters.value}m")
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun setSearchRadius() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        AlertDialog.Builder(this)
            .setTitle("Search Radius")
            .setView(input)
            .setMessage("Current: ${searchRadiusMeters.value}m\n\nEnter road search radius in meters\n\nNote: Maximum distance is 10km")
            .setPositiveButton("OK") { _, _ ->
                val value = input.text.toString()
                if (value.isNotEmpty()) {
                    val number = value.toIntOrNull()
                    if (number == null || number <= 0) {
                        Toast.makeText(this, "Enter a valid distance", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (number > MAX_SEARCH_RADIUS_METERS) {
                        AlertDialog.Builder(this)
                            .setTitle("Distance too large")
                            .setMessage("Exceeds maximum distance of ${MAX_SEARCH_RADIUS_METERS / 1000}km. Setting to ${MAX_SEARCH_RADIUS_METERS / 1000}km.")
                            .setPositiveButton("OK") { _, _ ->
                                searchRadiusMeters.value = MAX_SEARCH_RADIUS_METERS
                                scope.launch { followGpxRepository.saveSearchRadius(MAX_SEARCH_RADIUS_METERS) }
                            }
                            .show()
                    } else {
                        searchRadiusMeters.value = number
                        scope.launch { followGpxRepository.saveSearchRadius(number) }
                        Toast.makeText(this, "Search radius: ${number}m", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun setLoopClosingThreshold() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        val currentMeters = (loopClosingThresholdKm.value * 1000).toInt()
        AlertDialog.Builder(this)
            .setTitle("Loop Threshold")
            .setView(input)
            .setMessage("Current: ${currentMeters}m\n\nHow close the route's end must be to its start to offer \"Loop\" mode, in meters")
            .setPositiveButton("OK") { _, _ ->
                val value = input.text.toString()
                if (value.isNotEmpty()) {
                    val number = value.toIntOrNull()
                    if (number == null || number <= 0) {
                        Toast.makeText(this, "Enter a valid distance", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val km = number / 1000.0
                    loopClosingThresholdKm.value = km
                    scope.launch { followGpxRepository.saveLoopClosingThreshold(number) }
                    Toast.makeText(this, "Loop threshold: ${number}m", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun setPathWaviness() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        AlertDialog.Builder(this)
            .setTitle("Path Waviness")
            .setView(input)
            .setMessage("Current: ${pathWavinessMeters.value}m\n\nHow far the path wanders sideways off the road/route centerline, in meters\n\nHigher = wigglier path, lower = straighter path")
            .setPositiveButton("OK") { _, _ ->
                val value = input.text.toString()
                if (value.isNotEmpty()) {
                    val number = value.toDoubleOrNull()
                    if (number == null || number < 0) {
                        Toast.makeText(this, "Enter a valid distance", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    pathWavinessMeters.value = number
                    scope.launch { followGpxRepository.savePathWaviness(number) }
                    Toast.makeText(this, "Path waviness: ${number}m", Toast.LENGTH_SHORT).show()
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
                scope.launch { AppPreferences.setImperial(this@FollowGpxActivity, imperial) }
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
                scope.launch { AppPreferences.setWalkedTrailColor(this@FollowGpxActivity, color) }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private suspend fun saveCurrentTrailToHistory() {
        val points = importedTrailPoints.value
        if (points.size < 2 || !gpxImported.value) return
        val steps = followGpxSteps.value
        val timestamps = followGpxRepository.loadStepData().timestamps
        followGpxRepository.appendToPreviousTrails(
            PreviousTrail(
                savedAt = ZonedDateTime.now(),
                steps = steps,
                trailPoints = points,
                stepTimestamps = timestamps
            )
        )
    }

    private fun showPreviousTrailsDialog() {
        scope.launch {
            val trails = followGpxRepository.loadPreviousTrails()
            withContext(Dispatchers.Main) {
                if (trails.isEmpty()) {
                    Toast.makeText(this@FollowGpxActivity, "No previous trails saved", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
                val labels = trails.map { it.savedAt.format(fmt) }.toTypedArray()
                AlertDialog.Builder(this@FollowGpxActivity)
                    .setTitle("Previous Trails")
                    .setItems(labels) { _, which -> loadPreviousTrail(trails[which]) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun loadPreviousTrail(trail: PreviousTrail) {
        val resetIntent = Intent(this, StepCounterService::class.java).apply {
            action = StepCounterService.ACTION_RESET
        }
        startService(resetIntent)

        scope.launch {
            saveCurrentTrailToHistory()
            followGpxRepository.resetAllData()
            followGpxRepository.saveStartStepCount(0)

            withContext(Dispatchers.Main) {
                importedTrailPoints.value = trail.trailPoints
                accumulatedExtendedDistance = 0.0
                extendedTrailPoints.value = emptyList()
                lastGeneratedSteps = 0
                continueAngle = 0.0
                followGpxSteps.value = trail.steps
                lastRawSensorValue = -1
                startStepCount.value = 0
                StepCounterService.currentStepCount.value = trail.steps
                currentPosition.value = 0
                reachedEnd.value = false
                userChoseToContinue.value = false
                gpxImported.value = true
                selectedTab.value = 0

                isReversing.value = false
                reverseStartSteps.value = 0
                segmentBaselineSteps = 0
                trailResetIndex = 0; trailResetIndexState.value = 0
                exportStartIndex = 0
                extendedStartTrailPoints.value = emptyList()
                userChoseToContinueFromStart.value = false
                lastStartExtendedSteps = 0
                startExtendedAngle = 0.0
                accumulatedStartExtendedDistance = 0.0
                isLoopTrail.value = false
                completedLaps = 0
                pendingReverse.value = false
                isReversingFromExtended.value = false
                extendedReverseStartSteps = 0
                extendedReverseMarker.value = null
                isReversingFromStartExtended.value = false
                startExtendedReverseStartSteps = 0
                startExtendedReverseMarker.value = null
                Toast.makeText(this@FollowGpxActivity, "Loaded ${trail.trailPoints.size} points", Toast.LENGTH_SHORT).show()
            }

            followGpxRepository.saveImportedTrail(trail.trailPoints)
            followGpxRepository.saveSteps(trail.steps)
            if (trail.stepTimestamps.isNotEmpty()) followGpxRepository.saveTimestamps(trail.stepTimestamps)
        }
    }
    private fun buildFullTrail(): List<GeoPoint> {
        val imported = importedTrailPoints.value
        if (imported.isEmpty()) return emptyList()

        val reversing = isReversing.value

        // derive position from steps so export is correct even if currentPosition is stale
        fun indexForDistance(walkedDist: Double, startIdx: Int = 0): Int {
            var acc = 0.0
            for (i in (startIdx + 1) until imported.size) {
                acc += calculateDistance(imported[i - 1], imported[i])
                if (acc >= walkedDist) return i
            }
            return imported.size - 1
        }

        // reversing through the start extension (partial reverse shown in export)
        if (isReversingFromStartExtended.value) {
            val result = mutableListOf<GeoPoint>()
            result.addAll(imported)
            val ext = extendedTrailPoints.value
            if (ext.isNotEmpty()) {
                result.addAll(ext.drop(1))
                result.addAll(ext.reversed())
            }
            result.addAll(imported.reversed().drop(1))
            val extStart = extendedStartTrailPoints.value
            if (extStart.isNotEmpty()) {
                result.addAll(extStart.drop(1)) // forward start extension
                // partial reverse through start extension walked so far
                val stepsBack = (followGpxSteps.value - startExtendedReverseStartSteps).coerceAtLeast(0)
                val distBack = stepsBack * stepLengthMeters.value / 1000.0
                var extTotalDist = 0.0
                for (i in 1 until extStart.size) extTotalDist += calculateDistance(extStart[i - 1], extStart[i])
                val walkedBack = distBack.coerceAtMost(extTotalDist)
                var acc = 0.0
                for (i in extStart.size - 1 downTo 1) {
                    val seg = calculateDistance(extStart[i], extStart[i - 1])
                    if (acc + seg >= walkedBack) {
                        val t = (walkedBack - acc) / seg
                        val lat = extStart[i].latitude + (extStart[i - 1].latitude - extStart[i].latitude) * t
                        val lon = extStart[i].longitude + (extStart[i - 1].longitude - extStart[i].longitude) * t
                        result.add(GeoPoint(lat, lon))
                        break
                    }
                    result.add(extStart[i - 1])
                    acc += seg
                }
            }
            return result
        }

        // continue-from-start mode (user completed a full reverse and is now walking off the start)
        if (userChoseToContinueFromStart.value) {
            val result = mutableListOf<GeoPoint>()
            result.addAll(imported)                    // forward: A→Z
            val ext = extendedTrailPoints.value
            if (ext.isNotEmpty()) {
                result.addAll(ext.drop(1))             // forward extension: Z→rN
                result.addAll(ext.reversed())          // turnaround + full reverse: rN→Z
            }
            // full reverse of imported trail: Z→A
            result.addAll(imported.reversed().drop(1)) // skip dup Z at junction
            val extStart = extendedStartTrailPoints.value
            if (extStart.isNotEmpty()) result.addAll(extStart.drop(1)) // A→sN
            return result
        }

        // continue-past-end mode (also used while reversing through the extended trail)
        if (userChoseToContinue.value || isReversingFromExtended.value) {
            val result = mutableListOf<GeoPoint>()
            result.addAll(imported.subList(trailResetIndex, imported.size))
            val ext = extendedTrailPoints.value
            if (ext.isNotEmpty()) {
                result.addAll(ext.drop(1)) // forward random extension
                if (isReversingFromExtended.value) {
                    // append the portion of the extension walked back so far
                    val stepsBack = (followGpxSteps.value - extendedReverseStartSteps).coerceAtLeast(0)
                    val distBack = (stepsBack * stepLengthMeters.value / 1000.0)
                    var extTotalDist = 0.0
                    for (i in 1 until ext.size) extTotalDist += calculateDistance(ext[i - 1], ext[i])
                    val walkedBack = distBack.coerceAtMost(extTotalDist)
                    var acc = 0.0
                    for (i in ext.size - 1 downTo 1) {
                        val seg = calculateDistance(ext[i], ext[i - 1])
                        if (acc + seg >= walkedBack) {
                            val t = (walkedBack - acc) / seg
                            val lat = ext[i].latitude + (ext[i - 1].latitude - ext[i].latitude) * t
                            val lon = ext[i].longitude + (ext[i - 1].longitude - ext[i].longitude) * t
                            result.add(GeoPoint(lat, lon))
                            break
                        }
                        result.add(ext[i - 1])
                        acc += seg
                    }
                }
            }
            return result
        }

        // loop mode: exportStartIndex is the reset position and never changes across laps
        if (isLoopTrail.value) {
            val effectiveSteps = (followGpxSteps.value - segmentBaselineSteps).coerceAtLeast(0)
            val walkedDist = effectiveSteps * stepLengthMeters.value / 1000.0
            val result = mutableListOf<GeoPoint>()
            if (completedLaps == 0) {
                // still in the first (partial) lap, start from the reset position
                val pos = indexForDistance(walkedDist, exportStartIndex)
                result.addAll(imported.subList(exportStartIndex, pos + 1))
            } else {
                // first partial lap: exportStartIndex to end
                result.addAll(imported.subList(exportStartIndex, imported.size))
                // subsequent completed full laps
                repeat(completedLaps - 1) { result.addAll(imported) }
                // current lap progress (full lap, trailResetIndex is now 0)
                val pos = indexForDistance(walkedDist, 0)
                if (reversing) {
                    result.addAll(imported)
                    if (pos < imported.size - 1) result.addAll(imported.subList(pos, imported.size - 1).reversed())
                } else {
                    result.addAll(imported.subList(0, pos + 1))
                }
            }
            return result
        }

        // reverse mode
        if (reversing) {
            val stepsInReverse = (followGpxSteps.value - reverseStartSteps.value).coerceAtLeast(0)
            val reverseDist = stepsInReverse * stepLengthMeters.value / 1000.0
            var totalDist = 0.0
            for (i in 1 until imported.size) totalDist += calculateDistance(imported[i - 1], imported[i])
            val distFromEnd = (totalDist - reverseDist).coerceAtLeast(0.0)
            var pos = 0
            var acc = 0.0
            for (i in 1 until imported.size) {
                val seg = calculateDistance(imported[i - 1], imported[i])
                if (acc + seg >= distFromEnd) { pos = i - 1; break }
                acc += seg
            }
            val result = mutableListOf<GeoPoint>()
            result.addAll(imported)
            val ext = extendedTrailPoints.value
            if (ext.isNotEmpty()) {
                result.addAll(ext.drop(1))    // forward extension: Z→rN
                result.addAll(ext.reversed()) // turnaround + reverse: rN→Z (rN duplicated once, fine)
            }
            if (pos < imported.size - 1) result.addAll(imported.subList(pos, imported.size - 1).reversed())
            return result
        }

        // normal forward walk, start from trailResetIndex so export excludes pre-reset trail
        val effectiveSteps = (followGpxSteps.value - segmentBaselineSteps).coerceAtLeast(0)
        val walkedDist = effectiveSteps * stepLengthMeters.value / 1000.0
        val pos = indexForDistance(walkedDist, trailResetIndex)
        return imported.subList(trailResetIndex, pos + 1)
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
                Log.d(TAG, "Export - steps: ${stepData.steps}, timestamps: ${stepData.timestamps.size}")

                if (stepData.steps < 2 || stepData.timestamps.size < 2) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FollowGpxActivity, "Not enough steps", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val walkedPoints = buildFullTrail()

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

                if (stepData.steps < 2 || stepData.timestamps.size < 2) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FollowGpxActivity, "Not enough steps", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val walkedPoints = buildFullTrail()

                val tempFile = gpxExporter.generateGpxFile(
                    cacheDir,
                    walkedPoints,
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
                val fullTrail = buildFullTrail()

                if (fullTrail.isEmpty() || stepData.timestamps.isEmpty()) {
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
                    val timedTrackpoints = gpxExporter.generateTimedTrackpoints(fullTrail, stepData.timestamps, stepData.steps)
                    val result = photoProcessor.processPhotos(photosInRange, timedTrackpoints)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FollowGpxActivity, "Auto-tagged ${result.successCount} photos", Toast.LENGTH_SHORT).show()
                    }
                }

                if (photosOutOfRange.isNotEmpty()) {
                    photosNeedingManualLocation.value = photosOutOfRange
                    selectedPhotoIndex.value = 0
                    selectedTrackpointIndex.value = fullTrail.size / 2
                    withContext(Dispatchers.Main) {
                        selectedTab.value = 0
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

                val importedPoints = importedTrailPoints.value
                val extendedPoints = extendedTrailPoints.value
                val extendedStartPoints = extendedStartTrailPoints.value
                val isContinuingFromStart = userChoseToContinueFromStart.value
                val isContinuing = userChoseToContinue.value

                val resetIdx = trailResetIndex
                val base = if (resetIdx > 0 && resetIdx < importedPoints.size)
                    importedPoints.subList(resetIdx, importedPoints.size) else importedPoints
                val combinedPoints = when {
                    isContinuingFromStart -> base + extendedStartPoints
                    isContinuing -> base + extendedPoints
                    else -> base
                }

                val trackpointIndex = selectedTrackpointIndex.value.coerceIn(0, combinedPoints.size - 1)

                if (photoIndex >= photos.size) return@launch

                val location = combinedPoints[trackpointIndex]
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


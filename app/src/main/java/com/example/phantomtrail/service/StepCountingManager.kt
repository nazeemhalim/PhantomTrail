package com.example.phantomtrail.service

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.util.Log
import com.example.phantomtrail.data.StepRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

// handles step counting logic and state management
class StepCountingManager(
    private val scope: CoroutineScope,
    private val onStepUpdate: () -> Unit
) {
    companion object {
        private const val TAG = "StepCountingManager"
        private const val SENSOR_RESET_THRESHOLD = 300 // 5 minutes in seconds
        // minimum gap between step timestamps, spreads out sensor bursts to a realistic pace
        private const val MIN_STEP_INTERVAL_MS = 270L
    }

    private var initialStepCount = -1
    private var currentSteps = 0
    private var isInitialized = false
    private val stepTimestamps = mutableListOf<ZonedDateTime>()
    private var lastStepTimestamp: ZonedDateTime? = null

    private var lastSaveTime = 0L
    private val SAVE_INTERVAL_MS = 5000L // Save every 5 seconds max

    // restoring a previously saved sensor baseline lets the next sensor event compute the
    // correct total (including any steps taken while the service was dead) instead of quietly
    // rebasing to "no new steps happened"
    suspend fun initialize(
        initialSteps: Int = 0,
        initialTimestamps: List<ZonedDateTime> = emptyList(),
        savedInitialSensorCount: Int = -1
    ) {
        try {
            currentSteps = initialSteps
            initialStepCount = savedInitialSensorCount
            stepTimestamps.clear()
            stepTimestamps.addAll(initialTimestamps)
            lastStepTimestamp = initialTimestamps.lastOrNull()
            isInitialized = true

            Log.d(TAG, "Initialized with $currentSteps steps, ${stepTimestamps.size} timestamps, baseline $initialStepCount")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing: ${e.message}", e)
            isInitialized = true
        }
    }
    fun reset() {
        currentSteps = 0
        initialStepCount = -1
        stepTimestamps.clear()
        lastStepTimestamp = null
        lastSaveTime = 0L
        Log.d(TAG, "StepCountingManager reset to 0")
    }

    // next step timestamp, spaced at least MIN_STEP_INTERVAL_MS from the previous one
    private fun nextStepTimestamp(): ZonedDateTime {
        val now = ZonedDateTime.now()
        val prev = lastStepTimestamp
        val ts = if (prev == null) {
            now
        } else {
            val earliest = prev.plusNanos(MIN_STEP_INTERVAL_MS * 1_000_000)
            if (now.isAfter(earliest)) now else earliest
        }
        lastStepTimestamp = ts
        return ts
    }

    fun onSensorChanged(event: SensorEvent) {
        if (!isInitialized) {
            Log.w(TAG, "onSensorChanged called before initialization")
            return
        }

        try {
            when (event.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> handleStepCounter(event)
                Sensor.TYPE_STEP_DETECTOR -> handleStepDetector()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onSensorChanged: ${e.message}", e)
        }
    }

    private fun handleStepCounter(event: SensorEvent) {
        try {
            val totalSteps = event.values[0].toInt()
            Log.d(TAG, "Sensor total: $totalSteps, Initial: $initialStepCount, Current: $currentSteps")

            // detect sensor reset after reboot
            if (initialStepCount != -1 && totalSteps < initialStepCount) {
                Log.d(TAG, "Sensor reset detected! Resetting initialStepCount.")
                initialStepCount = -1
            }

            // set initial count
            if (initialStepCount == -1) {
                initialStepCount = totalSteps - currentSteps
                Log.d(TAG, "Set initial sensor count: $initialStepCount")
            }

            val newStepCount = totalSteps - initialStepCount

            // add timestamps for new steps
            while (currentSteps < newStepCount) {
                stepTimestamps.add(nextStepTimestamp())
                currentSteps++
            }

            saveThrottled()
            onStepUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleStepCounter: ${e.message}", e)
        }
    }

    private fun handleStepDetector() {
        try {
            stepTimestamps.add(nextStepTimestamp())
            currentSteps++
            saveThrottled()
            onStepUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleStepDetector: ${e.message}", e)
        }
    }

    private fun saveThrottled() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSaveTime > SAVE_INTERVAL_MS) {
            lastSaveTime = currentTime
        }
    }



    fun getCurrentSteps() = currentSteps
    fun getTimestamps() = stepTimestamps.toList()
    fun getInitialSensorCount() = initialStepCount
}
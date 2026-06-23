package com.example.phantomtrail.service

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.util.Log
import com.example.phantomtrail.data.StepRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * Handles step counting logic and state management
 */
class StepCountingManager(
    private val scope: CoroutineScope,
    private val onStepUpdate: () -> Unit
) {
    companion object {
        private const val TAG = "StepCountingManager"
        private const val SENSOR_RESET_THRESHOLD = 300 // 5 minutes in seconds
        // Minimum spacing between consecutive step timestamps. The sensor can deliver steps in
        // rapid bursts (FASTEST delay / batched delivery / injected steps), stamping hundreds of
        // steps within milliseconds and collapsing the exported pace. Capping the cadence here
        // keeps the recorded timeline realistic: slower real walking keeps its real timing, while
        // bursts are spread out to a target pace.
        // 270 ms ≈ 0.75 m × (1000/270) ≈ 2.78 m/s ≈ 6:00/km (jog), matching a typical run export.
        private const val MIN_STEP_INTERVAL_MS = 270L
    }

    private var initialStepCount = -1
    private var currentSteps = 0
    private var isInitialized = false
    private val stepTimestamps = mutableListOf<ZonedDateTime>()
    private var lastStepTimestamp: ZonedDateTime? = null

    private var lastSaveTime = 0L
    private val SAVE_INTERVAL_MS = 5000L // Save every 5 seconds max

    suspend fun initialize(initialSteps: Int = 0, initialTimestamps: List<ZonedDateTime> = emptyList()) {
        try {
            currentSteps = initialSteps
            initialStepCount = -1
            stepTimestamps.clear()
            stepTimestamps.addAll(initialTimestamps)
            lastStepTimestamp = initialTimestamps.lastOrNull()
            isInitialized = true

            Log.d(TAG, "Initialized with $currentSteps steps, ${stepTimestamps.size} timestamps")
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

    /**
     * Returns the timestamp to record for the next step: real wall-clock time, but never closer
     * than [MIN_STEP_INTERVAL_MS] to the previous step so bursts of steps spread to a realistic
     * cadence instead of collapsing onto the same instant.
     */
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

            // Detect sensor reset (happens after reboot)
            if (initialStepCount != -1 && totalSteps < initialStepCount) {
                Log.d(TAG, "Sensor reset detected! Resetting initialStepCount.")
                initialStepCount = -1
            }

            // Set initial count
            if (initialStepCount == -1) {
                initialStepCount = totalSteps - currentSteps
                Log.d(TAG, "Set initial sensor count: $initialStepCount")
            }

            val newStepCount = totalSteps - initialStepCount

            // Add timestamps for new steps, spread at a realistic cadence
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
}
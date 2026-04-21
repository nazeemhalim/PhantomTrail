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
    private val repository: StepRepository,
    private val scope: CoroutineScope,
    private val onStepUpdate: () -> Unit
) {
    companion object {
        private const val TAG = "StepCountingManager"
        private const val SENSOR_RESET_THRESHOLD = 300 // 5 minutes in seconds
    }

    private var initialStepCount = -1
    private var currentSteps = 0
    private var isInitialized = false
    private val stepTimestamps = mutableListOf<ZonedDateTime>()

    private var lastSaveTime = 0L
    private val SAVE_INTERVAL_MS = 5000L // Save every 5 seconds max

    suspend fun initialize() {
        try {
            val stepData = repository.loadStepData()
            currentSteps = stepData.steps
            initialStepCount = -1 // Always reset on initialization to handle phone reboots
            stepTimestamps.clear()
            stepTimestamps.addAll(stepData.timestamps)
            isInitialized = true

            Log.d(TAG, "Initialized with $currentSteps steps, ${stepTimestamps.size} timestamps")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing: ${e.message}", e)
            isInitialized = true // Continue anyway with defaults
        }
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
                saveNow()
            }

            val newStepCount = totalSteps - initialStepCount

            // Add timestamps for new steps
            while (currentSteps < newStepCount) {
                stepTimestamps.add(ZonedDateTime.now())
                currentSteps++
                Log.d(TAG, "Step incremented to: $currentSteps")
            }

            saveThrottled()
            onStepUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleStepCounter: ${e.message}", e)
        }
    }

    private fun handleStepDetector() {
        try {
            stepTimestamps.add(ZonedDateTime.now())
            currentSteps++
            Log.d(TAG, "Step detector - incremented to: $currentSteps")
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
            saveNow()
        }
    }

    private fun saveNow() {
        scope.launch {
            try {
                repository.saveStepData(currentSteps, initialStepCount, stepTimestamps)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving in background: ${e.message}", e)
            }
        }
    }

    fun getCurrentSteps() = currentSteps
    fun getTimestamps() = stepTimestamps.toList()
}
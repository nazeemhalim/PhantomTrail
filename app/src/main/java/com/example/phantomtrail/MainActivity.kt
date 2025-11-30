package com.example.phantomtrail

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.phantomtrail.ui.theme.PhantomTrailTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// DataStore delegate
private val Context.dataStore by preferencesDataStore(name = "step_counter")

class MainActivity : ComponentActivity(), SensorEventListener {

    private var running = false
    private lateinit var sensorManager: SensorManager
    private val stepsFlow = MutableStateFlow(0)

    // Use IO dispatcher for DataStore operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var initialStepCount = -1
    private var isInitialized = false

    companion object {
        private val STEPS_KEY = intPreferencesKey("saved_steps")
        private val INITIAL_SENSOR_COUNT_KEY = intPreferencesKey("initial_sensor_count")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Load saved data asynchronously
        scope.launch {
            val prefs = dataStore.data.first()
            val savedSteps = prefs[STEPS_KEY] ?: 0
            initialStepCount = prefs[INITIAL_SENSOR_COUNT_KEY] ?: -1
            stepsFlow.value = savedSteps
            isInitialized = true
        }

        if (checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION), 1)
        }

        setContent {
            val steps by stepsFlow.collectAsState()

            PhantomTrailTheme() {
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
                        text = "$steps",
                        color = Color.White,
                        fontSize = 50.sp
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = { resetSteps() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF7B9E87)
                        )
                    ) {
                        Text(
                            text = "Reset Steps",
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
        running = true

        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        if (stepSensor == null) {
            Toast.makeText(this, "No Step Sensor Available!", Toast.LENGTH_SHORT).show()
        } else {
            // Use SENSOR_DELAY_UI for better balance between latency and battery
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)

            if (stepSensor.type == Sensor.TYPE_STEP_DETECTOR) {
                Toast.makeText(this, "Using Step Detector", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        running = false
        sensorManager.unregisterListener(this)
        saveSteps()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || !isInitialized) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val totalSteps = event.values[0].toInt()

                if (initialStepCount == -1) {
                    initialStepCount = totalSteps - stepsFlow.value
                    // Save asynchronously without blocking
                    scope.launch {
                        dataStore.edit { prefs ->
                            prefs[INITIAL_SENSOR_COUNT_KEY] = initialStepCount
                        }
                    }
                }

                stepsFlow.value = totalSteps - initialStepCount
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                stepsFlow.value++
            }
        }
    }

    private fun saveSteps() {
        scope.launch {
            dataStore.edit { prefs ->
                prefs[STEPS_KEY] = stepsFlow.value
                prefs[INITIAL_SENSOR_COUNT_KEY] = initialStepCount
            }
        }
    }

    private fun resetSteps() {
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepSensor != null) {
            sensorManager.unregisterListener(this)
            initialStepCount = -1
            stepsFlow.value = 0

            scope.launch {
                dataStore.edit { prefs ->
                    prefs[STEPS_KEY] = 0
                    prefs[INITIAL_SENSOR_COUNT_KEY] = -1
                }
            }

            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            stepsFlow.value = 0
            scope.launch {
                dataStore.edit { prefs ->
                    prefs[STEPS_KEY] = 0
                }
            }
        }
    }
}
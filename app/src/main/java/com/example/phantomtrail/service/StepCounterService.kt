package com.example.phantomtrail.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.phantomtrail.FollowGpxActivity
import com.example.phantomtrail.FollowRandomRoad
import com.example.phantomtrail.MainActivity
import com.example.phantomtrail.data.FollowGpxStepRepository
import com.example.phantomtrail.data.RoadStepRepository
import com.example.phantomtrail.data.StepRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.ZonedDateTime

// foreground service step counting
enum class ActiveActivity { NONE, MAIN, FOLLOW_GPX, FOLLOW_ROAD }

class StepCounterService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: StepRepository
    private lateinit var roadRepository: RoadStepRepository
    private lateinit var gpxRepository: FollowGpxStepRepository
    private lateinit var stepCountingManager: StepCountingManager

    private var notificationManager: NotificationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var isInitialized = false

    companion object {
        const val CHANNEL_ID = "StepCounterChannel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "StepCounterService"
        val isMainRunning = MutableStateFlow(false)
        val isFollowGpxRunning = MutableStateFlow(false)
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_RESET = "ACTION_RESET"

        private var instance: StepCounterService? = null
        fun getTimestamps(): List<ZonedDateTime> = instance?.stepCountingManager?.getTimestamps() ?: emptyList<ZonedDateTime>()
        val isRunning = MutableStateFlow(false)
        val currentStepCount = MutableStateFlow(0)

        val activeActivity = MutableStateFlow(ActiveActivity.NONE)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Service onCreate")

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        repository = StepRepository(this)
        roadRepository = RoadStepRepository(this)
        gpxRepository = FollowGpxStepRepository(this)

        createNotificationChannel()

        // init step counting manager
        stepCountingManager = StepCountingManager(
            scope = scope,
            onStepUpdate = { updateStepCount() }
        )

        scope.launch {
            try {
                // seed with saved timestamps and sensor baseline so both the timeline and step
                // count survive service restarts (pause/resume, or the OS killing the process)
                val initialSteps: Int
                val initialTimestamps: List<ZonedDateTime>
                val initialSensorCount: Int
                when (activeActivity.value) {
                    ActiveActivity.FOLLOW_GPX -> {
                        val saved = gpxRepository.loadStepData()
                        initialSteps = saved.steps
                        initialTimestamps = saved.timestamps
                        initialSensorCount = saved.initialSensorCount
                    }
                    ActiveActivity.FOLLOW_ROAD -> {
                        val saved = roadRepository.loadStepData()
                        initialSteps = saved.steps
                        initialTimestamps = saved.timestamps
                        initialSensorCount = saved.initialSensorCount
                    }
                    else -> {
                        val stepData = repository.loadStepData()
                        initialSteps = stepData.steps
                        initialTimestamps = stepData.timestamps
                        initialSensorCount = stepData.initialSensorCount
                    }
                }
                stepCountingManager.initialize(initialSteps, initialTimestamps, initialSensorCount)
                currentStepCount.value = stepCountingManager.getCurrentSteps()
                isInitialized = true
                Log.d(TAG, "Service initialized with $initialSteps steps, ${initialTimestamps.size} timestamps, baseline $initialSensorCount")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing: ${e.message}", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}, isInitialized: $isInitialized")

        when (intent?.action) {
            ACTION_START -> {
                // wait for initialization if needed
                if (!isInitialized) {
                    Log.d(TAG, "Service not initialized yet, waiting...")
                    scope.launch {
                        // wait up to 2 seconds for initialization
                        var waitCount = 0
                        while (!isInitialized && waitCount < 20) {
                            delay(100)
                            waitCount++
                        }
                        if (isInitialized) {
                            withContext(Dispatchers.Main) {
                                startTracking()
                            }
                        } else {
                            Log.e(TAG, "Service initialization timeout!")
                        }
                    }
                } else {
                    startTracking()
                }
            }
            ACTION_STOP -> stopTracking()
            ACTION_RESET -> {
                stepCountingManager.reset()
            }
        }

        return START_STICKY
    }



    private fun startTracking() {
        try {
            Log.d(TAG, "Starting tracking - Current steps: ${stepCountingManager.getCurrentSteps()}")

            acquireWakeLock()
            startForegroundWithNotification()
            registerSensorListener()

            isRunning.value = true
            scope.launch { activeRepositorySaveIsTracking(true) }
            Log.d(TAG, "Tracking started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting tracking: ${e.message}", e)
            stopSelf()
        }
    }

    private fun stopTracking() {
        try {
            Log.d(TAG, "Stopping tracking")

            sensorManager.unregisterListener(this)
            releaseWakeLock()

            isRunning.value = false
            scope.launch { activeRepositorySaveIsTracking(false) }
            stopForeground()
            stopSelf()

            Log.d(TAG, "Tracking stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tracking: ${e.message}", e)
            stopSelf()
        }
    }

    private suspend fun activeRepositorySaveIsTracking(active: Boolean) {
        when (activeActivity.value) {
            ActiveActivity.FOLLOW_GPX -> gpxRepository.saveIsTracking(active)
            ActiveActivity.FOLLOW_ROAD -> roadRepository.saveIsTracking(active)
            else -> repository.saveIsTracking(active)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PhantomTrail::StepCounterWakeLock"
            ).apply {
                acquire(10 * 60 * 60 * 1000L) // 10 hours
            }
            Log.d(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake lock: ${e.message}", e)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    private fun startForegroundWithNotification() {
        try {
            val notification = createNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Started foreground with notification")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground: ${e.message}", e)
            throw e
        }
    }

    private fun stopForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground: ${e.message}", e)
        }
    }

    private fun registerSensorListener() {
        // try step_counter first
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepSensor == null) {
            Log.w(TAG, "STEP_COUNTER not available, trying STEP_DETECTOR")
            stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        }

        stepSensor?.let { sensor ->
            val delay = if (sensor.type == Sensor.TYPE_STEP_COUNTER) {
                SensorManager.SENSOR_DELAY_FASTEST
            } else {
                SensorManager.SENSOR_DELAY_NORMAL
            }

            val registered = sensorManager.registerListener(this, sensor, delay)
            Log.d(TAG, "Sensor registered: $registered, Type: ${sensor.type}, Name: ${sensor.name}, Delay: $delay")

            if (!registered) {
                Log.e(TAG, "Failed to register sensor listener!")
            }
        } ?: run {
            Log.e(TAG, "No step sensor available on this device!")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        Log.d(TAG, "onSensorChanged - Sensor: ${event.sensor.type}, Value: ${event.values[0]}")
        stepCountingManager.onSensorChanged(event)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "onAccuracyChanged - Sensor: ${sensor?.type}, Accuracy: $accuracy")
    }

    private fun updateStepCount() {
        val newCount = stepCountingManager.getCurrentSteps()
        Log.d(TAG, "updateStepCount - New count: $newCount")
        currentStepCount.value = newCount
        updateNotification()

        // persist steps + sensor baseline on every update (not just from the activity) so a
        // killed process never loses more progress than the single most recent sensor event
        val baseline = stepCountingManager.getInitialSensorCount()
        scope.launch {
            when (activeActivity.value) {
                ActiveActivity.FOLLOW_GPX -> {
                    gpxRepository.saveSteps(newCount)
                    gpxRepository.saveInitialSensorCount(baseline)
                }
                ActiveActivity.FOLLOW_ROAD -> {
                    roadRepository.saveSteps(newCount)
                    roadRepository.saveInitialSensorCount(baseline)
                }
                else -> {
                    repository.saveSteps(newCount)
                    repository.saveInitialSensorCount(baseline)
                }
            }
        }
    }



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
            Log.d(TAG, "Notification channel created")
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = when (activeActivity.value) {
            ActiveActivity.FOLLOW_GPX -> Intent(this, FollowGpxActivity::class.java)
            ActiveActivity.FOLLOW_ROAD -> Intent(this, FollowRandomRoad::class.java)
            else -> Intent(this, MainActivity::class.java)
        }
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

        // show correct steps based on active activity
        val displaySteps = when (activeActivity.value) {
            ActiveActivity.FOLLOW_GPX -> FollowGpxActivity.followGpxSteps.value
            ActiveActivity.FOLLOW_ROAD -> FollowRandomRoad.roadSteps.value
            else -> currentStepCount.value
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phantom Trail")
            .setContentText("Steps: $displaySteps")
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

    override fun onDestroy() {
        super.onDestroy()
        instance = this
        Log.d(TAG, "Service onDestroy")
        sensorManager.unregisterListener(this)
        releaseWakeLock()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
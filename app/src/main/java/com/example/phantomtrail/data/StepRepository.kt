package com.example.phantomtrail.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.osmdroid.util.GeoPoint
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val Context.dataStore by preferencesDataStore(name = "step_counter")

/**
 * Repository for managing step counter data persistence
 */
class StepRepository(private val context: Context) {

    companion object {
        private const val TAG = "StepRepository"

        private val STEPS_KEY = intPreferencesKey("saved_steps")
        private val INITIAL_SENSOR_COUNT_KEY = intPreferencesKey("initial_sensor_count")
        private val IS_TRACKING_KEY = booleanPreferencesKey("is_tracking")
        private val TIMESTAMPS_KEY = stringPreferencesKey("step_timestamps")
        private val SESSION_START_KEY = stringPreferencesKey("session_start")
        private val STEP_LENGTH_KEY = doublePreferencesKey("step_length_meters")
        private val TRAIL_POINTS_KEY = stringPreferencesKey("trail_points")
        private val CUSTOM_START_LAT_KEY = doublePreferencesKey("custom_start_lat")
        private val CUSTOM_START_LON_KEY = doublePreferencesKey("custom_start_lon")
        private val PREV_TRAILS_KEY = stringPreferencesKey("prev_trails")
        private const val MAX_PREV_TRAILS = 5
        private const val TRAIL_SEP = "¶"
        private const val FIELD_SEP = "§"
        private const val TS_SEP = "|"
    }

    // flow-based data access
    val stepCountFlow: Flow<Int> = context.dataStore.data.map { it[STEPS_KEY] ?: 0 }
    val stepLengthFlow: Flow<Double> = context.dataStore.data.map { it[STEP_LENGTH_KEY] ?: 0.75 }
    val startLocationFlow: Flow<Pair<Double, Double>> = context.dataStore.data.map {
        Pair(
            it[CUSTOM_START_LAT_KEY] ?: Constants.DEFAULT_START_LAT,
            it[CUSTOM_START_LON_KEY] ?: Constants.DEFAULT_START_LON
        )
    }

    suspend fun loadStepData(): StepData {
        val prefs = context.dataStore.data.first()
        return StepData(
            steps = prefs[STEPS_KEY] ?: 0,
            initialSensorCount = prefs[INITIAL_SENSOR_COUNT_KEY] ?: -1,
            timestamps = parseTimestamps(prefs[TIMESTAMPS_KEY]),
            stepLength = prefs[STEP_LENGTH_KEY] ?: 0.75,
            sessionStart = prefs[SESSION_START_KEY]?.let { ZonedDateTime.parse(it) },
            customStartLat = prefs[CUSTOM_START_LAT_KEY] ?: Constants.DEFAULT_START_LAT,
            customStartLon = prefs[CUSTOM_START_LON_KEY] ?: Constants.DEFAULT_START_LON
        )
    }

    suspend fun loadTrailPoints(): List<GeoPoint> {
        val prefs = context.dataStore.data.first()
        val savedTrailStr = prefs[TRAIL_POINTS_KEY] ?: return emptyList()

        return savedTrailStr.split(";").mapNotNull { pointStr ->
            val parts = pointStr.split(",")
            if (parts.size == 2) {
                try {
                    GeoPoint(parts[0].toDouble(), parts[1].toDouble())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing trail point: ${e.message}")
                    null
                }
            } else null
        }
    }
    suspend fun saveTimestamps(timestamps: List<ZonedDateTime>) {
        context.dataStore.edit { prefs ->
            val timestampsStr = timestamps.joinToString(";") {
                it.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
            }
            prefs[TIMESTAMPS_KEY] = timestampsStr
        }
    }
    suspend fun saveSteps(steps: Int) {
        context.dataStore.edit { prefs ->
            prefs[STEPS_KEY] = steps
        }
    }

    suspend fun saveStepData(
        steps: Int,
        initialSensorCount: Int,
        timestamps: List<ZonedDateTime>
    ) {
        context.dataStore.edit { prefs ->
            prefs[STEPS_KEY] = steps
            prefs[INITIAL_SENSOR_COUNT_KEY] = initialSensorCount
            prefs[TIMESTAMPS_KEY] = timestamps.joinToString(";")
        }
        Log.d(TAG, "Saved steps: $steps, initial sensor: $initialSensorCount")
    }

    suspend fun saveStepLength(length: Double) {
        context.dataStore.edit { prefs ->
            prefs[STEP_LENGTH_KEY] = length
        }
    }

    suspend fun saveInitialSensorCount(value: Int) {
        context.dataStore.edit { prefs -> prefs[INITIAL_SENSOR_COUNT_KEY] = value }
    }

    suspend fun saveIsTracking(active: Boolean) {
        context.dataStore.edit { prefs -> prefs[IS_TRACKING_KEY] = active }
    }

    suspend fun wasTracking(): Boolean =
        context.dataStore.data.first()[IS_TRACKING_KEY] ?: false

    suspend fun saveTrailPoints(points: List<GeoPoint>) {
        val trailStr = points.joinToString(";") { "${it.latitude},${it.longitude}" }
        context.dataStore.edit { prefs ->
            prefs[TRAIL_POINTS_KEY] = trailStr
        }
    }

    suspend fun saveStartLocation(lat: Double, lon: Double) {
        context.dataStore.edit { prefs ->
            prefs[CUSTOM_START_LAT_KEY] = lat
            prefs[CUSTOM_START_LON_KEY] = lon
        }
    }

    suspend fun resetAllData() {
        context.dataStore.edit { prefs ->
            prefs[STEPS_KEY] = 0
            prefs[INITIAL_SENSOR_COUNT_KEY] = -1
            prefs[TIMESTAMPS_KEY] = ""
            prefs[SESSION_START_KEY] = ZonedDateTime.now().toString()
            prefs[IS_TRACKING_KEY] = false
        }
    }

    suspend fun loadPreviousTrails(): List<PreviousTrail> {
        val raw = context.dataStore.data.first()[PREV_TRAILS_KEY] ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(TRAIL_SEP).mapNotNull { entry ->
            try {
                val parts = entry.split(FIELD_SEP)
                if (parts.size != 4) return@mapNotNull null
                val savedAt = ZonedDateTime.parse(parts[0], DateTimeFormatter.ISO_ZONED_DATE_TIME)
                val steps = parts[1].toInt()
                val points = parts[2].split(";").mapNotNull { pt ->
                    val c = pt.split(",")
                    if (c.size == 2) try { GeoPoint(c[0].toDouble(), c[1].toDouble()) } catch (e: Exception) { null } else null
                }
                val timestamps = if (parts[3].isBlank()) emptyList()
                    else parts[3].split(TS_SEP).mapNotNull { ts ->
                        try { ZonedDateTime.parse(ts, DateTimeFormatter.ISO_ZONED_DATE_TIME) } catch (e: Exception) { null }
                    }
                if (points.isEmpty()) null else PreviousTrail(savedAt, steps, points, timestamps)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse previous trail: ${e.message}")
                null
            }
        }
    }

    suspend fun appendToPreviousTrails(trail: PreviousTrail) {
        if (trail.trailPoints.size < 2) return
        val current = loadPreviousTrails().toMutableList()
        current.add(0, trail)
        if (current.size > MAX_PREV_TRAILS) current.subList(MAX_PREV_TRAILS, current.size).clear()
        context.dataStore.edit { prefs ->
            prefs[PREV_TRAILS_KEY] = current.joinToString(TRAIL_SEP) { t ->
                val pts = t.trailPoints.joinToString(";") { "${it.latitude},${it.longitude}" }
                val ts = t.stepTimestamps.joinToString(TS_SEP) { it.format(DateTimeFormatter.ISO_ZONED_DATE_TIME) }
                "${t.savedAt.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)}${FIELD_SEP}${t.steps}${FIELD_SEP}${pts}${FIELD_SEP}${ts}"
            }
        }
    }

    private fun parseTimestamps(timestampsStr: String?): List<ZonedDateTime> {
        if (timestampsStr.isNullOrBlank()) return emptyList()

        return timestampsStr.split(";").mapNotNull { ts ->
            if (ts.isNotBlank()) {
                try {
                    ZonedDateTime.parse(ts)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing timestamp: ${e.message}")
                    null
                }
            } else null
        }
    }
}

data class StepData(
    val steps: Int,
    val initialSensorCount: Int,
    val timestamps: List<ZonedDateTime>,
    val stepLength: Double,
    val sessionStart: ZonedDateTime?,
    val customStartLat: Double,
    val customStartLon: Double
)

object Constants {
    const val DEFAULT_START_LAT = 2.9279088973999023
    const val DEFAULT_START_LON = 101.64179229736328
    const val DEFAULT_STEP_LENGTH = 0.75
}
package com.example.phantomtrail.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.osmdroid.util.GeoPoint
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val Context.followGpxDataStore by preferencesDataStore(name = "follow_gpx_steps")

class FollowGpxStepRepository(private val context: Context) {

    companion object {
        private val STEPS_KEY = intPreferencesKey("steps")
        private val INITIAL_SENSOR_COUNT_KEY = intPreferencesKey("initial_sensor_count")
        private val IS_TRACKING_KEY = booleanPreferencesKey("is_tracking")
        private val STEP_LENGTH_KEY = doublePreferencesKey("step_length")
        private val TIMESTAMPS_KEY = stringPreferencesKey("timestamps")
        private val START_STEP_COUNT_KEY = intPreferencesKey("start_step_count")
        private val TRAIL_POINTS_KEY = stringPreferencesKey("imported_trail_points")
        private val GPX_IMPORTED_KEY = booleanPreferencesKey("gpx_imported")
        private val EXTENDED_TRAIL_KEY = stringPreferencesKey("extended_trail_points")
        private val USER_CONTINUING_KEY = booleanPreferencesKey("user_continuing")
        private val EXTENDED_START_TRAIL_KEY = stringPreferencesKey("extended_start_trail")
        private val USER_CONTINUING_FROM_START_KEY = booleanPreferencesKey("user_continuing_from_start")
        private val CONTINUE_AS_ROAD_KEY = booleanPreferencesKey("continue_as_road")
        private val SEARCH_RADIUS_KEY = intPreferencesKey("search_radius_meters")
        private val LOOP_THRESHOLD_KEY = intPreferencesKey("loop_closing_threshold_meters")
        private val PREV_TRAILS_KEY = stringPreferencesKey("prev_trails")
        private const val MAX_PREV_TRAILS = 5
        private const val TRAIL_SEP = "¶"
        private const val FIELD_SEP = "§"
        private const val TS_SEP = "|"
    }

    suspend fun saveContinueAsRoad(asRoad: Boolean) {
        context.followGpxDataStore.edit { prefs ->
            prefs[CONTINUE_AS_ROAD_KEY] = asRoad
        }
    }

    suspend fun wasContinueAsRoad(): Boolean {
        val prefs = context.followGpxDataStore.data.first()
        return prefs[CONTINUE_AS_ROAD_KEY] ?: false
    }
    suspend fun saveExtendedTrail(points: List<GeoPoint>, isContinuing: Boolean) {
        context.followGpxDataStore.edit { prefs ->
            val trailStr = points.joinToString(";") { "${it.latitude},${it.longitude}" }
            prefs[EXTENDED_TRAIL_KEY] = trailStr
            prefs[USER_CONTINUING_KEY] = isContinuing
        }
    }

    suspend fun saveExtendedStartTrail(points: List<GeoPoint>, isContinuing: Boolean) {
        context.followGpxDataStore.edit { prefs ->
            val trailStr = points.joinToString(";") { "${it.latitude},${it.longitude}" }
            prefs[EXTENDED_START_TRAIL_KEY] = trailStr
            prefs[USER_CONTINUING_FROM_START_KEY] = isContinuing
        }
    }



    suspend fun loadExtendedTrail(): List<GeoPoint> {
        val prefs = context.followGpxDataStore.data.first()
        val trailStr = prefs[EXTENDED_TRAIL_KEY] ?: return emptyList()
        return trailStr.split(";").mapNotNull { pointStr ->
            val parts = pointStr.split(",")
            if (parts.size == 2) {
                try {
                    GeoPoint(parts[0].toDouble(), parts[1].toDouble())
                } catch (e: Exception) { null }
            } else null
        }
    }

    suspend fun loadExtendedStartTrail(): List<GeoPoint> {
        val prefs = context.followGpxDataStore.data.first()
        val trailStr = prefs[EXTENDED_START_TRAIL_KEY] ?: return emptyList()
        return trailStr.split(";").mapNotNull { pointStr ->
            val parts = pointStr.split(",")
            if (parts.size == 2) {
                try { GeoPoint(parts[0].toDouble(), parts[1].toDouble()) }
                catch (e: Exception) { null }
            } else null
        }
    }


    suspend fun wasUserContinuing(): Boolean {
        val prefs = context.followGpxDataStore.data.first()
        return prefs[USER_CONTINUING_KEY] ?: false
    }

    suspend fun wasUserContinuingFromStart(): Boolean {
        val prefs = context.followGpxDataStore.data.first()
        return prefs[USER_CONTINUING_FROM_START_KEY] ?: false
    }
    suspend fun saveImportedTrail(points: List<GeoPoint>) {
        context.followGpxDataStore.edit { prefs ->
            val trailStr = points.joinToString(";") { "${it.latitude},${it.longitude}" }
            prefs[TRAIL_POINTS_KEY] = trailStr
            prefs[GPX_IMPORTED_KEY] = true
        }
    }

    suspend fun loadImportedTrail(): List<GeoPoint> {
        val prefs = context.followGpxDataStore.data.first()
        val trailStr = prefs[TRAIL_POINTS_KEY] ?: return emptyList()
        return trailStr.split(";").mapNotNull { pointStr ->
            val parts = pointStr.split(",")
            if (parts.size == 2) {
                try {
                    GeoPoint(parts[0].toDouble(), parts[1].toDouble())
                } catch (e: Exception) { null }
            } else null
        }
    }

    suspend fun isGpxImported(): Boolean {
        val prefs = context.followGpxDataStore.data.first()
        return prefs[GPX_IMPORTED_KEY] ?: false
    }

    data class StepData(
        val steps: Int,
        val initialSensorCount: Int,
        val stepLength: Double,
        val searchRadiusMeters: Int,
        val loopClosingThresholdMeters: Int,
        val timestamps: List<ZonedDateTime>,
        val startStepCount: Int
    )

    suspend fun saveSteps(steps: Int) {
        context.followGpxDataStore.edit { prefs ->
            prefs[STEPS_KEY] = steps
        }
    }

    suspend fun saveInitialSensorCount(value: Int) {
        context.followGpxDataStore.edit { prefs -> prefs[INITIAL_SENSOR_COUNT_KEY] = value }
    }

    suspend fun saveIsTracking(active: Boolean) {
        context.followGpxDataStore.edit { prefs -> prefs[IS_TRACKING_KEY] = active }
    }

    suspend fun wasTracking(): Boolean =
        context.followGpxDataStore.data.first()[IS_TRACKING_KEY] ?: false

    suspend fun saveStepLength(length: Double) {
        context.followGpxDataStore.edit { prefs ->
            prefs[STEP_LENGTH_KEY] = length
        }
    }

    suspend fun saveSearchRadius(meters: Int) {
        context.followGpxDataStore.edit { prefs -> prefs[SEARCH_RADIUS_KEY] = meters }
    }

    suspend fun saveLoopClosingThreshold(meters: Int) {
        context.followGpxDataStore.edit { prefs -> prefs[LOOP_THRESHOLD_KEY] = meters }
    }

    suspend fun saveStartStepCount(count: Int) {
        context.followGpxDataStore.edit { prefs ->
            prefs[START_STEP_COUNT_KEY] = count
        }
    }

    suspend fun addTimestamp(timestamp: ZonedDateTime) {
        context.followGpxDataStore.edit { prefs ->
            val current = prefs[TIMESTAMPS_KEY] ?: ""
            val formatted = timestamp.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
            val updated = if (current.isEmpty()) formatted else "$current;$formatted"
            prefs[TIMESTAMPS_KEY] = updated
        }
    }

    suspend fun loadStepData(): StepData {
        val prefs = context.followGpxDataStore.data.first()

        val steps = prefs[STEPS_KEY] ?: 0
        val initialSensorCount = prefs[INITIAL_SENSOR_COUNT_KEY] ?: -1
        val stepLength = prefs[STEP_LENGTH_KEY] ?: 0.75
        val searchRadiusMeters = prefs[SEARCH_RADIUS_KEY] ?: 1000
        val loopClosingThresholdMeters = prefs[LOOP_THRESHOLD_KEY] ?: 10
        val startStepCount = prefs[START_STEP_COUNT_KEY] ?: 0

        val timestampsStr = prefs[TIMESTAMPS_KEY] ?: ""
        val timestamps = if (timestampsStr.isEmpty()) {
            emptyList()
        } else {
            timestampsStr.split(";").mapNotNull {
                try {
                    ZonedDateTime.parse(it, DateTimeFormatter.ISO_ZONED_DATE_TIME)
                } catch (e: Exception) {
                    null
                }
            }
        }

        return StepData(steps, initialSensorCount, stepLength, searchRadiusMeters, loopClosingThresholdMeters, timestamps, startStepCount)
    }

    // clears the current walk/session state, but preserves user settings (step length, search
    // radius) and the previous trails history across a new GPX import
    suspend fun resetAllData() {
        context.followGpxDataStore.edit { prefs ->
            prefs[STEPS_KEY] = 0
            prefs[INITIAL_SENSOR_COUNT_KEY] = -1
            prefs[IS_TRACKING_KEY] = false
            prefs[TIMESTAMPS_KEY] = ""
            prefs[START_STEP_COUNT_KEY] = 0
            prefs[TRAIL_POINTS_KEY] = ""
            prefs[GPX_IMPORTED_KEY] = false
            prefs[EXTENDED_TRAIL_KEY] = ""
            prefs[USER_CONTINUING_KEY] = false
            prefs[EXTENDED_START_TRAIL_KEY] = ""
            prefs[USER_CONTINUING_FROM_START_KEY] = false
            prefs[CONTINUE_AS_ROAD_KEY] = false
        }
    }

    suspend fun loadPreviousTrails(): List<PreviousTrail> {
        val raw = context.followGpxDataStore.data.first()[PREV_TRAILS_KEY] ?: return emptyList()
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
                null
            }
        }
    }

    suspend fun appendToPreviousTrails(trail: PreviousTrail) {
        if (trail.trailPoints.size < 2) return
        val current = loadPreviousTrails().toMutableList()
        current.add(0, trail)
        if (current.size > MAX_PREV_TRAILS) current.subList(MAX_PREV_TRAILS, current.size).clear()
        context.followGpxDataStore.edit { prefs ->
            prefs[PREV_TRAILS_KEY] = current.joinToString(TRAIL_SEP) { t ->
                val pts = t.trailPoints.joinToString(";") { "${it.latitude},${it.longitude}" }
                val ts = t.stepTimestamps.joinToString(TS_SEP) { it.format(DateTimeFormatter.ISO_ZONED_DATE_TIME) }
                "${t.savedAt.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)}${FIELD_SEP}${t.steps}${FIELD_SEP}${pts}${FIELD_SEP}${ts}"
            }
        }
    }

    suspend fun saveTimestamps(timestamps: List<ZonedDateTime>) {
        context.followGpxDataStore.edit { prefs ->
            val timestampsStr = timestamps.joinToString(";") {
                it.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
            }
            prefs[TIMESTAMPS_KEY] = timestampsStr
        }
    }
}
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
        private val STEP_LENGTH_KEY = doublePreferencesKey("step_length")
        private val TIMESTAMPS_KEY = stringPreferencesKey("timestamps")
        private val START_STEP_COUNT_KEY = intPreferencesKey("start_step_count")
        private val TRAIL_POINTS_KEY = stringPreferencesKey("imported_trail_points")
        private val GPX_IMPORTED_KEY = booleanPreferencesKey("gpx_imported")
        private val EXTENDED_TRAIL_KEY = stringPreferencesKey("extended_trail_points")
        private val USER_CONTINUING_KEY = booleanPreferencesKey("user_continuing")

    }
    suspend fun saveExtendedTrail(points: List<GeoPoint>, isContinuing: Boolean) {
        context.followGpxDataStore.edit { prefs ->
            val trailStr = points.joinToString(";") { "${it.latitude},${it.longitude}" }
            prefs[EXTENDED_TRAIL_KEY] = trailStr
            prefs[USER_CONTINUING_KEY] = isContinuing
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

    suspend fun wasUserContinuing(): Boolean {
        val prefs = context.followGpxDataStore.data.first()
        return prefs[USER_CONTINUING_KEY] ?: false
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
        val stepLength: Double,
        val timestamps: List<ZonedDateTime>,
        val startStepCount: Int
    )

    suspend fun saveSteps(steps: Int) {
        context.followGpxDataStore.edit { prefs ->
            prefs[STEPS_KEY] = steps
        }
    }

    suspend fun saveStepLength(length: Double) {
        context.followGpxDataStore.edit { prefs ->
            prefs[STEP_LENGTH_KEY] = length
        }
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
        val stepLength = prefs[STEP_LENGTH_KEY] ?: 0.75
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

        return StepData(steps, stepLength, timestamps, startStepCount)
    }

    suspend fun resetAllData() {
        context.followGpxDataStore.edit { it.clear() }
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
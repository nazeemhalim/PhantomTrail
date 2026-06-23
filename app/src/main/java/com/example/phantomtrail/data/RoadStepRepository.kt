package com.example.phantomtrail.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.osmdroid.util.GeoPoint
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val Context.roadDataStore by preferencesDataStore(name = "road_step_counter")

class RoadStepRepository(private val context: Context) {

    companion object {
        private const val TAG = "RoadStepRepository"
        private val STEPS_KEY = intPreferencesKey("steps")
        private val TIMESTAMPS_KEY = stringPreferencesKey("timestamps")
        private val STEP_LENGTH_KEY = doublePreferencesKey("step_length")
        private val TRAIL_POINTS_KEY = stringPreferencesKey("trail_points")
        private val ROAD_PATH_KEY = stringPreferencesKey("road_path")
        private val START_LAT_KEY = doublePreferencesKey("start_lat")
        private val START_LON_KEY = doublePreferencesKey("start_lon")
    }

    suspend fun loadStepData(): RoadStepData {
        val prefs = context.roadDataStore.data.first()
        return RoadStepData(
            steps = prefs[STEPS_KEY] ?: 0,
            timestamps = parseTimestamps(prefs[TIMESTAMPS_KEY]),
            stepLength = prefs[STEP_LENGTH_KEY] ?: Constants.DEFAULT_STEP_LENGTH,
            customStartLat = prefs[START_LAT_KEY] ?: Constants.DEFAULT_START_LAT,
            customStartLon = prefs[START_LON_KEY] ?: Constants.DEFAULT_START_LON
        )
    }

    suspend fun loadTrailPoints(): List<GeoPoint> =
        parsePoints(context.roadDataStore.data.first()[TRAIL_POINTS_KEY])

    suspend fun loadRoadPath(): List<GeoPoint> =
        parsePoints(context.roadDataStore.data.first()[ROAD_PATH_KEY])

    suspend fun saveSteps(steps: Int) {
        context.roadDataStore.edit { it[STEPS_KEY] = steps }
    }

    suspend fun saveTimestamps(timestamps: List<ZonedDateTime>) {
        context.roadDataStore.edit { prefs ->
            prefs[TIMESTAMPS_KEY] = timestamps.joinToString(";") {
                it.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
            }
        }
    }

    suspend fun saveStepLength(length: Double) {
        context.roadDataStore.edit { it[STEP_LENGTH_KEY] = length }
    }

    suspend fun saveTrailPoints(points: List<GeoPoint>) {
        context.roadDataStore.edit { prefs ->
            prefs[TRAIL_POINTS_KEY] = points.joinToString(";") { "${it.latitude},${it.longitude}" }
        }
    }

    suspend fun saveRoadPath(points: List<GeoPoint>) {
        context.roadDataStore.edit { prefs ->
            prefs[ROAD_PATH_KEY] = points.joinToString(";") { "${it.latitude},${it.longitude}" }
        }
    }

    suspend fun saveStartLocation(lat: Double, lon: Double) {
        context.roadDataStore.edit { prefs ->
            prefs[START_LAT_KEY] = lat
            prefs[START_LON_KEY] = lon
        }
    }

    suspend fun resetAllData() {
        context.roadDataStore.edit { prefs ->
            prefs[STEPS_KEY] = 0
            prefs[TIMESTAMPS_KEY] = ""
            prefs[TRAIL_POINTS_KEY] = ""
            prefs[ROAD_PATH_KEY] = ""
        }
    }

    private fun parseTimestamps(str: String?): List<ZonedDateTime> {
        if (str.isNullOrBlank()) return emptyList()
        return str.split(";").mapNotNull { ts ->
            if (ts.isNotBlank()) try { ZonedDateTime.parse(ts) } catch (e: Exception) { null }
            else null
        }
    }

    private fun parsePoints(str: String?): List<GeoPoint> {
        if (str.isNullOrBlank()) return emptyList()
        return str.split(";").mapNotNull { pt ->
            val parts = pt.split(",")
            if (parts.size == 2) try {
                GeoPoint(parts[0].toDouble(), parts[1].toDouble())
            } catch (e: Exception) { null }
            else null
        }
    }
}

data class RoadStepData(
    val steps: Int,
    val timestamps: List<ZonedDateTime>,
    val stepLength: Double,
    val customStartLat: Double,
    val customStartLon: Double
)

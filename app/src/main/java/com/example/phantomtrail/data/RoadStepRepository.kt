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
        private val PREV_TRAILS_KEY = stringPreferencesKey("prev_trails")
        private const val MAX_PREV_TRAILS = 5
        // delimiters chosen to be safe against coordinate/iso-date characters
        private const val TRAIL_SEP = "¶"   // between trails
        private const val FIELD_SEP = "§"   // between fields inside one trail
        private const val TS_SEP = "|"       // between timestamps inside one trail
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

    suspend fun loadPreviousTrails(): List<PreviousTrail> {
        val raw = context.roadDataStore.data.first()[PREV_TRAILS_KEY] ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(TRAIL_SEP).mapNotNull { entry ->
            try {
                val parts = entry.split(FIELD_SEP)
                if (parts.size != 4) return@mapNotNull null
                val savedAt = ZonedDateTime.parse(parts[0], DateTimeFormatter.ISO_ZONED_DATE_TIME)
                val steps = parts[1].toInt()
                val points = parsePoints(parts[2])
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
        context.roadDataStore.edit { prefs ->
            prefs[PREV_TRAILS_KEY] = current.joinToString(TRAIL_SEP) { t ->
                val pts = t.trailPoints.joinToString(";") { "${it.latitude},${it.longitude}" }
                val ts = t.stepTimestamps.joinToString(TS_SEP) { it.format(DateTimeFormatter.ISO_ZONED_DATE_TIME) }
                "${t.savedAt.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)}${FIELD_SEP}${t.steps}${FIELD_SEP}${pts}${FIELD_SEP}${ts}"
            }
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

data class PreviousTrail(
    val savedAt: ZonedDateTime,
    val steps: Int,
    val trailPoints: List<GeoPoint>,
    val stepTimestamps: List<ZonedDateTime>
)

data class RoadStepData(
    val steps: Int,
    val timestamps: List<ZonedDateTime>,
    val stepLength: Double,
    val customStartLat: Double,
    val customStartLon: Double
)

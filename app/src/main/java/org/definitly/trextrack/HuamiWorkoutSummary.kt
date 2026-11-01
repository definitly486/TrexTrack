package org.definitly.trextrack

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date

/**
 * Lightweight parse of Zepp OS sports summary:
 * [uint16 LE version=0x8000][protobuf WorkoutSummary]
 *
 * Field numbers match Gadgetbridge huami.proto (WorkoutSummary).
 */
data class ParsedWorkoutSummary(
    val version: Int,
    val activityType: Int?,
    val startTimestampSec: Long?,
    val totalDurationSec: Int?,
    val workoutDurationSec: Int?,
    val distanceMeters: Float?,
    val calories: Int?,
    val avgHr: Int?,
    val maxHr: Int?,
    val minHr: Int?,
    val steps: Int?,
    val baseLatitude: Double?,
    val baseLongitude: Double?,
    val baseAltitudeM: Double?,
    val hasGps: Boolean
) {
    fun shortDescription(): String {
        val type = activityType?.let { typeName(it) } ?: "?"
        val dist = distanceMeters?.let { "%.0f м".format(it) } ?: "—"
        val dur = (workoutDurationSec ?: totalDurationSec)?.let { formatDuration(it) } ?: "—"
        val cal = calories?.let { "$it ккал" } ?: "—"
        val hr = avgHr?.let { "HR $it" } ?: ""
        val gps = if (hasGps) "GPS" else "no GPS"
        val whenStr = startTimestampSec?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(Date(it * 1000))
        } ?: "—"
        return "$whenStr · $type · $dist · $dur · $cal $hr · $gps"
    }

    companion object {
        fun typeName(code: Int): String = when (code) {
            1 -> "бег"
            3 -> "ходьба"
            4 -> "велосипед"
            8 -> "трейл"
            9 -> "бег на улице"
            else -> "type=$code"
        }

        private fun formatDuration(sec: Int): String {
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val s = sec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}

object HuamiWorkoutSummaryParser {
    private const val TAG = "HuamiSummary"

    fun parse(raw: ByteArray): ParsedWorkoutSummary? {
        if (raw.size < 4) return null
        val version = (raw[0].toInt() and 0xff) or ((raw[1].toInt() and 0xff) shl 8)
        if (version != 0x8000) {
            Log.w(TAG, "unexpected summary version 0x${"%04x".format(version)}")
        }
        val pb = raw.copyOfRange(2, raw.size)
        return parseProtobuf(version, pb)
    }

    private fun parseProtobuf(version: Int, data: ByteArray): ParsedWorkoutSummary {
        var activityType: Int? = null
        var startTs: Long? = null
        var totalDur: Int? = null
        var workoutDur: Int? = null
        var distance: Float? = null
        var calories: Int? = null
        var avgHr: Int? = null
        var maxHr: Int? = null
        var minHr: Int? = null
        var steps: Int? = null
        var baseLat: Double? = null
        var baseLon: Double? = null
        var baseAlt: Double? = null

        walk(data) { field, wire, value ->
            when (field) {
                2 -> if (wire == 2) { // Location message
                    walk(value as ByteArray) { f, w, v ->
                        when (f) {
                            1 -> if (w == 0) startTs = (v as Long)
                            5 -> if (w == 0) baseLat = (v as Long).toInt() / 6_000_000.0
                            6 -> if (w == 0) baseLon = (v as Long).toInt() / -6_000_000.0
                            7 -> if (w == 0) baseAlt = (v as Long).toInt() / 2.0
                        }
                    }
                }
                3 -> if (wire == 2) { // Type
                    walk(value as ByteArray) { f, w, v ->
                        if (f == 1 && w == 0) activityType = (v as Long).toInt()
                    }
                }
                4 -> if (wire == 2) { // Distance
                    walk(value as ByteArray) { f, w, v ->
                        if (f == 1 && w == 5) distance = v as Float
                    }
                }
                7 -> if (wire == 2) { // Time
                    walk(value as ByteArray) { f, w, v ->
                        when (f) {
                            1 -> if (w == 0) totalDur = (v as Long).toInt()
                            2 -> if (w == 0) workoutDur = (v as Long).toInt()
                        }
                    }
                }
                11 -> if (wire == 2) { // Steps
                    walk(value as ByteArray) { f, w, v ->
                        if (f == 4 && w == 0) steps = (v as Long).toInt()
                    }
                }
                16 -> if (wire == 2) { // Calories
                    walk(value as ByteArray) { f, w, v ->
                        if (f == 1 && w == 0) calories = (v as Long).toInt()
                    }
                }
                19 -> if (wire == 2) { // HeartRate
                    walk(value as ByteArray) { f, w, v ->
                        when (f) {
                            1 -> if (w == 0) avgHr = (v as Long).toInt()
                            2 -> if (w == 0) maxHr = (v as Long).toInt()
                            3 -> if (w == 0) minHr = (v as Long).toInt()
                        }
                    }
                }
            }
        }

        val hasGps = baseLat != null && baseLon != null
        return ParsedWorkoutSummary(
            version = version,
            activityType = activityType,
            startTimestampSec = startTs,
            totalDurationSec = totalDur,
            workoutDurationSec = workoutDur,
            distanceMeters = distance,
            calories = calories,
            avgHr = avgHr,
            maxHr = maxHr,
            minHr = minHr,
            steps = steps,
            baseLatitude = baseLat,
            baseLongitude = baseLon,
            baseAltitudeM = baseAlt,
            hasGps = hasGps
        )
    }

    /**
     * Minimal protobuf walker. Invokes [handler] with (fieldNumber, wireType, value).
     * value is Long for varint, ByteArray for length-delimited, Float for fixed32.
     */
    private fun walk(data: ByteArray, handler: (field: Int, wire: Int, value: Any) -> Unit) {
        var i = 0
        while (i < data.size) {
            val (tag, tagLen) = readVarint(data, i) ?: break
            i += tagLen
            val field = (tag shr 3).toInt()
            val wire = (tag and 7).toInt()
            when (wire) {
                0 -> { // varint
                    val (v, len) = readVarint(data, i) ?: break
                    i += len
                    handler(field, wire, v)
                }
                1 -> { // 64-bit
                    if (i + 8 > data.size) break
                    i += 8
                }
                2 -> { // length-delimited
                    val (lenV, lenLen) = readVarint(data, i) ?: break
                    i += lenLen
                    val len = lenV.toInt()
                    if (len < 0 || i + len > data.size) break
                    val slice = data.copyOfRange(i, i + len)
                    i += len
                    handler(field, wire, slice)
                }
                5 -> { // 32-bit
                    if (i + 4 > data.size) break
                    val f = ByteBuffer.wrap(data, i, 4).order(ByteOrder.LITTLE_ENDIAN).float
                    i += 4
                    handler(field, wire, f)
                }
                else -> break
            }
        }
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < data.size && shift < 64) {
            val b = data[i].toInt() and 0xff
            i++
            result = result or ((b and 0x7f).toLong() shl shift)
            if ((b and 0x80) == 0) return result to (i - start)
            shift += 7
        }
        return null
    }
}

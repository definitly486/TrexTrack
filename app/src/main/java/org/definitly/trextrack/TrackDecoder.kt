package org.definitly.trextrack

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class TrackPoint(
    val timeEpochSec: Long,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val hr: Int?
)

/**
 * Decodes Zepp/Huami delta-encoded track streams into absolute points.
 * lat/lon are cumulative deltas / 1e8; time is cumulative seconds from track start;
 * altitude is cm (/100); HR is cumulative deltas of bpm.
 */
object TrackDecoder {

    fun decodePoints(
        startEpochSec: Long,
        longitudeLatitude: String?,
        timeDeltas: String?,
        altitude: String?,
        heartRate: String?
    ): List<TrackPoint> {
        val latLonPairs = parseLatLon(longitudeLatitude)
        if (latLonPairs.isEmpty()) return emptyList()

        val times = accumulate(parseLongList(timeDeltas))
        val alts = parseLongList(altitude)
        val hrPairs = parseHr(heartRate)
        val hrTimes = accumulate(hrPairs.map { it.first })
        val hrValues = accumulate(hrPairs.map { it.second })

        var lat = 0L
        var lon = 0L
        val points = ArrayList<TrackPoint>(latLonPairs.size)
        for (i in latLonPairs.indices) {
            lat += latLonPairs[i].first
            lon += latLonPairs[i].second
            val t = if (i < times.size) startEpochSec + times[i] else startEpochSec + i
            val ele = if (i < alts.size && alts[i] != NO_VALUE) alts[i] / 100.0 else null
            val hr = lookupHr(t - startEpochSec, hrTimes, hrValues)
            points.add(
                TrackPoint(
                    timeEpochSec = t,
                    lat = lat / 1e8,
                    lon = lon / 1e8,
                    ele = ele,
                    hr = hr
                )
            )
        }
        return points
    }

    private const val NO_VALUE = -2000000L

    private fun parseLatLon(raw: String?): List<Pair<Long, Long>> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { part ->
            val p = part.trim()
            if (p.isEmpty()) return@mapNotNull null
            val bits = p.split(',')
            if (bits.size < 2) return@mapNotNull null
            val a = bits[0].toLongOrNull() ?: return@mapNotNull null
            val b = bits[1].toLongOrNull() ?: return@mapNotNull null
            a to b
        }
    }

    private fun parseLongList(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toLongOrNull() }
    }

    private fun parseHr(raw: String?): List<Pair<Long, Long>> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { part ->
            val p = part.trim()
            if (p.isEmpty()) return@mapNotNull null
            val bits = p.split(',')
            if (bits.size < 2) return@mapNotNull null
            val dt = bits[0].toLongOrNull() ?: 1L
            val dhr = bits[1].toLongOrNull() ?: return@mapNotNull null
            dt to dhr
        }
    }

    private fun accumulate(values: List<Long>): List<Long> {
        if (values.isEmpty()) return emptyList()
        val out = ArrayList<Long>(values.size)
        var s = 0L
        for (v in values) {
            s += v
            out.add(s)
        }
        return out
    }

    private fun lookupHr(relSec: Long, hrTimes: List<Long>, hrValues: List<Long>): Int? {
        if (hrTimes.isEmpty() || hrValues.isEmpty()) return null
        var best = hrValues[0]
        for (i in hrTimes.indices) {
            if (hrTimes[i] <= relSec) best = hrValues[i] else break
        }
        return best.toInt().takeIf { it in 30..250 }
    }
}

object GpxWriter {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun toGpx(workoutId: String, name: String, points: List<TrackPoint>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append('\n')
        sb.append(
            """<gpx version="1.1" creator="TrexTrack" xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1" """ +
                """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """ +
                """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">"""
        )
        sb.append('\n')
        sb.append("<metadata><name>").append(xmlEscape(name)).append("</name></metadata>\n")
        sb.append("<trk>\n<name>").append(xmlEscape(name)).append("</name>\n")
        sb.append("<desc>trackid=").append(xmlEscape(workoutId)).append("</desc>\n")
        sb.append("<trkseg>\n")
        for (p in points) {
            sb.append("""<trkpt lat="""").append(fmt(p.lat)).append('"')
            sb.append(""" lon="""").append(fmt(p.lon)).append("\">\n")
            if (p.ele != null) {
                sb.append("<ele>").append(fmt(p.ele)).append("</ele>\n")
            }
            sb.append("<time>").append(isoFormat.format(Date(p.timeEpochSec * 1000))).append("</time>\n")
            if (p.hr != null) {
                sb.append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>")
                    .append(p.hr)
                    .append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>\n")
            }
            sb.append("</trkpt>\n")
        }
        sb.append("</trkseg>\n</trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.7f", v)
    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

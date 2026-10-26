package org.definitly.trextrack

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Unofficial, read-only Zepp Cloud client. Zepp can change these private endpoints at any time. */
class ZeppCloudRepository(context: Context) {
    private val store = SecureSessionStore(context)
    @Volatile private var session: Session? = store.readSession()?.let { decodeSession(it.account, it.token) }

    data class Workout(
        val id: String,
        val title: String,
        val startTime: String?,
        val distanceMeters: Double?,
        val durationSeconds: Long?,
        val source: String?,
        val hasGpsTrack: Boolean
    )

    data class Session(val account: String, val appToken: String, val userId: String, val host: String = DEFAULT_HOST)

    fun hasSession(): Boolean = session != null
    fun accountName(): String? = session?.account

    @Synchronized
    fun authenticate(account: String, password: CharArray, country: String = "US") {
        require(account.isNotBlank()) { "Введите email или логин Zepp" }
        require(password.isNotEmpty()) { "Введите пароль" }
        val passwordString = String(password)
        try {
            val http = HttpClient()
            val registrationUrl = "https://api-user.huami.com/registrations/${urlEncode(account)}/tokens"
            val reg = http.request(
                "POST", registrationUrl,
                form = linkedMapOf(
                    "client_id" to "HuaMi", "country_code" to country,
                    "json_response" to "true", "name" to account, "password" to passwordString,
                    "redirect_uri" to WEB_REDIRECT_URI, "state" to "REDIRECTION", "token" to "access"
                ),
                headers = mapOf(
                    "app_name" to WEB_APP_NAME,
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Origin" to "https://user.zepp.com",
                    "Referer" to "https://user.zepp.com/",
                    "X-Request-ID" to UUID.randomUUID().toString()
                ), allowRedirects = false
            )
            val location = reg.headers["Location"]?.firstOrNull() ?: reg.headers["location"]?.firstOrNull()
            var accessCode: String? = location?.let { queryValue(it, "access") }
            if (accessCode.isNullOrBlank() && reg.body.isNotBlank()) {
                runCatching { JSONObject(reg.body).let { accessCode = it.optString("access").takeIf { value -> value.isNotBlank() } ?: it.optString("code").takeIf { value -> value.isNotBlank() } } }
            }
            if (accessCode.isNullOrBlank()) {
                throw ZeppException("Вход отклонён на первом шаге (HTTP ${reg.code}). Проверьте логин, пароль и код страны. Не повторяйте попытки часто: Zepp может ограничивать вход.")
            }

            val loginHeaders = mapOf(
                "app_name" to WEB_APP_NAME, "appname" to WEB_APP_NAME,
                "appplatform" to "web", "Origin" to "https://user.zepp.com",
                "Referer" to "https://user.zepp.com/", "User-Agent" to WEB_USER_AGENT
            )
            val login = http.request(
                "POST", "https://api-mifit.zepp.com/v2/client/login",
                form = linkedMapOf(
                    "allow_registration" to "false", "app_name" to WEB_APP_NAME,
                    "app_version" to "1.0.0", "code" to accessCode!!,
                    "country_code" to country, "device_id" to "web_${UUID.randomUUID()}",
                    "device_model" to "web",
                    "dn" to "api-mifit.zepp.com,api-user.zepp.com,api-watch.zepp.com,auth.zepp.com",
                    "grant_type" to "access_token", "source" to WEB_APP_NAME, "third_name" to "huami"
                ), headers = loginHeaders
            )
            val tokenInfo = runCatching { JSONObject(login.body).optJSONObject("token_info") }.getOrNull()
            val loginToken = tokenInfo?.optString("login_token")?.takeIf { it.isNotBlank() }
            val userId = tokenInfo?.optString("user_id")?.takeIf { it.isNotBlank() }
            if (login.code !in 200..299 || loginToken == null || userId == null) {
                throw ZeppException("Не удалось получить токен входа (HTTP ${login.code}). API Zepp мог измениться или аккаунт использует другой регион.")
            }

            val tokenResponse = http.request(
                "GET", "https://api-mifit.zepp.com/v1/client/app_tokens",
                query = linkedMapOf(
                    "app_name" to WEB_APP_NAME,
                    "dn" to "api-mifit.zepp.com,api-user.zepp.com,auth.zepp.com",
                    "login_token" to loginToken
                ), headers = loginHeaders
            )
            val appToken = runCatching {
                JSONObject(tokenResponse.body).optJSONObject("token_info")?.optString("app_token")
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (tokenResponse.code !in 200..299 || appToken == null) {
                throw ZeppException("Zepp не выдал app_token (HTTP ${tokenResponse.code}).")
            }

            val newSession = Session(account, appToken, userId)
            // Store only the app token and required metadata; password/login_token are not persisted.
            store.saveSession(account, JSONObject().put("appToken", appToken).put("userId", userId).put("host", DEFAULT_HOST).toString())
            session = newSession
        } finally {
            password.fill('\u0000')
        }
    }

    @Synchronized
    fun logout() {
        session = null
        store.clearSession()
    }

    fun listWorkouts(limit: Int = 100): List<Workout> {
        val s = requireSession()
        val response = apiGet(s, "/v1/sport/run/history.json", mapOf("source" to "run.mi.com", "userid" to s.userId, "limit" to limit.toString()))
        val items = extractItems(response)
        return items.mapNotNull { item ->
            val id = item.optString("trackid").ifBlank { item.optString("id") }.ifBlank { return@mapNotNull null }
            val type = item.optString("type", item.optString("sport_type", "Тренировка"))
            val distance = number(item, "distance_m", "total_distance", "distance")
            val duration = number(item, "duration", "duration_seconds", "time")?.toLong()
            val track = item.optString("longitude_latitude").isNotBlank() || item.optBoolean("has_gps", false) || item.optString("trackid").isNotBlank()
            Workout(id, sportName(type), item.optString("start_time").takeIf { it.isNotBlank() } ?: item.optString("startTime").takeIf { it.isNotBlank() }, distance, duration, item.optString("source").ifBlank { "run.mi.com" }, track)
        }
    }

    fun downloadTrack(workout: Workout): String {
        val s = requireSession()
        val response = apiGet(s, "/v1/sport/run/detail.json", mapOf("trackid" to workout.id, "source" to (workout.source ?: "run.mi.com"), "userid" to s.userId))
        val root = JSONObject(response)
        var data: Any? = root.opt("data")
        if (data is JSONObject && data.optJSONObject("data") != null) data = data.optJSONObject("data")
        if (data is String) data = decodeJsonOrBase64(data)
        val detail = when (data) { is JSONObject -> data; is JSONArray -> JSONObject().put("longitude_latitude", data); else -> root }
        val points = parseTrackPoints(detail, workout.id)
        if (points.isEmpty()) throw ZeppException("Тренировка получена, но GPS-точки не найдены. API мог вернуть сжатый или неизвестный формат трека.")
        return buildGpx(workout, points)
    }

    private data class TrackPoint(val lat: Double, val lon: Double, val ele: Double?, val time: String?)

    private fun parseTrackPoints(detail: JSONObject, workoutId: String): List<TrackPoint> {
        val candidates = listOf("longitude_latitude", "track", "gps", "points", "coordinates", "track_points")
        var raw: Any? = null
        for (key in candidates) {
            val v = detail.opt(key)
            if (v != null && v != JSONObject.NULL && v != "") { raw = v; break }
        }
        // Zepp's actual workout format stores GPS as semicolon-separated delta samples:
        // latitude_delta,longitude_delta. Time is also delta encoded; coordinates are scaled by 1e8.
        val encodedGps = detail.optString("longitude_latitude")
        if (encodedGps.contains(';') && encodedGps.contains(',')) {
            val gpsSamples = encodedGps.split(';').filter { it.isNotBlank() }
            val timeSamples = detail.optString("time").split(';').filter { it.isNotBlank() }
            val altitudeSamples = detail.optString("altitude").split(';').filter { it.isNotBlank() }
            if (gpsSamples.isNotEmpty()) {
                var latAcc = 0L
                var lonAcc = 0L
                var timeAcc = 0L
                val startSeconds = workoutId.toLongOrNull()
                val encodedPoints = mutableListOf<TrackPoint>()
                for (i in gpsSamples.indices) {
                    val pair = gpsSamples[i].split(',')
                    if (pair.size < 2) continue
                    val latDelta = pair[0].toLongOrNull() ?: continue
                    val lonDelta = pair[1].toLongOrNull() ?: continue
                    latAcc += latDelta
                    lonAcc += lonDelta
                    val deltaTime = timeSamples.getOrNull(i)?.toLongOrNull() ?: 0L
                    timeAcc += deltaTime
                    val altitude = altitudeSamples.getOrNull(i)?.toDoubleOrNull()?.div(100.0)
                    val timeIso = startSeconds?.let { normalizeTimestamp((it + timeAcc).toString()) }
                    val lat = latAcc / 100000000.0
                    val lon = lonAcc / 100000000.0
                    if (lat in -90.0..90.0 && lon in -180.0..180.0) encodedPoints += TrackPoint(lat, lon, altitude, timeIso)
                }
                if (encodedPoints.isNotEmpty()) return encodedPoints
            }
        }
        if (raw is String) raw = decodeJsonOrBase64(raw)
        val result = mutableListOf<TrackPoint>()
        fun addPair(lon: Any?, lat: Any?, ele: Any? = null, time: Any? = null) {
            val x = lon?.toString()?.toDoubleOrNull() ?: return
            val y = lat?.toString()?.toDoubleOrNull() ?: return
            if (x in -180.0..180.0 && y in -90.0..90.0) result += TrackPoint(y, x, ele?.toString()?.toDoubleOrNull(), time?.toString())
        }
        when (raw) {
            is JSONArray -> for (i in 0 until raw.length()) {
                val item = raw.opt(i)
                when (item) {
                    is JSONArray -> if (item.length() >= 2) addPair(item.opt(0), item.opt(1), item.opt(2), item.opt(3))
                    is JSONObject -> addPair(item.opt("lon") ?: item.opt("longitude") ?: item.opt("lng"), item.opt("lat") ?: item.opt("latitude"), item.opt("altitude") ?: item.opt("ele"), item.opt("time") ?: item.opt("timestamp"))
                }
            }
            is JSONObject -> {
                val nested = raw.optJSONArray("points") ?: raw.optJSONArray("data")
                if (nested != null) return parseTrackPoints(JSONObject().put("longitude_latitude", nested), workoutId)
                val lon = raw.optJSONArray("longitude") ?: raw.optJSONArray("lon")
                val lat = raw.optJSONArray("latitude") ?: raw.optJSONArray("lat")
                if (lon != null && lat != null) for (i in 0 until minOf(lon.length(), lat.length())) addPair(lon.opt(i), lat.opt(i))
            }
        }
        if (result.isNotEmpty()) return result
        // Some API versions store a flat alternating longitude/latitude number array.
        val text = raw?.toString() ?: return emptyList()
        val nums = Regex("-?\\d+(?:\\.\\d+)?").findAll(text).mapNotNull { it.value.toDoubleOrNull() }.toList()
        if (nums.size >= 4 && nums.size % 2 == 0) {
            for (i in nums.indices step 2) addPair(nums[i], nums[i + 1])
        }
        return result
    }

    private fun buildGpx(workout: Workout, points: List<TrackPoint>): String {
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"TrexTrack\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        out.append("<metadata><name>").append(xmlEscape(workout.title)).append("</name></metadata>\n<trk><name>")
            .append(xmlEscape(workout.title)).append("</name><trkseg>\n")
        for (p in points) {
            out.append("<trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon).append("\">")
            p.ele?.let { out.append("<ele>").append(it).append("</ele>") }
            p.time?.let { timestamp -> normalizeTimestamp(timestamp)?.let { out.append("<time>").append(it).append("</time>") } }
            out.append("</trkpt>\n")
        }
        return out.append("</trkseg></trk></gpx>\n").toString()
    }

    private fun apiGet(s: Session, path: String, params: Map<String, String>): String {
        val headers = linkedMapOf(
            "apptoken" to s.appToken, "x-request-id" to UUID.randomUUID().toString(),
            "hm-privacy-diagnostics" to "false", "country" to "US", "appplatform" to "android_phone",
            "hm-privacy-ceip" to "true", "timezone" to TimeZone.getDefault().id,
            "channel" to "a100900101016", "vb" to "202509151347", "cv" to "151689_9.12.5",
            "appname" to "com.huami.midong", "v" to "2.0", "vn" to "9.12.5", "lang" to "en_US",
            "user-agent" to "Zepp/9.12.5 (Android; TrexTrack)", "accept-encoding" to "gzip"
        )
        val result = HttpClient().request("GET", "https://${s.host}$path", query = params, headers = headers)
        if (result.code == 401 || result.code == 403) {
            logout()
            throw ZeppException("Сессия Zepp истекла или отозвана. Войдите снова.")
        }
        if (result.code !in 200..299) throw ZeppException("Ошибка Zepp API: HTTP ${result.code}")
        return result.body
    }

    private fun requireSession(): Session = session ?: throw ZeppException("Сначала войдите в аккаунт Zepp")

    private fun decodeSession(account: String, payload: String): Session? = runCatching {
        val json = JSONObject(payload)
        Session(account, json.getString("appToken"), json.getString("userId"), json.optString("host", DEFAULT_HOST))
    }.getOrNull()

    private fun extractItems(rootText: String): List<JSONObject> {
        val root = JSONObject(rootText)
        var data: Any? = root.opt("data") ?: root
        if (data is String) data = decodeJsonOrBase64(data)
        if (data is JSONObject) {
            val summary = data.opt("summary")
            if (summary is String) data = decodeJsonOrBase64(summary)
            else if (summary is JSONArray) return jsonObjects(summary)
        }
        if (data is JSONObject) {
            val arr = data.optJSONArray("items") ?: data.optJSONArray("data")
            if (arr != null) return jsonObjects(arr)
            val summary = data.optJSONObject("summary")?.optJSONArray("data")
            if (summary != null) return jsonObjects(summary)
        }
        if (data is JSONArray) return jsonObjects(data)
        return emptyList()
    }

    private fun jsonObjects(arr: JSONArray): List<JSONObject> = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    private fun decodeJsonOrBase64(value: String): Any? {
        runCatching { return JSONObject(value) }
        runCatching { return JSONArray(value) }
        runCatching {
            val decoded = String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8)
            runCatching { return JSONObject(decoded) }
            runCatching { return JSONArray(decoded) }
        }
        return null
    }
    private fun number(o: JSONObject, vararg keys: String): Double? = keys.firstNotNullOfOrNull { k ->
        val v = o.opt(k)
        when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
    }
    private fun sportName(type: String): String = when (type) {
        "1", "run", "running" -> "Бег"
        "6", "walk", "walking" -> "Ходьба"
        "9", "cycling", "bike" -> "Велосипед"
        "10" -> "Велотренажёр"
        "8" -> "Беговая дорожка"
        else -> if (type.all { it.isDigit() }) "Тренировка ($type)" else type.replaceFirstChar { it.uppercase() }
    }
    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun normalizeTimestamp(v: String): String? {
        v.toLongOrNull()?.let { millis ->
            val d = Date(if (millis < 10_000_000_000L) millis * 1000 else millis)
            return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)
        }
        return if (v.matches(Regex("\\d{4}-\\d\\d-\\d\\dT.*"))) v else null
    }
    private fun queryValue(url: String, name: String): String? = runCatching {
        val q = url.substringAfter('?', "").substringBefore('#')
        q.split('&').firstOrNull { it.substringBefore('=') == name }?.substringAfter('=')?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()

    class ZeppException(message: String) : Exception(message)

    private class HttpClient {
        data class Response(val code: Int, val body: String, val headers: Map<String, List<String>>)
        fun request(method: String, url: String, form: Map<String, String>? = null, query: Map<String, String>? = null,
                    headers: Map<String, String> = emptyMap(), allowRedirects: Boolean = true): Response {
            val fullUrl = if (query.isNullOrEmpty()) url else url + (if (url.contains('?')) "&" else "?") + query.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }
            val c = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20000; readTimeout = 30000
                instanceFollowRedirects = allowRedirects
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (form != null) {
                    doOutput = true
                    if (getRequestProperty("Content-Type") == null) setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val body = form.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }
                    outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            val result = Response(code, body, c.headerFields.filterKeys { it != null })
            c.disconnect()
            return result
        }
    }

    companion object {
        private const val DEFAULT_HOST = "api-mifit.zepp.com"
        private const val WEB_APP_NAME = "com.huami.webapp"
        private const val WEB_REDIRECT_URI = "https://s3-us-west-2.amazonaws.com/hm-registration/successsignin.html"
        private const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}

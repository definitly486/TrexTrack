package org.definitly.trextrack

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cloud repository: auth + workout history + GPS detail → GPX.
 */
class ZeppCloudRepositoryImpl(
    private val sessionStore: SessionStore,
    private val authClient: ZeppAuthClient = ZeppAuthClient(),
    private val http: OkHttpClient = defaultDataClient()
) : ZeppCloudRepository {

    private val sourceByTrackId = mutableMapOf<String, String>()

    /** Первая пара host/appname, на которой сервер ответил 2xx. Дальше используем только её. */
    @Volatile
    private var workingCombo: Pair<String, String>? = null

    override suspend fun authenticate(account: String, credential: CharArray): AuthResult {
        return try {
            val session = authClient.login(account.trim(), credential)
            credential.fill('\u0000')
            sessionStore.saveSession(
                appToken = session.appToken,
                loginToken = session.loginToken,
                userId = session.userId,
                accountHint = maskAccount(account)
            )
            AuthResult.Success
        } catch (e: ZeppAuthClient.AuthException) {
            credential.fill('\u0000')
            AuthResult.Failure(e.message ?: "Ошибка авторизации")
        } catch (e: Exception) {
            credential.fill('\u0000')
            AuthResult.Failure(e.message ?: "Сетевая или внутренняя ошибка")
        }
    }

    override suspend fun listWorkouts(): List<WorkoutSummary> {
        val token = sessionStore.getAppToken() ?: throw IllegalStateException("Нет активной сессии")
        val userId = sessionStore.getUserId() ?: throw IllegalStateException("Нет user_id")

        val all = ArrayList<WorkoutSummary>()
        var nextTrackId: Long? = null
        var pages = 0
        do {
            pages++
            val urlBuilder = "https://placeholder/v1/sport/run/history.json".toHttpUrl().newBuilder()
                .addQueryParameter("userid", userId)
                .addQueryParameter("limit", "50")
            if (nextTrackId != null) {
                urlBuilder.addQueryParameter("trackid", nextTrackId.toString())
            }
            val path = urlBuilder.build().encodedPath + "?" + (urlBuilder.build().encodedQuery ?: "")
            val body = getJson(path, token)
            val data = body.optJSONObject("data") ?: body
            val summary = data.optJSONArray("summary") ?: JSONArray()
            for (i in 0 until summary.length()) {
                val o = summary.optJSONObject(i) ?: continue
                val id = o.optString("trackid")
                if (id.isBlank()) continue
                val source = o.optString("source").ifBlank { "run.mi.com" }
                sourceByTrackId[id] = source
                val dis = o.optString("dis").toDoubleOrNull()
                val runTime = o.optString("run_time").toLongOrNull()
                val type = o.optInt("type", 0)
                all.add(
                    WorkoutSummary(
                        id = id,
                        title = titleFor(type, id),
                        startTimeIso = epochToIso(id.toLongOrNull()),
                        distanceMeters = dis,
                        durationSeconds = runTime,
                        hasGpsTrack = true
                    )
                )
            }
            val next = data.optLong("next", -1L)
            nextTrackId = if (next > 0) next else null
        } while (nextTrackId != null && pages < 20)

        Log.i(TAG, "listWorkouts: ${all.size} items, pages=$pages")
        return all
    }

    override suspend fun downloadTrack(workoutId: String): GpxTrack {
        val token = sessionStore.getAppToken() ?: throw IllegalStateException("Нет активной сессии")
        val userId = sessionStore.getUserId()
        val source = sourceByTrackId[workoutId] ?: "run.mi.com"

        val urlBuilder = "https://placeholder/v1/sport/run/detail.json".toHttpUrl().newBuilder()
            .addQueryParameter("trackid", workoutId)
            .addQueryParameter("source", source)
        if (!userId.isNullOrBlank()) {
            urlBuilder.addQueryParameter("userid", userId)
        }
        val path = urlBuilder.build().encodedPath + "?" + (urlBuilder.build().encodedQuery ?: "")
        val body = getJson(path, token)
        val data = body.optJSONObject("data") ?: body

        val start = workoutId.toLongOrNull()
            ?: throw TrackDownloadException(
                TrackDownloadException.Kind.PARSE, "Некорректный trackid: $workoutId"
            )
        val lonLat = data.optString("longitude_latitude").takeIf { it.isNotBlank() }
            ?: throw TrackDownloadException(
                TrackDownloadException.Kind.NO_GPS, "Тренировка без GPS-маршрута ($workoutId)"
            )
        val points = try {
            TrackDecoder.decodePoints(
                startEpochSec = start,
                longitudeLatitude = lonLat,
                timeDeltas = data.optString("time").takeIf { it.isNotBlank() },
                altitude = data.optString("altitude").takeIf { it.isNotBlank() },
                heartRate = data.optString("heart_rate").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            throw TrackDownloadException(
                TrackDownloadException.Kind.PARSE, "Ошибка разбора трека $workoutId: ${e.message}", e
            )
        }
        if (points.isEmpty()) {
            throw TrackDownloadException(
                TrackDownloadException.Kind.NO_GPS, "Нет GPS-точек для $workoutId"
            )
        }
        val name = "Zepp-$workoutId"
        val gpx = GpxWriter.toGpx(workoutId, name, points)
        Log.i(TAG, "downloadTrack $workoutId: ${points.size} points")
        return GpxTrack(workoutId = workoutId, gpxXml = gpx)
    }

    fun logout() {
        sessionStore.clear()
        sourceByTrackId.clear()
        workingCombo = null
    }

    private sealed interface Attempt {
        class Ok(val json: JSONObject) : Attempt
        /** Повторить на той же паре host/appname после паузы. */
        class Retry(val kind: TrackDownloadException.Kind, val msg: String, val waitMs: Long) : Attempt
        /** Эта пара не подходит. */
        class Fail(val kind: TrackDownloadException.Kind, val msg: String) : Attempt
    }

    private fun attemptOnce(host: String, appName: String, pathAndQuery: String, appToken: String, attempt: Int): Attempt {
        val request = Request.Builder()
            .url(host + pathAndQuery)
            .get()
            .header("apptoken", appToken)
            .header("appname", appName)
            .header("app_name", appName)
            .header("appPlatform", "android_phone")
            .header("User-Agent", "Zepp/9.12.5 (Android 12)")
            .header("Accept", "application/json")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val code = response.code
                val where = "$host/$appName"
                when {
                    response.isSuccessful -> try {
                        Attempt.Ok(JSONObject(text))
                    } catch (e: JSONException) {
                        Attempt.Fail(TrackDownloadException.Kind.PARSE, "Не JSON @ $where: ${text.take(80)}")
                    }
                    code == 429 -> {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()?.times(1000)
                        Attempt.Retry(
                            TrackDownloadException.Kind.RATE_LIMIT,
                            "HTTP 429 @ $where",
                            retryAfter ?: (2000L * attempt)
                        )
                    }
                    code in 500..599 -> Attempt.Retry(
                        TrackDownloadException.Kind.SERVER, "HTTP $code @ $where", 1500L * attempt
                    )
                    code == 401 || code == 403 -> Attempt.Fail(
                        TrackDownloadException.Kind.AUTH, "HTTP $code @ $where: ${text.take(80)}"
                    )
                    code == 404 -> Attempt.Fail(
                        TrackDownloadException.Kind.NOT_FOUND, "HTTP 404 @ $where"
                    )
                    else -> Attempt.Fail(
                        TrackDownloadException.Kind.SERVER, "HTTP $code @ $where: ${text.take(80)}"
                    )
                }
            }
        } catch (e: IOException) {
            Attempt.Retry(TrackDownloadException.Kind.NETWORK, "$host: ${e.message}", 1000L * attempt)
        }
    }

    private fun getJson(pathAndQuery: String, appToken: String): JSONObject {
        // pathAndQuery like "/v1/sport/run/history.json?userid=..."
        val cached = workingCombo
        if (cached != null) {
            // Рабочая пара уже известна: НЕ перебираем остальные 19 вариантов (это и давало лавину
            // запросов/таймаутов при массовом экспорте), а повторяем запрос с паузой.
            return requestWithRetry(cached.first, cached.second, pathAndQuery, appToken, maxAttempts = 3)
                ?: throw lastFailure
        }

        var last: TrackDownloadException? = null
        for (host in HOSTS) {
            for (appName in APP_NAMES) {
                val json = requestWithRetry(host, appName, pathAndQuery, appToken, maxAttempts = 1)
                if (json != null) {
                    Log.i(TAG, "OK $host appname=$appName")
                    workingCombo = host to appName
                    return json
                }
                last = lastFailure
            }
        }
        throw TrackDownloadException(
            last?.kind ?: TrackDownloadException.Kind.NETWORK,
            "Все варианты host/appname отклонены. tokenLen=${appToken.length}. " +
                "Последняя: ${last?.message}. Выйдите и войдите снова."
        )
    }

    private var lastFailure: TrackDownloadException =
        TrackDownloadException(TrackDownloadException.Kind.NETWORK, "unknown")

    /** @return JSON при успехе, иначе null и заполненный [lastFailure]. */
    private fun requestWithRetry(
        host: String,
        appName: String,
        pathAndQuery: String,
        appToken: String,
        maxAttempts: Int
    ): JSONObject? {
        var attempt = 0
        while (true) {
            attempt++
            when (val r = attemptOnce(host, appName, pathAndQuery, appToken, attempt)) {
                is Attempt.Ok -> return r.json
                is Attempt.Fail -> {
                    Log.w(TAG, r.msg)
                    lastFailure = TrackDownloadException(r.kind, r.msg)
                    return null
                }
                is Attempt.Retry -> {
                    Log.w(TAG, "${r.msg} (попытка $attempt/$maxAttempts)")
                    lastFailure = TrackDownloadException(r.kind, r.msg)
                    if (attempt >= maxAttempts) return null
                    try {
                        Thread.sleep(r.waitMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
        }
    }

    private fun titleFor(type: Int, id: String): String {
        val kind = when (type) {
            1 -> "Бег"
            6 -> "Ходьба"
            8 -> "Дорожка"
            9 -> "Вело"
            10 -> "Вело (зал)"
            else -> "Тренировка"
        }
        val iso = epochToIso(id.toLongOrNull()) ?: id
        return "$kind · $iso"
    }

    private fun epochToIso(epoch: Long?): String? {
        if (epoch == null || epoch <= 0) return null
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getDefault()
        return sdf.format(java.util.Date(epoch * 1000))
    }

    private fun maskAccount(account: String): String {
        val a = account.trim()
        if (a.length <= 4) return "***"
        return a.take(2) + "***" + a.takeLast(2)
    }

    companion object {
        private const val TAG = "ZeppRepo"
        private val APP_NAMES = listOf(
            "com.huami.midong",
            "com.xiaomi.hm.health",
            "com.huami.watch",
            "com.huami.webapp"
        )
        private val HOSTS = listOf(
            "https://api-mifit.zepp.com",
            "https://api-mifit-us2.zepp.com",
            "https://api-mifit-us.zepp.com",
            "https://api-mifit-de.zepp.com",
            "https://api-mifit.huami.com"
        )
        fun defaultDataClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}

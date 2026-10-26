package org.definitly.trextrack

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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
            ?: throw IllegalStateException("Некорректный trackid: $workoutId")
        val points = TrackDecoder.decodePoints(
            startEpochSec = start,
            longitudeLatitude = data.optString("longitude_latitude").takeIf { it.isNotBlank() },
            timeDeltas = data.optString("time").takeIf { it.isNotBlank() },
            altitude = data.optString("altitude").takeIf { it.isNotBlank() },
            heartRate = data.optString("heart_rate").takeIf { it.isNotBlank() }
        )
        if (points.isEmpty()) {
            throw IllegalStateException("Нет GPS-точек для $workoutId")
        }
        val name = "Zepp-$workoutId"
        val gpx = GpxWriter.toGpx(workoutId, name, points)
        Log.i(TAG, "downloadTrack $workoutId: ${points.size} points")
        return GpxTrack(workoutId = workoutId, gpxXml = gpx)
    }

    fun logout() {
        sessionStore.clear()
        sourceByTrackId.clear()
    }

    private fun getJson(pathAndQuery: String, appToken: String): JSONObject {
        // pathAndQuery like "/v1/sport/run/history.json?userid=..."
        val appNames = listOf(
            "com.huami.midong",
            "com.xiaomi.hm.health",
            "com.huami.watch",
            "com.huami.webapp"
        )
        val hosts = listOf(
            "https://api-mifit.zepp.com",
            "https://api-mifit-us2.zepp.com",
            "https://api-mifit-us.zepp.com",
            "https://api-mifit-de.zepp.com",
            "https://api-mifit.huami.com"
        )
        var lastError: String = "unknown"
        for (host in hosts) {
            for (appName in appNames) {
                val url = host + pathAndQuery
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("apptoken", appToken)
                    .header("appname", appName)
                    .header("app_name", appName)
                    .header("appPlatform", "android_phone")
                    .header("User-Agent", "Zepp/9.12.5 (Android 12)")
                    .header("Accept", "application/json")
                    .build()
                try {
                    http.newCall(request).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (response.isSuccessful) {
                            Log.i(TAG, "OK $host appname=$appName")
                            return JSONObject(text)
                        }
                        lastError = "HTTP ${response.code} @ $host/$appName: ${text.take(120)}"
                        Log.w(TAG, lastError)
                        // 401/403 → try next combo; other errors still try a bit
                        if (response.code !in listOf(401, 403, 404)) {
                            // keep trying
                        }
                    }
                } catch (e: Exception) {
                    lastError = "$host: ${e.message}"
                    Log.w(TAG, lastError)
                }
            }
        }
        val tokenLen = appToken.length
        throw IllegalStateException(
            "Все варианты host/appname отклонены. tokenLen=$tokenLen. Последняя: $lastError. " +
                "Выйдите и войдите снова (нужен mobile app_token)."
        )
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
        fun defaultDataClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}

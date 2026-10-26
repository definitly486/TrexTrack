package org.definitly.trextrack

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Direct Zepp account login without the Zepp app.
 *
 * Step 1: AES-CBC encrypt form body → POST /v2/registrations/tokens → 303 Location with access=
 * Step 2: Exchange access code → app_token + user_id
 *
 * Encryption parameters match the reverse-engineered mobile client used by community tools
 * (e.g. huami-token). They may change when Zepp updates the app.
 */
class ZeppAuthClient(
    private val client: OkHttpClient = defaultClient()
) {
    suspend fun login(email: String, password: CharArray): Result<ZeppSession> =
        withContext(Dispatchers.IO) {
            try {
                val trimmed = email.trim()
                require(trimmed.isNotEmpty()) { "Email пуст" }
                require(password.isNotEmpty()) { "Пароль пуст" }

                val (access, country) = requestAccessCode(trimmed, password)
                val session = exchangeForAppToken(access, country)
                Result.success(session.copy(email = trimmed))
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                password.fill('\u0000')
            }
        }

    private fun requestAccessCode(email: String, password: CharArray): Pair<String, String> {
        val form =
            "emailOrPhone=${enc(email)}" +
                "&state=REDIRECTION" +
                "&client_id=HuaMi" +
                "&password=${enc(String(password))}" +
                "&redirect_uri=${enc(REDIRECT_URI)}" +
                "&region=us-west-2" +
                "&token=access" +
                "&token=refresh" +
                "&country_code=US"

        val encrypted = aesCbcEncrypt(form.toByteArray(Charsets.UTF_8), AES_KEY, AES_IV)
        val body = encrypted.toRequestBody(FORM_MEDIA)

        val request = Request.Builder()
            .url(URL_REGISTRATIONS_TOKENS)
            .post(body)
            .header("app_name", APP_NAME)
            .header("appname", APP_NAME)
            .header("cv", "151689_9.12.5")
            .header("v", "2.0")
            .header("appplatform", "android_phone")
            .header("vb", "202509151347")
            .header("vn", "9.12.5")
            .header("user-agent", USER_AGENT_APP)
            .header("x-hm-ekv", "1")
            .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("x-request-id", UUID.randomUUID().toString())
            .header("accept-encoding", "gzip")
            .build()

        client.newCall(request).execute().use { resp ->
            val location = resp.header("Location")
            // Success is 303 (or 302) with Location containing access=
            if (location == null) {
                val code = resp.code
                val hint = when (code) {
                    401, 403 -> "Неверный email/пароль или нужен другой region host."
                    400 -> "Сервер отклонил запрос (проверьте шифрование/заголовки)."
                    429 -> "Слишком много попыток, подождите."
                    else -> "HTTP $code без Location."
                }
                error("$hint (HTTP $code)")
            }
            val params = parseQuery(location)
            if (params.containsKey("error")) {
                error("Сервер отклонил учётные данные (error=${params["error"]})")
            }
            val access = params["access"]
                ?: error("В redirect нет access")
            val country = params["country_code"] ?: "US"
            return access to country
        }
    }

    private fun exchangeForAppToken(accessCode: String, countryCode: String): ZeppSession {
        val deviceId = UUID.randomUUID().toString()
        val form =
            "app_name=$APP_NAME" +
                "&app_version=$APP_VERSION" +
                "&code=${enc(accessCode)}" +
                "&country_code=${enc(countryCode)}" +
                "&device_id=${enc(deviceId)}" +
                "&device_model=android_phone" +
                "&grant_type=access_token" +
                "&allow_registration=false" +
                "&third_name=huami" +
                "&dn=${enc(DN_LIST)}" +
                "&lang=en" +
                "&source=${enc("com.huami.watch.hmwatchmanager:$APP_VERSION:151689")}" +
                "&countryState=US-NY"

        val body = form.toRequestBody(FORM_MEDIA)

        val request = Request.Builder()
            .url(URL_CLIENT_LOGIN)
            .post(body)
            .header("app_name", "com.huami.webapp")
            .header("appname", "com.huami.webapp")
            .header("origin", "https://user.zepp.com")
            .header("referer", "https://user.zepp.com/")
            .header("user-agent", USER_AGENT_WEB)
            .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("accept", "application/json, text/plain, */*")
            .header("accept-language", "en-US,en;q=0.9")
            .build()

        client.newCall(request).execute().use { resp ->
            val bodyText = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("Login HTTP ${resp.code}: ${bodyText.take(200)}")
            }
            val json = JSONObject(bodyText)
            val tokenInfo = json.optJSONObject("token_info")
                ?: error("Нет token_info в ответе")
            val appToken = tokenInfo.optString("app_token").ifBlank { error("Нет app_token") }
            val userId = tokenInfo.optString("user_id").ifBlank { error("Нет user_id") }
            val loginToken = tokenInfo.optString("login_token").ifBlank { null }
            val host = resolveRegionHost(json)
            return ZeppSession(
                appToken = appToken,
                userId = userId,
                loginToken = loginToken,
                regionHost = host
            )
        }
    }

    private fun resolveRegionHost(json: JSONObject): String {
        val domains = json.optJSONArray("domains") ?: return ZeppSession.DEFAULT_REGION_HOST
        for (i in 0 until domains.length()) {
            val d = domains.optJSONObject(i) ?: continue
            val host = d.optString("host")
            if (host.contains("mifit", ignoreCase = true)) {
                val cname = d.optJSONArray("cnames")?.optString(0)?.takeIf { it.isNotBlank() }
                if (cname != null) return "https://$cname"
            }
        }
        return ZeppSession.DEFAULT_REGION_HOST
    }

    private fun parseQuery(url: String): Map<String, String> {
        val q = url.substringAfter('?', missingDelimiterValue = "")
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null
            else {
                val k = URLDecoder.decode(part.substring(0, i), Charsets.UTF_8.name())
                val v = URLDecoder.decode(part.substring(i + 1), Charsets.UTF_8.name())
                k to v
            }
        }.toMap()
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun aesCbcEncrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plain)
    }

    companion object {
        private const val APP_NAME = "com.huami.midong"
        private const val APP_VERSION = "9.12.5"
        private const val REDIRECT_URI =
            "https://s3-us-west-2.amazonaws.com/hm-registration/successsignin.html"
        // Regional hosts used by current community clients; fallback list if one fails can be added later
        private const val URL_REGISTRATIONS_TOKENS =
            "https://api-user.zepp.com/v2/registrations/tokens"
        private const val URL_CLIENT_LOGIN =
            "https://account.huami.com/v2/client/login"
        private const val DN_LIST =
            "api-mifit.zepp.com,api-user.zepp.com,api-mifit.zepp.com," +
                "api-watch.zepp.com,app-analytics.zepp.com,auth.zepp.com,api-analytics.zepp.com"
        private const val USER_AGENT_APP =
            "Zepp/9.12.5 (Pixel 4; Android 12; Density/2.75)"
        private const val USER_AGENT_WEB =
            "Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0"

        // Public reverse-engineered constants (huami-token / community clients)
        private val AES_KEY = "xeNtBVqzDc6tuNTh".toByteArray(Charsets.US_ASCII)
        private val AES_IV = "MAAAYAAAAAAAAABg".toByteArray(Charsets.US_ASCII)

        private val FORM_MEDIA =
            "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}

package org.definitly.trextrack

import android.net.Uri
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Direct Zepp/Huami login without the official Zepp app.
 *
 * Flow (as used by community tools such as huami-token):
 * 1) POST AES-CBC encrypted credentials to /v2/registrations/tokens → 303 Location with access/refresh
 * 2) POST access code to /v2/client/login → app_token, login_token, user_id
 */
class ZeppAuthClient(
    private val http: OkHttpClient = defaultClient()
) {

    data class Session(
        val appToken: String,
        val loginToken: String,
        val userId: String
    )

    fun login(emailOrPhone: String, password: CharArray): Session {
        try {
            val accessToken = requestAccessToken(emailOrPhone, password)
            return exchangeForAppToken(accessToken)
        } catch (e: AuthException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw AuthException("Таймаут сети. Проверьте интернет и VPN.")
        } catch (e: java.net.UnknownHostException) {
            throw AuthException("Нет DNS/интернета: ${e.message}")
        } catch (e: javax.net.ssl.SSLException) {
            throw AuthException("SSL ошибка: ${e.message}")
        } catch (e: java.io.IOException) {
            throw AuthException("Сеть: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun requestAccessToken(emailOrPhone: String, password: CharArray): String {
        val form = buildString {
            append("emailOrPhone=").append(urlEncode(emailOrPhone))
            append("&state=REDIRECTION")
            append("&client_id=HuaMi")
            append("&password=").append(urlEncode(String(password)))
            append("&redirect_uri=").append(
                urlEncode("https://s3-us-west-2.amazonaws.com/hm-registration/successsignin.html")
            )
            append("&region=us-west-2")
            append("&token=access&token=refresh")
            append("&country_code=US")
        }
        val encrypted = encryptPayload(form.toByteArray(StandardCharsets.UTF_8))

        val request = Request.Builder()
            .url(TOKENS_URL)
            .post(encrypted.toRequestBody(FORM_MEDIA))
            .header("app_name", APP_NAME)
            .header("appname", APP_NAME)
            .header("cv", "151689_9.12.5")
            .header("v", "2.0")
            .header("appplatform", "android_phone")
            .header("vb", "202509151347")
            .header("vn", "9.12.5")
            .header("user-agent", "Zepp/9.12.5 (Pixel 4; Android 12; Density/2.75)")
            .header("x-hm-ekv", "1")
            .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("accept-encoding", "gzip")
            .build()

        http.newCall(request).execute().use { response ->
            if (response.code != 303) {
                val bodySnippet = response.body?.string()?.take(120) ?: ""
                throw AuthException(
                    "Логин: HTTP ${response.code} (ожидали 303)" +
                        if (bodySnippet.isNotBlank()) " · $bodySnippet" else ""
                )
            }
            val location = response.header("Location")
                ?: throw AuthException("Логин: нет заголовка Location (HTTP ${response.code})")
            val uri = Uri.parse(location)
            val access = uri.getQueryParameter("access")
                ?: throw AuthException("Логин: в Location нет access token")
            return access
        }
    }

    private fun exchangeForAppToken(accessCode: String): Session {
        val body = FormBody.Builder()
            .add("code", accessCode)
            .add("device_id", UUID.randomUUID().toString().replace("-", ""))
            .add("device_model", "android_phone")
            .add("app_version", "9.12.5")
            .add(
                "dn",
                "api-mifit.zepp.com,api-user.zepp.com,api-mifit-us2.zepp.com," +
                    "api-watch.zepp.com,app-analytics.zepp.com,auth.zepp.com,api-analytics.zepp.com"
            )
            .add("third_name", "huami")
            .add("source", "com.huami.midong")
            .add("app_name", APP_NAME)
            .add("country_code", "US")
            .add("grant_type", "access_token")
            .add("allow_registration", "false")
            .add("lang", "en")
            .add("countryState", "US-NY")
            .build()

        val request = Request.Builder()
            .url(LOGIN_URL)
            .post(body)
            // Must match APP_NAME used for sport history (mobile Zepp, not web)
            .header("app_name", APP_NAME)
            .header("appname", APP_NAME)
            .header("appPlatform", "android_phone")
            .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("accept", "application/json")
            .header("User-Agent", "Zepp/9.12.5 (Android 12; Pixel 4)")
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw AuthException("Обмен токена не удался (HTTP ${response.code}): ${text.take(200)}")
            }
            val json = JSONObject(text)
            val tokenInfo = json.optJSONObject("token_info")
                ?: throw AuthException("В ответе login нет token_info")
            val appToken = tokenInfo.optString("app_token").takeIf { it.isNotBlank() }
                ?: throw AuthException("Нет app_token")
            val loginToken = tokenInfo.optString("login_token").takeIf { it.isNotBlank() }
                ?: throw AuthException("Нет login_token")
            val userId = tokenInfo.optString("user_id").takeIf { it.isNotBlank() }
                ?: throw AuthException("Нет user_id")
            return Session(appToken = appToken, loginToken = loginToken, userId = userId)
        }
    }

    private fun encryptPayload(plain: ByteArray): ByteArray {
        val key = SecretKeySpec(AES_KEY, "AES")
        val iv = IvParameterSpec(AES_IV)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        return cipher.doFinal(plain)
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    class AuthException(message: String) : Exception(message)

    companion object {
        private const val APP_NAME = "com.huami.midong"
        private const val TOKENS_URL = "https://api-user-us2.zepp.com/v2/registrations/tokens"
        private const val LOGIN_URL = "https://api-mifit-us2.zepp.com/v2/client/login"
        private val FORM_MEDIA = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

        // Public static key/IV used by Zepp client for x-hm-ekv=1 payload encryption.
        private val AES_KEY = "xeNtBVqzDc6tuNTh".toByteArray(StandardCharsets.UTF_8)
        private val AES_IV = "MAAAYAAAAAAAAABg".toByteArray(StandardCharsets.UTF_8)

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()
    }
}

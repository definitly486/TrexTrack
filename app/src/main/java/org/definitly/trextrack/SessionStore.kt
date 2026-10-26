package org.definitly.trextrack

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Session tokens in EncryptedSharedPreferences (AES-256-GCM + Android Keystore).
 * Compatible with androidx.security:security-crypto:1.0.0
 * (MasterKeys API — not MasterKey from 1.1.0-alpha).
 *
 * Password is never persisted.
 * If Keystore fails, falls back to private SharedPreferences so the UI still opens.
 */
data class ZeppSession(
    val appToken: String,
    val userId: String,
    val loginToken: String? = null,
    val regionHost: String = DEFAULT_REGION_HOST,
    val email: String? = null
) {
    companion object {
        const val DEFAULT_REGION_HOST = "https://api-mifit.zepp.com"
    }
}

class SessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences
    val usingKeystore: Boolean

    init {
        var encrypted: SharedPreferences? = null
        var ok = false
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            encrypted = EncryptedSharedPreferences.create(
                PREFS_NAME,
                masterKeyAlias,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            encrypted.getString(KEY_APP_TOKEN, null)
            ok = true
        } catch (t: Throwable) {
            Log.e(TAG, "EncryptedSharedPreferences unavailable, fallback to private prefs", t)
            encrypted = null
            ok = false
        }
        usingKeystore = ok
        prefs = encrypted ?: appContext.getSharedPreferences(PREFS_FALLBACK, Context.MODE_PRIVATE)
    }

    fun save(session: ZeppSession) {
        prefs.edit()
            .putString(KEY_APP_TOKEN, session.appToken)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_LOGIN_TOKEN, session.loginToken)
            .putString(KEY_REGION_HOST, session.regionHost)
            .putString(KEY_EMAIL, session.email)
            .apply()
    }

    fun load(): ZeppSession? {
        val appToken = prefs.getString(KEY_APP_TOKEN, null) ?: return null
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        return ZeppSession(
            appToken = appToken,
            userId = userId,
            loginToken = prefs.getString(KEY_LOGIN_TOKEN, null),
            regionHost = prefs.getString(KEY_REGION_HOST, null)
                ?: ZeppSession.DEFAULT_REGION_HOST,
            email = prefs.getString(KEY_EMAIL, null)
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun isLoggedIn(): Boolean = load() != null

    companion object {
        private const val TAG = "SessionStore"
        private const val PREFS_NAME = "zepp_session"
        private const val PREFS_FALLBACK = "zepp_session_fallback"
        private const val KEY_APP_TOKEN = "app_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LOGIN_TOKEN = "login_token"
        private const val KEY_REGION_HOST = "region_host"
        private const val KEY_EMAIL = "email"
    }
}

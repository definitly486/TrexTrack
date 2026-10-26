package org.definitly.trextrack

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores Zepp session tokens in EncryptedSharedPreferences backed by Android Keystore.
 * Falls back to regular SharedPreferences if Keystore/EncryptedSharedPreferences fails
 * (emulator, broken Keystore, etc.) so the app still launches.
 */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences
    private val encrypted: Boolean

    init {
        val appContext = context.applicationContext
        var p: SharedPreferences
        var isEncrypted = false
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            p = EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            isEncrypted = true
        } catch (t: Throwable) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, using plain prefs", t)
            p = appContext.getSharedPreferences(PREFS_NAME_FALLBACK, Context.MODE_PRIVATE)
        }
        prefs = p
        encrypted = isEncrypted
    }

    fun isLoggedIn(): Boolean =
        !prefs.getString(KEY_APP_TOKEN, null).isNullOrBlank() &&
            !prefs.getString(KEY_USER_ID, null).isNullOrBlank()

    fun getAppToken(): String? = prefs.getString(KEY_APP_TOKEN, null)
    fun getLoginToken(): String? = prefs.getString(KEY_LOGIN_TOKEN, null)
    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)
    fun getAccountHint(): String? = prefs.getString(KEY_ACCOUNT_HINT, null)
    fun isEncryptedStorage(): Boolean = encrypted

    fun saveSession(
        appToken: String,
        loginToken: String,
        userId: String,
        accountHint: String?
    ) {
        prefs.edit()
            .putString(KEY_APP_TOKEN, appToken)
            .putString(KEY_LOGIN_TOKEN, loginToken)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_ACCOUNT_HINT, accountHint)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "SessionStore"
        private const val PREFS_NAME = "trextrack_session"
        private const val PREFS_NAME_FALLBACK = "trextrack_session_fallback"
        private const val KEY_APP_TOKEN = "app_token"
        private const val KEY_LOGIN_TOKEN = "login_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ACCOUNT_HINT = "account_hint"
    }
}

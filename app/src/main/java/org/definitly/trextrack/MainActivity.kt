package org.definitly.trextrack

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var accountStatus: TextView
    private lateinit var importedName: TextView
    private lateinit var connectButton: Button
    private lateinit var logoutButton: Button
    private var importedGpx: String? = null

    private val openGpx = 1001
    private val saveGpx = 1002

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sessionStore: SessionStore
    private lateinit var repository: ZeppCloudRepositoryImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionStore = SessionStore(this)
        repository = ZeppCloudRepositoryImpl(sessionStore)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }
        val scroll = ScrollView(this)
        scroll.addView(content)
        setContentView(scroll)

        content.addView(TextView(this).apply {
            text = "T-Rex Track Exporter"
            textSize = 25f
            setTextColor(0xFF152238.toInt())
        }, matchWrap())

        content.addView(TextView(this).apply {
            text = "Получение спортивных треков из аккаунта Zepp и экспорт в GPX"
            textSize = 16f
            setPadding(0, dp(10), 0, dp(18))
        }, matchWrap())

        accountStatus = TextView(this).apply {
            textSize = 14f
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        content.addView(accountStatus, matchWrap())

        connectButton = Button(this).apply {
            text = "Подключить аккаунт Zepp"
            setOnClickListener { showLoginDialog() }
        }
        content.addView(connectButton, matchWrap())

        logoutButton = Button(this).apply {
            text = "Выйти из аккаунта"
            setOnClickListener {
                repository.logout()
                refreshAccountUi()
                status.text = "Сессия очищена"
            }
        }
        content.addView(logoutButton, matchWrap())

        content.addView(TextView(this).apply {
            text = "Локальный GPX"
            textSize = 20f
            setPadding(0, dp(24), 0, dp(8))
        }, matchWrap())

        content.addView(Button(this).apply {
            text = "Открыть GPX-файл"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }, openGpx
                )
            }
        }, matchWrap())

        importedName = TextView(this).apply {
            text = "Файл не выбран"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(importedName, matchWrap())

        content.addView(Button(this).apply {
            text = "Сохранить копию GPX"
            setOnClickListener {
                val gpx = importedGpx
                if (gpx == null) {
                    Toast.makeText(this@MainActivity, "Сначала откройте GPX-файл", Toast.LENGTH_SHORT).show()
                } else {
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/gpx+xml"
                            putExtra(Intent.EXTRA_TITLE, suggestedFileName(importedName.text.toString()))
                        }, saveGpx
                    )
                }
            }
        }, matchWrap())

        status = TextView(this).apply {
            text = "Статус: ожидание"
            textSize = 14f
            setPadding(0, dp(18), 0, 0)
        }
        content.addView(status, matchWrap())

        content.addView(TextView(this).apply {
            text = "Сессия хранится в EncryptedSharedPreferences (Android Keystore). Пароль в лог не пишется."
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            setPadding(0, dp(20), 0, 0)
        }, matchWrap())

        refreshAccountUi()
    }

    private fun refreshAccountUi() {
        if (sessionStore.isLoggedIn()) {
            val hint = sessionStore.getAccountHint() ?: sessionStore.getUserId() ?: "?"
            val storage = if (sessionStore.isEncryptedStorage()) "Keystore" else "fallback prefs"
            accountStatus.text = "Аккаунт подключён: $hint\nХранилище сессии: $storage"
            accountStatus.setTextColor(0xFF1B5E20.toInt())
            accountStatus.setBackgroundColor(0xFFE8F5E9.toInt())
            connectButton.isEnabled = false
            logoutButton.isEnabled = true
        } else {
            accountStatus.text = "Аккаунт не подключён"
            accountStatus.setTextColor(0xFF8A3B12.toInt())
            accountStatus.setBackgroundColor(0xFFFFF0D9.toInt())
            connectButton.isEnabled = true
            logoutButton.isEnabled = false
        }
    }

    private fun showLoginDialog() {
        val pad = dp(16)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val email = EditText(this).apply {
            hint = "Email или телефон"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val password = EditText(this).apply {
            hint = "Пароль"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(email, matchWrap())
        layout.addView(password, matchWrap())

        val dialog = AlertDialog.Builder(this)
            .setTitle("Вход в Zepp")
            .setView(layout)
            .setPositiveButton("Войти", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val account = email.text?.toString()?.trim().orEmpty()
                val passChars = password.text?.toString()?.toCharArray() ?: CharArray(0)
                if (account.isBlank() || passChars.isEmpty()) {
                    Toast.makeText(this, "Введите логин и пароль", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                doLogin(account, passChars)
            }
        }
        dialog.show()
    }

    private fun doLogin(account: String, password: CharArray) {
        status.text = "Авторизация…"
        connectButton.isEnabled = false
        logoutButton.isEnabled = false
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    repository.authenticate(account, password)
                }
            } catch (t: Throwable) {
                AuthResult.Failure(t.message ?: t.javaClass.simpleName)
            }
            when (result) {
                is AuthResult.Success -> {
                    status.text = "Вход выполнен"
                    Toast.makeText(this@MainActivity, "Аккаунт подключён", Toast.LENGTH_SHORT).show()
                }
                is AuthResult.Failure -> {
                    status.text = "Ошибка: ${result.message}"
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
            refreshAccountUi()
        }
    }

    @Deprecated("Activity result API kept minimal for starter project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            openGpx -> readGpx(uri)
            saveGpx -> writeGpx(uri)
        }
    }

    private fun readGpx(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("Не удалось открыть файл")
            if (!text.contains("<gpx", ignoreCase = true) || !text.contains("<trk", ignoreCase = true)) {
                error("Файл не похож на GPX-трек")
            }
            importedGpx = text
            importedName.text = uri.lastPathSegment ?: "GPX-файл выбран"
            status.text = "GPX загружен. Можно сохранить копию."
        } catch (e: Exception) {
            importedGpx = null
            status.text = "Ошибка чтения: ${e.message ?: "неизвестная ошибка"}"
        }
    }

    private fun writeGpx(uri: Uri) {
        try {
            val text = importedGpx ?: error("Нет данных")
            contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                ?: error("Не удалось создать файл")
            status.text = "GPX сохранён"
            Toast.makeText(this, "Файл сохранён", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            status.text = "Ошибка сохранения: ${e.message ?: "неизвестная ошибка"}"
        }
    }

    private fun suggestedFileName(label: String): String {
        val base = label.substringAfterLast('/').substringBeforeLast('.').ifBlank { "trex-track" }
        return "$base-copy.gpx"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

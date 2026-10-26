package org.definitly.trextrack

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var repository: ZeppCloudRepository
    private lateinit var accountInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var countryInput: EditText
    private lateinit var status: TextView
    private lateinit var workoutsContainer: LinearLayout
    private lateinit var connectButton: Button
    private lateinit var refreshButton: Button
    private var pendingGpx: String? = null
    private var pendingFilename = "trextrack.gpx"
    private val executor = Executors.newSingleThreadExecutor()
    private val saveGpxRequest = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ZeppCloudRepository(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        content.addView(TextView(this).apply {
            text = "T-Rex Track Exporter"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF152238.toInt())
        }, matchWrap())
        content.addView(TextView(this).apply {
            text = "Вход в Zepp Cloud · список тренировок · экспорт GPS в GPX"
            textSize = 15f
            setPadding(0, dp(8), 0, dp(16))
        }, matchWrap())

        accountInput = EditText(this).apply {
            hint = "Email / логин Zepp"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }
        content.addView(accountInput, matchWrap())
        passwordInput = EditText(this).apply {
            hint = "Пароль Zepp"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        content.addView(passwordInput, matchWrap())
        countryInput = EditText(this).apply {
            hint = "Код страны аккаунта (например, US)"
            setText("US")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true)
        }
        content.addView(countryInput, matchWrap())
        connectButton = Button(this).apply {
            text = "Войти в Zepp"
            setOnClickListener { login() }
        }
        content.addView(connectButton, matchWrap())
        content.addView(Button(this).apply {
            text = "Выйти и удалить сессию"
            setOnClickListener {
                repository.logout()
                workoutsContainer.removeAllViews()
                status.text = "Сессия удалена с устройства."
                Toast.makeText(this@MainActivity, "Вы вышли из Zepp", Toast.LENGTH_SHORT).show()
            }
        }, matchWrap())
        refreshButton = Button(this).apply {
            text = "Загрузить тренировки"
            setOnClickListener { loadWorkouts() }
        }
        content.addView(refreshButton, matchWrap())
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
            text = if (repository.hasSession()) "Сохранённая сессия найдена для ${repository.accountName()}. Можно загружать тренировки." else "Войдите в аккаунт Zepp. Пароль не сохраняется."
        }
        content.addView(status, matchWrap())
        content.addView(TextView(this).apply {
            text = "Тренировки"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(8))
        }, matchWrap())
        workoutsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(workoutsContainer, matchWrap())
        content.addView(TextView(this).apply {
            text = "Важно: используется неофициальный, reverse-engineered API Zepp. Он может меняться, а сервис может ограничивать частые входы. Не повторяйте неудачный вход много раз подряд."
            textSize = 12f
            setTextColor(0xFF805000.toInt())
            setPadding(0, dp(20), 0, 0)
        }, matchWrap())

        if (repository.hasSession()) loadWorkouts()
    }

    private fun login() {
        val account = accountInput.text.toString().trim()
        val password = passwordInput.text.toString().toCharArray()
        val country = countryInput.text.toString().trim().uppercase(Locale.ROOT).ifBlank { "US" }
        if (account.isBlank() || password.isEmpty()) {
            password.fill('\u0000')
            status.text = "Введите логин и пароль."
            return
        }
        setBusy(true, "Выполняется вход в Zepp…")
        executor.execute {
            try {
                repository.authenticate(account, password, country)
                runOnUiThread {
                    passwordInput.text.clear()
                    status.text = "Вход выполнен. Токен зашифрован и сохранён через Android Keystore."
                    setBusy(false)
                    loadWorkouts()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Ошибка входа: ${e.message ?: "неизвестная ошибка"}"
                    setBusy(false)
                }
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun loadWorkouts() {
        if (!repository.hasSession()) {
            status.text = "Сначала войдите в аккаунт Zepp."
            return
        }
        setBusy(true, "Загружаю список тренировок…")
        executor.execute {
            try {
                val workouts = repository.listWorkouts()
                runOnUiThread {
                    workoutsContainer.removeAllViews()
                    if (workouts.isEmpty()) status.text = "Zepp не вернул тренировок. Проверьте регион аккаунта или попробуйте позже."
                    else status.text = "Получено тренировок: ${workouts.size}. Нажмите «Экспорт GPX» у нужной тренировки."
                    workouts.forEach { workout -> addWorkoutRow(workout) }
                    setBusy(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Не удалось загрузить тренировки: ${e.message ?: "ошибка API"}"
                    setBusy(false)
                }
            }
        }
    }

    private fun addWorkoutRow(workout: ZeppCloudRepository.Workout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(0xFFF1F4F8.toInt())
        }
        val title = TextView(this).apply {
            text = workout.title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF17283F.toInt())
        }
        card.addView(title, matchWrap())
        val details = buildString {
            workout.startTime?.takeIf { it.isNotBlank() }?.let { append(it).append("\n") }
            workout.distanceMeters?.let { append(String.format(Locale.getDefault(), "%.2f км", it / 1000.0)).append(" · ") }
            workout.durationSeconds?.let { append(formatDuration(it)) }
            append("\nID: ").append(workout.id)
        }
        card.addView(TextView(this).apply {
            text = details
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        }, matchWrap())
        card.addView(Button(this).apply {
            text = "Экспорт GPX"
            isEnabled = workout.hasGpsTrack
            if (!workout.hasGpsTrack) text = "GPS-трек недоступен"
            setOnClickListener { exportWorkout(workout) }
        }, matchWrap())
        val params = matchWrap().apply { bottomMargin = dp(10) }
        workoutsContainer.addView(card, params)
    }

    private fun exportWorkout(workout: ZeppCloudRepository.Workout) {
        setBusy(true, "Загружаю GPS-точки тренировки ${workout.id}…")
        executor.execute {
            try {
                val gpx = repository.downloadTrack(workout)
                runOnUiThread {
                    pendingGpx = gpx
                    pendingFilename = "trextrack-${workout.id}.gpx"
                    setBusy(false, "Трек загружен. Выберите место сохранения GPX.")
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/gpx+xml"
                        putExtra(Intent.EXTRA_TITLE, pendingFilename)
                    }, saveGpxRequest)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Ошибка экспорта: ${e.message ?: "неизвестная ошибка"}"
                    setBusy(false)
                }
            }
        }
    }

    @Deprecated("Activity result API kept minimal for compatibility with starter project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != saveGpxRequest || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val gpx = pendingGpx ?: return
        try {
            contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(gpx) }
                ?: error("Не удалось создать файл")
            pendingGpx = null
            status.text = "GPX сохранён: ${uri.lastPathSegment ?: pendingFilename}"
            Toast.makeText(this, "GPX сохранён", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            status.text = "Ошибка сохранения GPX: ${e.message ?: "ошибка записи"}"
        }
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        connectButton.isEnabled = !busy
        refreshButton.isEnabled = !busy
        if (message != null) status.text = message
    }

    private fun formatDuration(seconds: Long): String = "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}

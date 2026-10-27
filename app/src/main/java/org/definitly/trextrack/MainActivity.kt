package org.definitly.trextrack

import android.app.Activity
import android.util.Log
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var accountStatus: TextView
    private lateinit var importedName: TextView
    private lateinit var connectButton: Button
    private lateinit var logoutButton: Button
    private var importedGpx: String? = null
    private var workouts: List<WorkoutSummary> = emptyList()
    private val selectedIds = LinkedHashSet<String>()

    private val openGpx = 1001
    private val saveGpx = 1002
    private val pickExportDir = 1003
    private var bulkJob: Job? = null

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
            text = "Тренировки Zepp"
            textSize = 20f
            setPadding(0, dp(24), 0, dp(8))
        }, matchWrap())

        content.addView(Button(this).apply {
            text = "Загрузить список тренировок"
            setOnClickListener { loadWorkouts() }
        }, matchWrap())

        content.addView(Button(this).apply {
            text = "Экспорт выбранных в папку"
            setOnClickListener { beginBulkExport() }
        }, matchWrap())

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
        Log.i("TrexTrack", "doLogin start accountLen=${account.length}")
        status.text = "Авторизация…"
        connectButton.isEnabled = false
        logoutButton.isEnabled = false
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    repository.authenticate(account, password)
                }
            } catch (t: Throwable) {
                Log.e("TrexTrack", "doLogin failed", t)
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
            pickExportDir -> startBulkExport(uri)
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


    private fun loadWorkouts() {
        if (!sessionStore.isLoggedIn()) {
            Toast.makeText(this, "Сначала войдите в аккаунт", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "Загрузка списка тренировок…"
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { repository.listWorkouts() }
                workouts = list
                if (list.isEmpty()) {
                    status.text = "Тренировок не найдено"
                    selectedIds.clear()
                } else {
                    showWorkoutPicker(list)
                }
            } catch (t: Throwable) {
                Log.e("TrexTrack", "loadWorkouts", t)
                status.text = "Ошибка списка: ${t.message}"
            }
        }
    }

    private fun showWorkoutPicker(list: List<WorkoutSummary>) {
        val labels = list.map { w ->
            val dist = w.distanceMeters?.let { String.format("%.2f км", it / 1000.0) } ?: "?"
            val dur = w.durationSeconds?.let { "%d:%02d".format(it / 60, it % 60) } ?: "?"
            "${w.title} · $dist · $dur"
        }.toTypedArray()
        // сохраняем прежний выбор только для тренировок, которые есть в новом списке
        selectedIds.retainAll(list.map { it.id }.toSet())
        val checked = BooleanArray(list.size) { list[it].id in selectedIds }

        fun title() = "Выбрано ${checked.count { it }} из ${list.size}"

        val dialog = AlertDialog.Builder(this)
            .setTitle(title())
            .setMultiChoiceItems(labels, checked, null)
            .setPositiveButton("Готово", null)
            .setNeutralButton("Выбрать все", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.setOnShowListener {
            val lv = dialog.listView
            val toggleAll = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

            fun refresh() {
                for (i in checked.indices) checked[i] = lv.isItemChecked(i)
                dialog.setTitle(title())
                toggleAll.text = if (checked.all { it }) "Снять все" else "Выбрать все"
            }
            refresh()

            lv.setOnItemClickListener { _, _, _, _ -> refresh() }
            toggleAll.setOnClickListener {
                val target = !checked.all { it }
                for (i in checked.indices) lv.setItemChecked(i, target)
                refresh()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                selectedIds.clear()
                for (i in checked.indices) if (checked[i]) selectedIds.add(list[i].id)
                status.text = if (selectedIds.isEmpty()) "Ничего не выбрано"
                else "Выбрано тренировок: ${selectedIds.size}. Нажмите «Экспорт выбранных в папку»"
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // ---------- Массовый экспорт ----------

    private fun beginBulkExport() {
        if (!sessionStore.isLoggedIn()) {
            Toast.makeText(this, "Сначала войдите в аккаунт", Toast.LENGTH_SHORT).show()
            return
        }
        if (bulkJob?.isActive == true) {
            Toast.makeText(this, "Экспорт уже идёт", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedIds.isEmpty()) {
            Toast.makeText(
                this, "Сначала загрузите список и отметьте тренировки", Toast.LENGTH_LONG
            ).show()
            return
        }
        Toast.makeText(
            this,
            "Выберите папку. Уже скачанные файлы будут пропущены — можно запускать повторно",
            Toast.LENGTH_LONG
        ).show()
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), pickExportDir)
    }

    private class BulkResult(
        val total: Int,
        val saved: Int,
        val skipped: Int,
        val failures: Map<TrackDownloadException.Kind, List<String>>
    ) {
        val failed get() = failures.values.sumOf { it.size }
    }

    private fun startBulkExport(treeUri: Uri) {
        bulkJob = scope.launch {
            try {
                val list = workouts.filter { it.id in selectedIds }
                if (list.isEmpty()) {
                    status.text = "Нет выбранных тренировок"
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    runBulkExport(treeUri, list) { done, total, saved, failed ->
                        runOnUiThread {
                            status.text = "Экспорт $done/$total · сохранено $saved · ошибок $failed"
                        }
                    }
                }
                showBulkSummary(result)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("TrexTrack", "bulkExport", t)
                status.text = "Ошибка массового экспорта: ${t.message}"
            }
        }
    }

    private suspend fun runBulkExport(
        treeUri: Uri,
        list: List<WorkoutSummary>,
        onProgress: (done: Int, total: Int, saved: Int, failed: Int) -> Unit
    ): BulkResult {
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val existing = listExistingNames(treeUri, treeDocId)

        var saved = 0
        var skipped = 0
        val failures = LinkedHashMap<TrackDownloadException.Kind, MutableList<String>>()

        for ((index, w) in list.withIndex()) {
            val fileName = "Zepp-${w.id}.gpx"
            if (fileName in existing) {
                skipped++
            } else {
                var attempt = 0
                while (true) {
                    attempt++
                    try {
                        val track = repository.downloadTrack(w.id)
                        writeTrackFile(dirUri, fileName, track.gpxXml)
                        saved++
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val kind = (e as? TrackDownloadException)?.kind
                            ?: TrackDownloadException.Kind.NETWORK
                        if (kind.retryable && attempt < 3) {
                            delay(2000L * attempt)
                            continue
                        }
                        Log.w("TrexTrack", "export ${w.id} failed: ${kind.label}: ${e.message}")
                        failures.getOrPut(kind) { mutableListOf() }
                            .add("${w.title}: ${e.message}")
                        break
                    }
                }
                delay(300) // не долбим API — иначе сервер начинает отвечать 429/5xx
            }
            val failed = failures.values.sumOf { it.size }
            onProgress(index + 1, list.size, saved, failed)
        }
        return BulkResult(list.size, saved, skipped, failures)
    }

    private fun listExistingNames(treeUri: Uri, treeDocId: String): Set<String> {
        val names = HashSet<String>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        contentResolver.query(
            children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { names.add(it) }
        }
        return names
    }

    private fun writeTrackFile(dirUri: Uri, fileName: String, gpx: String) {
        val doc = DocumentsContract.createDocument(
            contentResolver, dirUri, "application/octet-stream", fileName
        ) ?: throw IllegalStateException("Не удалось создать файл $fileName")
        try {
            contentResolver.openOutputStream(doc, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(gpx) }
                ?: error("Не удалось открыть $fileName для записи")
        } catch (e: Exception) {
            // не оставляем «пустышку», иначе при докачке файл посчитается скачанным
            try { DocumentsContract.deleteDocument(contentResolver, doc) } catch (ignored: Exception) {}
            throw e
        }
    }

    private fun showBulkSummary(r: BulkResult) {
        val noGps = r.failures[TrackDownloadException.Kind.NO_GPS]?.size ?: 0
        val realFailed = r.failed - noGps
        status.text = "Экспорт завершён: сохранено ${r.saved}, уже было ${r.skipped}, " +
            "без GPS $noGps, ошибок $realFailed (всего ${r.total})"

        val sb = StringBuilder()
        if (r.failures.isEmpty()) sb.append("Все тренировки обработаны без ошибок.")
        for ((kind, items) in r.failures) {
            sb.append("• ${kind.label}: ${items.size}\n")
            items.take(3).forEach { sb.append("    ${it.take(110)}\n") }
            if (items.size > 3) sb.append("    … ещё ${items.size - 3}\n")
        }
        if (realFailed > 0) sb.append("\nЗапустите экспорт ещё раз в ту же папку — докачаются только недостающие.")
        AlertDialog.Builder(this)
            .setTitle("Итоги экспорта")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun suggestedFileName(label: String): String {
        val base = label.substringAfterLast('/').substringBeforeLast('.').ifBlank { "trex-track" }
        return "$base-copy.gpx"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

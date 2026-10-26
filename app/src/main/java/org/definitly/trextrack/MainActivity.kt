package org.definitly.trextrack

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.InputStream

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var importedName: TextView
    private var importedGpx: String? = null

    private val openGpx = 1001
    private val saveGpx = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        content.addView(TextView(this).apply {
            text = "Подключение к облаку Zepp пока не реализовано. Приложение не запускает и не использует Zepp. Для реального импорта требуется подтвердить способ прямой авторизации и API маршрутов."
            textSize = 14f
            setTextColor(0xFF8A3B12.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFFFF0D9.toInt())
        }, matchWrap())

        val connect = Button(this).apply {
            text = "Подключить аккаунт Zepp"
            setOnClickListener {
                status.text = "Подключение недоступно в этой версии: официальный API требует одобрения партнёра, а неофициальный облачный API ещё нужно адаптировать и проверить для вашего аккаунта."
            }
        }
        content.addView(connect, matchWrap())

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
            text = "Следующий этап: реализовать ZeppCloudRepository, получить список тренировок и загрузить GPS-точки. Экспорт локального GPX здесь служит проверкой файлового потока."
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            setPadding(0, dp(20), 0, 0)
        }, matchWrap())
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
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

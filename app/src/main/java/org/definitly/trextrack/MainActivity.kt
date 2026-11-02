package org.definitly.trextrack

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Главный экран: выбор режима — облако Zepp или прямая работа с часами.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        val scroll = ScrollView(this)
        scroll.addView(content)
        setContentView(scroll)

        content.addView(TextView(this).apply {
            text = "T-Rex Track Exporter"
            textSize = 26f
            setTextColor(0xFF152238.toInt())
        }, matchWrap())

        content.addView(TextView(this).apply {
            text = "Выберите способ работы с треками Amazfit T-Rex 2"
            textSize = 16f
            setPadding(0, dp(12), 0, dp(28))
            setTextColor(0xFF444444.toInt())
        }, matchWrap())

        content.addView(Button(this).apply {
            text = "Работа с облаком Zepp"
            textSize = 16f
            setOnClickListener {
                startActivity(Intent(this@MainActivity, CloudActivity::class.java))
            }
        }, matchWrap())

        content.addView(TextView(this).apply {
            text = "Вход в аккаунт, список тренировок из облака, экспорт GPS в GPX"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(dp(4), 0, dp(4), dp(20))
        }, matchWrap())

        content.addView(Button(this).apply {
            text = "Работа напрямую с часами"
            textSize = 16f
            setOnClickListener {
                startActivity(Intent(this@MainActivity, WatchDirectActivity::class.java))
            }
        }, matchWrap())

        content.addView(TextView(this).apply {
            text = "BLE-подключение к T-Rex 2, обнаружение сервисов, загрузка треков с часов"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(dp(4), 0, dp(4), dp(20))
        }, matchWrap())
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(6) }
}

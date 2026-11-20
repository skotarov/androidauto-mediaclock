package com.kotarov.autoclock

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.kotarov.autoclock.media.AutoClockMediaService

/** Phone-side settings. No timer, background worker or location permission. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(AutoClockMediaService.PREFERENCES, MODE_PRIVATE)
        val selected = prefs.getString(AutoClockMediaService.KEY_MODE, "digital")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(24))
            setBackgroundColor(Color.rgb(10, 10, 12))
        }
        fun text(value: String, size: Float, color: Int = Color.WHITE) {
            root.addView(TextView(this).apply {
                this.text = value
                textSize = size
                setTextColor(color)
                setPadding(0, 0, 0, dp(20))
            })
        }

        text("Auto Clock 2", 30f)
        text(
            "Лек часовник за лично тестване в Android Auto. " +
                "Работи само при избиране от мултимедийното меню.",
            16f,
            Color.LTGRAY
        )
        text("Избери изглед", 20f)

        val choices = linkedMapOf(
            "digital" to "Цифров часовник",
            "analog" to "Аналогов часовник",
            "weather" to "Времето (очаква данни)",
            "both" to "Часовник + време (очаква данни)"
        )
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val ids = mutableMapOf<Int, String>()
        for ((key, title) in choices) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = title
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(0, dp(6), 0, dp(6))
            }
            ids[button.id] = key
            group.addView(button)
            if (key == selected) group.check(button.id)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            ids[checkedId]?.let {
                prefs.edit().putString(AutoClockMediaService.KEY_MODE, it).apply()
            }
        }
        root.addView(group)

        text(
            "Изборът ще се приложи при следващото стартиране на часовника " +
                "в Android Auto. Може да сменяш изгледа и от менюто в колата.",
            14f,
            Color.LTGRAY
        )
        text(
            "За икономия на батерия: без GPS, без интернет заявки, " +
                "без анимация всяка секунда. Изображението се сменя веднъж в минута.",
            14f,
            Color.GRAY
        )
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

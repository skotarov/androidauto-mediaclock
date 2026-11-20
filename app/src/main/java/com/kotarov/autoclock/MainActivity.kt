package com.kotarov.autoclock

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.kotarov.autoclock.artwork.ClockRenderer
import com.kotarov.autoclock.media.AutoClockMediaService
import com.kotarov.autoclock.weather.WeatherStore
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

/** All phone settings work locally; there is no activity timer or polling. */
class MainActivity : AppCompatActivity() {
    private var previewBitmap: Bitmap? = null
    private lateinit var preview: ImageView
    private lateinit var weatherStatus: TextView
    private val light = Color.WHITE
    private val muted = Color.LTGRAY
    private val background = Color.rgb(10, 10, 12)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(AutoClockMediaService.PREFERENCES, MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(30))
            setBackgroundColor(this@MainActivity.background)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        root.addView(label("Auto Clock 2", 30f, light, dp(7)))
        root.addView(label("Аналогов циферблат във всички режими", 17f, muted, dp(20)))

        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Преглед на избрания часовник"
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240)
            )
        }
        root.addView(preview)

        root.addView(label("Какво да се показва", 21f, light, dp(8)))

        val modeOptions = linkedMapOf(
            "digital" to "Часовник — час и дата",
            "analog" to "Часовник — аналогов фон",
            "weather" to "Времето",
            "both" to "Часовник + време"
        )
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val modeIds = mutableMapOf<Int, String>()
        for ((key, title) in modeOptions) {
            val button = radio(title)
            modeIds[button.id] = key
            modeGroup.addView(button)
            if (key == prefs.getString(AutoClockMediaService.KEY_MODE, "digital")) {
                modeGroup.check(button.id)
            }
        }
        modeGroup.setOnCheckedChangeListener { _, id ->
            modeIds[id]?.let {
                prefs.edit().putString(AutoClockMediaService.KEY_MODE, it).apply()
                updatePreview()
            }
        }
        root.addView(modeGroup)

        val cleanText = CheckBox(this).apply {
            text = "Само фон — скрий надписите за песента"
            textSize = 17f
            setTextColor(light)
            isChecked = prefs.getBoolean(AutoClockMediaService.KEY_HIDE_MEDIA_TEXT, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit()
                    .putBoolean(AutoClockMediaService.KEY_HIDE_MEDIA_TEXT, checked)
                    .apply()
            }
        }
        root.addView(cleanText)
        root.addView(label(
            "Експериментално: Android Auto може да запази системните бутони " +
                "или да покаже заместващ надпис. Не можем да изключим неговия " +
                "медиен интерфейс от приложението.",
            13f, muted, dp(10)
        ))

        val weatherOverlay = CheckBox(this).apply {
            text = "Иконка и температура върху аналоговия циферблат"
            textSize = 17f
            setTextColor(light)
            isChecked = prefs.getBoolean(
                AutoClockMediaService.KEY_SHOW_WEATHER_OVERLAY, true
            )
            setOnCheckedChangeListener { _, checked ->
                prefs.edit()
                    .putBoolean(AutoClockMediaService.KEY_SHOW_WEATHER_OVERLAY, checked)
                    .apply()
                updatePreview()
            }
        }
        root.addView(weatherOverlay)

        root.addView(label("Град за прогнозата", 21f, light, dp(7)))
        root.addView(label(
            "Изборът на град спестява постоянно използване на GPS.",
            14f, muted, dp(10)
        ))

        val cityGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val cityIds = mutableMapOf<Int, String>()
        for (city in WeatherStore.City.entries) {
            val button = radio(city.label)
            cityIds[button.id] = city.id
            cityGroup.addView(button)
            if (city == WeatherStore.city(this)) cityGroup.check(button.id)
        }
        cityGroup.setOnCheckedChangeListener { _, id ->
            cityIds[id]?.let {
                WeatherStore.setCity(this, it)
                updateWeatherStatus()
                updatePreview()
            }
        }
        root.addView(cityGroup)

        weatherStatus = label("", 15f, muted, dp(12))
        root.addView(weatherStatus)

        val refreshButton = Button(this).apply {
            text = "Обнови времето сега"
            isAllCaps = false
        }
        refreshButton.setOnClickListener {
            refreshButton.isEnabled = false
            weatherStatus.text = "Зареждам данни за времето..."
            WeatherStore.refreshIfDue(this, force = true) { success ->
                if (!isFinishing && !isDestroyed) {
                    refreshButton.isEnabled = true
                    updateWeatherStatus()
                    if (!success) {
                        weatherStatus.text = "Няма връзка. Часовникът продължава да работи офлайн."
                    }
                    updatePreview()
                }
            }
        }
        root.addView(refreshButton)

        root.addView(label(
            "В Android Auto избери Auto Clock 2 от мултимедийните приложения " +
                "и натисни Play. Режимът може да се сменя от приложението " +
                "или с Previous / Next. Промените от телефона се прилагат " +
                "най-късно при следващата минута.",
            15f, muted, dp(13)
        ))
        root.addView(label(
            "Икономичен режим: часовникът се обновява веднъж в минута; " +
                "прогнозата — най-много веднъж на час, когато се показва върху часовника. " +
                "Няма GPS следене, аудио поток или непрекъсната фонова задача.",
            14f, muted, dp(12)
        ))
        root.addView(label(
            "Данни за времето: Open-Meteo.com. Обновяване само при нужда.",
            13f, Color.GRAY, dp(6)
        ))
        root.addView(label(
            "Екранът в Android Auto се управлява от самия Android Auto. " +
                "Показването в малкия панел зависи от неговата версия.",
            13f, Color.GRAY, dp(8)
        ))

        updateWeatherStatus()
        updatePreview()
    }

    private fun radio(title: String): RadioButton = RadioButton(this).apply {
        id = View.generateViewId()
        text = title
        textSize = 17f
        setTextColor(light)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun label(value: String, size: Float, color: Int, below: Int): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setPadding(0, 0, 0, below)
        }

    private fun updateWeatherStatus() {
        val snapshot = WeatherStore.current(this)
        weatherStatus.text = if (snapshot == null) {
            "За ${WeatherStore.city(this).label} още няма актуални данни."
        } else {
            val time = SimpleDateFormat("HH:mm", Locale("bg", "BG"))
                .format(Date(snapshot.observedAtMs))
            "${WeatherStore.city(this).label}: ${snapshot.formattedTemperature}, " +
                "${snapshot.description.lowercase(Locale("bg", "BG"))} (обновено $time)"
        }
    }

    private fun updatePreview() {
        if (!::preview.isInitialized) return
        val key = getSharedPreferences(
            AutoClockMediaService.PREFERENCES, MODE_PRIVATE
        ).getString(AutoClockMediaService.KEY_MODE, "digital")
        val mode = if (key == "digital") "clock" else key ?: "clock"
        val showWeather = (mode == "weather" || mode == "both") ||
            getSharedPreferences(AutoClockMediaService.PREFERENCES, MODE_PRIVATE)
                .getBoolean(AutoClockMediaService.KEY_SHOW_WEATHER_OVERLAY, true)
        val weather = if (showWeather) WeatherStore.current(this) else null
        val image = ClockRenderer.render(
            mode, LocalDateTime.now(),
            weather?.weatherCode,
            weather?.isDay ?: true,
            weather?.formattedTemperature
        )
        preview.setImageBitmap(image)
        previewBitmap?.recycle()
        previewBitmap = image
    }

    override fun onDestroy() {
        preview.setImageDrawable(null)
        previewBitmap?.recycle()
        previewBitmap = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

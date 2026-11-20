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
import com.kotarov.autoclock.media.ClockFamily
import com.kotarov.autoclock.media.ClockPreset
import com.kotarov.autoclock.media.ClockSelectionStore
import com.kotarov.autoclock.weather.WeatherStore
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

/**
 * Phone-side configuration: three main visual styles and contextual options.
 * SharedPreferences updates are observed by the bound media service immediately,
 * without recurring worker, polling timer, location permission or wake lock.
 */
class MainActivity : AppCompatActivity() {
    private var previewBitmap: Bitmap? = null
    private lateinit var preview: ImageView
    private lateinit var weatherStatus: TextView
    private lateinit var optionsContainer: LinearLayout

    private val light = Color.WHITE
    private val muted = Color.LTGRAY
    private val background = Color.rgb(10, 10, 12)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(AutoClockMediaService.PREFERENCES, MODE_PRIVATE)

        ClockSelectionStore.migrateIfNeeded(prefs)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(27), dp(22), dp(32))
            setBackgroundColor(this@MainActivity.background)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        root.addView(label("Auto Clock 2", 30f, light, dp(5)))
        root.addView(label("Три основни визии с допълнения към всяка", 16f, muted, dp(16)))

        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "Преглед на избраната визия"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(225)
            )
        }
        root.addView(preview)

        root.addView(label("1. Основна визия", 21f, light, dp(8)))
        val mainGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val familyIds = mutableMapOf<Int, ClockFamily>()
        val initial = currentPreset()
        for (family in ClockFamily.entries) {
            val rb = radio(family.label)
            familyIds[rb.id] = family
            mainGroup.addView(rb)
            if (family == initial.family) mainGroup.check(rb.id)
        }
        root.addView(mainGroup)

        root.addView(label("2. Добави към избраната визия", 21f, light, dp(7)))
        optionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(optionsContainer)
        root.addView(label(
            "Без отметка — само основната визия. Можеш да включиш " +
                "една или две добавки едновременно.",
            14f, muted, dp(18)
        ))

        updateVariantOptions(initial.family)
        mainGroup.setOnCheckedChangeListener { _, id ->
            familyIds[id]?.let { family ->
                prefs.edit().putString(ClockPreset.KEY_FAMILY, family.id).apply()
                updateVariantOptions(family)
                updatePreview()
                requestWeatherForPreview()
            }
        }

        root.addView(label("Размер на екрана", 20f, light, dp(7)))
        root.addView(label(
            "Компактният размер оставя повече свободно място над бутоните.",
            14f, muted, dp(5)
        ))
        val sizeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val small = radio("Компактен (за малка карта)")
        val large = radio("Стандартен (по-голяма визия)")
        sizeGroup.addView(small)
        sizeGroup.addView(large)
        sizeGroup.check(
            if (prefs.getString(AutoClockMediaService.KEY_LAYOUT, "compact") == "standard")
                large.id else small.id
        )
        sizeGroup.setOnCheckedChangeListener { _, id ->
            val size = if (id == large.id) "standard" else "compact"
            prefs.edit().putString(AutoClockMediaService.KEY_LAYOUT, size).apply()
            updatePreview()
        }
        root.addView(sizeGroup)

        root.addView(checkOption(
            "Движение на прогрес бара (секунди)",
            prefs.getBoolean(AutoClockMediaService.KEY_PROGRESS_ANIMATED, true)
        ) { checked ->
            prefs.edit().putBoolean(AutoClockMediaService.KEY_PROGRESS_ANIMATED, checked).apply()
        })

        root.addView(checkOption(
            "Само фон — скрий системните надписи за песента",
            prefs.getBoolean(AutoClockMediaService.KEY_HIDE_MEDIA_TEXT, true)
        ) { checked ->
            prefs.edit().putBoolean(AutoClockMediaService.KEY_HIDE_MEDIA_TEXT, checked).apply()
        })

        root.addView(label(
            "Тези настройки се изпращат веднага към активния Android Auto. " +
                "Прогресът и системните бутони се изобразяват от самия Android Auto.",
            13f, muted, dp(15)
        ))

        root.addView(label("Град за прогнозата", 20f, light, dp(6)))
        root.addView(label("Без GPS и постоянно следене на местоположението.", 14f, muted, dp(6)))
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
                requestWeatherForPreview()
            }
        }
        root.addView(cityGroup)

        weatherStatus = label("", 14f, muted, dp(10))
        root.addView(weatherStatus)
        val refresh = Button(this).apply {
            text = "Обнови прогнозата"
            isAllCaps = false
        }
        refresh.setOnClickListener {
            refresh.isEnabled = false
            weatherStatus.text = "Зареждам данни за времето..."
            WeatherStore.refreshIfDue(this, force = true) { success ->
                if (!isFinishing && !isDestroyed) {
                    refresh.isEnabled = true
                    updateWeatherStatus()
                    updatePreview()
                    if (!success) weatherStatus.text =
                        "Няма връзка. Часовникът работи и без интернет."
                }
            }
        }
        root.addView(refresh)

        root.addView(label(
            "В Android Auto избираш една от трите основни визии. " +
                "Отметките за всяка се запазват поотделно. Часовникът " +
                "се обновява веднъж в минута, прогнозата — най-много веднъж на час.",
            14f, muted, dp(8)
        ))
        root.addView(label("Данни за времето: Open-Meteo.com", 13f, Color.GRAY, dp(4)))

        updateWeatherStatus()
        updatePreview()
        requestWeatherForPreview()
    }

    private fun currentPreset(): ClockPreset =
        ClockSelectionStore.current(
            getSharedPreferences(AutoClockMediaService.PREFERENCES, MODE_PRIVATE)
        )

    /**
     * The only radio group is for choosing the primary style. Additional
     * features are two independent checkboxes. Clearing both always gives
     * the pure selected style.
     */
    private fun updateVariantOptions(family: ClockFamily) {
        optionsContainer.removeAllViews()
        val prefs = getSharedPreferences(AutoClockMediaService.PREFERENCES, MODE_PRIVATE)
        val firstLabel = when (family) {
            ClockFamily.ANALOG -> "Добави цифров час"
            ClockFamily.DIGITAL -> "Добави аналогов часовник"
            ClockFamily.WEATHER -> "Добави аналогов часовник"
        }
        val secondLabel = when (family) {
            ClockFamily.ANALOG, ClockFamily.DIGITAL -> "Добави времето"
            ClockFamily.WEATHER -> "Добави цифров час"
        }
        fun addCheckbox(label: String, key: String) {
            val box = checkOption(label, prefs.getBoolean(key, false)) { checked ->
                prefs.edit().putBoolean(key, checked).apply()
                updatePreview()
                requestWeatherForPreview()
            }
            optionsContainer.addView(box)
        }
        addCheckbox(firstLabel, ClockPreset.firstKey(family))
        addCheckbox(secondLabel, ClockPreset.secondKey(family))
    }

    private fun requestWeatherForPreview() {
        if (!currentPreset().usesWeather) return
        WeatherStore.refreshIfDue(this) { success ->
            if (success && !isFinishing && !isDestroyed) {
                updateWeatherStatus()
                updatePreview()
            }
        }
    }

    private fun updatePreview() {
        if (!::preview.isInitialized) return
        val preset = currentPreset()
        val weather = if (preset.usesWeather) WeatherStore.current(this) else null
        val prefs = getSharedPreferences(AutoClockMediaService.PREFERENCES, MODE_PRIVATE)
        val compact = prefs.getString(AutoClockMediaService.KEY_LAYOUT, "compact") != "standard"
        val image = ClockRenderer.render(
            preset.id, LocalDateTime.now(),
            weather?.weatherCode, weather?.isDay ?: true,
            weather?.formattedTemperature, compact,
            condition = weather?.description ?: "Няма данни"
        )
        preview.setImageBitmap(image)
        previewBitmap?.recycle()
        previewBitmap = image
    }

    private fun updateWeatherStatus() {
        if (!::weatherStatus.isInitialized) return
        val current = WeatherStore.current(this)
        weatherStatus.text = if (current == null) {
            "За ${WeatherStore.city(this).label} няма актуални данни."
        } else {
            val time = SimpleDateFormat("HH:mm", Locale("bg", "BG"))
                .format(Date(current.observedAtMs))
            "${WeatherStore.city(this).label}: ${current.formattedTemperature}, " +
                "${current.description} (обновено $time)"
        }
    }

    private fun checkOption(title: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox =
        CheckBox(this).apply {
            text = title
            textSize = 16f
            setTextColor(light)
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    private fun radio(title: String): RadioButton =
        RadioButton(this).apply {
            id = View.generateViewId()
            text = title
            textSize = 17f
            setTextColor(light)
            setPadding(0, dp(3), 0, dp(3))
        }

    private fun label(textValue: String, size: Float, color: Int, below: Int): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            setPadding(0, 0, 0, below)
        }

    override fun onDestroy() {
        preview.setImageDrawable(null)
        previewBitmap?.recycle()
        previewBitmap = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

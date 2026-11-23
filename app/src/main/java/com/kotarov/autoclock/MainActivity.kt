package com.kotarov.autoclock

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.kotarov.autoclock.weather.WeatherLocation
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
import com.kotarov.autoclock.media.ClockTheme
import com.kotarov.autoclock.media.PlaybackDisplay
import com.kotarov.autoclock.weather.WeatherStore
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

/**
 * Phone-side configuration: two clock styles; Pause always shows weather.
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
        // Only a previously UNSET source is initialized to Auto.
        // Explicitly selected manual cities survive upgrades and restarts.
        val firstAutoSetup = !prefs.contains(WeatherStore.KEY_CITY)
        if (firstAutoSetup) {
            prefs.edit().putString(WeatherStore.KEY_CITY, WeatherStore.AUTO_ID).apply()
        }

        ClockSelectionStore.migrateSimplifiedIfNeeded(prefs)

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
        root.addView(label(
            "Play — часовник · Stop — времето · 3 реда",
            16f, muted, dp(16)
        ))

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
        for (family in PlaybackDisplay.selectableFamilies) {
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
            "При аналоговия можеш да оставиш само циферблата и стрелките. " +
                "При Stop се показва времето, " +
                "без да се сменя избраният часовник.",
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

        root.addView(label(
            "В Android Auto: Play показва часовника, Stop/Pause — времето. " +
                "Вторият екран съдържа час, време и налягане/надморска височина.",
            14f, muted, dp(14)
        ))

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
            if (prefs.getString(AutoClockMediaService.KEY_LAYOUT, "standard") == "compact")
                small.id else large.id
        )
        sizeGroup.setOnCheckedChangeListener { _, id ->
            val size = if (id == large.id) "standard" else "compact"
            prefs.edit().putString(AutoClockMediaService.KEY_LAYOUT, size).apply()
            updatePreview()
        }
        root.addView(sizeGroup)

        root.addView(label("Тема на часовника", 20f, light, dp(7)))
        val themes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val dark = radio("Тъмна — черен фон (по подразбиране)")
        val reverse = radio("Светла — обратни цветове")
        themes.addView(dark)
        themes.addView(reverse)
        themes.check(if (ClockTheme.fromId(prefs.getString(ClockTheme.KEY, null)) ==
            ClockTheme.LIGHT) reverse.id else dark.id)
        themes.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString(ClockTheme.KEY,
                if (id == reverse.id) ClockTheme.LIGHT.id else ClockTheme.DARK.id).apply()
            updatePreview()
        }
        root.addView(themes)

        root.addView(label(
            "Няма Next/Previous или прогрес. Play/Pause превключва изгледа.",
            14f, muted, dp(8)
        ))

        root.addView(checkOption(
            "Скрий часа и времето под часовника в Android Auto",
            prefs.getBoolean(AutoClockMediaService.KEY_HIDE_MEDIA_TEXT, false)
        ) { checked ->
            prefs.edit().putBoolean(AutoClockMediaService.KEY_HIDE_MEDIA_TEXT, checked).apply()
        })

        root.addView(label(
            "Android Auto сам решава кога да прерисува видимия списък, " +
                "след като изпратим известие за обновяване.",
            13f, muted, dp(15)
        ))

        root.addView(checkOption(
            "Цветни иконки на времето (по-ясен дъжд)",
            WeatherStore.coloredIcons(prefs)
        ) { checked ->
            prefs.edit().putBoolean(WeatherStore.KEY_COLORED_ICONS, checked).apply()
            updatePreview()
        })

        root.addView(label("Източник за времето", 20f, light, dp(6)))
        root.addView(label("Авто използва местоположение еднократно, при обновяване. " +
            "Избира най-близката метеостанция с актуално измерване; " +
            "ръчните градове използват Open-Meteo.", 14f, muted, dp(6)))
        val cityGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val cityIds = mutableMapOf<Int, String>()
        val auto = radio("Авто — най-близка метеостанция")
        cityIds[auto.id] = WeatherStore.AUTO_ID
        cityGroup.addView(auto)
        if (WeatherStore.isAuto(this)) cityGroup.check(auto.id)
        for (city in WeatherStore.City.entries) {
            val button = radio(city.label)
            cityIds[button.id] = city.id
            cityGroup.addView(button)
            if (!WeatherStore.isAuto(this) && city == WeatherStore.city(this)) cityGroup.check(button.id)
        }
        cityGroup.setOnCheckedChangeListener { _, id ->
            cityIds[id]?.let {
                WeatherStore.setCity(this, it)
                updateWeatherStatus()
                updatePreview()
                if (it == WeatherStore.AUTO_ID && !WeatherLocation.permitted(this)) {
                    askForLocation()
                } else {
                    requestWeatherForPreview()
                }
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
            if (WeatherStore.isAuto(this) && !WeatherLocation.permitted(this)) {
                askForLocation()
            } else {
                refresh.isEnabled = false
                weatherStatus.text = "Зареждам данни за времето..."
                WeatherStore.refreshIfDue(this, force = true) { success ->
                    if (!isFinishing && !isDestroyed) {
                        refresh.isEnabled = true
                        updateWeatherStatus()
                        updatePreview()
                        if (!success) weatherStatus.text =
                            "Няма достъпни данни. Провери връзката и разрешенията."
                    }
                }
            }
        }
        root.addView(refresh)

        root.addView(label(
            "Play показва часовника, Stop/Pause — времето. " +
                "Трите реда се подават наново всяка минута, а " +
                "метеоданните се заявяват най-много веднъж на 15 минути.",
            14f, muted, dp(8)
        ))
        root.addView(label("Източници: NOAA Aviation Weather (METAR измервания) " +
            "и Open-Meteo (метеомодел).", 13f, Color.GRAY, dp(4)))

        updateWeatherStatus()
        updatePreview()
        if (firstAutoSetup && !WeatherLocation.permitted(this)) {
            // The phone may request coarse location once; Android Auto's
            // media service never opens a runtime permission dialog.
            askForLocation()
        } else {
            requestWeatherForPreview()
        }
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
        fun addOption(label: String, key: String, defaultChecked: Boolean = false) {
            optionsContainer.addView(
                checkOption(label, prefs.getBoolean(key, defaultChecked)) { checked ->
                    prefs.edit().putBoolean(key, checked).apply()
                    updatePreview()
                    requestWeatherForPreview()
                }
            )
        }

        when (family) {
            ClockFamily.ANALOG -> {
                addOption("Добави цифров час", ClockPreset.KEY_ANALOG_ADD_DIGITAL)
                addOption("Добави времето", ClockPreset.KEY_ANALOG_ADD_WEATHER)
            }
            ClockFamily.DIGITAL ->
                addOption("Добави времето", ClockPreset.KEY_DIGITAL_ADD_WEATHER)
            // Weather is Pause-only and is not selectable in the phone UI.
            ClockFamily.WEATHER -> Unit
        }
    }

    private fun askForLocation() {
        if (!WeatherStore.isAuto(this)) return
        if (WeatherLocation.permitted(this)) {
            requestWeatherForPreview(force = true)
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_REQUEST
            )
        }
    }

    @Deprecated("The ActivityResult API may replace this permission callback later")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST) {
            updateWeatherStatus()
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                requestWeatherForPreview(force = true)
            }
        }
    }

    private fun requestWeatherForPreview(force: Boolean = false) {
        val prefs = getSharedPreferences(AutoClockMediaService.PREFERENCES, MODE_PRIVATE)
        // The library always includes weather and pressure/elevation rows,
        // even when the selected clock style has no weather add-on.
        if (WeatherStore.isAuto(this) && !WeatherLocation.permitted(this)) {
            updateWeatherStatus()
            return
        }
        WeatherStore.refreshIfDue(this, force = force) { success ->
            if (!isFinishing && !isDestroyed) {
                updateWeatherStatus()
                if (success) {
                    updatePreview()
                } else if (WeatherStore.isAuto(this) && WeatherLocation.permitted(this)) {
                    weatherStatus.text =
                        "Авто: няма нова локация или връзка. Провери местоположението " +
                            "или избери град ръчно."
                }
            }
        }
    }

    private fun updatePreview() {
        if (!::preview.isInitialized) return
        val prefs = getSharedPreferences(AutoClockMediaService.PREFERENCES, MODE_PRIVATE)
        val preset = currentPreset()
        val weather = if (preset.usesWeather) WeatherStore.current(this) else null
        val compact = prefs.getString(AutoClockMediaService.KEY_LAYOUT, "standard") == "compact"
        val image = ClockRenderer.render(
            preset.id, LocalDateTime.now(),
            weather?.weatherCode, weather?.isDay ?: true,
            weather?.formattedTemperature, compact,
            condition = weather?.description ?: "Няма данни",
            theme = ClockTheme.fromId(prefs.getString(ClockTheme.KEY, null)),
            coloredWeatherIcons = WeatherStore.coloredIcons(prefs)
        )
        preview.setImageBitmap(image)
        previewBitmap?.recycle()
        previewBitmap = image
    }

    private fun updateWeatherStatus() {
        if (!::weatherStatus.isInitialized) return
        weatherStatus.text = WeatherStore.sourceStatus(this)
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

    companion object { private const val LOCATION_REQUEST = 7401 }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

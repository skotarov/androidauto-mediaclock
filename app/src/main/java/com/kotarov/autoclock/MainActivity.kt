package com.kotarov.autoclock

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.kotarov.autoclock.media.CityAnnouncement
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import com.kotarov.autoclock.artwork.ClockRenderer
import com.kotarov.autoclock.media.AutoClockMediaService
import com.kotarov.autoclock.media.ClockFamily
import com.kotarov.autoclock.media.ClockPreset
import com.kotarov.autoclock.media.ClockSelectionStore
import com.kotarov.autoclock.media.ClockTheme
import com.kotarov.autoclock.weather.WeatherLocation
import com.kotarov.autoclock.weather.WeatherStore
import com.kotarov.autoclock.weather.ForecastStore
import java.time.LocalDateTime

/**
 * Compact phone settings. Each selectable card is a LIVE preview of that
 * clock family's saved add-ons; both cards always render in the chosen theme.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var analogCard: LinearLayout
    private lateinit var digitalCard: LinearLayout
    private lateinit var analogImage: ImageView
    private lateinit var digitalImage: ImageView
    private lateinit var analogLabel: TextView
    private lateinit var digitalLabel: TextView
    private lateinit var weatherToggle: SwitchCompat
    private var syncingWeatherToggle = false
    private val weatherPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ClockPreset.KEY_ADD_WEATHER) syncWeatherToggle()
    }
    private var analogBitmap: Bitmap? = null
    private var digitalBitmap: Bitmap? = null
    private val messageHandler = Handler(Looper.getMainLooper())
    private lateinit var locationMessage: TextView
    private val hideLocationMessage = Runnable {
        if (::locationMessage.isInitialized) locationMessage.visibility = View.GONE
    }

    private val ink = Color.WHITE
    private val background = Color.rgb(10, 10, 12)
    private val accent = Color.rgb(35, 155, 255)
    private val faint = Color.rgb(87, 93, 105)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = settings()
        // This is now an auto-location-only product, including old installs.
        if (prefs.getString(WeatherStore.KEY_CITY, null) != WeatherStore.AUTO_ID) {
            WeatherStore.setCity(this, WeatherStore.AUTO_ID)
        }
        ClockSelectionStore.migrateSimplifiedIfNeeded(prefs)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(24))
            setBackgroundColor(this@MainActivity.background)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
        root.addView(text("Auto Clock 2", 27f, dp(16)))

        // The blue outline is the exclusive selection, as in phone display
        // settings. No radio-circle UI is added next to the thumbnails.
        val choices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        val (analog, analogPreview, analogTitle) =
            createChoiceCard("Аналогов", ClockFamily.ANALOG)
        val (digital, digitalPreview, digitalTitle) =
            createChoiceCard("Цифров", ClockFamily.DIGITAL)
        analogCard = analog
        digitalCard = digital
        analogImage = analogPreview
        digitalImage = digitalPreview
        analogLabel = analogTitle
        digitalLabel = digitalTitle
        choices.addView(analogCard,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            })
        choices.addView(digitalCard,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            })
        root.addView(choices)

        // Directly BELOW the two thumbnails, with no explanatory widgets.
        root.addView(checkOption(
            "Светла тема",
            ClockTheme.fromId(prefs.getString(ClockTheme.KEY, null)) == ClockTheme.LIGHT
        ) { checked ->
            prefs.edit().putString(
                ClockTheme.KEY,
                if (checked) ClockTheme.LIGHT.id else ClockTheme.DARK.id
            ).apply()
            updatePreviewTiles()
        })

        root.addView(checkOption(
            "Добави времето",
            ClockSelectionStore.weatherEnabled(prefs),
            captureToggle = { weatherToggle = it }
        ) { checked ->
            if (!syncingWeatherToggle) {
                ClockSelectionStore.setWeatherEnabled(prefs, checked)
                updatePreviewTiles()
            }
        })
        updatePreviewTiles()

        root.addView(text("Размер", 18f, dp(5)))
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        sizeRow.addView(text("А", 14f, 0))
        val sizeSlider = SeekBar(this).apply {
            max = 1 // Keep the two validated render sizes: compact / standard.
            progress = if (prefs.getString(
                    AutoClockMediaService.KEY_LAYOUT, "standard"
                ) == "compact") 0 else 1
            contentDescription = "Размер на часовника"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?, value: Int, fromUser: Boolean
                ) {
                    if (!fromUser) return
                    prefs.edit().putString(
                        AutoClockMediaService.KEY_LAYOUT,
                        if (value == 0) "compact" else "standard"
                    ).apply()
                    updatePreviewTiles()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        sizeRow.addView(sizeSlider,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sizeRow.addView(text("А", 28f, 0))
        root.addView(sizeRow)

        root.addView(Button(this).apply {
            text = "Виж локация"
            isAllCaps = false
            setOnClickListener {
                val city = WeatherStore.current(this@MainActivity)?.localityLabel
                locationMessage.text = city ?: "Няма данни за локацията"
                locationMessage.visibility = View.VISIBLE
                messageHandler.removeCallbacks(hideLocationMessage)
                messageHandler.postDelayed(hideLocationMessage, CityAnnouncement.DURATION_MS)
                // The already connected media service receives the same
                // request; no playback change or new GPS/network request.
                prefs.edit().putLong(AutoClockMediaService.KEY_SHOW_LOCATION,
                    SystemClock.elapsedRealtimeNanos()).apply()
            }
        })
        locationMessage = text("", 18f, dp(4)).apply {
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(locationMessage)

    }

    override fun onStart() {
        super.onStart()
        settings().registerOnSharedPreferenceChangeListener(weatherPreferenceListener)
        syncWeatherToggle()
    }

    override fun onStop() {
        settings().unregisterOnSharedPreferenceChangeListener(weatherPreferenceListener)
        super.onStop()
    }

    private fun syncWeatherToggle() {
        if (!::weatherToggle.isInitialized) return
        val checked = ClockSelectionStore.weatherEnabled(settings())
        if (weatherToggle.isChecked != checked) {
            syncingWeatherToggle = true
            weatherToggle.isChecked = checked
            syncingWeatherToggle = false
            updatePreviewTiles()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::analogImage.isInitialized) updatePreviewTiles()
        // Every opening of settings starts a current observation refresh.
        if (WeatherLocation.permitted(this)) {
            WeatherStore.refreshIfDue(this, force = true) {
                if (!isFinishing && !isDestroyed && ::analogImage.isInitialized)
                    updatePreviewTiles()
            }
        } else if (!hasRequestedLocation) {
            hasRequestedLocation = true
            requestLocation()
        }
    }

    private var hasRequestedLocation = false

    private fun settings() =
        getSharedPreferences(AutoClockMediaService.PREFERENCES, Context.MODE_PRIVATE)

    private data class Choice(
        val container: LinearLayout, val image: ImageView, val title: TextView
    )

    private fun createChoiceCard(label: String, family: ClockFamily): Choice {
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "$label часовник"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val frame = SquareFrame(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(image)
        }
        val caption = text(label, 17f, 0).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(10), 0, dp(8))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            addView(frame, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(caption)
            setOnClickListener {
                settings().edit().putString(ClockPreset.KEY_FAMILY, family.id).apply()
                updatePreviewTiles()
            }
        }
        return Choice(card, image, caption)
    }

    private fun updatePreviewTiles() {
        if (!::analogImage.isInitialized || !::digitalImage.isInitialized) return
        val prefs = settings()
        val theme = ClockTheme.fromId(prefs.getString(ClockTheme.KEY, null))
        val compact = prefs.getString(AutoClockMediaService.KEY_LAYOUT, "standard") == "compact"
        val now = LocalDateTime.now()
        val weather = WeatherStore.current(this)
        fun make(family: ClockFamily): Bitmap {
            val preset = ClockSelectionStore.forFamily(prefs, family)
            return ClockRenderer.render(
                preset.id, now, weather?.weatherCode, weather?.isDay ?: true,
                weather?.formattedTemperature, compact,
                outputSize = 256, condition = weather?.description,
                sourceLabel = weather?.artworkSource,
                theme = theme,
                forecast = if (family != ClockFamily.WEATHER && preset.usesWeather)
                    ForecastStore.currentRing(this,
                        now.atZone(java.time.ZoneId.systemDefault()).toEpochSecond(), weather) else emptyList()
            )
        }
        val newAnalog = make(ClockFamily.ANALOG)
        val newDigital = make(ClockFamily.DIGITAL)
        analogImage.setImageBitmap(newAnalog)
        digitalImage.setImageBitmap(newDigital)
        analogBitmap?.recycle()
        digitalBitmap?.recycle()
        analogBitmap = newAnalog
        digitalBitmap = newDigital

        val selected = ClockSelectionStore.current(prefs).family
        updateSelectionStyle(analogCard, analogLabel, selected == ClockFamily.ANALOG)
        updateSelectionStyle(digitalCard, digitalLabel, selected == ClockFamily.DIGITAL)
    }

    private fun updateSelectionStyle(
        card: LinearLayout, title: TextView, selected: Boolean
    ) {
        val square = card.getChildAt(0)
        square.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.rgb(18, 20, 27))
            setStroke(dp(if (selected) 3 else 1), if (selected) accent else faint)
        }
        card.isSelected = selected
        title.setTextColor(if (selected) accent else ink)
    }

    private fun requestLocation() {
        if (!WeatherLocation.permitted(this)) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_REQUEST)
        } else {
            WeatherStore.refreshIfDue(this, force = true) {
                if (!isFinishing && !isDestroyed) updatePreviewTiles()
            }
        }
    }

    @Deprecated("Android runtime location permission callback")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestLocation()
        }
    }

    /**
     * Spacious settings row. A compact switch on the right replaces the
     * old checkbox, while the entire row remains a generous touch target.
     */
    private fun checkOption(
        title: String, checked: Boolean,
        captureToggle: ((SwitchCompat) -> Unit)? = null,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val toggle = SwitchCompat(this).apply {
            showText = false
            isChecked = checked
            contentDescription = title
            scaleX = 0.85f
            scaleY = 0.85f
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, Color.rgb(198, 202, 211))
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, faint)
            )
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        captureToggle?.invoke(toggle)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(0, dp(10), 0, dp(10))
            val label = text(title, 16f, 0).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, dp(12), 0)
            }
            addView(label, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(toggle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16)
            }
            setOnClickListener { toggle.isChecked = !toggle.isChecked }
        }
    }

    private fun text(text: String, size: Float, bottom: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(ink)
            setPadding(0, 0, 0, bottom)
        }

    /** Native square measure: thumbnail borders frame the entire drawing. */
    private class SquareFrame(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY))
        }
    }

    override fun onDestroy() {
        messageHandler.removeCallbacks(hideLocationMessage)
        if (::analogImage.isInitialized) analogImage.setImageDrawable(null)
        if (::digitalImage.isInitialized) digitalImage.setImageDrawable(null)
        analogBitmap?.recycle()
        digitalBitmap?.recycle()
        analogBitmap = null
        digitalBitmap = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object { private const val LOCATION_REQUEST = 7401 }
}

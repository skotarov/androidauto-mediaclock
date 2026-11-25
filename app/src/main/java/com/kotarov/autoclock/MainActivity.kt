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
import android.app.Activity
import android.app.AlertDialog
import com.kotarov.autoclock.media.NoticeSound
import com.kotarov.autoclock.media.NoticeAudio
import com.kotarov.autoclock.media.NoticeSoundPlayer
import android.widget.Switch
import com.kotarov.autoclock.artwork.ClockRenderer
import com.kotarov.autoclock.artwork.ArtworkWorkers
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
class MainActivity : Activity() {
    private lateinit var analogCard: LinearLayout
    private lateinit var digitalCard: LinearLayout
    private lateinit var analogImage: ImageView
    private lateinit var digitalImage: ImageView
    private lateinit var analogLabel: TextView
    private lateinit var digitalLabel: TextView
    private lateinit var weatherToggle: Switch
    private var syncingWeatherToggle = false
    private val soundPreview by lazy { NoticeSoundPlayer() }
    private val weatherPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ClockPreset.KEY_ADD_WEATHER) syncWeatherToggle()
        else if (key == "weather_fetched_at" || key == WeatherStore.KEY_LOCALITY) {
            updatePreviewTiles()
            if (key == WeatherStore.KEY_LOCALITY && awaitingLocation) {
                WeatherStore.locality(this)?.let { finishLocation(it, true) }
            }
        }
    }
    private var analogBitmap: Bitmap? = null
    private var digitalBitmap: Bitmap? = null
    private data class PreviewKey(
        val minute: LocalDateTime, val theme: ClockTheme, val compact: Boolean,
        val analogPreset: String, val digitalPreset: String,
        val locality: String?,
        val forecast: List<ForecastStore.Hour>
    )
    private var previewKey: PreviewKey? = null
    private var requestedPreview: PreviewKey? = null
    private data class PreviewImages(val analog: Bitmap, val digital: Bitmap) {
        fun discard() { analog.recycle(); digital.recycle() }
    }
    private val previewWorker = ArtworkWorkers.create<PreviewImages> { it.discard() }
    private val messageHandler = Handler(Looper.getMainLooper())
    private lateinit var locationMessage: TextView
    private var awaitingLocation = false
    private var showLocationAfterPermission = false
    private val locationTimeout = Runnable {
        if (awaitingLocation) {
            val city = WeatherStore.locality(this)
            finishLocation(city ?: WeatherStore.localityFailure(this), city != null)
        }
    }
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
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout())
                view.setPadding(dp(16) + bars.left, dp(22) + bars.top,
                    dp(16) + bars.right, dp(24) + bars.bottom)
                insets
            }
            root.requestApplyInsets()
        }
        root.addView(text("Auto Clock 2", 27f, dp(16)))

        // The blue outline is the exclusive selection, as in phone display
        // settings. No radio-circle UI is added next to the thumbnails.
        val choices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        val (analog, analogPreview, analogTitle) =
            createChoiceCard("Analog", ClockFamily.ANALOG)
        val (digital, digitalPreview, digitalTitle) =
            createChoiceCard("Digital", ClockFamily.DIGITAL)
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
            "Light theme",
            ClockTheme.fromId(prefs.getString(ClockTheme.KEY, null)) == ClockTheme.LIGHT
        ) { checked ->
            prefs.edit().putString(
                ClockTheme.KEY,
                if (checked) ClockTheme.LIGHT.id else ClockTheme.DARK.id
            ).apply()
            updatePreviewTiles()
        })

        root.addView(checkOption(
            "Weather + voice alerts",
            ClockSelectionStore.weatherEnabled(prefs),
            captureToggle = { weatherToggle = it }
        ) { checked ->
            if (!syncingWeatherToggle) {
                ClockSelectionStore.setWeatherEnabled(prefs, checked)
                updatePreviewTiles()
            }
        })
        updatePreviewTiles()

        root.addView(text("Size", 18f, dp(5)))
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        sizeRow.addView(text("A", 14f, 0))
        val sizeSlider = SeekBar(this).apply {
            max = 1 // Keep the two validated render sizes: compact / standard.
            progress = if (prefs.getString(
                    AutoClockMediaService.KEY_LAYOUT, "standard"
                ) == "compact") 0 else 1
            contentDescription = "Clock size"
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
        sizeRow.addView(text("A", 28f, 0))
        root.addView(sizeRow)

        root.addView(Button(this).apply {
            text = "Location"
            isAllCaps = false
            setOnClickListener { showLocation() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(32) })
        locationMessage = text("", 18f, dp(4)).apply {
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(locationMessage)

        val soundButton = Button(this).apply {
            isAllCaps = false
            text = "Chime: " + NoticeSound.fromId(prefs.getString(NoticeSound.KEY, null)).label
            setOnClickListener {
                val options = NoticeSound.entries
                val selected = NoticeSound.fromId(prefs.getString(NoticeSound.KEY, null))
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Alert chime (voice remains enabled)")
                    .setSingleChoiceItems(options.map { it.label }.toTypedArray(),
                        options.indexOf(selected)) { dialog, index ->
                        val sound = options[index]
                        prefs.edit().putString(NoticeSound.KEY, sound.id).apply()
                        text = "Chime: " + sound.label
                        soundPreview.play(sound)
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        root.addView(soundButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        if (android.os.Build.VERSION.SDK_INT >= 31) {
            root.addView(checkOption(
                "Bluetooth phone audio",
                prefs.getBoolean(NoticeAudio.KEY_PHONE_CHANNEL, false)
            ) { checked ->
                prefs.edit().putBoolean(NoticeAudio.KEY_PHONE_CHANNEL, checked).apply()
            })
        }

        // Weather attribution stays at the bottom of the phone settings.
        root.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val credit = "Weather data: NOAA / Open-Meteo (CC BY 4.0)\nAdapted for Auto Clock\nPrivacy Policy"
        val attribution = android.text.SpannableString(credit).apply {
            fun link(label: String, url: String) {
                val start = credit.indexOf(label)
                setSpan(android.text.style.URLSpan(url), start, start + label.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            link("NOAA", "https://aviationweather.gov/")
            link("Open-Meteo", "https://open-meteo.com/")
            link("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/")
            link("Privacy Policy", "https://github.com/skotarov/androidauto-mediaclock/blob/revamp/low-power-v2/PRIVACY.md")
        }
        root.addView(TextView(this).apply {
            text = attribution
            textSize = 12f
            setTextColor(Color.rgb(160, 166, 178))
            setLinkTextColor(Color.rgb(160, 166, 178))
            gravity = android.view.Gravity.CENTER
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            setPadding(0, dp(28), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    override fun onStart() {
        super.onStart()
        settings().registerOnSharedPreferenceChangeListener(weatherPreferenceListener)
        syncWeatherToggle()
    }

    override fun onStop() {
        awaitingLocation = false
        messageHandler.removeCallbacks(locationTimeout)
        messageHandler.removeCallbacks(hideLocationMessage)
        if (::locationMessage.isInitialized) locationMessage.visibility = View.GONE
        WeatherStore.stop(WeatherStore.Client.PHONE)
        settings().unregisterOnSharedPreferenceChangeListener(weatherPreferenceListener)
        soundPreview.stop()
        releasePreviews()
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
            WeatherStore.start(this, WeatherStore.Client.PHONE)
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
            contentDescription = "$label clock"
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
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        val analog = ClockSelectionStore.forFamily(prefs, ClockFamily.ANALOG)
        val digital = ClockSelectionStore.forFamily(prefs, ClockFamily.DIGITAL)
        val hasWeather = analog.usesWeather || digital.usesWeather
        val weather = if (hasWeather) WeatherStore.current(this) else null
        val locality = if (hasWeather) WeatherStore.locality(this) ?: weather?.artworkSource else null
        val forecast = if (hasWeather) ForecastStore.currentRing(this,
            now.atZone(java.time.ZoneId.systemDefault()).toEpochSecond(), weather) else emptyList()
        val next = PreviewKey(now, theme, compact, analog.id, digital.id, locality, forecast)
        if (previewKey != next || analogBitmap == null || digitalBitmap == null) {
            if (requestedPreview != next) {
                requestedPreview = next
                previewWorker.submit({
                    fun make(preset: ClockPreset): Bitmap = ClockRenderer.render(
                        preset.id, now, compact, outputSize = 256,
                        sourceLabel = if (preset.usesWeather) locality else null,
                        theme = theme, forecast = if (preset.usesWeather) forecast else emptyList())
                    val first = make(analog)
                    try {
                        PreviewImages(first, make(digital))
                    } catch (e: Throwable) {
                        first.recycle()
                        throw e
                    }
                }) { outcome ->
                    requestedPreview = null
                    val result = outcome.getOrNull()
                    if (result != null) {
                        analogImage.setImageBitmap(result.analog)
                        digitalImage.setImageBitmap(result.digital)
                        analogBitmap?.recycle()
                        digitalBitmap?.recycle()
                        analogBitmap = result.analog
                        digitalBitmap = result.digital
                        previewKey = next
                    } else {
                        android.util.Log.w("AutoClock", "Preview render failed", outcome.exceptionOrNull())
                    }
                }
            }
        } else if (requestedPreview != null) {
            previewWorker.cancel()
            requestedPreview = null
        }

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

    private fun showLocation() {
        if (!WeatherLocation.permitted(this)) {
            finishLocation("Location permission required", false)
            showLocationAfterPermission = true
            requestLocation()
            return
        }
        if (!WeatherLocation.enabled(this)) {
            finishLocation("Turn on phone location", false)
            return
        }
        WeatherStore.locality(this)?.let {
            finishLocation(it, true)
            return
        }
        if (awaitingLocation) return
        awaitingLocation = true
        locationMessage.text = "Finding location…"
        locationMessage.visibility = View.VISIBLE
        messageHandler.removeCallbacks(hideLocationMessage)
        messageHandler.removeCallbacks(locationTimeout)
        messageHandler.postDelayed(locationTimeout, 30_000L)
        WeatherStore.refreshLocality(this)
    }

    private fun finishLocation(message: String, announce: Boolean) {
        awaitingLocation = false
        messageHandler.removeCallbacks(locationTimeout)
        locationMessage.text = message
        locationMessage.visibility = View.VISIBLE
        messageHandler.removeCallbacks(hideLocationMessage)
        messageHandler.postDelayed(hideLocationMessage, CityAnnouncement.DURATION_MS)
        if (announce) settings().edit().putLong(AutoClockMediaService.KEY_SHOW_LOCATION,
            SystemClock.elapsedRealtimeNanos()).apply()
    }

    private fun requestLocation() {
        if (!WeatherLocation.permitted(this)) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_REQUEST)
        } else {
            WeatherStore.start(this, WeatherStore.Client.PHONE)
        }
    }

    @Deprecated("Android runtime location permission callback")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_REQUEST) return
        val manual = showLocationAfterPermission
        showLocationAfterPermission = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestLocation()
            if (manual) showLocation()
        } else if (manual) {
            finishLocation("Location permission required", false)
        }
    }

    /**
     * Spacious settings row. A compact switch on the right replaces the
     * old checkbox, while the entire row remains a generous touch target.
     */
    private fun checkOption(
        title: String, checked: Boolean,
        captureToggle: ((Switch) -> Unit)? = null,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val toggle = Switch(this).apply {
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
        releasePreviews()
        super.onDestroy()
    }

    private fun releasePreviews() {
        previewWorker.cancel()
        requestedPreview = null
        if (::analogImage.isInitialized) analogImage.setImageDrawable(null)
        if (::digitalImage.isInitialized) digitalImage.setImageDrawable(null)
        analogBitmap?.recycle()
        digitalBitmap?.recycle()
        analogBitmap = null
        digitalBitmap = null
        previewKey = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object { private const val LOCATION_REQUEST = 7401 }
}

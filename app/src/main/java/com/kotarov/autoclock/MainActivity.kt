package com.kotarov.autoclock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ClipData
import android.net.Uri
import android.view.DragEvent
import android.support.v4.media.MediaBrowserCompat
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.kotarov.autoclock.media.PhoneAudioMode
import com.kotarov.autoclock.media.CustomStreamPlayer
import com.kotarov.autoclock.media.StreamLibrary
import com.kotarov.autoclock.media.StreamMetadata
import com.kotarov.autoclock.media.NoticeTest
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
    private lateinit var weatherButton: android.widget.ImageButton
    private var phoneBrowser: MediaBrowserCompat? = null
    private val previewTick = object : Runnable {
        override fun run() {
            updatePreviewTiles()
            messageHandler.postDelayed(this, 60000L - System.currentTimeMillis() % 60000L)
        }
    }
    private val soundPreview by lazy { NoticeSoundPlayer() }
    private val testAudio by lazy { NoticeAudio(this) }
    private lateinit var streamRows: LinearLayout
    private val weatherPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == StreamLibrary.KEY_LIST || key == StreamLibrary.KEY_SELECTED) renderStreams()
        if (key == ClockPreset.KEY_ADD_WEATHER) syncWeatherToggle()
        else if (key == "weather_fetched_at" || key == WeatherStore.KEY_LOCALITY) {
            updatePreviewTiles()
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
    private val phoneCaption: (String?) -> Unit = { value ->
        if (::locationMessage.isInitialized) {
            locationMessage.text = value.orEmpty()
            locationMessage.visibility = if (value == null) View.GONE else View.VISIBLE
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
        StreamLibrary.migrate(prefs)

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
        val (analog, analogPreview) =
            createChoiceCard("Analog", ClockFamily.ANALOG)
        val (digital, digitalPreview) =
            createChoiceCard("Digital", ClockFamily.DIGITAL)
        analogCard = analog
        digitalCard = digital
        analogImage = analogPreview
        digitalImage = digitalPreview
        choices.addView(analogCard,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            })
        choices.addView(digitalCard,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            })
        root.addView(choices)
        val weatherRow = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            addView(text("Weather", 17f, 0), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(12) })
        }
        weatherButton = android.widget.ImageButton(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                setColor(Color.rgb(225, 228, 232))
                setStroke(dp(2), Color.rgb(120, 126, 135))
            }
            setColorFilter(Color.rgb(47, 52, 60))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener {
                val p = settings()
                ClockSelectionStore.setWeatherEnabled(p, !ClockSelectionStore.weatherEnabled(p))
                syncWeatherToggle()
            }
        }
        weatherRow.addView(weatherButton, LinearLayout.LayoutParams(dp(108), dp(52)))
        addSpaced(root, weatherRow)
        syncWeatherToggle()

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
        root.addView(sizeRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(24) })

        if (android.os.Build.VERSION.SDK_INT >= 31) {
            root.addView(checkOption(
                "Use car phone audio",
                PhoneAudioMode.read(prefs) != PhoneAudioMode.OFF,
                bluetoothIcon = true
            ) { checked ->
                testAudio.stop()
                prefs.edit().putString(PhoneAudioMode.KEY,
                    if (checked) PhoneAudioMode.ALERTS.id else PhoneAudioMode.OFF.id).apply()
            })
        }

        val messages = settingsGroup(root, "Messages")
        val soundButton = Button(this).apply {
            isAllCaps = false
            text = "Chime: " + NoticeSound.fromId(prefs.getString(NoticeSound.KEY, null)).label
            setOnClickListener {
                val options = NoticeSound.entries
                val selected = NoticeSound.fromId(prefs.getString(NoticeSound.KEY, null))
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Alert chime")
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
        addSpaced(messages, soundButton)
        messages.addView(checkOption("Speak messages",
            prefs.getBoolean(NoticeAudio.KEY_SPEAK, true)) { checked ->
            testAudio.stop()
            prefs.edit().putBoolean(NoticeAudio.KEY_SPEAK, checked).apply()
        })
        addSpaced(messages, Button(this).apply {
            text = "Test"
            isAllCaps = false
            setOnClickListener { showSoundTest() }
        })
        locationMessage = text("", 18f, dp(4)).apply {
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }
        messages.addView(locationMessage)

        val streams = settingsGroup(root, "Streams")
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addRow.addView(Button(this).apply {
            text = "Add stream"
            isAllCaps = false
            setOnClickListener { editStream(null) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(Button(this).apply {
            text = "Add file"
            isAllCaps = false
            setOnClickListener { pickFile() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        addSpaced(streams, addRow)
        streamRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        streams.addView(streamRows)
        renderStreams()

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
        renderStreams()
        NoticeTest.phoneCaption = phoneCaption
        phoneCaption(NoticeTest.currentCaption)
        phoneBrowser = MediaBrowserCompat(this, ComponentName(this, AutoClockMediaService::class.java),
            object : MediaBrowserCompat.ConnectionCallback() {}, null).also { it.connect() }
        messageHandler.removeCallbacks(previewTick)
        messageHandler.post(previewTick)
    }

    override fun onStop() {
        messageHandler.removeCallbacks(hideLocationMessage)
        messageHandler.removeCallbacks(previewTick)
        if (NoticeTest.phoneCaption === phoneCaption) NoticeTest.phoneCaption = null
        phoneBrowser?.disconnect()
        phoneBrowser = null
        if (::locationMessage.isInitialized) locationMessage.visibility = View.GONE
        WeatherStore.stop(WeatherStore.Client.PHONE)
        settings().unregisterOnSharedPreferenceChangeListener(weatherPreferenceListener)
        soundPreview.stop()
        testAudio.stop()
        releasePreviews()
        super.onStop()
    }

    private fun syncWeatherToggle() {
        if (!::weatherButton.isInitialized) return
        val playing = ClockSelectionStore.weatherEnabled(settings())
        weatherButton.setImageResource(if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play)
        weatherButton.contentDescription = if (playing) "Pause" else "Play"
        updatePreviewTiles()
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
        val container: LinearLayout, val image: ImageView
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
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            addView(frame, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            setOnClickListener {
                settings().edit().putString(ClockPreset.KEY_FAMILY, family.id).apply()
                updatePreviewTiles()
            }
        }
        return Choice(card, image)
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
        updateSelectionStyle(analogCard, selected == ClockFamily.ANALOG)
        updateSelectionStyle(digitalCard, selected == ClockFamily.DIGITAL)
    }

    private fun updateSelectionStyle(
        card: LinearLayout, selected: Boolean
    ) {
        val square = card.getChildAt(0)
        square.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.rgb(18, 20, 27))
            setStroke(dp(if (selected) 3 else 1), if (selected) accent else faint)
        }
        card.isSelected = selected
    }

    private fun addSpaced(parent: LinearLayout, view: View) {
        parent.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })
    }

    private fun settingsGroup(root: LinearLayout, heading: String): LinearLayout {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), faint)
                setColor(Color.rgb(18, 20, 27))
            }
            addView(text(heading, 20f, dp(12)))
        }
        root.addView(group, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12); bottomMargin = dp(24) })
        return group
    }

    private fun renderStreams() {
        if (!::streamRows.isInitialized) return
        streamRows.removeAllViews()
        val prefs = settings()
        val selected = prefs.getString(StreamLibrary.KEY_SELECTED, StreamLibrary.ALERT_ID)
        val alert = text("Alert only", 16f, dp(8)).apply {
            setTextColor(if (selected == StreamLibrary.ALERT_ID) accent else ink)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { selectPhoneSource(StreamLibrary.ALERT_ID) }
        }
        addSpaced(streamRows, alert)
        StreamLibrary.list(prefs).forEach { station ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                setOnDragListener { view, event ->
                    when (event.action) {
                        DragEvent.ACTION_DRAG_STARTED -> event.localState is String
                        DragEvent.ACTION_DRAG_ENTERED -> { view.alpha = 0.55f; true }
                        DragEvent.ACTION_DRAG_EXITED -> { view.alpha = 1f; true }
                        DragEvent.ACTION_DROP -> {
                            view.alpha = 1f
                            val source = event.localState as? String
                            if (source != null) {
                                val p = settings()
                                val after = event.y > view.height / 2f
                                // Read the latest list after drag dispatch (metadata may have updated).
                                messageHandler.post {
                                    StreamLibrary.save(p, StreamLibrary.move(StreamLibrary.list(p), source, station.id, after))
                                    if (!isDestroyed) renderStreams()
                                }
                            }
                            true
                        }
                        DragEvent.ACTION_DRAG_ENDED -> { view.alpha = 1f; true }
                        else -> true
                    }
                }
            }
            row.addView(text("☰", 23f, 0).apply {
                gravity = android.view.Gravity.CENTER
                contentDescription = "Drag to reorder " + station.name
                setOnLongClickListener {
                    val data = ClipData.newPlainText("source", "")
                    if (android.os.Build.VERSION.SDK_INT >= 24)
                        startDragAndDrop(data, View.DragShadowBuilder(row), station.id, 0)
                    else {
                        @Suppress("DEPRECATION")
                        startDrag(data, View.DragShadowBuilder(row), station.id, 0)
                    }
                    true
                }
            }, LinearLayout.LayoutParams(dp(34), dp(48)))
            row.addView(ImageView(this).apply {
                val icon = station.bitmap()
                if (icon != null) setImageBitmap(icon)
                else setImageResource(android.R.drawable.ic_media_play)
                contentDescription = station.name
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
            row.addView(text(station.name, 16f, 0).apply {
                setTextColor(if (selected == station.id) accent else ink)
                setOnClickListener { selectPhoneSource(station.id) }
            }, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Edit"
                isAllCaps = false
                minWidth = 0; minimumWidth = 0
                setOnClickListener { if (station.local) editFileName(station) else editStream(station) }
            })
            row.addView(Button(this).apply {
                text = "Delete"
                isAllCaps = false
                minWidth = 0; minimumWidth = 0
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle(station.name)
                        .setMessage(if (station.local) "Remove file from list?" else "Delete stream?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            val prefs = settings()
                            val remaining = StreamLibrary.list(prefs).filterNot { it.id == station.id }
                            StreamLibrary.save(prefs, remaining)
                            if (station.local && remaining.none { it.url == station.url }) {
                                runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(station.url), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            }
                            renderStreams()
                        }.show()
                }
            })
            addSpaced(streamRows, row)
        }
    }

    private fun selectPhoneSource(id: String) {
        val prefs = settings()
        prefs.edit().putString(StreamLibrary.KEY_SELECTED, id).apply()
        ClockSelectionStore.setWeatherEnabled(prefs, true)
        syncWeatherToggle()
        renderStreams()
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, FILE_REQUEST) }.onFailure {
            android.widget.Toast.makeText(this, "File picker unavailable", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Android document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (!CustomStreamPlayer.validLocal(uri.toString())) return
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
        if (mime != null && !mime.startsWith("audio/") && !mime.startsWith("video/") &&
            mime != "application/octet-stream") {
            android.widget.Toast.makeText(this, "Select an audio or video file", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val granted = runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (!granted) {
            android.widget.Toast.makeText(this, "Persistent file access unavailable", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: "Local file"
        val prefs = settings()
        val station = StreamLibrary.newFile(uri.toString(), name)
        StreamLibrary.save(prefs, StreamLibrary.list(prefs) + station)
        renderStreams()
    }

    private fun editFileName(station: StreamLibrary.Station) {
        val field = android.widget.EditText(this).apply { setSingleLine(true); setText(station.name) }
        AlertDialog.Builder(this).setTitle("Edit file").setView(field)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val name = field.text.toString().trim().take(128).ifBlank { station.name }
                val prefs = settings()
                StreamLibrary.save(prefs, StreamLibrary.list(prefs).map {
                    if (it.id == station.id) it.copy(name = name) else it
                })
                renderStreams()
            }.show()
    }

    private fun editStream(existing: StreamLibrary.Station?) {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val url = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://..."
            setSingleLine(true)
            setText(existing?.url.orEmpty())
        }
        val name = android.widget.EditText(this).apply {
            hint = "Name (automatic)"
            setSingleLine(true)
            setText(existing?.name.orEmpty())
        }
        fields.addView(url)
        fields.addView(name)
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "Add stream" else "Edit stream")
            .setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = url.text.toString().trim()
                if (!CustomStreamPlayer.valid(address)) {
                    url.error = "Enter a direct HTTPS audio URL"
                    return@setOnClickListener
                }
                val entered = name.text.toString().trim().take(64)
                val autoName = entered.isBlank()
                val base = existing ?: StreamLibrary.newStation(address)
                val saved = base.copy(url = address,
                    name = entered.ifBlank { StreamLibrary.defaultName(address) },
                    icon = if (existing?.url == address) base.icon else "")
                val prefs = settings()
                val current = StreamLibrary.list(prefs)
                val next = if (existing == null) current + saved else current.map {
                    if (it.id == existing.id) saved else it
                }
                StreamLibrary.save(prefs, next)
                renderStreams()
                dialog.dismiss()
                // A single bounded metadata lookup per explicit save, not a background polling service.
                Thread {
                    val info = runCatching { StreamMetadata.lookup(address) }.getOrNull()
                    if (info != null) messageHandler.post {
                        val latest = StreamLibrary.list(prefs)
                        val match = latest.firstOrNull { it.id == saved.id }
                        if (match == saved) {
                            val updated = saved.copy(name = if (autoName) info.name ?: saved.name else saved.name,
                                icon = info.icon.ifBlank { saved.icon })
                            StreamLibrary.save(prefs, latest.map { if (it.id == saved.id) updated else it })
                            if (!isDestroyed) renderStreams()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun showSoundTest() {
        val samples = NoticeTest.samples(WeatherStore.locality(this))
        AlertDialog.Builder(this)
            .setTitle("Test sound")
            .setItems(samples.map { it.subtitle }.toTypedArray()) { _, index ->
                val sample = samples[index]
                val preview = NoticeTest.marked(sample)
                locationMessage.text = preview.subtitle
                locationMessage.visibility = View.VISIBLE
                messageHandler.removeCallbacks(hideLocationMessage)
                messageHandler.postDelayed(hideLocationMessage, CityAnnouncement.DURATION_MS)
                soundPreview.stop()
                testAudio.stop()
                // Use the active car service to avoid two simultaneous audio routes.
                if (!NoticeTest.send(sample)) {
                    val prefs = settings()
                    testAudio.play(NoticeSound.fromId(prefs.getString(NoticeSound.KEY, null)),
                        NoticeTest.spoken(sample),
                        PhoneAudioMode.read(prefs) != PhoneAudioMode.OFF,
                        speakEnabled = prefs.getBoolean(NoticeAudio.KEY_SPEAK, true))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestLocation()
        }
    }

    /**
     * Spacious settings row. A compact switch on the right replaces the
     * old checkbox, while the entire row remains a generous touch target.
     */
    private fun checkOption(
        title: String, checked: Boolean,
        captureToggle: ((Switch) -> Unit)? = null,
        bluetoothIcon: Boolean = false,
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
            minimumHeight = dp(60)
            setPadding(0, dp(10), 0, dp(10))
            if (bluetoothIcon) addView(ImageView(this@MainActivity).apply {
                setImageResource(com.kotarov.autoclock.R.drawable.ic_bluetooth_audio)
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
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
                bottomMargin = dp(24)
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

    companion object {
        private const val LOCATION_REQUEST = 7401
        private const val FILE_REQUEST = 7402
    }
}

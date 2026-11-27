package com.kotarov.autoclock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ClipData
import android.net.Uri
import android.view.DragEvent
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.ImageButton
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
import com.kotarov.autoclock.media.PlaybackSpeed
import com.kotarov.autoclock.media.StreamMetadata
import com.kotarov.autoclock.media.MediaPageDirectory
import com.kotarov.autoclock.media.BookNavigation
import com.kotarov.autoclock.media.WebReader
import com.kotarov.autoclock.media.WebTextProvider
import com.kotarov.autoclock.media.NoticeTest
import com.kotarov.autoclock.media.NoticeSoundPlayer
import android.widget.Switch
import com.kotarov.autoclock.artwork.ClockRenderer
import com.kotarov.autoclock.artwork.ArtworkWorkers
import com.kotarov.autoclock.media.AutoClockMediaService
import com.kotarov.autoclock.media.CarProjectionMonitor
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
    private var removeProjectionListener: (() -> Unit)? = null
    private var phoneBrowser: MediaBrowserCompat? = null
    private var phoneController: MediaControllerCompat? = null
    private lateinit var carAudioButton: ImageButton
    private lateinit var sizeButton: ImageButton
    private lateinit var speakButton: ImageButton
    private lateinit var themeButton: ImageButton
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var slowerButton: ImageButton
    private lateinit var fasterButton: ImageButton
    private var transportUiKey: String? = null
    private var pendingPhonePlay: Boolean? = null
    private var pendingPhoneSource: String? = null
    private val playbackCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            syncTransportButtons(state)
            if (BuildConfig.PLAY_MEDIA_MODE) syncWeatherToggle()
        }
        override fun onSessionDestroyed() {
            detachPhoneController()
        }
    }
    private val previewTick = object : Runnable {
        override fun run() {
            updatePreviewTiles()
            messageHandler.postDelayed(this, 60000L - System.currentTimeMillis() % 60000L)
        }
    }
    private val soundPreview by lazy { NoticeSoundPlayer() }
    private val testAudio by lazy { NoticeAudio(this) }
    private lateinit var streamRows: LinearLayout
    private val sourceTitles = mutableMapOf<String, TextView>()
    private var renderedSources: List<StreamLibrary.Station>? = null
    private var highlightedSource: String? = null
    private val weatherPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == StreamLibrary.KEY_LIST || key == StreamLibrary.KEY_SELECTED) {
            if (key == StreamLibrary.KEY_LIST) renderStreams() else highlightSelectedSource()
            syncTransportButtons(phoneController?.playbackState)
        }
        if (key == PhoneAudioMode.KEY || key == NoticeAudio.KEY_SPEAK ||
            key == AutoClockMediaService.KEY_LAYOUT || key == ClockTheme.KEY) {
            syncQuickButtons()
            updatePreviewTiles()
        }
        if (PlaybackSpeed.isSourceKey(key)) syncSpeedControls(phoneController?.playbackState)
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
        // Observe even while the phone panel is in the background, until destroyed.
        removeProjectionListener = CarProjectionMonitor.subscribe(this) { connected ->
            if (!connected && !isFinishing && !isDestroyed) finishAndRemoveTask()
        }
        val prefs = settings()
        // This is now an auto-location-only product, including old installs.
        ClockSelectionStore.migrateSimplifiedIfNeeded(prefs)
        StreamLibrary.migrate(prefs)
        PlaybackSpeed.migrate(prefs)

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
        root.addView(text("SmartTrip Clock", 27f, dp(16)).apply {
            setOnLongClickListener { showPerformanceDiagnostics(); true }
        })

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
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        carAudioButton = quickButton(R.drawable.ic_bluetooth_audio, "Use car phone audio") {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                testAudio.stop()
                PhoneAudioMode.toggle(prefs)
                syncQuickButtons()
            }
        }
        carAudioButton.isEnabled = !BuildConfig.PLAY_MEDIA_MODE && android.os.Build.VERSION.SDK_INT >= 31
        sizeButton = quickButton(R.drawable.ic_setting_size, "Clock size") {
            val compact = prefs.getString(AutoClockMediaService.KEY_LAYOUT, "standard") == "compact"
            prefs.edit().putString(AutoClockMediaService.KEY_LAYOUT,
                if (compact) "standard" else "compact").apply()
            syncQuickButtons()
            updatePreviewTiles()
        }
        speakButton = quickButton(R.drawable.ic_setting_speak, "Speak messages") {
            testAudio.stop()
            prefs.edit().putBoolean(NoticeAudio.KEY_SPEAK,
                !prefs.getBoolean(NoticeAudio.KEY_SPEAK, true)).apply()
            syncQuickButtons()
        }
        val testButton = quickButton(R.drawable.ic_setting_test, "Test") { showSoundTest() }
        themeButton = quickButton(R.drawable.ic_setting_light, "Light theme") {
            val light = ClockTheme.fromId(prefs.getString(ClockTheme.KEY, null)) == ClockTheme.LIGHT
            prefs.edit().putString(ClockTheme.KEY,
                if (light) ClockTheme.DARK.id else ClockTheme.LIGHT.id).apply()
            syncQuickButtons()
            updatePreviewTiles()
        }
        val chimeButton = quickButton(R.drawable.ic_setting_chime, "Alert chime") {
            showChimePicker()
        }
        fun addQuickControl(parent: LinearLayout, button: ImageButton) {
            parent.addView(FrameLayout(this).apply {
                addView(button, FrameLayout.LayoutParams(dp(48), dp(48), android.view.Gravity.CENTER))
            }, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        listOf(sizeButton, themeButton).forEach { button ->
            addQuickControl(quickRow, button)
        }
        // A single subtle outline links the three message-related controls.
        // Equal weights keep the three message controls together.
        val messageControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), faint)
            }
        }
        listOf(speakButton, chimeButton, testButton).forEach { button ->
            addQuickControl(messageControls, button)
        }
        if (!BuildConfig.PLAY_MEDIA_MODE) quickRow.addView(messageControls, LinearLayout.LayoutParams(0, dp(56), 3f))
        addSpaced(root, quickRow)
        syncQuickButtons()

        val transportRow = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        weatherButton = ImageButton(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                setColor(Color.rgb(225, 228, 232))
                setStroke(dp(2), Color.rgb(120, 126, 135))
            }
            setColorFilter(Color.rgb(47, 52, 60))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener {
                val p = settings()
                if (BuildConfig.PLAY_MEDIA_MODE) {
                    val c = phoneController
                    if (c != null) {
                        if (c.playbackState?.state == PlaybackStateCompat.STATE_PLAYING ||
                            c.playbackState?.state == PlaybackStateCompat.STATE_BUFFERING) c.transportControls.pause()
                        else c.transportControls.play()
                    }
                } else {
                    val enabled = !ClockSelectionStore.weatherEnabled(p)
                    val controls = phoneController?.transportControls
                    if (controls != null) {
                        if (enabled) controls.play() else controls.pause()
                    } else {
                        pendingPhoneSource = null
                        pendingPhonePlay = enabled
                        ClockSelectionStore.setWeatherEnabled(p, enabled)
                    }
                }
                syncWeatherToggle()
            }
        }
        backButton = quickButton(R.drawable.ic_replay_10, "Back 10 seconds") {
            phoneController?.transportControls?.sendCustomAction(AutoClockMediaService.ACTION_BACK_10, null)
        }
        forwardButton = quickButton(R.drawable.ic_forward_10, "Forward 10 seconds") {
            phoneController?.transportControls?.sendCustomAction(AutoClockMediaService.ACTION_FORWARD_10, null)
        }
        slowerButton = quickButton(R.drawable.ic_speed_slower, "Decrease playback speed") { changeSpeed(-1) }
        fasterButton = quickButton(R.drawable.ic_speed_faster, "Increase playback speed") { changeSpeed(1) }
        transportRow.addView(weatherButton, LinearLayout.LayoutParams(dp(88), dp(52)))
        listOf(backButton, forwardButton, slowerButton, fasterButton).forEach { button ->
            transportRow.addView(button, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        }
        if (!BuildConfig.PLAY_MEDIA_MODE) transportRow.addView(carAudioButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        addSpaced(root, transportRow)
        syncTransportButtons(null)
        syncWeatherToggle()

        locationMessage = text("", 18f, dp(4)).apply {
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(locationMessage)

        val streams = settingsGroup(root, if (BuildConfig.PLAY_MEDIA_MODE) "Audio library" else "Streams")
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addRow.addView(Button(this).apply {
            text = "Stream"
            setAddIcon(this, R.drawable.ic_add_link)
            isAllCaps = false
            setOnClickListener { editStream(null) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(Button(this).apply {
            text = "File"
            setAddIcon(this, R.drawable.ic_source_file)
            isAllCaps = false
            setOnClickListener { pickFile() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        if (!BuildConfig.PLAY_MEDIA_MODE) addRow.addView(Button(this).apply {
            text = "Folder"
            setAddIcon(this, R.drawable.ic_source_folder)
            isAllCaps = false
            setOnClickListener { pickFolder() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        addSpaced(streams, addRow)
        if (!BuildConfig.PLAY_MEDIA_MODE) {
            val webRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            webRow.addView(Button(this).apply {
                text = "Web Article"
                setAddIcon(this, R.drawable.ic_source_text)
                isAllCaps = false
                setOnClickListener { addWebPage() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            webRow.addView(Button(this).apply {
                text = "Web Media"
                setAddIcon(this, R.drawable.ic_source_web)
                isAllCaps = false
                setOnClickListener { editMediaPage(null) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            addSpaced(streams, webRow)
        }
        streamRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        streams.addView(streamRows)
        renderStreams()

        // Weather attribution stays at the bottom of the phone settings.
        root.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val credit = if (BuildConfig.PLAY_MEDIA_MODE) "Privacy Policy"
            else "Weather data: NOAA / Open-Meteo (CC BY 4.0)\nAdapted for SmartTrip Clock\nPrivacy Policy"
        val attribution = android.text.SpannableString(credit).apply {
            fun link(label: String, url: String) {
                val start = credit.indexOf(label)
                setSpan(android.text.style.URLSpan(url), start, start + label.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (!BuildConfig.PLAY_MEDIA_MODE) {
                link("NOAA", "https://aviationweather.gov/")
                link("Open-Meteo", "https://open-meteo.com/")
                link("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/")
            }
            link("Privacy Policy", if (BuildConfig.PLAY_MEDIA_MODE)
                "https://github.com/skotarov/androidauto-mediaclock/blob/revamp/low-power-v2/PRIVACY-PLAY.md"
                else "https://github.com/skotarov/androidauto-mediaclock/blob/revamp/low-power-v2/PRIVACY.md")
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

    private fun showPerformanceDiagnostics() {
        if (!PerfDiagnostics.enabled) PerfDiagnostics.start()
        val dialog = AlertDialog.Builder(this).setTitle("Performance diagnostics")
            .setMessage(PerfDiagnostics.snapshot())
            .setPositiveButton("Refresh", null).setNeutralButton("Reset", null)
            .setNegativeButton("Stop") { _, _ -> PerfDiagnostics.stop() }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.setMessage(PerfDiagnostics.snapshot()) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                PerfDiagnostics.start()
                dialog.setMessage(PerfDiagnostics.snapshot())
            }
        }
        dialog.show()
    }

    override fun onStart() {
        super.onStart()
        settings().registerOnSharedPreferenceChangeListener(weatherPreferenceListener)
        syncWeatherToggle()
        renderStreams()
        NoticeTest.phoneCaption = phoneCaption
        phoneCaption(NoticeTest.currentCaption)
        phoneBrowser = MediaBrowserCompat(this, ComponentName(this, AutoClockMediaService::class.java),
            object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    val browser = phoneBrowser ?: return
                    if (!browser.isConnected) return
                    detachPhoneController()
                    phoneController = MediaControllerCompat(this@MainActivity, browser.sessionToken).also {
                        it.registerCallback(playbackCallback, messageHandler)
                        syncTransportButtons(it.playbackState)
                        if (BuildConfig.PLAY_MEDIA_MODE) syncWeatherToggle()
                    }
                    val source = pendingPhoneSource
                    val play = pendingPhonePlay
                    pendingPhoneSource = null
                    pendingPhonePlay = null
                    if (source != null) phoneController?.transportControls?.playFromMediaId(source, null)
                    else if (play == true) phoneController?.transportControls?.play()
                    else if (play == false) phoneController?.transportControls?.pause()
                }
                override fun onConnectionSuspended() { detachPhoneController() }
                override fun onConnectionFailed() { detachPhoneController() }
            }, null).also { it.connect() }
        syncQuickButtons()
        messageHandler.removeCallbacks(previewTick)
        messageHandler.post(previewTick)
    }

    override fun onStop() {
        messageHandler.removeCallbacks(hideLocationMessage)
        messageHandler.removeCallbacks(previewTick)
        if (NoticeTest.phoneCaption === phoneCaption) NoticeTest.phoneCaption = null
        detachPhoneController()
        phoneBrowser?.disconnect()
        phoneBrowser = null
        pendingPhonePlay = null
        pendingPhoneSource = null
        if (::locationMessage.isInitialized) locationMessage.visibility = View.GONE
        WeatherStore.stop(WeatherStore.Client.PHONE)
        settings().unregisterOnSharedPreferenceChangeListener(weatherPreferenceListener)
        soundPreview.stop()
        testAudio.stop()
        releasePreviews()
        super.onStop()
    }

    private fun setAddIcon(button: Button, resource: Int) {
        val icon = getDrawable(resource)?.mutate() ?: return
        icon.setBounds(0, 0, dp(20), dp(20))
        button.setCompoundDrawablesRelative(icon, null, null, null)
        button.compoundDrawablePadding = dp(4)
        button.minWidth = 0
        button.minimumWidth = 0
        button.setPadding(dp(6), button.paddingTop, dp(6), button.paddingBottom)
    }

    private fun quickButton(icon: Int, label: String, action: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = label
            if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = label
            setOnClickListener { action() }
            paintQuickButton(this, false)
        }

    private fun paintQuickButton(button: ImageButton, active: Boolean) {
        button.isSelected = active
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) accent else background)
            setStroke(dp(2), if (active) accent else faint)
        }
        button.setColorFilter(Color.WHITE)
        button.alpha = if (button.isEnabled) 1f else 0.35f
    }

    private fun syncQuickButtons() {
        if (!::carAudioButton.isInitialized || !::themeButton.isInitialized) return
        val prefs = settings()
        paintQuickButton(carAudioButton, carAudioButton.isEnabled && PhoneAudioMode.read(prefs) != PhoneAudioMode.OFF)
        paintQuickButton(sizeButton, prefs.getString(AutoClockMediaService.KEY_LAYOUT, "standard") == "standard")
        paintQuickButton(speakButton, prefs.getBoolean(NoticeAudio.KEY_SPEAK, true))
        paintQuickButton(themeButton, ClockTheme.fromId(prefs.getString(ClockTheme.KEY, null)) == ClockTheme.LIGHT)
    }

    private fun syncTransportButtons(state: PlaybackStateCompat?) {
        if (!::backButton.isInitialized || !::forwardButton.isInitialized) return
        val selectedSource = StreamLibrary.selected(settings())
        val alertOnly = selectedSource == null
        val actions = state?.customActions.orEmpty()
        val key = "${selectedSource?.id}|${actions.joinToString { it.action }}|${PlaybackSpeed.read(settings(), selectedSource)}"
        if (transportUiKey == key) return
        transportUiKey = key
        backButton.visibility = if (alertOnly) View.GONE else View.VISIBLE
        forwardButton.visibility = if (alertOnly) View.GONE else View.VISIBLE
        backButton.isEnabled = !alertOnly && actions.any { it.action == AutoClockMediaService.ACTION_BACK_10 }
        forwardButton.isEnabled = !alertOnly && actions.any { it.action == AutoClockMediaService.ACTION_FORWARD_10 }
        paintQuickButton(backButton, false)
        paintQuickButton(forwardButton, false)
        syncSpeedControls(state)
    }

    private fun changeSpeed(direction: Int) {
        val p = settings()
        val source = StreamLibrary.selected(p) ?: return
        val speed = PlaybackSpeed.read(p, source)
        PlaybackSpeed.write(p, source, PlaybackSpeed.step(speed, direction))
    }

    private fun syncSpeedControls(state: PlaybackStateCompat?) {
        if (!::slowerButton.isInitialized || !::fasterButton.isInitialized) return
        val selected = StreamLibrary.selected(settings())
        val known = state?.customActions.orEmpty().any {
            it.action == AutoClockMediaService.ACTION_SPEED_DOWN || it.action == AutoClockMediaService.ACTION_SPEED_UP
        }
        val visible = selected != null && (selected.textFile || selected.local || known)
        slowerButton.visibility = if (visible) View.VISIBLE else View.GONE
        fasterButton.visibility = if (visible) View.VISIBLE else View.GONE
        val speed = PlaybackSpeed.read(settings(), selected)
        slowerButton.isEnabled = visible && speed > PlaybackSpeed.MIN
        fasterButton.isEnabled = visible && speed < PlaybackSpeed.MAX
        paintQuickButton(slowerButton, false)
        paintQuickButton(fasterButton, false)
        // Preserve the original larger play/pause button when speed actions are hidden.
        val playWidth = dp(if (visible && !BuildConfig.PLAY_MEDIA_MODE) 64 else if (visible) 88 else 108)
        if (weatherButton.layoutParams.width != playWidth) {
            weatherButton.layoutParams = weatherButton.layoutParams.apply { width = playWidth }
        }
        // Six controls must fit on a narrow phone, keeping each round.
        val small = dp(if (visible && !BuildConfig.PLAY_MEDIA_MODE) 40 else 48)
        listOf(backButton, forwardButton, slowerButton, fasterButton, carAudioButton).forEach { button ->
            val lp = button.layoutParams ?: return@forEach
            if (lp.width != small || lp.height != small) {
                lp.width = small; lp.height = small; button.layoutParams = lp
                val padding = dp(if (small == dp(40)) 8 else 12)
                button.setPadding(padding, padding, padding, padding)
            }
        }
    }

    private fun detachPhoneController() {
        phoneController?.unregisterCallback(playbackCallback)
        phoneController = null
        syncTransportButtons(null)
    }

    private fun syncWeatherToggle() {
        if (!::weatherButton.isInitialized) return
        val playing = if (BuildConfig.PLAY_MEDIA_MODE) phoneController?.playbackState?.state.let {
            it == PlaybackStateCompat.STATE_PLAYING || it == PlaybackStateCompat.STATE_BUFFERING
        } else ClockSelectionStore.weatherEnabled(settings())
        weatherButton.setImageResource(if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play)
        weatherButton.contentDescription = if (playing) "Pause" else "Play"
        weatherButton.isSelected = playing
        weatherButton.background = GradientDrawable().apply {
            cornerRadius = dp(32).toFloat()
            setColor(if (playing) Color.WHITE else background)
            setStroke(dp(2), if (playing) Color.WHITE else faint)
        }
        weatherButton.setColorFilter(if (playing) Color.rgb(47, 52, 60) else Color.WHITE)
        updatePreviewTiles()
    }

    override fun onResume() {
        super.onResume()
        if (::analogImage.isInitialized) updatePreviewTiles()
        // Every opening of settings starts a current observation refresh.
        if (BuildConfig.PLAY_MEDIA_MODE) return
        if (WeatherLocation.permitted(this)) {
            WeatherStore.start(this, WeatherStore.Client.PHONE)
            requestCallPermissionOnce()
        } else if (!hasRequestedLocation) {
            hasRequestedLocation = true
            requestLocation()
        } else requestCallPermissionOnce()
    }

    private var hasRequestedLocation = false
    private fun requestCallPermissionOnce() {
        if (BuildConfig.PLAY_MEDIA_MODE || checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val prefs = settings()
        if (prefs.getBoolean("call_permission_asked_v118", false)) return
        prefs.edit().putBoolean("call_permission_asked_v118", true).apply()
        requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), CALL_PERMISSION_REQUEST)
    }

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
        val analog = if (BuildConfig.PLAY_MEDIA_MODE) ClockPreset.ANALOG_PURE else ClockSelectionStore.forFamily(prefs, ClockFamily.ANALOG)
        val digital = if (BuildConfig.PLAY_MEDIA_MODE) ClockPreset.DIGITAL_TIME else ClockSelectionStore.forFamily(prefs, ClockFamily.DIGITAL)
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
        val prefs = settings()
        val sources = StreamLibrary.list(prefs)
        if (renderedSources == sources) {
            highlightSelectedSource()
            return
        }
        renderedSources = sources
        streamRows.removeAllViews()
        sourceTitles.clear()
        val selected = StreamLibrary.selectedRootId(prefs)
        highlightedSource = selected
        val alert = text("Alert only", 16f, 0).apply {
            setTextColor(if (selected == StreamLibrary.ALERT_ID) accent else ink)
            setOnClickListener { selectPhoneSource(StreamLibrary.ALERT_ID) }
        }
        sourceTitles[StreamLibrary.ALERT_ID] = alert
        val alertRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            // Match the drag-handle slot and icon size of the stream rows,
            // but keep Alert only fixed and non-draggable.
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(34), dp(48)))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_source_travel)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
            addView(alert, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { selectPhoneSource(StreamLibrary.ALERT_ID) }
        }
        if (!BuildConfig.PLAY_MEDIA_MODE) addSpaced(streamRows, alertRow)
        sources.forEach { station ->
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
                setImageResource(if (station.localFolder) R.drawable.ic_source_folder else if (station.mediaPage) R.drawable.ic_source_web else if (station.textFile) R.drawable.ic_source_text else if (station.local) R.drawable.ic_source_file else R.drawable.ic_source_podcast)
                contentDescription = station.name
                if (station.browsable || station.textFile) setOnClickListener { if (station.browsable) openMediaPage(station) else openBook(station) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
            val title = text(station.name, 16f, 0).apply {
                setTextColor(if (selected == station.id) accent else ink)
                setOnClickListener { if (station.browsable) openMediaPage(station) else if (station.textFile) openBook(station) else selectPhoneSource(station.id) }
            }
            sourceTitles[station.id] = title
            row.addView(title, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Edit"
                isAllCaps = false
                minWidth = 0; minimumWidth = 0
                setOnClickListener { if (station.localFolder) editFileName(station) else if (station.mediaPage) editMediaPage(station) else if (station.local) editFileName(station) else editStream(station) }
            })
            row.addView(Button(this).apply {
                text = "Delete"
                isAllCaps = false
                minWidth = 0; minimumWidth = 0
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle(station.name)
                        .setMessage(if (station.browsable) "Remove folder from list?" else if (station.local) "Remove file from list?" else "Delete stream?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            val prefs = settings()
                            val remaining = StreamLibrary.list(prefs).filterNot { it.id == station.id }
                            StreamLibrary.save(prefs, remaining)
                            if (station.local && remaining.none { it.url == station.url }) {
                                if (WebTextProvider.owns(station.url)) WebTextProvider.delete(this@MainActivity, station.url)
                                else runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(station.url), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            }
                            renderStreams()
                        }.show()
                }
            })
            addSpaced(streamRows, row)
        }
    }

    private fun highlightSelectedSource() {
        val selected = StreamLibrary.selectedRootId(settings())
        if (selected == highlightedSource) return
        highlightedSource?.let { sourceTitles[it]?.setTextColor(ink) }
        selected?.let { sourceTitles[it]?.setTextColor(accent) }
        highlightedSource = selected
    }

    private fun selectPhoneSource(id: String) {
        val prefs = settings()
        if (BuildConfig.PLAY_MEDIA_MODE) {
            phoneController?.transportControls?.playFromMediaId(id, null)
            return
        }
        val controls = phoneController?.transportControls
        if (controls != null) controls.playFromMediaId(id, null)
        else {
            pendingPhoneSource = id
            pendingPhonePlay = true
            if (BookNavigation.parseChoice(id) == null) {
                if (!StreamLibrary.select(prefs, id)) { pendingPhoneSource = null; pendingPhonePlay = null; return }
                ClockSelectionStore.setWeatherEnabled(prefs, true)
            }
        }
        syncWeatherToggle()
        highlightSelectedSource()
    }

    private fun editMediaPage(existing: StreamLibrary.Station?) {
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
            hint = "Folder name (automatic)"
            setSingleLine(true)
            setText(existing?.name.orEmpty())
        }
        fields.addView(url)
        fields.addView(name)
        val dialog = AlertDialog.Builder(this).setTitle("Web Media")
            .setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = url.text.toString().trim()
                if (!WebReader.valid(address)) {
                    url.error = "Enter a public HTTPS page or playlist URL"
                    return@setOnClickListener
                }
                val title = name.text.toString().trim().take(128).ifBlank { StreamLibrary.defaultName(address) }
                val page = existing?.copy(name = title, url = address) ?: StreamLibrary.newMediaPage(address, title)
                val p = settings()
                val current = StreamLibrary.list(p)
                StreamLibrary.save(p, if (existing == null) current + page else current.map { if (it.id == page.id) page else it })
                renderStreams()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun openMediaPage(page: StreamLibrary.Station) {
        var cancelled = false
        val progress = AlertDialog.Builder(this).setTitle(page.name).setMessage("Finding media…")
            .setNegativeButton("Cancel") { _, _ -> cancelled = true }
            .setOnCancelListener { cancelled = true }.create()
        progress.show()
        MediaPageDirectory.load(this, page) { result ->
            if (!cancelled && !isDestroyed && !isFinishing) {
                progress.dismiss()
                val items = result.getOrNull().orEmpty()
                if (items.isEmpty()) AlertDialog.Builder(this).setTitle(page.name)
                    .setMessage(if (page.localFolder) "No files found, or folder unavailable." else "No direct playable media found, or page unavailable.")
                    .setPositiveButton("OK", null).show()
                else AlertDialog.Builder(this).setTitle(page.name)
                    .setItems((if (page.localFolder) listOf("▶ Play folder") + items.map { it.name } else items.map { it.name + " · " + it.format }).toTypedArray()) { _, index ->
                        val item = items[if (page.localFolder) (index - 1).coerceAtLeast(0) else index]
                        val book = if (item.textFile && !(page.localFolder && index == 0)) StreamLibrary.book(settings(), item.id) else null
                        if (book != null) openBook(book) else selectPhoneSource(item.id)
                    }.setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun openBook(book: StreamLibrary.Station) {
        if (BuildConfig.PLAY_MEDIA_MODE || !BookNavigation.mayHaveContents(book.name)) {
            selectPhoneSource(book.id)
            return
        }
        var cancelled = false
        val progress = AlertDialog.Builder(this).setTitle(book.name).setMessage("Finding chapters…")
            .setNegativeButton("Cancel") { _, _ -> cancelled = true }
            .setOnCancelListener { cancelled = true }.create()
        progress.show()
        BookNavigation.load(this, book) { chapters ->
            if (!cancelled && !isDestroyed && !isFinishing) {
                progress.dismiss()
                if (chapters.isEmpty()) selectPhoneSource(book.id)
                else {
                    val choices = BookNavigation.choices(book, chapters)
                    AlertDialog.Builder(this).setTitle(book.name)
                        .setItems(choices.map { it.title }.toTypedArray()) { _, i -> selectPhoneSource(choices[i].id) }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        }
    }

    private var webImport: Thread? = null

    private fun addWebPage() {
        if (webImport != null) return
        val field = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://..."
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Web Article")
            .setView(field).setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = field.text.toString().trim()
                if (!WebReader.valid(address)) {
                    field.error = "Enter a public HTTPS page URL"
                    return@setOnClickListener
                }
                dialog.dismiss()
                val progress = AlertDialog.Builder(this).setTitle("Reader Mode")
                    .setMessage("Extracting article text…").setCancelable(false)
                    .setNegativeButton("Cancel", null).create()
                val worker = Thread({
                    val result = runCatching { WebReader.fetch(address) }
                    val self = Thread.currentThread()
                    messageHandler.post {
                        if (webImport === self) {
                            webImport = null
                            if (!isDestroyed && !isFinishing) {
                                progress.dismiss()
                                result.fold(onSuccess = { page ->
                                    runCatching {
                                        val uri = WebTextProvider.save(this, page.text)
                                        try {
                                            val prefs = settings()
                                            StreamLibrary.save(prefs, StreamLibrary.list(prefs) +
                                                StreamLibrary.newFile(uri, page.title, textFile = true))
                                        } catch (e: Exception) {
                                            WebTextProvider.delete(this, uri)
                                            throw e
                                        }
                                        renderStreams()
                                    }.onFailure {
                                        AlertDialog.Builder(this).setTitle("Reader Mode")
                                            .setMessage("Could not save article. Check available storage.")
                                            .setPositiveButton("OK", null).show()
                                    }
                                }, onFailure = {
                                    AlertDialog.Builder(this).setTitle("Reader Mode")
                                        .setMessage("No readable article found, or the page is unavailable. Try a public HTTPS article without a login.")
                                        .setPositiveButton("OK", null).show()
                                })
                            }
                        }
                    }
                }, "AutoClockWebReader")
                webImport = worker
                progress.setOnShowListener {
                    progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        if (webImport === worker) { webImport = null; worker.interrupt() }
                        progress.dismiss()
                    }
                }
                progress.show()
                worker.start()
            }
        }
        dialog.show()
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, FOLDER_REQUEST) }.onFailure {
            android.widget.Toast.makeText(this, "Folder picker unavailable", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun addLocalFolder(uri: Uri) {
        val granted = runCatching {
            require(android.provider.DocumentsContract.isTreeUri(uri))
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (!granted) {
            android.widget.Toast.makeText(this, "Persistent folder access unavailable", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val name = runCatching {
            val id = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val root = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, id)
            contentResolver.query(root, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: id.substringAfterLast('/').substringAfterLast(':')
        }.getOrNull().orEmpty().ifBlank { "Folder" }
        val p = settings()
        val current = StreamLibrary.list(p)
        if (current.none { it.localFolder && it.url == uri.toString() }) {
            StreamLibrary.save(p, current + StreamLibrary.newFolder(uri.toString(), name.take(128)))
            renderStreams()
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, FILE_REQUEST) }.onFailure {
            android.widget.Toast.makeText(this, "File picker unavailable", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Android document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == FOLDER_REQUEST) { data?.data?.let { addLocalFolder(it) }; return }
        if (requestCode != FILE_REQUEST) return
        val uri = data?.data ?: return
        if (!CustomStreamPlayer.validLocal(uri.toString())) return
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: "Local file"
        val textFile = com.kotarov.autoclock.media.BookFormat.detect(name, mime) != null
        val granted = runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (!granted) {
            android.widget.Toast.makeText(this, "Persistent file access unavailable", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val prefs = settings()
        val station = StreamLibrary.newFile(uri.toString(), name, textFile)
        StreamLibrary.save(prefs, StreamLibrary.list(prefs) + station)
        renderStreams()
    }

    private fun editFileName(station: StreamLibrary.Station) {
        val field = android.widget.EditText(this).apply { setSingleLine(true); setText(station.name) }
        val languages = listOf("auto" to "Automatic", "bg" to "Bulgarian", "en" to "English",
            "ru" to "Russian", "uk" to "Ukrainian", "de" to "German", "fr" to "French", "es" to "Spanish")
        val language = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, languages.map { it.second })
            setSelection(languages.indexOfFirst { it.first == station.language }.coerceAtLeast(0))
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(field)
            if (station.textFile) {
                addView(text("Reading language", 14f, dp(8)))
                addView(language)
            }
        }
        AlertDialog.Builder(this).setTitle("Edit file").setView(fields)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val name = field.text.toString().trim().take(128).ifBlank { station.name }
                val prefs = settings()
                StreamLibrary.save(prefs, StreamLibrary.list(prefs).map {
                    if (it.id == station.id) it.copy(name = name,
                        language = if (station.textFile) languages[language.selectedItemPosition.coerceAtLeast(0)].first else it.language) else it
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
            hint = "https:// or http:// (public audio)"
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
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "Stream" else "Edit stream")
            .setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = url.text.toString().trim()
                if (!CustomStreamPlayer.valid(address)) {
                    url.error = "Enter a direct HTTP(S) audio URL. HTTP must have no credentials or query parameters."
                    return@setOnClickListener
                }
                val entered = name.text.toString().trim().take(64)
                val autoName = entered.isBlank()
                val base = existing ?: StreamLibrary.newStation(address)
                val saved = base.copy(url = address,
                    name = entered.ifBlank { StreamLibrary.defaultName(address) },
                    icon = "")
                val prefs = settings()
                val current = StreamLibrary.list(prefs)
                val next = if (existing == null) current + saved else current.map {
                    if (it.id == existing.id) saved else it
                }
                StreamLibrary.save(prefs, next)
                renderStreams()
                dialog.dismiss()
                // A single bounded metadata lookup per explicit save, not a background polling service.
                if (autoName) Thread {
                    val info = runCatching { StreamMetadata.lookup(address) }.getOrNull()
                    if (info != null) messageHandler.post {
                        val latest = StreamLibrary.list(prefs)
                        val match = latest.firstOrNull { it.id == saved.id }
                        if (match == saved) {
                            val updated = saved.copy(name = info.name ?: saved.name, icon = "")
                            StreamLibrary.save(prefs, latest.map { if (it.id == saved.id) updated else it })
                            if (!isDestroyed) renderStreams()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun showChimePicker() {
        val prefs = settings()
        val options = NoticeSound.entries
        val selected = NoticeSound.fromId(prefs.getString(NoticeSound.KEY, null))
        AlertDialog.Builder(this)
            .setTitle("Alert chime")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(),
                options.indexOf(selected)) { dialog, index ->
                val sound = options[index]
                prefs.edit().putString(NoticeSound.KEY, sound.id).apply()
                soundPreview.play(sound)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        if (requestCode == CALL_PERMISSION_REQUEST) {
            settings().edit().putLong(com.kotarov.autoclock.media.CallInterruptionMonitor.PERMISSION_REFRESH,
                android.os.SystemClock.elapsedRealtime()).apply()
            return
        }
        if (requestCode != LOCATION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestLocation()
        }
        requestCallPermissionOnce()
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
        webImport?.interrupt()
        webImport = null
        removeProjectionListener?.invoke()
        removeProjectionListener = null
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
        private const val CALL_PERMISSION_REQUEST = 7403
        private const val FILE_REQUEST = 7402
        private const val FOLDER_REQUEST = 7404
    }
}

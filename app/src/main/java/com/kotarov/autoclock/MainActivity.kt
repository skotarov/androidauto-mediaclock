package com.kotarov.autoclock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ClipData
import android.net.Uri
import android.view.DragEvent
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.os.SystemClock
import com.kotarov.autoclock.media.PhoneSeekPosition
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
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.ViewFlipper
import kotlin.math.abs
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
import com.kotarov.autoclock.media.LibraryMediaKind
import com.kotarov.autoclock.media.MediaDownload
import com.kotarov.autoclock.media.DownloadLocation
import com.kotarov.autoclock.media.PlaybackSpeed
import com.kotarov.autoclock.media.RemotePlaybackBookmarks
import com.kotarov.autoclock.media.StreamMetadata
import com.kotarov.autoclock.media.MediaPageDirectory
import com.kotarov.autoclock.media.MediaPageParser
import com.kotarov.autoclock.media.BookNavigation
import com.kotarov.autoclock.media.CarLibraryTabs
import com.kotarov.autoclock.media.FolderNavigation
import com.kotarov.autoclock.media.WebReader
import com.kotarov.autoclock.media.WebTextProvider
import com.kotarov.autoclock.media.NoticeTest
import com.kotarov.autoclock.media.NoticeSoundPlayer
import android.widget.Switch
import com.kotarov.autoclock.artwork.ClockRenderer
import com.kotarov.autoclock.artwork.ArtworkWorkers
import com.kotarov.autoclock.media.AutoClockMediaService
import android.text.TextUtils
import android.text.TextWatcher
import android.text.Editable
import android.widget.PopupMenu
import android.widget.ProgressBar
import com.kotarov.autoclock.media.ClockFamily
import com.kotarov.autoclock.media.ClockPreset
import com.kotarov.autoclock.media.ClockSelectionStore
import com.kotarov.autoclock.media.ClockTheme
import com.kotarov.autoclock.weather.WeatherLocation
import com.kotarov.autoclock.weather.WeatherStore
import com.kotarov.autoclock.weather.ForecastStore
import java.time.LocalDateTime

/**
 * Compact phone settings. Swipe through live clock previews; only tapping
 * a card selects that clock family for Android Auto.
 */
class MainActivity : Activity() {
    private lateinit var analogCard: LinearLayout
    private lateinit var digitalCard: LinearLayout
    private lateinit var analogImage: ImageView
    private lateinit var digitalImage: ImageView
    private lateinit var clockPager: ClockSwipePager
    private lateinit var clockPageDots: List<View>
    private lateinit var weatherButton: android.widget.ImageButton
    private lateinit var playerTitle: TextView
    private lateinit var playerDetail: TextView
    private lateinit var playerProgress: ProgressBar
    private lateinit var playerExpand: TextView
    private var playerExpanded = false
    private lateinit var playerDock: LinearLayout
    private lateinit var playerPanel: LinearLayout
    private lateinit var playerTransport: LinearLayout
    private var fullClockDialog: android.app.Dialog? = null
    private var fullClockImage: ImageView? = null
    private var pendingDownload: MediaDownload.Spec? = null
    private var pendingDownloadMode: String? = null
    private lateinit var downloadLocationButton: Button
    private class DownloadJob {
        @Volatile var cancelled = false
        var worker: Thread? = null
    }
    private var downloadJob: DownloadJob? = null
    private var libraryQuery = ""
    private var libraryFilter = 0
    private var renderedLibraryKey: String? = null
    private var lastRecentSource: String? = null
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
    private lateinit var seekPanel: LinearLayout
    private lateinit var positionSlider: SeekBar
    private lateinit var elapsedLabel: TextView
    private lateinit var durationLabel: TextView
    private var seekState: PlaybackStateCompat? = null
    private var seekMetadata: MediaMetadataCompat? = null
    private var seekSource: String? = null
    private var seekDuration = 0L
    private var dragSource: String? = null
    private var dragDuration = 0L
    private var seekHoldUntil = 0L
    private val seekTick = Runnable { syncPhoneSeek() }
    private var pendingPhonePlay: Boolean? = null
    private var pendingPhoneSource: String? = null
    private val playbackCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            syncTransportButtons(state)
            seekState = state
            syncMiniPlayer()
            syncWeatherToggle()
            syncPhoneSeek()
        }
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            seekMetadata = metadata
            syncMiniPlayer()
            syncPhoneSeek()
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
    private var renderedAlertPin: Boolean? = null
    private var highlightedSource: String? = null
    private val weatherPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == StreamLibrary.KEY_LIST || key == StreamLibrary.KEY_SELECTED || key == CarLibraryTabs.KEY_ALERT_PIN) {
            if (key != StreamLibrary.KEY_SELECTED) renderStreams() else highlightSelectedSource()
            syncTransportButtons(phoneController?.playbackState)
            syncMiniPlayer()
            syncPhoneSeek()
        }
        if (key == PhoneAudioMode.KEY || key == NoticeAudio.KEY_SPEAK ||
            key == AutoClockMediaService.KEY_LAYOUT || key == ClockTheme.KEY) {
            syncQuickButtons()
            updatePreviewTiles()
        }
        if (PlaybackSpeed.isSourceKey(key)) {
            syncSpeedControls(phoneController?.playbackState)
            syncMiniPlayer()
        }
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
        PlaybackSpeed.migrate(prefs)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(24))
            setBackgroundColor(this@MainActivity.background)
        }
        playerExpanded = savedInstanceState?.getBoolean("playerExpanded", false) ?: false
        libraryFilter = savedInstanceState?.getInt("libraryFilter", 0)?.coerceIn(0, 2) ?: 0
        libraryQuery = savedInstanceState?.getString("libraryQuery").orEmpty()
        savedInstanceState?.let { state ->
            pendingDownloadMode = state.getString("downloadLocationMode")
            val url = state.getString("downloadUrl").orEmpty()
            val name = state.getString("downloadName").orEmpty()
            val mime = state.getString("downloadMime").orEmpty()
            val length = state.getLong("downloadLength", 0)
            if (pendingDownloadMode != null && MediaDownload.offer(url) && name.isNotBlank() && mime.isNotBlank() && length > 0)
                pendingDownload = MediaDownload.Spec(url, name, mime, length)
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        shell.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val player = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(8))
            setBackgroundColor(Color.rgb(18, 20, 27))
        }
        shell.addView(View(this).apply { setBackgroundColor(faint) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        playerDock = shell
        playerPanel = player
        shell.addView(player, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(shell)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            shell.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                insets
            }
            shell.requestApplyInsets()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_auto_clock)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(6)
            })
            addView(text(getString(R.string.app_name), 27f, 0).apply {
                setOnLongClickListener { showPerformanceDiagnostics(); true }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(header)

        // The page is only a preview; the blue outline still indicates the
        // persisted selection. Keep the existing click listeners unchanged.
        val (analog, analogPreview) =
            createChoiceCard("Analog", ClockFamily.ANALOG)
        val (digital, digitalPreview) =
            createChoiceCard("Digital", ClockFamily.DIGITAL)
        analogCard = analog
        digitalCard = digital
        analogImage = analogPreview
        digitalImage = digitalPreview
        clockPager = ClockSwipePager(this).apply {
            addView(analogCard, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(digitalCard, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        root.addView(clockPager, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val pageIndicators = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(6), 0, dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        clockPageDots = List(2) {
            View(this).also { dot ->
                pageIndicators.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                })
            }
        }
        root.addView(pageIndicators)
        root.addView(text("Full screen", 12f, dp(4)).apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.rgb(185, 190, 199))
            minimumHeight = dp(36)
            setOnClickListener { showFullClock() }
        })
        clockPager.onPageChanged = { page -> updateClockPageDots(page) }
        showSelectedClockPage()
        if (savedInstanceState?.getBoolean("fullClockOpen") == true)
            root.post { if (!isFinishing && !isDestroyed) showFullClock() }
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
        fun addQuickControl(parent: LinearLayout, button: ImageButton, label: String) {
            val control = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                addView(FrameLayout(this@MainActivity).apply {
                    addView(button, FrameLayout.LayoutParams(dp(48), dp(48), android.view.Gravity.CENTER))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
                addView(text(label, 10f, 0).apply {
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.rgb(185, 190, 199))
                    maxLines = 1
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setOnClickListener { button.performClick() }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            parent.addView(control, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        addQuickControl(quickRow, sizeButton, "Size")
        addQuickControl(quickRow, themeButton, "Theme")

        // Link the three message controls through their button centres.
        // Draw behind the opaque circular buttons so only the gaps show a line.
        val messageControls = FrameLayout(this)
        val connector = View(this).apply {
            setBackgroundColor(faint)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        messageControls.addView(connector, FrameLayout.LayoutParams(0, dp(1), android.view.Gravity.TOP or android.view.Gravity.LEFT).apply {
            topMargin = dp(28)
        })
        val messageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        addQuickControl(messageRow, speakButton, "Speak")
        addQuickControl(messageRow, chimeButton, "Chime")
        addQuickControl(messageRow, testButton, "Test")
        messageControls.addView(messageRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        messageControls.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            if (width > 0 && (width != oldRight - oldLeft || connector.layoutParams.width == 0)) {
                connector.layoutParams = (connector.layoutParams as FrameLayout.LayoutParams).apply {
                    this.width = width * 2 / 3
                    leftMargin = width / 6
                }
            }
        }
        if (!BuildConfig.PLAY_MEDIA_MODE) quickRow.addView(messageControls,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f))
        addSpaced(root, quickRow)
        syncQuickButtons()

        val playerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(42)
            isClickable = true
            isFocusable = true
            contentDescription = "Expand or collapse player"
            setOnClickListener { setPlayerExpanded(!playerExpanded) }
            var downY = 0f
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downY = event.y; true }
                    MotionEvent.ACTION_UP -> {
                        val delta = event.y - downY
                        if (abs(delta) > dp(28)) setPlayerExpanded(delta < 0)
                        else view.performClick()
                        true
                    }
                    else -> true
                }
            }
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        playerTitle = text("Alert only", 15f, 0).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        playerDetail = text("", 12f, 0).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.rgb(185, 190, 199))
        }
        labels.addView(playerTitle)
        labels.addView(playerDetail)
        playerHeader.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        playerExpand = text("⌃", 23f, 0).apply {
            gravity = android.view.Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        playerHeader.addView(playerExpand, LinearLayout.LayoutParams(dp(40), dp(42)))
        player.addView(playerHeader)
        playerProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PhoneSeekPosition.STEPS
            progressTintList = ColorStateList.valueOf(accent)
            visibility = View.GONE
        }
        player.addView(playerProgress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(4)))
        addPhoneSeekPanel(player)

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
            setPadding(0, 0, 0, 0)
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
                    val current = phoneController?.playbackState?.state
                    val playing = if (current != null) current == PlaybackStateCompat.STATE_PLAYING ||
                        current == PlaybackStateCompat.STATE_BUFFERING else pendingPhonePlay == true
                    val enabled = !playing
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
        listOf(slowerButton, backButton, weatherButton, forwardButton, fasterButton).forEach { button ->
            transportRow.addView(button, LinearLayout.LayoutParams(
                dp(if (button === weatherButton) 64 else 48), dp(48)).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
        }
        if (!BuildConfig.PLAY_MEDIA_MODE) transportRow.addView(carAudioButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(3) })
        playerTransport = transportRow
        transportRow.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) resizePlayerControls()
        }
        player.addView(transportRow)
        syncMiniPlayer()
        syncTransportButtons(null)
        syncWeatherToggle()

        locationMessage = text("", 18f, dp(4)).apply {
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(locationMessage)

        val streams = settingsGroup(root, if (BuildConfig.PLAY_MEDIA_MODE) "Audio library" else "Library")
        val libraryTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val search = android.widget.EditText(this).apply {
            hint = "Search library"
            setSingleLine(true)
            textSize = 15f
            setText(libraryQuery)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    libraryQuery = s?.toString().orEmpty()
                    renderStreams()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        libraryTools.addView(search, LinearLayout.LayoutParams(0, dp(48), 1f))
        libraryTools.addView(quickButton(R.drawable.ic_speed_faster, "Add to library") { showAddLibrary() },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        streams.addView(libraryTools)
        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        listOf("All", "Favorites", "Recent").forEachIndexed { index, label ->
            text(label, 14f, 0).apply {
                gravity = android.view.Gravity.CENTER
                setTextColor(if (libraryFilter == index) accent else ink)
                isSelected = libraryFilter == index
                setOnClickListener {
                    libraryFilter = index
                    for (i in 0 until filters.childCount) {
                        (filters.getChildAt(i) as TextView).apply {
                            setTextColor(if (i == index) accent else ink)
                            isSelected = i == index
                        }
                    }
                    renderStreams()
                }
                filters.addView(this, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
        }
        streams.addView(filters)
        // Do not open the keyboard merely by launching the app.
        root.isFocusableInTouchMode = true
        root.requestFocus()
        streamRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        streams.addView(streamRows)
        renderStreams()

        val storage = settingsGroup(root, "Downloads")
        downloadLocationButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener { chooseDownloadLocation() }
        }
        storage.addView(downloadLocationButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        syncDownloadLocation()
        // Weather attribution stays at the bottom of the phone settings.
        root.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(Button(this).apply {
            text = getString(R.string.about_title)
            isAllCaps = false
            setOnClickListener { showAbout() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            topMargin = dp(20)
        })
        val credit = if (BuildConfig.PLAY_MEDIA_MODE) "Privacy Policy"
            else "Weather data: NOAA / Open-Meteo (CC BY 4.0)\nAdapted for Travel to Awaken\nPrivacy Policy"
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

    private fun showAbout() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_auto_clock)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(88), dp(88)))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 24f
                gravity = android.view.Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(6))
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.about_tagline)
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.ITALIC)
                setPadding(0, 0, 0, dp(20))
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.about_message)
                textSize = 16f
                setLineSpacing(dp(4).toFloat(), 1f)
            })
        }
        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
        showSelectedClockPage()
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
                        seekState = it.playbackState
                        seekMetadata = it.metadata
                    }
                    syncWeatherToggle()
                    syncMiniPlayer()
                    syncPhoneSeek()
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
        syncMiniPlayer()
        val key = "${selectedSource?.id}|${actions.joinToString { it.action }}|${PlaybackSpeed.read(settings(), selectedSource)}"
        if (transportUiKey == key) return
        transportUiKey = key
        backButton.isEnabled = !alertOnly && actions.any { it.action == AutoClockMediaService.ACTION_BACK_10 }
        forwardButton.isEnabled = !alertOnly && actions.any { it.action == AutoClockMediaService.ACTION_FORWARD_10 }
        backButton.visibility = if (backButton.isEnabled) View.VISIBLE else View.GONE
        forwardButton.visibility = if (forwardButton.isEnabled) View.VISIBLE else View.GONE
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
        resizePlayerControls()
    }

    private fun resizePlayerControls() {
        if (!::playerTransport.isInitialized) return
        val buttons = listOf(backButton, forwardButton, slowerButton, fasterButton, carAudioButton)
        val count = buttons.count { it.parent === playerTransport && it.visibility == View.VISIBLE }.coerceAtLeast(1)
        val available = playerTransport.width.takeIf { it > 0 } ?: dp(resources.configuration.screenWidthDp - 24)
        val small = ((available - dp(64 + 6 + count * 6)) / count).coerceIn(dp(28), dp(48))
        buttons.forEach { button ->
            val lp = button.layoutParams ?: return@forEach
            if (lp.width != small || lp.height != small) {
                lp.width = small; lp.height = small; button.layoutParams = lp
                val padding = ((small - dp(24)) / 2).coerceAtLeast(dp(2))
                button.setPadding(padding, padding, padding, padding)
            }
        }
    }

    private fun orderPlayerControls(full: Boolean) {
        if (!::playerTransport.isInitialized) return
        val buttons = if (full) listOf(weatherButton, backButton, forwardButton, slowerButton, fasterButton)
            else listOf(slowerButton, backButton, weatherButton, forwardButton, fasterButton)
        playerTransport.removeAllViews()
        (buttons + if (BuildConfig.PLAY_MEDIA_MODE) emptyList() else listOf(carAudioButton)).forEach { button ->
            playerTransport.addView(button, LinearLayout.LayoutParams(
                dp(if (button === weatherButton) 64 else 48), dp(48)).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
        }
        resizePlayerControls()
    }

    private fun addPhoneSeekPanel(root: LinearLayout) {
        elapsedLabel = text("0:00", 11f, 0)
        durationLabel = text("0:00", 11f, 0).apply {
            gravity = android.view.Gravity.END
            setTextColor(Color.rgb(185, 190, 199))
        }
        positionSlider = SeekBar(this).apply {
            max = PhoneSeekPosition.STEPS
            contentDescription = "Playback position"
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {
                    dragSource = seekSource
                    dragDuration = seekDuration
                    seekHoldUntil = 0L
                }
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val duration = if (dragSource != null) dragDuration else seekDuration
                    elapsedLabel.text = PhoneSeekPosition.format(PhoneSeekPosition.position(progress, duration))
                    // Accessibility and keyboard changes don't start a touch gesture.
                    if (dragSource == null) commitPhoneSeek(seekSource, duration, progress)
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    val source = dragSource
                    val duration = dragDuration
                    dragSource = null
                    commitPhoneSeek(source, duration, bar.progress)
                    syncPhoneSeek()
                }
            })
        }
        seekPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(positionSlider, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), 0, dp(16), dp(8))
                addView(elapsedLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(durationLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        root.addView(seekPanel)
    }

    private fun commitPhoneSeek(id: String?, duration: Long, progress: Int) {
        if (id == null || id != seekSource || duration != seekDuration ||
            id != StreamLibrary.selected(settings())?.id || duration <= 0L) return
        val controller = phoneController ?: return
        if ((seekState?.actions ?: 0L) and PlaybackStateCompat.ACTION_SEEK_TO == 0L) return
        seekHoldUntil = SystemClock.elapsedRealtime() + 1000L
        controller.transportControls.seekTo(PhoneSeekPosition.position(progress, duration))
    }

    private fun syncPhoneSeek() {
        messageHandler.removeCallbacks(seekTick)
        if (!::seekPanel.isInitialized) return
        val source = StreamLibrary.selected(settings())
        val metadata = seekMetadata
        val state = seekState
        val duration = metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L
        val id = metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
        val valid = phoneController != null && source != null && id == source.id &&
            duration > 0L && state != null && state.position >= 0L &&
            (state.actions and PlaybackStateCompat.ACTION_SEEK_TO) != 0L
        if (!valid || state == null) {
            seekPanel.visibility = View.GONE
            if (::playerProgress.isInitialized) playerProgress.visibility = View.GONE
            seekSource = null
            seekDuration = 0L
            dragSource = null
            seekHoldUntil = 0L
            return
        }
        if (seekSource != id || seekDuration != duration) {
            dragSource = null
            seekHoldUntil = 0L
        }
        seekSource = id
        seekDuration = duration
        val expanded = playerExpanded || fullClockDialog != null
        seekPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        playerProgress.visibility = if (expanded) View.GONE else View.VISIBLE
        durationLabel.text = (if (source?.textFile == true) "≈ " else "") + PhoneSeekPosition.format(duration)
        val now = SystemClock.elapsedRealtime()
        val advancing = state.state == PlaybackStateCompat.STATE_PLAYING && state.playbackSpeed > 0f
        if (dragSource == null && now >= seekHoldUntil) {
            val position = PhoneSeekPosition.current(state.position, duration, state.lastPositionUpdateTime,
                now, if (advancing) state.playbackSpeed else 0f)
            positionSlider.progress = PhoneSeekPosition.progress(position, duration)
            playerProgress.progress = positionSlider.progress
            elapsedLabel.text = PhoneSeekPosition.format(position)
        }
        // Only while the phone panel is connected, and only for a moving or pending slider.
        if (advancing || now < seekHoldUntil) messageHandler.postDelayed(seekTick, 1000L)
    }

    private fun detachPhoneController() {
        phoneController?.unregisterCallback(playbackCallback)
        phoneController = null
        seekState = null
        seekMetadata = null
        syncPhoneSeek()
        syncTransportButtons(null)
        syncMiniPlayer()
    }

    private fun syncWeatherToggle() {
        if (!::weatherButton.isInitialized) return
        val state = phoneController?.playbackState?.state
        val playing = if (state != null) state == PlaybackStateCompat.STATE_PLAYING ||
            state == PlaybackStateCompat.STATE_BUFFERING else pendingPhonePlay == true
        weatherButton.setImageResource(if (playing) R.drawable.ic_transport_pause
            else R.drawable.ic_transport_play)
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
                        preset.id, now, compact, outputSize = 512,
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
                        val oldAnalog = analogBitmap
                        val oldDigital = digitalBitmap
                        analogBitmap = result.analog
                        digitalBitmap = result.digital
                        updateFullClockImage()
                        oldAnalog?.recycle()
                        oldDigital?.recycle()
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
        updateFullClockImage()
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

    private fun syncMiniPlayer() {
        if (!::playerTitle.isInitialized || !::playerExpand.isInitialized) return
        val prefs = settings()
        val source = StreamLibrary.selected(prefs)
        val state = phoneController?.playbackState ?: seekState
        val playing = state?.state == PlaybackStateCompat.STATE_PLAYING ||
            state?.state == PlaybackStateCompat.STATE_BUFFERING
        val name = source?.name ?: if (BuildConfig.PLAY_MEDIA_MODE) "Select audio" else "Alert only"
        val kind = when {
            source == null -> if (BuildConfig.PLAY_MEDIA_MODE) "Audio" else "Voice and weather alerts"
            source.textFile -> "Text reading"
            source.local -> "Local audio"
            else -> "Stream"
        }
        val rate = if (source != null && (source.textFile || source.local ||
                state?.customActions.orEmpty().any { it.action == AutoClockMediaService.ACTION_SPEED_UP }))
            " · " + String.format(java.util.Locale.ROOT, "%.1f×", PlaybackSpeed.read(prefs, source)) else ""
        playerTitle.text = name
        playerTitle.maxLines = if (playerExpanded || fullClockDialog != null) 3 else 1
        playerDetail.text = kind + rate + if (playing) " · Playing" else " · Paused"
        playerExpand.text = if (playerExpanded) "⌄" else "⌃"
        playerExpand.visibility = if (fullClockDialog != null) View.GONE else View.VISIBLE
        if (playing && source != null) {
            val rootId = StreamLibrary.selectedRootId(prefs)
            if (rootId != lastRecentSource) {
                lastRecentSource = rootId
                val recent = (listOf(rootId) + recentSources().filterNot { it == rootId }).take(40)
                prefs.edit().putString("phone_library_recent", recent.joinToString("\n")).apply()
                if (libraryFilter == 2) renderStreams()
            }
        }
    }

    private fun setPlayerExpanded(expanded: Boolean) {
        playerExpanded = expanded
        syncMiniPlayer()
        syncPhoneSeek()
    }

    private fun showFullClock() {
        if (fullClockDialog?.isShowing == true) return
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(this@MainActivity.background)
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val keepOn = Switch(this).apply {
            text = "Keep screen on"
            isChecked = settings().getBoolean("phone_full_clock_keep_on", false)
            setOnCheckedChangeListener { _, checked ->
                settings().edit().putBoolean("phone_full_clock_keep_on", checked).apply()
                if (checked) dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        controls.addView(keepOn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(Button(this).apply {
            text = "Close"
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        })
        frame.addView(controls)
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Selected live clock"
        }
        fullClockImage = image
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val body = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
        }
        frame.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(image, if (landscape) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        fullClockDialog = dialog
        playerDock.removeView(playerPanel)
        body.addView(playerPanel, if (landscape) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        orderPlayerControls(true)
        syncMiniPlayer()
        syncPhoneSeek()
        dialog.setContentView(frame)
        dialog.setOnDismissListener {
            fullClockImage?.setImageDrawable(null)
            fullClockImage = null
            fullClockDialog = null
            (playerPanel.parent as? android.view.ViewGroup)?.removeView(playerPanel)
            playerDock.addView(playerPanel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            orderPlayerControls(false)
            syncMiniPlayer()
            syncPhoneSeek()
        }
        dialog.show()
        dialog.window?.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT)
        if (keepOn.isChecked) dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateFullClockImage()
        updatePreviewTiles()
    }

    private fun updateFullClockImage() {
        fullClockImage?.setImageBitmap(if (ClockSelectionStore.current(settings()).family == ClockFamily.ANALOG)
            analogBitmap else digitalBitmap)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("fullClockOpen", fullClockDialog?.isShowing == true)
        outState.putBoolean("playerExpanded", playerExpanded)
        outState.putInt("libraryFilter", libraryFilter)
        outState.putString("libraryQuery", libraryQuery)
        outState.putString("downloadLocationMode", pendingDownloadMode)
        pendingDownload?.let {
            outState.putString("downloadUrl", it.url)
            outState.putString("downloadName", it.name)
            outState.putString("downloadMime", it.mime)
            outState.putLong("downloadLength", it.length)
        }
        super.onSaveInstanceState(outState)
    }

    private fun showAddLibrary() {
        val options = if (BuildConfig.PLAY_MEDIA_MODE) arrayOf("Add URL", "Select file")
            else arrayOf("Add URL", "Select file", "Select folder")
        AlertDialog.Builder(this).setTitle("Add to library").setItems(options) { _, index ->
            when (index) {
                0 -> if (BuildConfig.PLAY_MEDIA_MODE) editStream(null) else addUrl()
                1 -> pickFile()
                2 -> pickFolder()
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun favoriteSources(): Set<String> {
        val p = settings()
        if (!p.contains("phone_library_favorites")) {
            val initial = StreamLibrary.list(p).filter { it.pinned }.map { it.id }.toMutableSet()
            if (p.getBoolean(CarLibraryTabs.KEY_ALERT_PIN, false)) initial.add(StreamLibrary.ALERT_ID)
            p.edit().putStringSet("phone_library_favorites", initial).apply()
        }
        return p.getStringSet("phone_library_favorites", emptySet()).orEmpty().toSet()
    }

    private fun recentSources(): List<String> =
        settings().getString("phone_library_recent", "").orEmpty().lines().filter { it.isNotBlank() }

    private fun favoriteButton(id: String): TextView {
        val favorite = id in favoriteSources()
        return text(if (favorite) "★" else "☆", 23f, 0).apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(if (favorite) Color.rgb(255, 190, 0) else ink)
            contentDescription = if (favorite) "Remove from favorites" else "Add to favorites"
            setOnClickListener {
                val next = favoriteSources().toMutableSet()
                if (!next.add(id)) next.remove(id)
                settings().edit().putStringSet("phone_library_favorites", next).apply()
                renderStreams()
            }
        }
    }

    private fun toggleCarPin(id: String) {
        val p = settings()
        val latest = StreamLibrary.list(p)
        val alertPinned = p.getBoolean(CarLibraryTabs.KEY_ALERT_PIN, false)
        if (id == StreamLibrary.ALERT_ID) {
            if (!alertPinned && latest.count { it.pinned } >= CarLibraryTabs.MAX_TABS - 1) {
                android.widget.Toast.makeText(this, "Up to 3 pinned tabs. Unpin another first.", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            p.edit().putBoolean(CarLibraryTabs.KEY_ALERT_PIN, !alertPinned).apply()
        } else {
            val updated = CarLibraryTabs.toggle(latest, id, alertPinned)
            if (updated == null) {
                android.widget.Toast.makeText(this, "Up to 3 pinned tabs. Unpin another first.", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            StreamLibrary.save(p, updated)
        }
        renderStreams()
    }

    private fun renderStreams() {
        if (!::streamRows.isInitialized) return
        val prefs = settings()
        val sources = StreamLibrary.list(prefs)
        val alertPinned = prefs.getBoolean(CarLibraryTabs.KEY_ALERT_PIN, false)
        val favorites = favoriteSources()
        val recent = recentSources()
        val query = libraryQuery.trim()
        val key = "${libraryFilter}|${query}|${favorites.sorted()}|${if (libraryFilter == 2) recent else emptyList<String>()}"
        if (renderedSources == sources && renderedAlertPin == alertPinned && renderedLibraryKey == key) {
            highlightSelectedSource()
            return
        }
        renderedSources = sources
        renderedAlertPin = alertPinned
        renderedLibraryKey = key
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
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(24), dp(44)))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_source_alert)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
            addView(alert, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(favoriteButton(StreamLibrary.ALERT_ID), LinearLayout.LayoutParams(dp(36), dp(44)))
            addView(text("⋮", 24f, 0).apply {
                gravity = android.view.Gravity.CENTER
                contentDescription = "Alert only options"
                setOnClickListener { anchor ->
                    PopupMenu(this@MainActivity, anchor).apply {
                        menu.add(if (alertPinned) "Unpin from car tabs" else "Pin to car tabs")
                            .setOnMenuItemClickListener { toggleCarPin(StreamLibrary.ALERT_ID); true }
                        show()
                    }
                }
            }, LinearLayout.LayoutParams(dp(40), dp(44)))
            setOnClickListener { selectPhoneSource(StreamLibrary.ALERT_ID) }
        }
        if (!BuildConfig.PLAY_MEDIA_MODE && libraryFilter != 2 &&
            (libraryFilter != 1 || StreamLibrary.ALERT_ID in favorites) &&
            "Alert only".contains(query, ignoreCase = true)) streamRows.addView(alertRow)
        val visibleSources = sources.filter { station ->
            station.name.contains(query, ignoreCase = true) && when (libraryFilter) {
                1 -> station.id in favorites
                2 -> station.id in recent
                else -> true
            }
        }.let { if (libraryFilter == 2) it.sortedBy { station -> recent.indexOf(station.id) } else it }
        visibleSources.forEach { station ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                if (libraryFilter == 0 && query.isEmpty()) setOnDragListener { view, event ->
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
            if (libraryFilter == 0 && query.isEmpty()) row.addView(text("☰", 18f, 0).apply {
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
            }, LinearLayout.LayoutParams(dp(24), dp(44)))
            val downloaded = station.downloadSourceUrl != null
            val mediaIcon = FrameLayout(this).apply {
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(LibraryMediaKind.station(station).icon)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(32), dp(32), android.view.Gravity.CENTER))
                if (downloaded) addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_download_badge)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(17), dp(17),
                    android.view.Gravity.BOTTOM or android.view.Gravity.END))
                contentDescription = station.name + if (downloaded) ", Downloaded" else ""
                if (station.browsable || station.textFile) setOnClickListener {
                    if (station.browsable) openMediaPage(station) else openBook(station)
                }
            }
            row.addView(mediaIcon, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) })
            val title = text(station.name, 15f, 0).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (selected == station.id) accent else ink)
                setOnClickListener { if (station.browsable) openMediaPage(station) else if (station.textFile) openBook(station) else selectPhoneSource(station.id) }
            }
            sourceTitles[station.id] = title
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(text(LibraryMediaKind.station(station).label +
                    (if (station.downloadSourceUrl != null) " · Downloaded" else "") +
                    (if (station.pinned) " · Car tab" else ""), 11f, 0).apply {
                    setTextColor(Color.rgb(185, 190, 199))
                    maxLines = 1
                })
            }
            row.addView(details, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(favoriteButton(station.id), LinearLayout.LayoutParams(dp(36), dp(44)))
            row.addView(text("⋮", 24f, 0).apply {
                gravity = android.view.Gravity.CENTER
                contentDescription = "Options: " + station.name
                setOnClickListener { anchor ->
                    PopupMenu(this@MainActivity, anchor).apply {
                        menu.add("Edit").setOnMenuItemClickListener {
                            if (station.localFolder) editFileName(station) else if (station.mediaPage) editMediaPage(station)
                            else if (station.local) editFileName(station) else editStream(station)
                            true
                        }
                        if (!station.local && !station.browsable && MediaDownload.offer(station.url))
                            menu.add("Download").setOnMenuItemClickListener { requestMediaDownload(station.url, station.name); true }
                        if (station.textFile && WebTextProvider.owns(station.url)) {
                            menu.add("Refresh").setOnMenuItemClickListener { refreshArticle(station); true }
                            if (station.articleUrl != null) menu.add("Open original").setOnMenuItemClickListener {
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(station.articleUrl))) }
                                    .onFailure { android.widget.Toast.makeText(this@MainActivity, "No browser available", android.widget.Toast.LENGTH_SHORT).show() }
                                true
                            }
                        }
                        if (!BuildConfig.PLAY_MEDIA_MODE && CarLibraryTabs.eligible(station))
                            menu.add(if (station.pinned) "Unpin from car tabs" else "Pin to car tabs")
                                .setOnMenuItemClickListener { toggleCarPin(station.id); true }
                        menu.add("Delete").setOnMenuItemClickListener { deleteLibrarySource(station); true }
                        show()
                    }
                }
            }, LinearLayout.LayoutParams(dp(40), dp(44)))
            row.setOnClickListener { title.performClick() }
            streamRows.addView(row)
            streamRows.addView(View(this).apply { setBackgroundColor(Color.rgb(42, 45, 54)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        }
        if (streamRows.childCount == 0) streamRows.addView(text("No matching items", 14f, dp(12)))
    }

    private fun deleteLibrarySource(station: StreamLibrary.Station) {
        AlertDialog.Builder(this).setTitle(station.name)
            .setMessage(if (station.downloadSourceUrl != null) "Delete the downloaded file from storage and remove it from the library? The original online link is kept."
                else if (station.browsable) "Remove folder from list?" else if (station.local) "Remove file from list?" else "Delete stream?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val prefs = settings()
                val current = StreamLibrary.list(prefs)
                val entry = current.firstOrNull { it.id == station.id && it.url == station.url }
                    ?: return@setPositiveButton
                val remaining = current.filterNot { it.id == entry.id }
                val lastReference = remaining.none { it.url == entry.url }
                if (entry.downloadSourceUrl != null && lastReference) {
                    val uri = Uri.parse(entry.url)
                    // Only delete documents created by our explicit Download action.
                    val deleted = runCatching {
                        android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
                    }.getOrDefault(false)
                    if (!deleted) {
                        showDownloadedFileDeleteError(entry)
                        return@setPositiveButton
                    }
                }
                if (entry.downloadSourceUrl != null && !lastReference) {
                    // Preserve download ownership on another reference to this exact URI.
                    val index = remaining.indexOfFirst { it.url == entry.url && it.local && !it.browsable }
                    val updated = if (index >= 0 && remaining[index].downloadSourceUrl == null)
                        remaining.mapIndexed { i, other -> if (i == index) other.copy(downloadSourceUrl = entry.downloadSourceUrl) else other }
                    else remaining
                    removeLibraryEntry(entry, updated)
                } else removeLibraryEntry(entry, remaining)
            }.show()
    }

    private fun showDownloadedFileDeleteError(station: StreamLibrary.Station) {
        AlertDialog.Builder(this).setTitle("File not deleted")
            .setMessage("Android did not allow deleting this downloaded file. It may have been saved by an older version with read-only access, removed already, or the storage may be unavailable. Delete it with your file manager, then remove the library entry if needed.")
            .setNegativeButton("Keep entry", null)
            .setPositiveButton("Remove entry only") { _, _ ->
                val entries = StreamLibrary.list(settings())
                val current = entries.firstOrNull { it.id == station.id && it.url == station.url }
                    ?: return@setPositiveButton
                removeLibraryEntry(current, entries.filterNot { it.id == current.id })
            }.show()
    }

    private fun removeLibraryEntry(station: StreamLibrary.Station, remaining: List<StreamLibrary.Station>) {
        val prefs = settings()
        StreamLibrary.save(prefs, remaining)
        prefs.edit().putStringSet("phone_library_favorites", favoriteSources() - station.id)
            .putString("phone_library_recent", recentSources().filterNot { it == station.id }.joinToString("\n")).apply()
        RemotePlaybackBookmarks(this).removeRoot(station.id)
        if (station.local && remaining.none { it.url == station.url }) {
            if (WebTextProvider.owns(station.url)) WebTextProvider.delete(this, station.url)
            else {
                val uri = Uri.parse(station.url)
                val modes = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { contentResolver.releasePersistableUriPermission(uri, modes) }
            }
        }
        renderStreams()
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
            if (BookNavigation.parseChoice(id) == null && FolderNavigation.parse(id) == null) {
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
                else {
                    val choices = if (page.localFolder && !BuildConfig.PLAY_MEDIA_MODE) FolderNavigation.choices(page.id) else emptyList()
                    val rows = choices.map { Triple(it.title, "Folder playback", R.drawable.ic_source_folder) } +
                        items.map { item ->
                            val kind = LibraryMediaKind.item(item, page.localFolder)
                            Triple(item.name, kind.label + " · " + item.format, kind.icon)
                        }
                    val adapter = object : android.widget.BaseAdapter() {
                        override fun getCount() = rows.size
                        override fun getItem(position: Int) = rows[position]
                        override fun getItemId(position: Int) = position.toLong()
                        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                            val (name, detail, icon) = rows[position]
                            return LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                setPadding(dp(16), dp(8), dp(16), dp(8))
                                addView(ImageView(this@MainActivity).apply { setImageResource(icon) },
                                    LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })
                                addView(LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    addView(text(name, 15f, 0))
                                    addView(text(detail, 12f, 0).apply { setTextColor(Color.rgb(185, 190, 199)) })
                                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                                val item = items.getOrNull(position - choices.size)
                                if (!page.localFolder && item != null && MediaDownload.offer(item.url)) {
                                    addView(text("⋮", 24f, 0).apply {
                                        gravity = android.view.Gravity.CENTER
                                        contentDescription = "Options: " + item.name
                                        isFocusable = false
                                        setOnClickListener { anchor ->
                                            PopupMenu(this@MainActivity, anchor).apply {
                                                menu.add("Download").setOnMenuItemClickListener {
                                                    requestMediaDownload(item.url, item.name)
                                                    true
                                                }
                                                show()
                                            }
                                        }
                                    }, LinearLayout.LayoutParams(dp(40), dp(44)))
                                }
                            }
                        }
                    }
                    AlertDialog.Builder(this).setTitle(page.name).setAdapter(adapter) { _, index ->
                        if (index < choices.size) selectPhoneSource(choices[index].id)
                        else {
                            val item = items[index - choices.size]
                            val book = if (item.textFile) StreamLibrary.book(settings(), item.id) else null
                            if (book != null) openBook(book) else selectPhoneSource(item.id)
                        }
                    }.setNegativeButton("Cancel", null).show()
                }
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

    private fun addUrl() {
        if (webImport != null) return
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val url = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://..."
            setSingleLine(true)
        }
        val name = android.widget.EditText(this).apply {
            hint = "Name (automatic)"
            setSingleLine(true)
        }
        fields.addView(url)
        fields.addView(name)
        val dialog = AlertDialog.Builder(this).setTitle("Add URL").setView(fields)
            .setNegativeButton("Cancel", null).setPositiveButton("Add", null)
            .setNeutralButton("Choose type", null).create()
        dialog.setOnShowListener {
            fun submit(manual: Boolean) {
                val address = url.text.toString().trim()
                if (!CustomStreamPlayer.valid(address)) {
                    url.error = "Enter a valid HTTP(S) URL"
                    return
                }
                val title = name.text.toString().trim().take(128)
                dialog.dismiss()
                if (manual) chooseUrlType(address, title, null, false)
                else runUrlWork("Checking URL…", { MediaPageParser.inspect(address) }) { result ->
                    val info = result.getOrNull()
                    val suggested = info?.suggested().orEmpty()
                    if (suggested.size == 1) saveUrlType(address, title, suggested.single(), info)
                    else chooseUrlType(address, title, info, true)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit(false) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { submit(true) }
        }
        dialog.show()
    }

    private fun chooseUrlType(address: String, name: String, info: MediaPageParser.Inspection?, checked: Boolean) {
        val options = if (WebReader.valid(address)) listOf("Read article", "Browse media", "Stream") else listOf("Stream")
        val suggested = info?.suggested().orEmpty()
        val ordered = (suggested.filter { it in options } + options).distinct()
        val heading = if (checked && suggested.isEmpty()) "URL not recognized — choose type" else "Choose URL type"
        AlertDialog.Builder(this).setTitle(heading)
            .setItems(ordered.toTypedArray()) { _, index -> saveUrlType(address, name, ordered[index], info) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun saveUrlType(address: String, name: String, type: String, info: MediaPageParser.Inspection?) {
        when (type) {
            "Read article" -> {
                if (!WebReader.valid(address)) return
                val cached = info?.article
                if (cached != null) saveArticle(cached, name, address)
                else runUrlWork("Extracting article text…", { WebReader.fetch(address) }) { result ->
                    result.fold(onSuccess = { saveArticle(it, name, address) }, onFailure = {
                        AlertDialog.Builder(this).setTitle("Reader Mode")
                            .setMessage("No readable article found, or the page is unavailable.")
                            .setPositiveButton("OK", null).show()
                    })
                }
            }
            "Browse media" -> {
                if (!WebReader.valid(address)) return
                saveLibrarySource(StreamLibrary.newMediaPage(address, name.ifBlank { StreamLibrary.defaultName(address) }))
            }
            "Stream" -> saveLibrarySource(StreamLibrary.newStation(address).copy(
                name = name.ifBlank { info?.streamName ?: StreamLibrary.defaultName(address) }))
        }
    }

    private fun saveLibrarySource(station: StreamLibrary.Station) {
        val prefs = settings()
        StreamLibrary.save(prefs, StreamLibrary.list(prefs) + station)
        renderStreams()
    }

    private fun saveArticle(page: WebReader.Page, name: String, address: String) {
        runCatching {
            val uri = WebTextProvider.save(this, page.text)
            try {
                saveLibrarySource(StreamLibrary.newFile(uri, name.ifBlank { page.title }, textFile = true).copy(articleUrl = address))
            } catch (e: Exception) {
                WebTextProvider.delete(this, uri)
                throw e
            }
        }.onFailure {
            AlertDialog.Builder(this).setTitle("Reader Mode")
                .setMessage("Could not save article. Check available storage.")
                .setPositiveButton("OK", null).show()
        }
    }

    private fun refreshArticle(station: StreamLibrary.Station) {
        if (webImport != null) return
        val address = station.articleUrl
        if (address != null && WebReader.valid(address)) {
            fetchArticleReplacement(station, address)
            return
        }
        // Older imports stored only the extracted text. Let the user attach
        // the missing original URL without deleting favorites or the entry.
        val input = android.widget.EditText(this).apply {
            hint = "https://..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Original article URL")
            .setMessage("This older article has no saved original address. Enter it to refresh the text.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Refresh", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = input.text.toString().trim()
                if (!WebReader.valid(url)) {
                    input.error = "Enter a valid HTTPS address"
                    return@setOnClickListener
                }
                dialog.dismiss()
                fetchArticleReplacement(station, url)
            }
        }
        dialog.show()
    }

    private fun fetchArticleReplacement(station: StreamLibrary.Station, address: String) {
        runUrlWork("Refreshing article…", { WebReader.fetch(address) }) { result ->
            result.fold(onSuccess = { page ->
                val prefs = settings()
                val entries = StreamLibrary.list(prefs)
                val old = entries.firstOrNull { it.id == station.id && it.url == station.url }
                    ?: return@fold
                runCatching {
                    val uri = WebTextProvider.save(this, page.text)
                    val updated = old.copy(url = uri, articleUrl = address)
                    try {
                        // A new URI prevents an existing TTS reader from reusing
                        // stale text and offsets. Preserve source-specific speed.
                        PlaybackSpeed.write(prefs, updated, PlaybackSpeed.read(prefs, old))
                        StreamLibrary.save(prefs, entries.map { if (it.id == old.id) updated else it })
                    } catch (e: Exception) {
                        WebTextProvider.delete(this, uri)
                        throw e
                    }
                    if (entries.none { it.id != old.id && it.url == old.url }) {
                        WebTextProvider.delete(this, old.url)
                        prefs.edit().remove(PlaybackSpeed.sourceKey(old.url)).apply()
                        getSharedPreferences("text_bookmarks", MODE_PRIVATE).edit().remove(old.url).apply()
                    }
                    renderStreams()
                    android.widget.Toast.makeText(this, "Article refreshed", android.widget.Toast.LENGTH_SHORT).show()
                }.onFailure { showArticleRefreshError() }
            }, onFailure = { showArticleRefreshError() })
        }
    }

    private fun showArticleRefreshError() {
        AlertDialog.Builder(this).setTitle("Reader Mode")
            .setMessage("Could not refresh the article. The previously saved text is still available.")
            .setPositiveButton("OK", null).show()
    }

    private fun <T> runUrlWork(message: String, work: () -> T, done: (Result<T>) -> Unit) {
        if (webImport != null) return
        val progress = AlertDialog.Builder(this).setTitle("Add URL")
            .setMessage(message).setCancelable(false).setNegativeButton("Cancel", null).create()
        val worker = Thread({
            val result = runCatching { work() }
            val self = Thread.currentThread()
            messageHandler.post {
                if (webImport === self) {
                    webImport = null
                    if (!isDestroyed && !isFinishing) {
                        progress.dismiss()
                        done(result)
                    }
                }
            }
        }, "AutoClockUrlImport")
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

    private fun requestMediaDownload(address: String, name: String) {
        if (downloadJob != null || pendingDownload != null || pendingDownloadMode != null || webImport != null) return
        if (!MediaDownload.offer(address)) return
        runUrlWork("Checking downloadable media…", { MediaDownload.inspect(address, name) }) { result ->
            result.fold(onSuccess = { spec ->
                val mode = DownloadLocation.selected(settings())
                val tree = DownloadLocation.tree(settings(), mode)
                if (tree != null && DownloadLocation.hasAccess(contentResolver, tree, mode)) {
                    createMediaDownload(spec, tree)
                } else {
                    AlertDialog.Builder(this).setTitle("Download location")
                        .setMessage("Allow access to Music on the selected storage once. Future downloads go to Music/Travel to Awaken automatically.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Allow") { _, _ ->
                            pendingDownload = spec
                            openDownloadTree(mode)
                        }.show()
                }
            }, onFailure = {
                showDownloadError("This link does not provide a downloadable audio or video file with a known size. Live radio and playlists remain streaming only.")
            })
        }
    }

    private fun syncDownloadLocation() {
        if (!::downloadLocationButton.isInitialized) return
        val mode = DownloadLocation.selected(settings())
        val tree = DownloadLocation.tree(settings(), mode)
        val ready = tree != null && DownloadLocation.hasAccess(contentResolver, tree, mode)
        val name = if (mode == DownloadLocation.CARD) "SD card" else "Internal storage"
        downloadLocationButton.text = "Download location: $name" +
            if (ready) "\nMusic/Travel to Awaken" else "\nTap to authorize"
    }

    private fun chooseDownloadLocation() {
        if (downloadJob != null || pendingDownload != null || pendingDownloadMode != null || webImport != null) return
        val modes = arrayOf(DownloadLocation.INTERNAL, DownloadLocation.CARD)
        val selected = modes.indexOf(DownloadLocation.selected(settings())).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Download location")
            .setSingleChoiceItems(arrayOf("Internal storage", "SD card"), selected) { dialog, index ->
                dialog.dismiss()
                val mode = modes[index]
                val tree = DownloadLocation.tree(settings(), mode)
                if (tree != null && DownloadLocation.hasAccess(contentResolver, tree, mode)) {
                    DownloadLocation.remember(settings(), mode, tree)
                    syncDownloadLocation()
                } else openDownloadTree(mode)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun openDownloadTree(mode: String) {
        val volume = if (android.os.Build.VERSION.SDK_INT >= 24) {
            val storage = getSystemService(android.os.storage.StorageManager::class.java)
            if (mode == DownloadLocation.CARD)
                storage?.storageVolumes?.firstOrNull { it.isRemovable && !it.isPrimary && it.state == android.os.Environment.MEDIA_MOUNTED }
            else storage?.primaryStorageVolume
        } else null
        if (mode == DownloadLocation.CARD && android.os.Build.VERSION.SDK_INT >= 24 && volume == null) {
            pendingDownload = null
            showDownloadError("No writable SD card found. Insert a card or select internal storage.")
            return
        }
        val volumeId = if (mode == DownloadLocation.INTERNAL) "primary" else volume?.uuid
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            addFlags(DownloadLocation.MODES or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            if (android.os.Build.VERSION.SDK_INT >= 26 && volumeId != null)
                putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, DownloadLocation.initialUri(volumeId))
        }
        pendingDownloadMode = mode
        runCatching { startActivityForResult(picker, DOWNLOAD_REQUEST) }.onFailure {
            pendingDownloadMode = null
            pendingDownload = null
            showDownloadError("Folder authorization is unavailable.")
        }
    }

    private fun createMediaDownload(spec: MediaDownload.Spec, tree: Uri) {
        // The persisted tree grant covers all documents created below it;
        // never request a separate file permission for each download.
        runCatching { DownloadLocation.create(contentResolver, tree, spec) }
            .onSuccess { uri -> saveMediaDownload(spec, uri) }
            .onFailure {
                showDownloadError("Cannot create the file in Music/Travel to Awaken. Check storage availability or authorize the location again.")
            }
    }

    private fun showDownloadError(message: String) {
        if (isDestroyed || isFinishing) return
        AlertDialog.Builder(this).setTitle("Download").setMessage(message)
            .setPositiveButton("OK", null).show()
    }

    private fun removeIncompleteDownload(uri: Uri): Boolean {
        val removed = runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }.getOrDefault(false)
        runCatching { contentResolver.releasePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        return removed
    }

    private fun saveMediaDownload(spec: MediaDownload.Spec, uri: Uri) {
        if (!CustomStreamPlayer.validLocal(uri.toString())) return
        val resolver = contentResolver
        val job = DownloadJob()
        downloadJob = job
        val info = text("Preparing…", 14f, dp(8))
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            addView(info)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12)))
        }
        val dialog = AlertDialog.Builder(this).setTitle(spec.name)
            .setView(fields).setCancelable(false).setNegativeButton("Cancel", null).create()
        val worker = Thread({
            val result = runCatching {
                MediaDownload.save(resolver, uri, spec, { job.cancelled }) { bytes, total ->
                    messageHandler.post {
                        if (downloadJob === job && !job.cancelled && !isDestroyed) {
                            bar.progress = (bytes.toDouble() / total * 100).toInt().coerceIn(0, 100)
                            info.text = String.format(java.util.Locale.ROOT, "%.1f / %.1f MB",
                                bytes / 1048576.0, total / 1048576.0)
                        }
                    }
                }
            }
            messageHandler.post {
                if (downloadJob === job) downloadJob = null
                if (!isDestroyed && !isFinishing) dialog.dismiss()
                if (job.cancelled || result.isFailure || isDestroyed || isFinishing) {
                    val removed = removeIncompleteDownload(uri)
                    if (!isDestroyed && !isFinishing) {
                        if (!removed) showDownloadError("Download incomplete. Please remove the partial file from the selected folder.")
                        else if (!job.cancelled) showDownloadError("Download failed. No incomplete file was kept.")
                    }
                } else {
                    val actualName = runCatching {
                        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                            null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    }.getOrNull()?.ifBlank { spec.name } ?: spec.name
                    val station = StreamLibrary.newFile(uri.toString(), actualName)
                        .copy(downloadSourceUrl = spec.url)
                    val prefs = settings()
                    val original = StreamLibrary.list(prefs).firstOrNull { !it.local && it.url == spec.url }
                    if (original != null) PlaybackSpeed.write(prefs, station, PlaybackSpeed.read(prefs, original))
                    saveLibrarySource(station)
                    android.widget.Toast.makeText(this, "Saved to selected folder and added to library", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, "AutoClockMediaDownload")
        job.worker = worker
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                job.cancelled = true
                worker.interrupt()
                info.text = "Cancelling…"
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            }
        }
        dialog.show()
        worker.start()
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
        if (requestCode == DOWNLOAD_REQUEST) {
            val spec = pendingDownload
            val mode = pendingDownloadMode
            pendingDownload = null
            pendingDownloadMode = null
            if (resultCode != RESULT_OK || mode == null) return
            val tree = data?.data ?: return
            if (!DownloadLocation.validTree(tree, mode)) {
                showDownloadError("Select the Music folder on the chosen storage, or Music/Travel to Awaken.")
                return
            }
            val granted = runCatching {
                contentResolver.takePersistableUriPermission(tree, DownloadLocation.MODES)
            }.isSuccess
            if (!granted || !DownloadLocation.hasAccess(contentResolver, tree, mode)) {
                showDownloadError("Persistent folder access was not granted.")
                return
            }
            DownloadLocation.remember(settings(), mode, tree)
            syncDownloadLocation()
            if (spec != null) createMediaDownload(spec, tree)
            return
        }
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

    private fun showSelectedClockPage() {
        if (!::clockPager.isInitialized) return
        val page = if (ClockSelectionStore.current(settings()).family == ClockFamily.ANALOG) 0 else 1
        clockPager.showPage(page, animate = false)
        updateClockPageDots(page)
    }

    private fun updateClockPageDots(page: Int) {
        if (!::clockPageDots.isInitialized) return
        clockPageDots.forEachIndexed { index, dot ->
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (index == page) accent else faint)
            }
        }
    }

    /** Lightweight two-page square carousel. A horizontal drag cancels the
     * child's click; a vertical drag remains available to the outer ScrollView. */
    private class ClockSwipePager(context: Context) : ViewFlipper(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val swipeDistance = maxOf(touchSlop * 2, (32 * resources.displayMetrics.density).toInt())
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        var onPageChanged: ((Int) -> Unit)? = null

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY))
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    if (dx > touchSlop && dx > dy) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!dragging) return super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (abs(dx) >= swipeDistance && abs(dx) > abs(event.y - downY)) {
                        val target = displayedChild + if (dx < 0) 1 else -1
                        if (target in 0 until childCount) showPage(target, animate = true)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }

        fun showPage(page: Int, animate: Boolean) {
            if (page !in 0 until childCount) return
            if (page == displayedChild) return
            for (index in 0 until childCount) getChildAt(index).clearAnimation()
            if (animate) {
                val direction = if (page > displayedChild) 1f else -1f
                fun slide(from: Float, to: Float) = TranslateAnimation(
                    Animation.RELATIVE_TO_SELF, from, Animation.RELATIVE_TO_SELF, to,
                    Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f
                ).apply { duration = 180L }
                inAnimation = slide(direction, 0f)
                outAnimation = slide(0f, -direction)
            } else {
                inAnimation = null
                outAnimation = null
            }
            displayedChild = page
            onPageChanged?.invoke(page)
        }
    }

    /** Native square measure: thumbnail borders frame the entire drawing. */
    private class SquareFrame(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY))
        }
    }

    override fun onDestroy() {
        downloadJob?.let { it.cancelled = true; it.worker?.interrupt() }
        fullClockDialog?.dismiss()
        webImport?.interrupt()
        webImport = null
        messageHandler.removeCallbacks(hideLocationMessage)
        releasePreviews()
        super.onDestroy()
    }

    private fun releasePreviews() {
        previewWorker.cancel()
        requestedPreview = null
        if (::analogImage.isInitialized) analogImage.setImageDrawable(null)
        if (::digitalImage.isInitialized) digitalImage.setImageDrawable(null)
        fullClockImage?.setImageDrawable(null)
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
        private const val DOWNLOAD_REQUEST = 7405
    }
}

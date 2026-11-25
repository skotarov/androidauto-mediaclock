package com.kotarov.autoclock.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Typeface
import com.kotarov.autoclock.media.ClockPreset
import com.kotarov.autoclock.media.ClockFamily
import com.kotarov.autoclock.media.ClockTheme
import com.kotarov.autoclock.weather.ForecastStore
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * Three main visual styles with zero, one or two independent additions.
 * Art is a single RGB_565 bitmap, refreshed only when the minute or
 * selected settings change. The foreground never animates every second.
 */
object ClockRenderer {
    const val SIZE = 512
    const val MEDIA_BITMAP_SIZE = 320
    private const val CENTER_X = 256f
    // The large analog dial uses the original v0.29/v0.30 geometry except
    // for a minimal 6px downward adjustment to prevent top-rim cropping.
    // No padding, scaling or horizontal translation is applied.
    private const val CONTENT_CENTER_Y = 190f
    // Slightly lower the pure digital face; analog and weather-only remain.
    private const val DIGITAL_CENTER_DOWN = 10f
    private const val DIGITAL_TIME_SIZE = 96f
    // All digital forecast measurements are in the main artwork coordinate
    // space; no separate scaled forecast canvas.
    private const val DIGITAL_FORECAST_TOP = 44f
    private const val DIGITAL_FORECAST_PITCH = 51f
    private const val DIGITAL_FORECAST_CITY_OFFSET = 115f
    private const val COMPACT_ANALOG_CENTER_Y = 208f
    // Original standard Y=216 with radius 122. Restore original near-top
    // placement, adding only 6px clearance: Y=222, top=100 instead of 94.
    private const val STANDARD_ANALOG_CENTER_Y = 222f
    private const val COMPACT_RADIUS = 96f
    private const val STANDARD_RADIUS = 122f
    // Keep the analog forecast at the same size and vertical position with
    // or without the optional digital HH:mm. Compact / standard use slightly
    // different offsets to match the size of the dial in Android Auto.
    private val tickX = FloatArray(60) { cos(Math.toRadians(it * 6.0 - 90)).toFloat() }
    private val tickY = FloatArray(60) { sin(Math.toRadians(it * 6.0 - 90)).toFloat() }
    // A forecast describes the interval starting at its hour, not the dial mark.
    // Place it exactly halfway to the next hour (+15 degrees clockwise).
    private const val STANDALONE_TEMPERATURE_RADIUS = 1.19f
    private val forecastX = FloatArray(12) { cos(Math.toRadians(it * 30.0 - 75)).toFloat() }
    private val forecastY = FloatArray(12) { sin(Math.toRadians(it * 30.0 - 75)).toFloat() }
    private fun themed(color: Int, theme: ClockTheme): Int =
        if (theme == ClockTheme.DARK) color else color xor 0x00ffffff
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val boldFace by lazy { Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    private val condensedBoldFace by lazy { Typeface.create("sans-serif-condensed", Typeface.BOLD) }
    private data class DialLines(val major: FloatArray, val minor: FloatArray)
    private fun dialLines(radius: Float, centreY: Float): DialLines {
        val unit = radius / STANDARD_RADIUS
        val major = FloatArray(12 * 4)
        val minor = FloatArray(48 * 4)
        var majorIndex = 0
        var minorIndex = 0
        repeat(60) { index ->
            val isMajor = index % 5 == 0
            val inner = radius - if (isMajor) 26f * unit else 17f * unit
            val outer = radius - 7f * unit
            val array = if (isMajor) major else minor
            val at = if (isMajor) majorIndex else minorIndex
            array[at] = CENTER_X + tickX[index] * inner
            array[at + 1] = centreY + tickY[index] * inner
            array[at + 2] = CENTER_X + tickX[index] * outer
            array[at + 3] = centreY + tickY[index] * outer
            if (isMajor) majorIndex += 4 else minorIndex += 4
        }
        return DialLines(major, minor)
    }
    private val standardLines by lazy { dialLines(STANDARD_RADIUS, STANDARD_ANALOG_CENTER_Y) }
    private val compactLines by lazy { dialLines(COMPACT_RADIUS, COMPACT_ANALOG_CENTER_Y) }
    private val white = Color.rgb(255, 255, 255)
    private val pale = Color.rgb(224, 239, 247)
    private val cyan = Color.rgb(108, 218, 255)

    fun render(
        presetId: String,
        time: LocalDateTime,
        compact: Boolean = false,
        outputSize: Int = SIZE,
        sourceLabel: String? = null,
        theme: ClockTheme = ClockTheme.DARK,
        forecast: List<ForecastStore.Hour> = emptyList()
    ): Bitmap {
        require(outputSize in 192..SIZE) { "Unsupported artwork size" }
        val bitmap = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.scale(outputSize.toFloat() / SIZE, outputSize.toFloat() / SIZE)
        canvas.drawColor(themed(Color.rgb(2, 5, 11), theme))

        val preset = ClockPreset.fromId(presetId)
        val radius = if (compact) COMPACT_RADIUS else STANDARD_RADIUS
        val unit = radius / STANDARD_RADIUS
        val clockY = if (preset.hasAnalog) {
            if (compact) COMPACT_ANALOG_CENTER_Y else STANDARD_ANALOG_CENTER_Y
        } else CONTENT_CENTER_Y + DIGITAL_CENTER_DOWN

        if (preset.hasAnalog) {
            drawDial(canvas, time, radius, clockY, theme)
            if (preset.usesWeather) drawForecastRing(canvas, time, radius, clockY,
                forecast, theme)
        } else {
            // One identical clock face in both digital modes: same font,
            // position, outlines and user-selected size.
            drawText(canvas, time.format(timeFormat), CENTER_X, clockY,
                DIGITAL_TIME_SIZE * unit, white, unit, theme)
            if (preset.usesWeather)
                drawDigitalForecast(canvas, time, clockY, unit, forecast, theme)
        }
        if (preset.usesWeather && !sourceLabel.isNullOrBlank()) {
            val sourceY = if (preset.hasAnalog) clockY + radius * 1.28f + 65f * unit
                else clockY + (DIGITAL_FORECAST_TOP + DIGITAL_FORECAST_CITY_OFFSET) * unit
            val sourceX = if (preset.hasAnalog) CENTER_X + 175f * unit else CENTER_X
            val maxWidth = if (preset.hasAnalog) 160f * unit else 290f * unit
            drawSourceLabel(canvas, sourceLabel, sourceX, sourceY, unit, maxWidth, theme,
                alignRight = preset.hasAnalog, size = if (preset.hasAnalog) 22f else 27f)
        }
        return bitmap
    }

    /** Media quick-control graphic: always ONE primary family, never add-ons. */
    fun renderMini(
        family: ClockFamily,
        time: LocalDateTime,
        theme: ClockTheme = ClockTheme.DARK,
        outputSize: Int = 160
    ): Bitmap {
        require(outputSize in 64..256)
        val image = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.RGB_565)
        val canvas = Canvas(image)
        canvas.scale(outputSize / 160f, outputSize / 160f)
        val dark = theme == ClockTheme.DARK
        val ink = if (dark) Color.WHITE else Color.rgb(14, 24, 35)
        val accent = if (dark) Color.rgb(108, 218, 255) else Color.rgb(12, 65, 115)
        canvas.drawColor(if (dark) Color.rgb(2, 5, 11) else Color.WHITE)
        when (family) {
            ClockFamily.ANALOG -> {
                val cx = 80f
                val cy = 80f
                canvas.drawCircle(cx, cy, 75f, stroke(ink, 4.5f))
                val tick = stroke(ink, 4f)
                repeat(12) { i ->
                    val dx = tickX[i * 5]
                    val dy = tickY[i * 5]
                    canvas.drawLine(cx + dx * 55f, cy + dy * 55f,
                        cx + dx * 67f, cy + dy * 67f, tick)
                }
                val h = Math.toRadians((time.hour % 12) * 30.0 + time.minute * 0.5 - 90.0)
                val m = Math.toRadians(time.minute * 6.0 - 90.0)
                canvas.drawLine(cx, cy, cx + cos(h).toFloat() * 43f,
                    cy + sin(h).toFloat() * 43f, stroke(ink, 11f))
                canvas.drawLine(cx, cy, cx + cos(m).toFloat() * 63f,
                    cy + sin(m).toFloat() * 63f, stroke(accent, 8f))
                canvas.drawCircle(cx, cy, 5f, fill(ink))
            }
            ClockFamily.DIGITAL ->
                drawMiniText(canvas, time.format(timeFormat), 80f, 80f, 54f, ink, true)

        }
        return image
    }

    private fun drawMiniText(
        canvas: Canvas, text: String, x: Float, y: Float,
        size: Float, ink: Int, mono: Boolean
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textAlign = Paint.Align.CENTER
            textSize = size
            typeface = Typeface.create(
                if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, Typeface.BOLD
            )
        }
        val width = paint.measureText(text)
        if (width > 150f) paint.textSize *= 150f / width
        val baseline = y - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        canvas.drawText(text, x, baseline, paint)
    }

    /** Reverse only the light main artwork; dark incurs no additional work. */
    /** Six hourly positions with change-only future glyphs. Keep each
     * glyph and temperature outside the dial's outer stroke, accounting for
     * the vertical glyph lift and the different shapes around the circle.
     * Fade each complete slot in a small alpha layer. */
    private fun drawForecastRing(
        canvas: Canvas, now: LocalDateTime, radius: Float, centreY: Float,
        forecast: List<ForecastStore.Hour>, theme: ClockTheme
    ) {
        val unit = radius / STANDARD_RADIUS
        val minimumRing = radius * 1.28f + 4f * unit
        // Outer stroke is 4.8 * unit wide. Reserve an additional visible gap.
        val clearance = radius + 12f * unit
        val zone = ZoneId.systemDefault()
        val currentSlot = now.atZone(zone)
            .withMinute(0).withSecond(0).withNano(0).toEpochSecond()
        val changes = ForecastStore.ringChanges(forecast, currentSlot)
        // Keep the guide line out of the weather glyphs and text. These
        // exclusions are used only when drawing the arc, not the dial.
        val arcExclusions = Path()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = themed(pale, theme)
            textAlign = Paint.Align.CENTER
            textSize = 20f * unit
            typeface = boldFace
        }
        val fm = paint.fontMetrics
        for (hour in forecast) {
            val local = LocalDateTime.ofInstant(Instant.ofEpochSecond(hour.epochSeconds), zone)
            // Match real elapsed hourly slots; the dial uses the phone zone.
            val offset = ((hour.epochSeconds - currentSlot) / 3600L).toInt()
            if (offset !in 0..ForecastStore.RING_FUTURE_HOURS || hour.code < 0) continue
            val index = local.hour % 12
            val dx = forecastX[index]
            val dy = forecastY[index]
            val hasIcon = hour.epochSeconds in changes.iconEpochs
            val hasTemperature = hour.epochSeconds in changes.temperatureEpochs
            if (!hasIcon && !hasTemperature) continue
            val geometry = iconGeometry(hour.code, hour.isDay)
            val iconSize = 41f
            val scale = iconSize * unit / maxOf(geometry.width, geometry.height)
            val lift = 16f * unit
            val temperature = "${hour.temperatureC.roundToInt()}°"
            // Project the glyph and text bounding boxes onto the outward
            // radial direction. Move only as far as necessary for clearance.
            // Temperature-only slots sit centred on a common circular path,
            // rather than leaving an empty icon-sized gap above the text.
            val baseline = if (hasIcon) 21f * unit else -(fm.ascent + fm.descent) / 2f
            val textInner = dy * baseline + minOf(dy * fm.ascent, dy * fm.descent) -
                abs(dx) * paint.measureText(temperature) / 2f
            // Halve the standalone temperature centre's gap from the dial:
            // 1.38R -> 1.19R. Allow a minimal outward adjustment only if
            // wide text would otherwise touch the outer stroke.
            val temperatureRing = if (hasIcon) minimumRing else radius * STANDALONE_TEMPERATURE_RADIUS
            val temperatureClearance = if (hasIcon) clearance else radius + 4f * unit
            var ring = if (hasTemperature)
                maxOf(temperatureRing, temperatureClearance - textInner) else minimumRing
            if (hasIcon) {
                val iconInner = -dy * lift - scale *
                    (abs(dx) * geometry.width + abs(dy) * geometry.height) / 2f
                ring = maxOf(ring, clearance - iconInner)
            }
            val x = CENTER_X + dx * ring
            val y = centreY + dy * ring
            val gap = 3f * unit
            if (hasTemperature) {
                val halfWidth = paint.measureText(temperature) / 2f
                arcExclusions.addRect(x - halfWidth - gap, y + baseline + fm.ascent - gap,
                    x + halfWidth + gap, y + baseline + fm.descent + gap, Path.Direction.CW)
            }
            if (hasIcon) {
                val halfWidth = geometry.width * scale / 2f
                val halfHeight = geometry.height * scale / 2f
                arcExclusions.addRect(x - halfWidth - gap, y - lift - halfHeight - gap,
                    x + halfWidth + gap, y - lift + halfHeight + gap, Path.Direction.CW)
            }
            val alpha = ForecastStore.ringAlpha(offset)
            // A temperature-only slot has no overlapping drawing primitives.
            // Direct paint alpha avoids allocating an offscreen layer.
            if (!hasIcon) {
                paint.alpha = alpha
                canvas.drawText(temperature, x, y + baseline, paint)
                paint.alpha = 255
                continue
            }
            val bounds = 60f * unit
            val save = if (alpha < 255)
                canvas.saveLayerAlpha(x - bounds, y - bounds, x + bounds, y + bounds, alpha)
                else canvas.save()
            if (hasIcon) {
                canvas.save()
                canvas.translate(x, y - lift - geometry.visualCentreY * scale)
                canvas.scale(scale, scale)
                drawWeatherIcon(canvas, hour.code, hour.isDay, theme)
                canvas.restore()
            }
            if (hasTemperature) canvas.drawText(temperature, x, y + baseline, paint)
            canvas.restoreToCount(save)
        }
        // A solid guide ends at the last available forecast hour, not at a faded nominal endpoint.
        val last = forecast.filter { it.code >= 0 && it.epochSeconds > currentSlot &&
            it.epochSeconds <= currentSlot + ForecastStore.RING_FUTURE_HOURS * 3600L }
            .maxOfOrNull { it.epochSeconds }
        if (currentSlot in changes.iconEpochs && last != null) {
            drawForecastArc(canvas, now.hour, radius, centreY, theme, arcExclusions,
                (last - currentSlot) / 3600f * 30f)
        }
    }

    @Suppress("DEPRECATION")
    private fun drawForecastArc(
        canvas: Canvas, currentHour: Int, radius: Float, centreY: Float,
        theme: ClockTheme, exclusions: Path, sweep: Float
    ) {
        val unit = radius / STANDARD_RADIUS
        val arcRadius = radius * STANDALONE_TEMPERATURE_RADIUS
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * unit
            color = themed(pale, theme)
            alpha = 140
        }
        val oval = RectF(CENTER_X - arcRadius, centreY - arcRadius,
            CENTER_X + arcRadius, centreY + arcRadius)
        val save = canvas.save()
        canvas.clipPath(exclusions, Region.Op.DIFFERENCE)
        canvas.rotate((currentHour % 12) * 30f - 75f, CENTER_X, centreY)
        canvas.drawArc(oval, 0f, sweep, false, paint)
        canvas.restoreToCount(save)
    }

    /** Six hourly columns, drawn directly at their final artwork sizes. */
    private fun drawDigitalForecast(
        canvas: Canvas, now: LocalDateTime, centreY: Float, unit: Float,
        forecast: List<ForecastStore.Hour>, theme: ClockTheme
    ) {
        val top = centreY + DIGITAL_FORECAST_TOP * unit
        val slots = ForecastStore.digitalSlots(forecast, now)
        val pitch = DIGITAL_FORECAST_PITCH * unit
        val firstBoundary = CENTER_X - slots.size * pitch / 2f
        val label = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = themed(pale, theme)
            textAlign = Paint.Align.CENTER
            typeface = condensedBoldFace
            textSize = 20f * unit
        }
        // Keep all seven HH:mm boundary labels equally sized and separated.
        val labels = slots.map { it.time.format(timeFormat) } +
            listOfNotNull(slots.lastOrNull()?.time?.plusHours(1)?.format(timeFormat))
        val widest = labels.maxOfOrNull { label.measureText(it) } ?: 0f
        if (widest > pitch - 4f * unit)
            label.textSize *= (pitch - 4f * unit) / widest
        val temp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = themed(white, theme)
            textAlign = Paint.Align.CENTER
            typeface = boldFace
            textSize = 22f * unit
        }
        labels.forEachIndexed { index, value ->
            label.alpha = slots[minOf(index, slots.lastIndex)].alpha
            canvas.drawText(value, firstBoundary + index * pitch, top + 16f * unit, label)
        }
        slots.forEachIndexed { index, slot ->
            val x = firstBoundary + (index + 0.5f) * pitch
            val hour = slot.hour
            if (hour != null && hour.code >= 0) {
                val geometry = iconGeometry(hour.code, hour.isDay)
                val iconScale = 37f * unit / maxOf(geometry.width, geometry.height)
                // Apply opacity to the complete icon only. Text is painted
                // directly, with its own alpha, for clean antialiased edges.
                val half = 23f * unit
                val iconY = top + 44f * unit
                val save = if (slot.alpha < 255)
                    canvas.saveLayerAlpha(x - half, iconY - half, x + half, iconY + half, slot.alpha)
                    else canvas.save()
                canvas.translate(x, iconY - geometry.visualCentreY * iconScale)
                canvas.scale(iconScale, iconScale)
                drawWeatherIcon(canvas, hour.code, hour.isDay, theme)
                canvas.restoreToCount(save)
            }
            temp.alpha = slot.alpha
            val temperature = hour?.let { "${it.temperatureC.roundToInt()}°" } ?: "–"
            // Extra air below the icon; the city sits below this row.
            canvas.drawText(temperature, x, top + 88f * unit, temp)
        }
    }

    private fun drawSourceLabel(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        unit: Float,
        maxWidth: Float,
        theme: ClockTheme,
        alignRight: Boolean = false,
        size: Float = 22f
    ) {
        val baseSize = size * unit
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = themed(pale, theme)
            textAlign = Paint.Align.CENTER
            textSize = baseSize
            typeface = boldFace
        }
        val width = paint.measureText(value)
        val scale = if (width > 0f) minOf(1f, maxWidth / width) else 1f
        val centreX = if (alignRight) x - width * scale / 2f else x
        drawText(canvas, value, centreX, y, baseSize * scale, pale,
            if (alignRight) unit else unit * 0.65f, theme)
    }

    private fun stroke(color: Int, thickness: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = thickness
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private fun fill(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

    private fun drawDial(canvas: Canvas, now: LocalDateTime, radius: Float, centreY: Float, theme: ClockTheme) {
        val unit = radius / STANDARD_RADIUS
        val x = CENTER_X
        val y = centreY
        canvas.drawCircle(x, y, radius + 1f, fill(themed(Color.rgb(8, 17, 27), theme)))
        canvas.drawCircle(x, y, radius, stroke(themed(pale, theme), 4.8f * unit))
        canvas.drawCircle(x, y, radius - 9f * unit,
            stroke(themed(Color.rgb(53, 92, 115), theme), 1.8f * unit))
        val major = stroke(themed(white, theme), 6.5f * unit)
        val minor = stroke(themed(Color.rgb(157, 193, 210), theme), 2.3f * unit)
        val lines = if (radius == COMPACT_RADIUS) compactLines else standardLines
        canvas.drawLines(lines.major, major)
        canvas.drawLines(lines.minor, minor)
        val hourAngle = Math.toRadians(
            (now.hour % 12) * 30.0 + now.minute * 0.5 - 90.0
        )
        val minuteAngle = Math.toRadians(now.minute * 6.0 - 90.0)
        canvas.drawLine(x, y,
            x + cos(hourAngle).toFloat() * radius * 0.68f,
            y + sin(hourAngle).toFloat() * radius * 0.68f,
            stroke(themed(white, theme), 10f * unit))
        canvas.drawLine(x, y,
            x + cos(minuteAngle).toFloat() * radius * 0.83f,
            y + sin(minuteAngle).toFloat() * radius * 0.83f,
            stroke(themed(cyan, theme), 6.5f * unit))
    }

    /** Actual icon bounds, not a constant empty slot around every symbol.
     * A thin crescent is much narrower than a rainy cloud, and using the same
     * reserved width made the moon look far away from the temperature.
     */
    private data class IconGeometry(
        val width: Float,
        val height: Float,
        val visualCentreY: Float
    )

    private fun iconGeometry(code: Int, isDay: Boolean): IconGeometry = when (code) {
        0 -> if (isDay) IconGeometry(82f, 82f, 0f)
             else IconGeometry(46f, 46f, 0f)
        1, 2 -> if (isDay) IconGeometry(111f, 89f, 3f)
                else IconGeometry(120f, 105f, -15f)
        45, 48 -> IconGeometry(98f, 105f, 4f)
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 ->
            if (isDay) IconGeometry(98f, 85f, 2f)
            else IconGeometry(122f, 138f, -13f)
        71, 73, 75, 77, 85, 86 -> if (isDay) IconGeometry(98f, 94f, 2f)
            else IconGeometry(122f, 130f, -17f)
        95, 96, 99 -> if (isDay) IconGeometry(98f, 105f, 6f)
            else IconGeometry(122f, 142f, -11f)
        else -> IconGeometry(98f, 65f, -6f)
    }

    private fun drawText(
        canvas: Canvas,
        value: String,
        x: Float,
        centreY: Float,
        size: Float,
        color: Int,
        unit: Float,
        theme: ClockTheme
    ) {
        val face = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = themed(color, theme)
            textAlign = Paint.Align.CENTER
            textSize = size
            typeface = boldFace
            style = Paint.Style.FILL
        }
        val baseline = centreY - (face.fontMetrics.ascent + face.fontMetrics.descent) / 2f

        // Negative/knockout contour around each glyph hides underlying hands.
        // No luminous blur is painted over the white face of the characters.
        val distantHalo = Paint(face).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f * unit
            strokeJoin = Paint.Join.ROUND
            this.color = themed(Color.argb(85, 106, 202, 255), theme)
        }
        val reverseOutline = Paint(face).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6.5f * unit
            strokeJoin = Paint.Join.ROUND
            this.color = themed(Color.rgb(3, 9, 17), theme)
        }
        canvas.drawText(value, x, baseline, distantHalo)
        canvas.drawText(value, x, baseline, reverseOutline)
        canvas.drawText(value, x, baseline, face)
    }

    /**
     * Icon color palette. The light main-card artwork is reversed as a whole
     * after drawing; pre-inverting the intended light colors here preserves
     * recognisable blue rain, yellow sun, etc. The mini panel is not inverted.
     */
    private data class WeatherPalette(
        val sun: Int, val moon: Int, val moonCutout: Int,
        val cloudFill: Int, val cloudOutline: Int,
        val rain: Int, val rainHalo: Int, val fog: Int,
        val snow: Int, val bolt: Int
    )

    private val darkWeather = makeWeatherPalette(false)
    private val lightWeather = makeWeatherPalette(true)

    private fun makeWeatherPalette(light: Boolean): WeatherPalette {
        fun color(dark: Int, bright: Int) = if (light) bright else dark
        val outline = color(Color.rgb(234, 244, 255), Color.rgb(37, 72, 107))
        return WeatherPalette(
            sun = color(Color.rgb(255, 217, 76), Color.rgb(234, 150, 13)),
            moon = color(Color.rgb(237, 246, 255), Color.rgb(65, 85, 125)),
            moonCutout = color(Color.rgb(8, 17, 27), Color.WHITE),
            cloudFill = color(Color.rgb(22, 42, 65), Color.rgb(220, 234, 244)),
            cloudOutline = outline,
            rain = color(Color.rgb(46, 220, 255), Color.rgb(0, 100, 220)),
            rainHalo = color(Color.rgb(228, 251, 255), Color.rgb(236, 246, 255)),
            fog = outline,
            snow = color(Color.rgb(203, 245, 255), Color.rgb(30, 99, 155)),
            bolt = color(Color.rgb(255, 214, 73), Color.rgb(203, 116, 0))
        )
    }

    private fun drawWeatherIcon(
        canvas: Canvas,
        code: Int?,
        isDay: Boolean,
        theme: ClockTheme
    ) {
        val palette = if (theme == ClockTheme.DARK) darkWeather else lightWeather
        val cx = 0f
        val cy = 0f
        when (code) {
            0 -> if (isDay) drawSun(canvas, cx, cy, palette)
                else drawMoon(canvas, cx, cy, palette)
            1, 2 -> {
                if (isDay) drawSun(canvas, cx - 21f, cy - 20f, palette, 18f)
                else drawCombinedMoon(canvas, cx - 27f, cy - 33f, palette)
                drawCloud(canvas, cx, cy + 12f, palette)
            }
            3, null, -1 -> drawCloud(canvas, cx, cy, palette)
            45, 48 -> {
                drawCloud(canvas, cx, cy - 13f, palette)
                val fog = stroke(palette.fog, 6.5f)
                repeat(3) { i ->
                    val y = cy + 26f + i * 10f
                    canvas.drawLine(cx - 37f + (i % 2) * 8f, y, cx + 37f, y, fog)
                }
            }
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> {
                if (!isDay) drawCombinedMoon(canvas, cx - 28f, cy - 48f, palette)
                // Draw rain BELOW the cloud, with a bright wide halo and a
                // saturated core. The old 6px/15px dashes disappeared after
                // Android Auto scaled and dimmed the clock artwork.
                drawCloud(canvas, cx, cy - 13f, palette)
                val halo = stroke(palette.rainHalo, 14f)
                val rain = stroke(palette.rain, 9.5f)
                repeat(3) { i ->
                    val x = cx - 25f + i * 25f
                    canvas.drawLine(x + 8f, cy + 27f, x - 1f, cy + 48f, halo)
                    canvas.drawLine(x + 8f, cy + 27f, x - 1f, cy + 48f, rain)
                }
            }
            71, 73, 75, 77, 85, 86 -> {
                if (!isDay) drawCombinedMoon(canvas, cx - 28f, cy - 48f, palette)
                drawCloud(canvas, cx, cy - 14f, palette)
                val snow = stroke(palette.snow, 6f)
                repeat(3) { i ->
                    val x = cx - 24f + i * 24f
                    val y = cy + 32f + (i % 2) * 8f
                    canvas.drawLine(x - 6f, y, x + 6f, y, snow)
                    canvas.drawLine(x, y - 6f, x, y + 6f, snow)
                }
            }
            95, 96, 99 -> {
                if (!isDay) drawCombinedMoon(canvas, cx - 28f, cy - 48f, palette)
                drawCloud(canvas, cx, cy - 13f, palette)
                val bolt = Path().apply {
                    moveTo(cx + 5f, cy + 14f)
                    lineTo(cx - 10f, cy + 41f)
                    lineTo(cx + 1f, cy + 39f)
                    lineTo(cx - 8f, cy + 60f)
                    lineTo(cx + 21f, cy + 29f)
                    lineTo(cx + 8f, cy + 30f)
                    close()
                }
                canvas.drawPath(bolt, fill(palette.bolt))
            }
            else -> drawCloud(canvas, cx, cy, palette)
        }
    }

    private fun drawSun(
        canvas: Canvas, cx: Float, cy: Float, palette: WeatherPalette,
        radius: Float = 23f
    ) {
        canvas.drawCircle(cx, cy, radius, fill(palette.sun))
        val rays = stroke(palette.sun, 6f)
        repeat(8) { i ->
            val angle = Math.toRadians(i * 45.0)
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            canvas.drawLine(
                cx + dx * (radius + 8f), cy + dy * (radius + 8f),
                cx + dx * (radius + 17f), cy + dy * (radius + 17f), rays
            )
        }
    }

    private fun drawMoon(
        canvas: Canvas, cx: Float, cy: Float, palette: WeatherPalette,
        radius: Float = 23f
    ) {
        canvas.drawCircle(cx, cy, radius + 3f, fill(palette.cloudFill))
        canvas.drawCircle(cx, cy, radius, fill(palette.moon))
        canvas.drawCircle(cx + radius * 0.46f, cy - radius * 0.36f,
            radius, fill(palette.moonCutout))
    }

    /** A larger, thicker crescent behind the cloud. Cut the shape out
     * rather than painting a background-colored disc over nearby artwork.
     * Keep the standalone clear-night moon unchanged. */
    private fun drawCombinedMoon(
        canvas: Canvas, cx: Float, cy: Float, palette: WeatherPalette
    ) {
        val radius = 33f
        val crescent = Path().apply {
            addCircle(cx, cy, radius, Path.Direction.CW)
        }
        val cutout = Path().apply {
            addCircle(cx + radius * 0.66f, cy - radius * 0.32f,
                radius, Path.Direction.CW)
        }
        crescent.op(cutout, Path.Op.DIFFERENCE)
        canvas.drawPath(crescent, fill(palette.moon))
    }

    private fun drawCloud(
        canvas: Canvas, cx: Float, cy: Float, palette: WeatherPalette
    ) {
        val shape = Path().apply {
            moveTo(cx - 34f, cy + 20f)
            cubicTo(cx - 47f, cy + 19f, cx - 49f, cy - 3f, cx - 34f, cy - 9f)
            cubicTo(cx - 33f, cy - 29f, cx - 12f, cy - 38f, cx + 2f, cy - 26f)
            cubicTo(cx + 25f, cy - 35f, cx + 40f, cy - 17f, cx + 37f, cy - 1f)
            cubicTo(cx + 53f, cy + 2f, cx + 49f, cy + 21f, cx + 31f, cy + 22f)
            close()
        }
        canvas.drawPath(shape, fill(palette.cloudFill))
        canvas.drawPath(shape, stroke(palette.cloudOutline, 7f))
    }

}

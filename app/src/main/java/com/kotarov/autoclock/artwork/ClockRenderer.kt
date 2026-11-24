package com.kotarov.autoclock.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.kotarov.autoclock.media.ClockPreset
import com.kotarov.autoclock.media.ClockFamily
import com.kotarov.autoclock.media.ClockTheme
import com.kotarov.autoclock.weather.ForecastStore
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    private const val CENTER_X = 256f
    // The large analog dial uses the original v0.29/v0.30 geometry except
    // for a minimal 6px downward adjustment to prevent top-rim cropping.
    // No padding, scaling or horizontal translation is applied.
    private const val CONTENT_CENTER_Y = 190f
    // Slightly lower the pure digital face; analog and weather-only remain.
    private const val DIGITAL_CENTER_DOWN = 10f
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
    private val forecastX = FloatArray(12) { cos(Math.toRadians(it * 30.0 - 75)).toFloat() }
    private val forecastY = FloatArray(12) { sin(Math.toRadians(it * 30.0 - 75)).toFloat() }
    private fun themed(color: Int, theme: ClockTheme): Int =
        if (theme == ClockTheme.DARK) color else color xor 0x00ffffff
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
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
        } else if (preset.usesWeather) {
            drawDigitalForecast(canvas, time, clockY, compact, forecast, theme)
        } else {
            drawText(canvas, time.format(timeFormat), CENTER_X, clockY,
                96f * unit, white, unit, theme)
        }
        if (preset.usesWeather && !sourceLabel.isNullOrBlank()) {
            val sourceY = if (preset.hasAnalog) clockY + radius * 1.28f + 50f * unit
                else clockY + 148f * (if (compact) 0.82f else 1f)
            val maxWidth = if (preset.hasAnalog) radius * 2f + 40f * unit else 390f
            drawSourceLabel(canvas, sourceLabel, CENTER_X, sourceY, unit, maxWidth, theme)
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
    /** Eleven slots at their actual clock positions. The past slot fades
     * to 40%; current is opaque; the last two use 65% and 30%. Missing slots stay blank.
     * Draw each glyph into a small alpha layer so its overlapping strokes
     * fade together; no persistent bitmap or per-second work is added.
     */
    private fun drawForecastRing(
        canvas: Canvas, now: LocalDateTime, radius: Float, centreY: Float,
        forecast: List<ForecastStore.Hour>, theme: ClockTheme
    ) {
        val unit = radius / STANDARD_RADIUS
        val ring = radius * 1.28f
        val zone = ZoneId.systemDefault()
        val currentSlot = now.atZone(zone)
            .withMinute(0).withSecond(0).withNano(0).toEpochSecond()
        val iconEpochs = ForecastStore.ringIconEpochs(forecast, currentSlot)
        for (hour in forecast) {
            val local = LocalDateTime.ofInstant(Instant.ofEpochSecond(hour.epochSeconds), zone)
            // Match real elapsed hourly slots; the dial uses the phone zone.
            val offset = ((hour.epochSeconds - currentSlot) / 3600L).toInt()
            if (offset !in -1..ForecastStore.RING_FUTURE_HOURS || hour.code < 0) continue
            val index = local.hour % 12
            val x = CENTER_X + forecastX[index] * ring
            val y = centreY + forecastY[index] * ring
            val alpha = ForecastStore.ringAlpha(offset)
            val bounds = 60f * unit
            val save = if (alpha < 255)
                canvas.saveLayerAlpha(x - bounds, y - bounds, x + bounds, y + bounds, alpha)
                else canvas.save()
            if (hour.epochSeconds in iconEpochs) {
                val geometry = iconGeometry(hour.code, hour.isDay)
                val iconSize = if (offset == 0) 58f else 45f
                val scale = iconSize * unit / maxOf(geometry.width, geometry.height)
                val lift = if (offset == 0) 24f else 16f
                canvas.save()
                canvas.translate(x, y - lift * unit - geometry.visualCentreY * scale)
                canvas.scale(scale, scale)
                drawWeatherIcon(canvas, hour.code, hour.isDay, theme)
                canvas.restore()
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = themed(pale, theme)
                textAlign = Paint.Align.CENTER
                textSize = (if (offset == 0) 18f else 15f) * unit
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            canvas.drawText("${hour.temperatureC.roundToInt()}°", x, y + 21f * unit, paint)
            canvas.restoreToCount(save)
        }
    }

    /** Digital weather layout: prominent HH:mm, then six evenly spaced
     * hourly columns. The first column is larger and fully opaque. */
    private fun drawDigitalForecast(
        canvas: Canvas, now: LocalDateTime, centreY: Float, compact: Boolean,
        forecast: List<ForecastStore.Hour>, theme: ClockTheme
    ) {
        val scale = if (compact) 0.82f else 1f
        canvas.save()
        canvas.translate(CENTER_X, centreY)
        canvas.scale(scale, scale)

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = themed(white, theme)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 132f
        }
        val shownTime = now.format(timeFormat)
        val width = timePaint.measureText(shownTime)
        if (width > 448f) timePaint.textSize *= 448f / width
        val timeBaseline = -61f - (timePaint.fontMetrics.ascent + timePaint.fontMetrics.descent) / 2f
        canvas.drawText(shownTime, 0f, timeBaseline, timePaint)

        val slots = ForecastStore.digitalSlots(forecast, now)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = themed(pale, theme)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = 17f
        }
        val temp = Paint(label).apply { color = themed(white, theme) }
        // Seven boundary labels delimit the six intervals. Keep the existing
        // icon and temperature columns, sizes, spacing and fade unchanged.
        val pitch = 73f
        val firstBoundary = -slots.size * pitch / 2f
        val labelColor = label.color
        slots.forEachIndexed { index, slot ->
            label.color = labelColor
            label.alpha = slot.alpha
            canvas.drawText(slot.time.format(timeFormat), firstBoundary + index * pitch, 25f, label)
        }
        if (slots.isNotEmpty()) {
            label.alpha = slots.last().alpha
            canvas.drawText(slots.last().time.plusHours(1).format(timeFormat),
                firstBoundary + slots.size * pitch, 25f, label)
        }
        slots.forEachIndexed { index, slot ->
            val x = firstBoundary + (index + 0.5f) * pitch
            val save = if (slot.alpha < 255)
                canvas.saveLayerAlpha(x - 36f, 36f, x + 36f, 122f, slot.alpha)
                else canvas.save()
            val hour = slot.hour
            if (hour != null && hour.code >= 0) {
                val geometry = iconGeometry(hour.code, hour.isDay)
                val iconSize = if (index == 0) 56f else 39f
                val iconScale = iconSize / maxOf(geometry.width, geometry.height)
                canvas.save()
                canvas.translate(x, (if (index == 0) 58f else 59f) - geometry.visualCentreY * iconScale)
                canvas.scale(iconScale, iconScale)
                drawWeatherIcon(canvas, hour.code, hour.isDay, theme)
                canvas.restore()
            }
            temp.textSize = if (index == 0) 32f else 25f
            temp.typeface = Typeface.create(Typeface.SANS_SERIF,
                if (index == 0) Typeface.BOLD else Typeface.NORMAL)
            val temperature = hour?.let { "${it.temperatureC.roundToInt()}°" } ?: "–"
            canvas.drawText(temperature, x, 108f, temp)
            canvas.restoreToCount(save)
        }
        canvas.restore()
    }

    private fun drawSourceLabel(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        unit: Float,
        maxWidth: Float,
        theme: ClockTheme
    ) {
        val baseSize = 22f * unit
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = themed(pale, theme)
            textAlign = Paint.Align.CENTER
            textSize = baseSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val width = paint.measureText(value)
        val scale = if (width > 0f) minOf(1f, maxWidth / width) else 1f
        drawText(canvas, value, x, y, baseSize * scale, pale, unit, theme)
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
        repeat(60) { index ->
            val dx = tickX[index]
            val dy = tickY[index]
            val inner = radius - if (index % 5 == 0) 26f * unit else 17f * unit
            val outer = radius - 7f * unit
            canvas.drawLine(x + dx * inner, y + dy * inner,
                x + dx * outer, y + dy * outer,
                if (index % 5 == 0) major else minor)
        }
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
                else IconGeometry(111f, 100f, -5f)
        45, 48 -> IconGeometry(98f, 105f, 4f)
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 ->
            if (isDay) IconGeometry(98f, 85f, 2f)
            else IconGeometry(108f, 130f, -8f)
        71, 73, 75, 77, 85, 86 -> if (isDay) IconGeometry(98f, 94f, 2f)
            else IconGeometry(108f, 130f, -8f)
        95, 96, 99 -> if (isDay) IconGeometry(98f, 105f, 6f)
            else IconGeometry(108f, 138f, -5f)
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
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
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
                else drawMoon(canvas, cx - 28f, cy - 28f, palette, 22f)
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
                if (!isDay) drawMoon(canvas, cx - 29f, cy - 48f, palette, 18f)
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
                if (!isDay) drawMoon(canvas, cx - 29f, cy - 48f, palette, 18f)
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
                if (!isDay) drawMoon(canvas, cx - 29f, cy - 48f, palette, 18f)
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

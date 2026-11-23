package com.kotarov.autoclock.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.kotarov.autoclock.media.ClockPreset
import com.kotarov.autoclock.media.ClockFamily
import com.kotarov.autoclock.media.ClockTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

/**
 * Three main visual styles with zero, one or two independent additions.
 * Art is a single RGB_565 bitmap, refreshed only when the minute or
 * selected settings change. The foreground never animates every second.
 */
object ClockRenderer {
    const val SIZE = 512
    private const val CENTER_X = 256f
    // Different optical centres for compact and standard Android Auto tiles.
    // Restore the full original large analog radius and original horizontal
    // centre. Lower only the standard dial, leaving room for the host crop.
    // The clock's own digits, weather and source label move together with it.
    private const val CONTENT_CENTER_Y = 190f
    private const val COMPACT_ANALOG_CENTER_Y = 208f
    // Previously 216px (v0.30) / 236px with smaller 104px radius (v0.31).
    // With the original 122px radius, centre 254 keeps the same top rim as
    // v0.31: 254 - 122 = 132px, but restores the original large size.
    private const val STANDARD_ANALOG_CENTER_Y = 254f
    private const val COMPACT_RADIUS = 96f
    private const val STANDARD_RADIUS = 122f
    // Keep the analog forecast at the same size and vertical position with
    // or without the optional digital HH:mm. Compact / standard use slightly
    // different offsets to match the size of the dial in Android Auto.
    private const val COMPACT_ANALOG_WEATHER_Y_OFFSET = 30f
    private const val STANDARD_ANALOG_WEATHER_Y_OFFSET = 34f
    private const val ANALOG_WEATHER_TEXT_SIZE = 29f
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val white = Color.rgb(255, 255, 255)
    private val pale = Color.rgb(224, 239, 247)
    private val cyan = Color.rgb(108, 218, 255)
    private val navy = Color.rgb(12, 23, 35)

    fun render(
        presetId: String,
        time: LocalDateTime,
        weatherCode: Int? = null,
        isDay: Boolean = true,
        temperature: String? = null,
        compact: Boolean = false,
        outputSize: Int = SIZE,
        condition: String? = null,
        sourceLabel: String? = null,
        theme: ClockTheme = ClockTheme.DARK,
        coloredWeatherIcons: Boolean = true
    ): Bitmap {
        require(outputSize in 192..SIZE) { "Unsupported artwork size" }
        val bitmap = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.scale(outputSize.toFloat() / SIZE, outputSize.toFloat() / SIZE)
        canvas.drawColor(Color.rgb(2, 5, 11))

        val preset = ClockPreset.fromId(presetId)
        val radius = if (compact) COMPACT_RADIUS else STANDARD_RADIUS
        val unit = radius / STANDARD_RADIUS
        val clockY = if (preset.hasAnalog) {
            if (compact) COMPACT_ANALOG_CENTER_Y else STANDARD_ANALOG_CENTER_Y
        } else CONTENT_CENTER_Y
        val analogWeatherOffset = if (compact)
            COMPACT_ANALOG_WEATHER_Y_OFFSET else STANDARD_ANALOG_WEATHER_Y_OFFSET

        // The primary style establishes the visual hierarchy. Add-ons are
        // independent: no checkboxes means no secondary information at all.
        if (preset.hasAnalog) drawDial(canvas, time, radius, clockY)

        when (preset.family) {
            ClockFamily.ANALOG -> {
                when {
                    preset.hasDigitalTime && preset.usesWeather -> {
                        drawText(canvas, time.format(timeFormat), CENTER_X,
                            clockY - 24f * unit, 46f * unit, white, unit)
                        drawWeatherRow(canvas, CENTER_X, clockY + analogWeatherOffset * unit,
                            weatherCode, isDay, temperature,
                            ANALOG_WEATHER_TEXT_SIZE * unit, unit,
                            coloredWeatherIcons = coloredWeatherIcons, theme = theme)
                    }
                    preset.hasDigitalTime ->
                        drawText(canvas, time.format(timeFormat), CENTER_X,
                            clockY, 46f * unit, white, unit)
                    preset.usesWeather ->
                        drawWeatherRow(canvas, CENTER_X, clockY + analogWeatherOffset * unit,
                            weatherCode, isDay, temperature,
                            ANALOG_WEATHER_TEXT_SIZE * unit, unit,
                            coloredWeatherIcons = coloredWeatherIcons, theme = theme)
                    // No additions: genuinely clean analog face, with
                    // hour/minute hands and markings but no digits or weather.
                    else -> Unit
                }
            }
            ClockFamily.DIGITAL -> {
                if (preset.hasAnalog) {
                    if (preset.usesWeather) {
                        drawText(canvas, time.format(timeFormat), CENTER_X,
                            clockY - 24f * unit, 46f * unit, white, unit)
                        drawWeatherRow(canvas, CENTER_X, clockY + 47f * unit,
                            weatherCode, isDay, temperature, 29f * unit, unit,
                            coloredWeatherIcons = coloredWeatherIcons, theme = theme)
                    } else {
                        drawText(canvas, time.format(timeFormat), CENTER_X,
                            clockY, 46f * unit, white, unit)
                    }
                } else if (preset.usesWeather) {
                    drawText(canvas, time.format(timeFormat), CENTER_X,
                        clockY - 35f * unit, 88f * unit, white, unit)
                    drawWeatherRow(canvas, CENTER_X, clockY + 47f * unit,
                        weatherCode, isDay, temperature, 36f * unit, unit, 330f,
                        coloredWeatherIcons, theme)
                } else {
                    // Pure digital: no dial, date or weather.
                    drawText(canvas, time.format(timeFormat), CENTER_X,
                        clockY, 96f * unit, white, unit)
                }
            }
            ClockFamily.WEATHER -> {
                val weatherSize = if (preset.hasAnalog) 61f * unit else 84f * unit
                if (preset.hasDigitalTime) {
                    drawWeatherRow(canvas, CENTER_X, clockY - 30f * unit,
                        weatherCode, isDay, temperature, weatherSize, unit,
                        if (preset.hasAnalog) radius * 2f - 14f else 345f,
                        coloredWeatherIcons, theme)
                    drawText(canvas, time.format(timeFormat), CENTER_X,
                        clockY + 45f * unit, 37f * unit, pale, unit)
                } else {
                    drawWeatherRow(canvas, CENTER_X,
                        clockY + (if (preset.hasAnalog) 18f else -9f) * unit,
                        weatherCode, isDay, temperature, weatherSize, unit,
                        if (preset.hasAnalog) radius * 2f - 14f else 345f,
                        coloredWeatherIcons, theme)
                    if (!preset.hasAnalog) {
                        drawText(canvas, condition ?: "Няма данни", CENTER_X,
                            clockY + 69f * unit, 27f * unit, pale, unit)
                    }
                }
            }
        }
        if (preset.usesWeather && !sourceLabel.isNullOrBlank()) {
            // Position the observation/source name immediately BELOW the ring.
            // The baseline is computed from each dial's actual bottom edge,
            // not from a fixed offset from the centre.
            val sourceY = if (preset.hasAnalog) {
                val gap = if (compact) 18f * unit else 16f * unit
                clockY + radius + gap
            } else CONTENT_CENTER_Y + 118f * unit

            val maxWidth = if (preset.hasAnalog)
                radius * 2f + 40f * unit
            else 390f

            drawSourceLabel(canvas, sourceLabel, CENTER_X, sourceY, unit, maxWidth)
        }
        return applyTheme(bitmap, theme)
    }

    /** Distinct low-cost miniature for pressure and station terrain elevation. */
    fun renderMiniMetrics(
        theme: ClockTheme = ClockTheme.DARK, outputSize: Int = 160
    ): Bitmap {
        require(outputSize in 64..256)
        val image = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.RGB_565)
        val canvas = Canvas(image)
        canvas.scale(outputSize / 160f, outputSize / 160f)
        val dark = theme == ClockTheme.DARK
        val ink = if (dark) Color.rgb(227, 239, 248) else Color.rgb(17, 33, 51)
        val accent = if (dark) Color.rgb(108, 218, 255) else Color.rgb(24, 95, 149)
        canvas.drawColor(if (dark) Color.rgb(2, 5, 11) else Color.WHITE)
        canvas.drawCircle(80f, 64f, 49f, stroke(ink, 4f))
        canvas.drawArc(45f, 29f, 115f, 99f, 200f, 140f, false, stroke(accent, 6f))
        drawMiniText(canvas, "hPa", 80f, 63f, 37f, ink, false)
        val mountains = Path().apply {
            moveTo(22f, 139f)
            lineTo(49f, 112f)
            lineTo(67f, 125f)
            lineTo(93f, 101f)
            lineTo(138f, 139f)
        }
        canvas.drawPath(mountains, stroke(accent, 6f))
        return image
    }

    /** One icon and NO temperature or lettering, for 3-day forecast rows. */
    fun renderForecastSymbol(
        weatherCode: Int?,
        theme: ClockTheme = ClockTheme.DARK,
        coloredWeatherIcons: Boolean = true,
        outputSize: Int = 160
    ): Bitmap {
        require(outputSize in 64..256)
        val image = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.RGB_565)
        val canvas = Canvas(image)
        canvas.scale(outputSize / 160f, outputSize / 160f)
        canvas.drawColor(if (theme == ClockTheme.DARK)
            Color.rgb(2, 5, 11) else Color.WHITE)
        canvas.save()
        canvas.translate(80f, 78f)
        canvas.scale(1.15f, 1.15f)
        if (weatherCode == null || weatherCode == -1) {
            // Missing data must not be falsely displayed as cloudy weather.
            drawMiniText(canvas, "?", 0f, 0f, 70f,
                if (theme == ClockTheme.DARK) Color.WHITE else Color.BLACK, false)
        } else {
            drawWeatherIcon(canvas, weatherCode, true, coloredWeatherIcons, theme, false)
        }
        canvas.restore()
        return image
    }

    /** Media quick-control graphic: always ONE primary family, never add-ons. */
    fun renderMini(
        family: ClockFamily,
        time: LocalDateTime,
        weatherCode: Int? = null,
        isDay: Boolean = true,
        temperature: String? = null,
        theme: ClockTheme = ClockTheme.DARK,
        outputSize: Int = 160,
        coloredWeatherIcons: Boolean = true
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
                    val a = Math.toRadians(i * 30.0 - 90.0)
                    val dx = cos(a).toFloat()
                    val dy = sin(a).toFloat()
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
            ClockFamily.WEATHER -> {
                // Contrast island lets existing icon drawing work on both themes.
                canvas.drawCircle(80f, 52f, 35f, fill(Color.rgb(12, 23, 35)))
                canvas.save()
                canvas.translate(80f, 52f)
                canvas.scale(0.55f, 0.55f)
                drawWeatherIcon(canvas, weatherCode, isDay,
                    coloredWeatherIcons, theme, false)
                canvas.restore()
                drawMiniText(canvas, temperature ?: "--°C", 80f, 119f, 54f, ink, false)
            }
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
    private fun applyTheme(original: Bitmap, theme: ClockTheme): Bitmap {
        if (theme == ClockTheme.DARK) return original
        val result = Bitmap.createBitmap(
            original.width, original.height, Bitmap.Config.RGB_565
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        Canvas(result).drawBitmap(original, 0f, 0f, paint)
        original.recycle()
        return result
    }

    private fun drawSourceLabel(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        unit: Float,
        maxWidth: Float
    ) {
        val baseSize = 22f * unit
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pale
            textAlign = Paint.Align.CENTER
            textSize = baseSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val width = paint.measureText(value)
        val scale = if (width > 0f) minOf(1f, maxWidth / width) else 1f
        drawText(canvas, value, x, y, baseSize * scale, pale, unit)
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

    private fun drawDial(canvas: Canvas, now: LocalDateTime, radius: Float, centreY: Float) {
        val unit = radius / STANDARD_RADIUS
        val x = CENTER_X
        val y = centreY
        canvas.drawCircle(x, y, radius + 1f, fill(Color.rgb(8, 17, 27)))
        canvas.drawCircle(x, y, radius, stroke(pale, 4.8f * unit))
        canvas.drawCircle(x, y, radius - 9f * unit,
            stroke(Color.rgb(53, 92, 115), 1.8f * unit))
        val major = stroke(white, 6.5f * unit)
        val minor = stroke(Color.rgb(157, 193, 210), 2.3f * unit)
        repeat(60) { index ->
            val a = Math.toRadians((index * 6 - 90).toDouble())
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
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
            stroke(white, 10f * unit))
        canvas.drawLine(x, y,
            x + cos(minuteAngle).toFloat() * radius * 0.83f,
            y + sin(minuteAngle).toFloat() * radius * 0.83f,
            stroke(cyan, 6.5f * unit))
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
        1, 2 -> IconGeometry(111f, 89f, 3f)
        45, 48 -> IconGeometry(98f, 105f, 4f)
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 ->
            IconGeometry(98f, 85f, 2f)
        71, 73, 75, 77, 85, 86 -> IconGeometry(98f, 94f, 2f)
        95, 96, 99 -> IconGeometry(98f, 105f, 6f)
        else -> IconGeometry(98f, 65f, -6f)
    }

    private fun drawWeatherRow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        code: Int?,
        isDay: Boolean,
        temperature: String?,
        textSize: Float,
        unit: Float,
        maxWidth: Float = 2f * STANDARD_RADIUS * unit - 8f,
        coloredWeatherIcons: Boolean = true,
        theme: ClockTheme = ClockTheme.DARK
    ) {
        val shown = temperature ?: "--°C"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val textWidth = textPaint.measureText(shown)
        val geometry = if (code != null && code >= 0) iconGeometry(code, isDay) else null
        // Use actual artwork width and keep the icon about as tall as the text.
        val iconScale = if (geometry == null) 0f else
            textSize * 1.12f / geometry.height
        val iconWidth = if (geometry == null) 0f else geometry.width * iconScale
        val gap = if (geometry == null) 0f else textSize * 0.065f
        val totalWidth = textWidth + iconWidth + gap
        val fit = minOf(1f, maxWidth / totalWidth)
        val left = cx - totalWidth * fit / 2f
        if (geometry != null && code != null) {
            val scaled = iconScale * fit
            val iconX = left + iconWidth * fit / 2f
            canvas.save()
            canvas.translate(iconX, cy - geometry.visualCentreY * scaled)
            canvas.scale(scaled, scaled)
            drawWeatherIcon(canvas, code, isDay,
                coloredWeatherIcons, theme, theme == ClockTheme.LIGHT)
            canvas.restore()
        }
        val textX = left + (iconWidth + gap + textWidth / 2f) * fit
        drawText(canvas, shown, textX, cy, textSize * fit, white, unit)
    }

    private fun drawText(
        canvas: Canvas,
        value: String,
        x: Float,
        centreY: Float,
        size: Float,
        color: Int,
        unit: Float
    ) {
        val face = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
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
            this.color = Color.argb(85, 106, 202, 255)
        }
        val reverseOutline = Paint(face).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6.5f * unit
            strokeJoin = Paint.Join.ROUND
            this.color = Color.rgb(3, 9, 17)
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

    private fun weatherPalette(
        colored: Boolean,
        theme: ClockTheme,
        preInvertForMainLight: Boolean
    ): WeatherPalette {
        val light = theme == ClockTheme.LIGHT
        fun result(dark: Int, lightColor: Int): Int {
            val color = if (light) lightColor else dark
            return if (light && preInvertForMainLight)
                Color.rgb(255 - Color.red(color), 255 - Color.green(color),
                    255 - Color.blue(color)) else color
        }
        val cloudFill = if (colored)
            result(Color.rgb(22, 42, 65), Color.rgb(220, 234, 244))
        else result(Color.rgb(13, 24, 35), Color.rgb(245, 245, 245))
        val outline = if (colored)
            result(Color.rgb(234, 244, 255), Color.rgb(37, 72, 107))
        else result(Color.WHITE, Color.rgb(32, 32, 32))
        val rain = if (colored)
            result(Color.rgb(46, 220, 255), Color.rgb(0, 100, 220))
        else result(Color.WHITE, Color.rgb(34, 42, 52))
        val halo = if (colored)
            result(Color.rgb(228, 251, 255), Color.rgb(236, 246, 255))
        else result(Color.rgb(12, 29, 43), Color.WHITE)
        val sun = if (colored)
            result(Color.rgb(255, 217, 76), Color.rgb(234, 150, 13))
        else result(Color.WHITE, Color.rgb(42, 42, 42))
        val moon = if (colored)
            result(Color.rgb(237, 246, 255), Color.rgb(65, 85, 125))
        else result(Color.WHITE, Color.rgb(45, 45, 45))
        return WeatherPalette(
            sun = sun, moon = moon,
            moonCutout = result(Color.rgb(8, 17, 27), Color.WHITE),
            cloudFill = cloudFill, cloudOutline = outline,
            rain = rain, rainHalo = halo,
            fog = outline,
            snow = if (colored)
                result(Color.rgb(203, 245, 255), Color.rgb(30, 99, 155))
            else outline,
            bolt = if (colored)
                result(Color.rgb(255, 214, 73), Color.rgb(203, 116, 0))
            else outline
        )
    }

    private fun drawWeatherIcon(
        canvas: Canvas,
        code: Int?,
        isDay: Boolean,
        colored: Boolean,
        theme: ClockTheme,
        preInvertForMainLight: Boolean
    ) {
        val palette = weatherPalette(colored, theme, preInvertForMainLight)
        val cx = 0f
        val cy = 0f
        when (code) {
            0 -> if (isDay) drawSun(canvas, cx, cy, palette)
                else drawMoon(canvas, cx, cy, palette)
            1, 2 -> {
                if (isDay) drawSun(canvas, cx - 21f, cy - 20f, palette, 18f)
                else drawMoon(canvas, cx - 21f, cy - 20f, palette, 18f)
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

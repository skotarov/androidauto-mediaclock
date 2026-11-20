package com.kotarov.autoclock.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.kotarov.autoclock.media.ClockPreset
import com.kotarov.autoclock.media.ClockFamily
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
    // The top of album art is cropped on the compact Android Auto media tile.
    // Move only analog-containing layouts down while keeping their lower edge
    // above the native transport controls.
    private const val CONTENT_CENTER_Y = 190f
    private const val COMPACT_ANALOG_CENTER_Y = 216f
    private const val STANDARD_ANALOG_CENTER_Y = 198f
    private const val COMPACT_RADIUS = 96f
    private const val STANDARD_RADIUS = 122f
    // Shared weather geometry in both analogue variants. The optional time
    // label must never cause the forecast to jump or resize.
    private const val ANALOG_WEATHER_Y_OFFSET = 39f
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
        compact: Boolean = true,
        outputSize: Int = SIZE,
        condition: String? = null,
        sourceLabel: String? = null
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

        // The primary style establishes the visual hierarchy. Add-ons are
        // independent: no checkboxes means no secondary information at all.
        if (preset.hasAnalog) drawDial(canvas, time, radius, clockY)

        when (preset.family) {
            ClockFamily.ANALOG -> {
                when {
                    preset.hasDigitalTime && preset.usesWeather -> {
                        drawText(canvas, time.format(timeFormat), CENTER_X,
                            clockY - 24f * unit, 46f * unit, white, unit)
                        drawWeatherRow(canvas, CENTER_X, clockY + ANALOG_WEATHER_Y_OFFSET * unit,
                            weatherCode, isDay, temperature,
                            ANALOG_WEATHER_TEXT_SIZE * unit, unit)
                    }
                    preset.hasDigitalTime ->
                        drawText(canvas, time.format(timeFormat), CENTER_X,
                            clockY, 46f * unit, white, unit)
                    preset.usesWeather ->
                        drawWeatherRow(canvas, CENTER_X, clockY + ANALOG_WEATHER_Y_OFFSET * unit,
                            weatherCode, isDay, temperature,
                            ANALOG_WEATHER_TEXT_SIZE * unit, unit)
                    // For compatibility, basic analog always includes HH:mm.
                    else -> drawText(canvas, time.format(timeFormat), CENTER_X,
                        clockY, 46f * unit, white, unit)
                }
            }
            ClockFamily.DIGITAL -> {
                if (preset.hasAnalog) {
                    if (preset.usesWeather) {
                        drawText(canvas, time.format(timeFormat), CENTER_X,
                            clockY - 24f * unit, 46f * unit, white, unit)
                        drawWeatherRow(canvas, CENTER_X, clockY + 47f * unit,
                            weatherCode, isDay, temperature, 29f * unit, unit)
                    } else {
                        drawText(canvas, time.format(timeFormat), CENTER_X,
                            clockY, 46f * unit, white, unit)
                    }
                } else if (preset.usesWeather) {
                    drawText(canvas, time.format(timeFormat), CENTER_X,
                        clockY - 35f * unit, 88f * unit, white, unit)
                    drawWeatherRow(canvas, CENTER_X, clockY + 47f * unit,
                        weatherCode, isDay, temperature, 36f * unit, unit, 330f)
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
                        if (preset.hasAnalog) radius * 2f - 14f else 345f)
                    drawText(canvas, time.format(timeFormat), CENTER_X,
                        clockY + 45f * unit, 37f * unit, pale, unit)
                } else {
                    drawWeatherRow(canvas, CENTER_X,
                        clockY + (if (preset.hasAnalog) 18f else -9f) * unit,
                        weatherCode, isDay, temperature, weatherSize, unit,
                        if (preset.hasAnalog) radius * 2f - 14f else 345f)
                    if (!preset.hasAnalog) {
                        drawText(canvas, condition ?: "Няма данни", CENTER_X,
                            clockY + 69f * unit, 27f * unit, pale, unit)
                    }
                }
            }
        }
        if (preset.usesWeather && !sourceLabel.isNullOrBlank()) {
            // Source must be visible in Auto even when Android Auto hides
            // the media title/artist metadata. Draw below the main values.
            drawSourceLabel(
                canvas,
                sourceLabel,
                CENTER_X,
                if (preset.hasAnalog) clockY + 94f * unit
                else CONTENT_CENTER_Y + 118f * unit,
                unit
            )
        }
        return bitmap
    }

    private fun drawSourceLabel(
        canvas: Canvas, value: String, x: Float, y: Float, unit: Float
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pale
            textAlign = Paint.Align.CENTER
            textSize = 22f * unit
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val width = paint.measureText(value)
        val scale = minOf(1f, 400f / width)
        drawText(canvas, value, x, y, 22f * unit * scale, pale, unit)
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
        maxWidth: Float = 2f * STANDARD_RADIUS * unit - 8f
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
            textSize * 0.86f / geometry.height
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
            drawWeatherIcon(canvas, code, isDay)
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

    private fun drawWeatherIcon(canvas: Canvas, code: Int?, isDay: Boolean) {
        // Coordinates are local to the translated/scaled weather line.
        val cx = 0f
        val cy = 0f
        when (code) {
            0 -> if (isDay) drawSun(canvas, cx, cy) else drawMoon(canvas, cx, cy)
            1, 2 -> {
                if (isDay) drawSun(canvas, cx - 21f, cy - 20f, 18f)
                else drawMoon(canvas, cx - 21f, cy - 20f, 18f)
                drawCloud(canvas, cx, cy + 12f)
            }
            3, null, -1 -> drawCloud(canvas, cx, cy)
            45, 48 -> {
                drawCloud(canvas, cx, cy - 13f)
                val fog = stroke(pale, 5f)
                repeat(3) { i ->
                    val y = cy + 26f + i * 10f
                    canvas.drawLine(cx - 37f + (i % 2) * 8f, y, cx + 37f, y, fog)
                }
            }
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> {
                drawCloud(canvas, cx, cy - 9f)
                val rain = stroke(cyan, 6f)
                repeat(3) { i ->
                    val x = cx - 24f + i * 24f
                    canvas.drawLine(x + 7f, cy + 24f, x, cy + 39f, rain)
                }
            }
            71, 73, 75, 77, 85, 86 -> {
                drawCloud(canvas, cx, cy - 13f)
                val snow = stroke(white, 4f)
                repeat(3) { i ->
                    val x = cx - 24f + i * 24f
                    val y = cy + 32f + (i % 2) * 8f
                    canvas.drawLine(x - 5f, y, x + 5f, y, snow)
                    canvas.drawLine(x, y - 5f, x, y + 5f, snow)
                }
            }
            95, 96, 99 -> {
                drawCloud(canvas, cx, cy - 11f)
                val bolt = Path().apply {
                    moveTo(cx + 5f, cy + 14f)
                    lineTo(cx - 10f, cy + 41f)
                    lineTo(cx + 1f, cy + 39f)
                    lineTo(cx - 8f, cy + 60f)
                    lineTo(cx + 21f, cy + 29f)
                    lineTo(cx + 8f, cy + 30f)
                    close()
                }
                canvas.drawPath(bolt, fill(Color.rgb(255, 209, 73)))
            }
            else -> drawCloud(canvas, cx, cy)
        }
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, radius: Float = 23f) {
        val gold = Color.rgb(255, 219, 93)
        canvas.drawCircle(cx, cy, radius, fill(gold))
        val rays = stroke(gold, 5.5f)
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

    private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, radius: Float = 23f) {
        // Dark outline follows the crescent; underlying clock hands do not
        // shine through the white portion of the weather icon.
        canvas.drawCircle(cx, cy, radius + 3f, fill(Color.rgb(3, 9, 17)))
        canvas.drawCircle(cx, cy, radius, fill(pale))
        canvas.drawCircle(cx + radius * 0.46f, cy - radius * 0.36f, radius, fill(Color.rgb(8, 17, 27)))
    }

    private fun drawCloud(canvas: Canvas, cx: Float, cy: Float) {
        // Opaque dark underlay + bright outline are robust against media scrims.
        val backing = fill(navy)
        val outline = stroke(white, 6.5f)
        val shape = Path().apply {
            moveTo(cx - 34f, cy + 20f)
            cubicTo(cx - 47f, cy + 19f, cx - 49f, cy - 3f, cx - 34f, cy - 9f)
            cubicTo(cx - 33f, cy - 29f, cx - 12f, cy - 38f, cx + 2f, cy - 26f)
            cubicTo(cx + 25f, cy - 35f, cx + 40f, cy - 17f, cx + 37f, cy - 1f)
            cubicTo(cx + 53f, cy + 2f, cx + 49f, cy + 21f, cx + 31f, cy + 22f)
            close()
        }
        canvas.drawPath(shape, backing)
        canvas.drawPath(shape, outline)
    }
}

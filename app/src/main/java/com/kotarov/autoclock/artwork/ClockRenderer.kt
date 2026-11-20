package com.kotarov.autoclock.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.kotarov.autoclock.media.ClockPreset
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

/**
 * Three main visual styles with seven information arrangements.
 * Art is a single RGB_565 bitmap, refreshed only when the minute or
 * selected settings change. The foreground never animates every second.
 */
object ClockRenderer {
    const val SIZE = 512
    private const val CENTER_X = 256f
    private const val CENTER_Y = 190f
    private const val COMPACT_RADIUS = 96f
    private const val STANDARD_RADIUS = 122f
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
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
        condition: String? = null
    ): Bitmap {
        require(outputSize in 192..SIZE) { "Unsupported artwork size" }
        val bitmap = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.scale(outputSize.toFloat() / SIZE, outputSize.toFloat() / SIZE)
        canvas.drawColor(Color.rgb(2, 5, 11))

        val preset = ClockPreset.fromId(presetId)
        val radius = if (compact) COMPACT_RADIUS else STANDARD_RADIUS
        val unit = radius / STANDARD_RADIUS

        when (preset) {
            ClockPreset.ANALOG_TIME -> {
                drawDial(canvas, time, radius)
                drawText(canvas, time.format(timeFormat), CENTER_X, CENTER_Y, 46f * unit, white, unit)
            }
            ClockPreset.ANALOG_TIME_WEATHER -> {
                drawDial(canvas, time, radius)
                drawText(canvas, time.format(timeFormat), CENTER_X, CENTER_Y - 24f * unit, 46f * unit, white, unit)
                drawWeatherRow(canvas, CENTER_X, CENTER_Y + 35f * unit,
                    weatherCode, isDay, temperature, 29f * unit, unit)
            }
            ClockPreset.ANALOG_WEATHER -> {
                drawDial(canvas, time, radius)
                drawWeatherRow(canvas, CENTER_X, CENTER_Y, weatherCode, isDay,
                    temperature, 49f * unit, unit)
            }
            ClockPreset.DIGITAL_TIME -> {
                drawText(canvas, time.format(timeFormat), CENTER_X, CENTER_Y - 10f * unit,
                    96f * unit, white, unit)
                drawText(canvas, time.format(dateFormat), CENTER_X, CENTER_Y + 55f * unit,
                    29f * unit, pale, unit)
            }
            ClockPreset.DIGITAL_TIME_WEATHER -> {
                // Digital time dominates; weather is intentionally secondary.
                drawText(canvas, time.format(timeFormat), CENTER_X, CENTER_Y - 30f * unit,
                    88f * unit, white, unit)
                drawWeatherRow(canvas, CENTER_X, CENTER_Y + 44f * unit,
                    weatherCode, isDay, temperature, 36f * unit, unit)
            }
            ClockPreset.WEATHER_ONLY -> {
                drawWeatherRow(canvas, CENTER_X, CENTER_Y - 18f * unit,
                    weatherCode, isDay, temperature, 84f * unit, unit)
                drawText(canvas, condition ?: "Няма данни", CENTER_X, CENTER_Y + 66f * unit,
                    28f * unit, pale, unit)
            }
            ClockPreset.WEATHER_WITH_TIME -> {
                // Weather dominates; digital clock is secondary on the second row.
                drawWeatherRow(canvas, CENTER_X, CENTER_Y - 38f * unit,
                    weatherCode, isDay, temperature, 81f * unit, unit)
                drawText(canvas, time.format(timeFormat), CENTER_X, CENTER_Y + 49f * unit,
                    42f * unit, pale, unit)
            }
        }
        return bitmap
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

    private fun drawDial(canvas: Canvas, now: LocalDateTime, radius: Float) {
        val unit = radius / STANDARD_RADIUS
        val x = CENTER_X
        val y = CENTER_Y
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

    private fun drawWeatherRow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        code: Int?,
        isDay: Boolean,
        temperature: String?,
        textSize: Float,
        unit: Float
    ) {
        val shown = temperature ?: "--°C"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val textWidth = textPaint.measureText(shown)
        val iconWidth = if (code != null && code >= 0) textSize * 1.23f else 0f
        val gap = if (iconWidth > 0f) textSize * 0.16f else 0f
        val totalWidth = textWidth + iconWidth + gap
        // Keep weather content inside the safe square even for the biggest label.
        val fit = minOf(1f, (2f * STANDARD_RADIUS * unit - 8f) / totalWidth)
        val left = cx - totalWidth * fit / 2f
        if (iconWidth > 0f) {
            canvas.save()
            canvas.translate(left + iconWidth * fit / 2f, cy - 1f * unit)
            canvas.scale(textSize * 0.014f * fit, textSize * 0.014f * fit)
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
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.CENTER
            textSize = size
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val baseline = centreY - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2f
        val glow = Paint(p).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * unit
            this.color = Color.argb(175, 99, 195, 255)
            strokeJoin = Paint.Join.ROUND
            setShadowLayer(10f * unit, 0f, 0f, Color.argb(205, 94, 191, 255))
        }
        val edge = Paint(p).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.4f * unit
            this.color = Color.rgb(8, 24, 38)
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawText(value, x, baseline, glow)
        canvas.drawText(value, x, baseline, edge)
        canvas.drawText(value, x, baseline, p)
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

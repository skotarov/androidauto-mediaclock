package com.kotarov.autoclock.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.sin

/**
 * Artwork is visual only. Android Auto paints its own title, subtitle and
 * playback controls on top of this image, so we never bake words into it.
 *
 * Every mode has the same, high-contrast analog clock background.
 * Active weather data adds a vector weather symbol and temperature to the upper right.
 * The minute hand changes once per minute; no animated second hand.
 */
object ClockRenderer {
    const val SIZE = 512

    // Portrait artwork is cropped/overlaid by Android Auto media controls.
    // Keep the lower ~160 px unused; fit the dial above the control strip.
    // No reliable compact/full-screen signal is exposed through MediaSession.
    private const val DIAL_Y = 187f
    private const val DIAL_X = 256f
    private const val DIAL_RADIUS = 146f
    private const val WEATHER_DIAL_X = 212f
    private const val WEATHER_DIAL_RADIUS = 125f
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val white = Color.rgb(255, 255, 255)
    private val pale = Color.rgb(224, 239, 247)
    private val cyan = Color.rgb(108, 218, 255)
    private val navy = Color.rgb(12, 23, 35)

    fun render(
        mode: String,
        time: LocalDateTime,
        weatherCode: Int? = null,
        isDay: Boolean = true,
        temperature: String? = null
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(2, 5, 11))

        // "analog" deliberately suppresses weather, even if an old overlay
        // preference was enabled in the previous release.
        val withWeather = (mode == "weather" || mode == "both") &&
            weatherCode != null && weatherCode >= 0
        drawClock(canvas, time, withWeather, showDigits = mode != "weather")
        if (withWeather) {
            drawWeatherBadge(canvas, weatherCode!!, isDay, temperature)
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

    private fun drawClock(
        canvas: Canvas,
        now: LocalDateTime,
        withWeather: Boolean,
        showDigits: Boolean
    ) {
        // Clock-only uses the horizontal centre, while weather variants
        // reserve a narrow independent column for the weather badge.
        val cx = if (withWeather) WEATHER_DIAL_X else DIAL_X
        val cy = DIAL_Y
        val r = if (withWeather) WEATHER_DIAL_RADIUS else DIAL_RADIUS

        canvas.drawCircle(cx, cy, r + 1f, fill(Color.rgb(8, 17, 27)))
        canvas.drawCircle(cx, cy, r, stroke(pale, 5.5f))
        canvas.drawCircle(cx, cy, r - 11f, stroke(Color.rgb(53, 92, 115), 2f))

        val hourTick = stroke(white, 7.5f)
        val minuteTick = stroke(Color.rgb(157, 193, 210), 2.5f)
        repeat(60) { index ->
            val angle = Math.toRadians((index * 6 - 90).toDouble())
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            val major = index % 5 == 0
            val inner = r - if (major) 28f else 19f
            val outer = r - 7f
            canvas.drawLine(
                cx + dx * inner, cy + dy * inner,
                cx + dx * outer, cy + dy * outer,
                if (major) hourTick else minuteTick
            )
        }

        val hourAngle = Math.toRadians(
            ((now.hour % 12) * 30.0 + now.minute * 0.5 - 90.0)
        )
        val minuteAngle = Math.toRadians((now.minute * 6 - 90).toDouble())

        canvas.drawLine(
            cx, cy, cx + cos(hourAngle).toFloat() * r * 0.65f,
            cy + sin(hourAngle).toFloat() * r * 0.65f,
            stroke(white, if (withWeather) 11f else 13f)
        )
        canvas.drawLine(
            cx, cy, cx + cos(minuteAngle).toFloat() * r * 0.79f,
            cy + sin(minuteAngle).toFloat() * r * 0.79f,
            stroke(cyan, if (withWeather) 7f else 8f)
        )

        if (showDigits) {
            // Put HH:mm directly at the pivot. A dark opaque plate masks
            // the crossed hands so the numbers remain readable at any angle.
            val halfWidth = if (withWeather) 68f else 78f
            val halfHeight = if (withWeather) 23f else 26f
            val labelBounds = RectF(
                cx - halfWidth, cy - halfHeight,
                cx + halfWidth, cy + halfHeight
            )
            canvas.drawRoundRect(
                labelBounds, 11f, 11f, fill(Color.rgb(10, 24, 36))
            )
            canvas.drawRoundRect(
                labelBounds, 11f, 11f, stroke(Color.rgb(147, 197, 220), 1.8f)
            )
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = white
                textAlign = Paint.Align.CENTER
                textSize = if (withWeather) 37f else 43f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val middleBaseline = cy - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f
            canvas.drawText(now.format(timeFormat), cx, middleBaseline, label)
        } else {
            canvas.drawCircle(cx, cy, 8f, fill(white))
            canvas.drawCircle(cx, cy, 3.5f, fill(navy))
        }
    }

    /**
     * Icons are drawn from vectors, not fonts or emoji. They remain legible
     * even if Android Auto desaturates and dims the album art.
     */
    private fun drawWeatherBadge(
        canvas: Canvas,
        code: Int,
        isDay: Boolean,
        temperature: String?
    ) {
        // Place real weather information above Android Auto's lower-left
        // media text and transport buttons. The card is part of the bitmap.
        val rect = RectF(350f, 71f, 480f, 227f)
        canvas.drawRoundRect(rect, 19f, 19f, fill(Color.rgb(5, 16, 26)))
        canvas.drawRoundRect(rect, 19f, 19f, stroke(Color.rgb(186, 219, 235), 3.5f))
        drawWeatherIcon(canvas, code, isDay)
        if (!temperature.isNullOrBlank()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 36f
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF,
                    android.graphics.Typeface.BOLD
                )
            }
            canvas.drawText(temperature, 415f, 208f, p)
        }
    }

    private fun drawWeatherIcon(canvas: Canvas, code: Int?, isDay: Boolean) {
        val cx = 415f
        val cy = 128f
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

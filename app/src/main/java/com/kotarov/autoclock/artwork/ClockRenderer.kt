package com.kotarov.autoclock.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.sin

/**
 * Artwork is visual only. Android Auto paints its own title, subtitle and
 * playback controls on top of this image, so we never bake words into it.
 *
 * Every mode has the same, high-contrast analog clock background.
 * Weather modes add a small vector weather symbol at the upper right.
 * The minute hand changes once per minute; no animated second hand.
 */
object ClockRenderer {
    const val SIZE = 512

    private const val CENTER_X = 253f
    private const val CENTER_Y = 235f
    private const val RADIUS = 194f
    private val white = Color.rgb(255, 255, 255)
    private val pale = Color.rgb(224, 239, 247)
    private val cyan = Color.rgb(108, 218, 255)
    private val navy = Color.rgb(12, 23, 35)

    fun render(
        mode: String,
        time: LocalDateTime,
        weatherCode: Int? = null,
        isDay: Boolean = true
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(2, 5, 11))

        drawClock(canvas, time)
        if (mode == "weather" || mode == "both") {
            drawWeatherIcon(canvas, weatherCode, isDay)
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

    private fun drawClock(canvas: Canvas, now: LocalDateTime) {
        // The dial is slightly right of centre so that Android Auto's
        // lower-left media text doesn't compete with the hands.
        canvas.drawCircle(CENTER_X, CENTER_Y, RADIUS + 2f, fill(Color.rgb(8, 17, 27)))
        canvas.drawCircle(CENTER_X, CENTER_Y, RADIUS, stroke(pale, 8f))
        canvas.drawCircle(CENTER_X, CENTER_Y, RADIUS - 13f, stroke(Color.rgb(42, 80, 105), 2.5f))

        val hourTicks = stroke(white, 10f)
        val minuteTicks = stroke(Color.rgb(145, 182, 204), 3.5f)
        repeat(60) { index ->
            val radians = Math.toRadians((index * 6 - 90).toDouble())
            val dx = cos(radians).toFloat()
            val dy = sin(radians).toFloat()
            val important = index % 5 == 0
            val inner = RADIUS - if (important) 34f else 24f
            val outer = RADIUS - 10f
            canvas.drawLine(
                CENTER_X + dx * inner, CENTER_Y + dy * inner,
                CENTER_X + dx * outer, CENTER_Y + dy * outer,
                if (important) hourTicks else minuteTicks
            )
        }

        val hourAngle = Math.toRadians(
            ((now.hour % 12) * 30.0 + now.minute * 0.5 - 90.0)
        )
        val minuteAngle = Math.toRadians((now.minute * 6 - 90).toDouble())
        canvas.drawLine(
            CENTER_X, CENTER_Y,
            CENTER_X + cos(hourAngle).toFloat() * 105f,
            CENTER_Y + sin(hourAngle).toFloat() * 105f,
            stroke(white, 19f)
        )
        canvas.drawLine(
            CENTER_X, CENTER_Y,
            CENTER_X + cos(minuteAngle).toFloat() * 148f,
            CENTER_Y + sin(minuteAngle).toFloat() * 148f,
            stroke(cyan, 12f)
        )
        canvas.drawCircle(CENTER_X, CENTER_Y, 11f, fill(white))
        canvas.drawCircle(CENTER_X, CENTER_Y, 5f, fill(navy))
    }

    /**
     * Icons are drawn from vectors, not fonts or emoji. They remain legible
     * even if Android Auto desaturates and dims the album art.
     */
    private fun drawWeatherIcon(canvas: Canvas, code: Int?, isDay: Boolean) {
        val cx = 386f
        val cy = 119f
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

package com.kotarov.autoclock.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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

    // The media artwork is the same 512px image in compact and full cards.
    // Because Android Auto doesn't expose the card dimensions to this service,
    // users can choose the conservative default or a larger dial on the phone.
    // Both variants leave the lower area clear for transport controls.
    private const val DIAL_X = 256f
    private const val DIAL_Y = 196f
    private const val COMPACT_RADIUS = 96f
    private const val STANDARD_RADIUS = 122f
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
        temperature: String? = null,
        compact: Boolean = true
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(2, 5, 11))

        val weatherEnabled = (mode == "weather" || mode == "both") &&
            weatherCode != null && weatherCode >= 0 && !temperature.isNullOrBlank()
        drawClock(
            canvas = canvas,
            now = time,
            radius = if (compact) COMPACT_RADIUS else STANDARD_RADIUS,
            weatherCode = if (weatherEnabled) weatherCode else null,
            isDay = isDay,
            temperature = if (weatherEnabled) temperature else null
        )
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
        radius: Float,
        weatherCode: Int?,
        isDay: Boolean,
        temperature: String?
    ) {
        val cx = DIAL_X
        val cy = DIAL_Y
        val r = radius
        val unit = r / STANDARD_RADIUS

        canvas.drawCircle(cx, cy, r + 1f, fill(Color.rgb(8, 17, 27)))
        canvas.drawCircle(cx, cy, r, stroke(pale, 4.8f * unit))
        canvas.drawCircle(cx, cy, r - 9f * unit, stroke(Color.rgb(53, 92, 115), 1.8f * unit))

        val hourTick = stroke(white, 6.5f * unit)
        val minuteTick = stroke(Color.rgb(157, 193, 210), 2.3f * unit)
        repeat(60) { index ->
            val a = Math.toRadians((index * 6 - 90).toDouble())
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            val major = index % 5 == 0
            val inner = r - if (major) 26f * unit else 17f * unit
            val outer = r - 7f * unit
            canvas.drawLine(
                cx + dx * inner, cy + dy * inner,
                cx + dx * outer, cy + dy * outer,
                if (major) hourTick else minuteTick
            )
        }

        val h = Math.toRadians((now.hour % 12) * 30.0 + now.minute * 0.5 - 90.0)
        val m = Math.toRadians(now.minute * 6.0 - 90.0)
        canvas.drawLine(
            cx, cy,
            cx + cos(h).toFloat() * r * 0.68f,
            cy + sin(h).toFloat() * r * 0.68f,
            stroke(white, 10f * unit)
        )
        canvas.drawLine(
            cx, cy,
            cx + cos(m).toFloat() * r * 0.83f,
            cy + sin(m).toFloat() * r * 0.83f,
            stroke(cyan, 6.5f * unit)
        )

        // No rectangle, background or border behind the digits.
        // A subtle shadow + dark thin outline helps on top of the hands.
        val withWeather = weatherCode != null && temperature != null
        val timeSize = (if (withWeather) 39f else 46f) * unit
        val timeY = if (withWeather) cy - 18f * unit else cy
        drawGlowingText(canvas, now.format(timeFormat), cx, timeY, timeSize, white)

        if (withWeather) {
            val lineY = cy + 37f * unit
            val iconX = cx - 47f * unit
            val textX = cx + 16f * unit
            canvas.save()
            canvas.translate(iconX, lineY - 8f * unit)
            canvas.scale(0.47f * unit, 0.47f * unit)
            drawWeatherIcon(canvas, weatherCode!!, isDay)
            canvas.restore()
            drawGlowingText(
                canvas, temperature!!, textX, lineY,
                32f * unit, white
            )
        }
    }

    private fun drawGlowingText(
        canvas: Canvas,
        value: String,
        x: Float,
        centreY: Float,
        size: Float,
        color: Int
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.CENTER
            textSize = size
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(3f, 0f, 1f, Color.rgb(4, 10, 17))
        }
        val baseline = centreY - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2f
        // Thin outline is confined to the glyphs; not a box.
        val edge = Paint(p).apply {
            clearShadowLayer()
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
            this.color = Color.rgb(7, 22, 35)
            strokeJoin = Paint.Join.ROUND
        }
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

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
 * Artwork can be a digital-only face or a high-contrast analogue face.
 * Combined weather is aligned below the full-size time digits, without a box.
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
        compact: Boolean = true,
        outputSize: Int = SIZE
    ): Bitmap {
        require(outputSize in 192..SIZE) { "Unsupported artwork size" }
        val bitmap = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.scale(outputSize.toFloat() / SIZE, outputSize.toFloat() / SIZE)
        canvas.drawColor(Color.rgb(2, 5, 11))

        val r = if (compact) COMPACT_RADIUS else STANDARD_RADIUS
        if (mode == "clock" || mode == "digital") {
            // This is a genuinely digital-only display: no analogue ring or hands.
            drawDigitalFace(canvas, time, r)
        } else {
            val weatherEnabled = (mode == "weather" || mode == "both") &&
                weatherCode != null && weatherCode >= 0 && !temperature.isNullOrBlank()
            drawClock(
                canvas = canvas,
                now = time,
                radius = r,
                weatherCode = if (weatherEnabled) weatherCode else null,
                isDay = isDay,
                temperature = if (weatherEnabled) temperature else null
            )
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

    private fun drawDigitalFace(canvas: Canvas, now: LocalDateTime, radius: Float) {
        val unit = radius / STANDARD_RADIUS
        drawGlowingText(
            canvas, now.format(timeFormat), DIAL_X, DIAL_Y - 11f * unit,
            96f * unit, white, unit
        )
        drawGlowingText(
            canvas, now.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
            DIAL_X, DIAL_Y + 55f * unit, 29f * unit, pale, unit
        )
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
        // HH:mm has exactly the same font size with or without weather.
        val timeSize = 46f * unit
        val timeY = if (withWeather) cy - 25f * unit else cy
        drawGlowingText(canvas, now.format(timeFormat), cx, timeY, timeSize, white, unit)

        if (withWeather) {
            drawWeatherLine(canvas, cx, cy + 39f * unit, weatherCode!!, isDay, temperature!!, unit)
        }
    }

    /**
     * Horizontally centre the complete weather row, not the individual pieces.
     * Icon and temperature share one optical centreline and scale together.
     */
    private fun drawWeatherLine(
        canvas: Canvas,
        cx: Float,
        y: Float,
        code: Int,
        isDay: Boolean,
        temperature: String,
        unit: Float
    ) {
        val textSize = 32f * unit
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val textWidth = textPaint.measureText(temperature)
        val iconWidth = 57f * unit
        val gap = 7f * unit
        val totalWidth = iconWidth + gap + textWidth
        val left = cx - totalWidth / 2f
        val iconX = left + iconWidth / 2f
        val textX = left + iconWidth + gap + textWidth / 2f

        canvas.save()
        canvas.translate(iconX, y)
        canvas.scale(0.56f * unit, 0.56f * unit)
        drawWeatherIcon(canvas, code, isDay)
        canvas.restore()

        drawGlowingText(canvas, temperature, textX, y, textSize, white, unit)
    }

    private fun drawGlowingText(
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
        // The thin dark glyph outline protects readability over moving hands;
        // this is not a rectangular background or padded label.
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

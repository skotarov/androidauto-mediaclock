package com.kotarov.autoclock.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * One small, opaque bitmap per requested minute. RGB_565 at 512x512 uses
 * roughly 0.5 MiB of pixel memory instead of 4 MiB for 1024x1024 ARGB_8888.
 */
object ClockRenderer {
    const val SIZE = 512
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale("bg", "BG"))
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale("bg", "BG"))

    fun render(mode: String, time: LocalDateTime, temperature: String = "--°C", condition: String = "Няма данни"): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(10, 10, 12))

        when (mode) {
            "analog" -> drawAnalog(canvas, time)
            "weather" -> drawWeather(canvas, time, temperature, condition)
            "both" -> drawDigital(canvas, time, withWeather = true, temperature = temperature, condition = condition)
            else -> drawDigital(canvas, time, withWeather = false)
        }
        return bitmap
    }

    private fun paint(size: Float, color: Int = Color.WHITE, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(
                Typeface.SANS_SERIF,
                if (bold) Typeface.BOLD else Typeface.NORMAL
            )
        }

    private fun drawDigital(canvas: Canvas, now: LocalDateTime, withWeather: Boolean, temperature: String = "--°C", condition: String = "Няма данни") {
        canvas.drawText(now.format(timeFormat), 256f, 245f, paint(114f, bold = true))
        if (withWeather) {
            canvas.drawText(temperature, 256f, 323f, paint(65f, Color.LTGRAY))
            canvas.drawText(condition, 256f, 376f, paint(28f, Color.LTGRAY))
        } else {
            canvas.drawText(now.format(dateFormat), 256f, 320f, paint(37f, Color.LTGRAY))
        }
        canvas.drawText("AUTO CLOCK", 256f, 430f, paint(23f, Color.GRAY))
    }

    private fun drawWeather(canvas: Canvas, now: LocalDateTime, temperature: String, condition: String) {
        canvas.drawText(temperature, 256f, 245f, paint(110f, bold = true))
        canvas.drawText(condition, 256f, 313f, paint(30f, Color.LTGRAY))
        canvas.drawText(now.format(timeFormat), 256f, 423f, paint(35f, Color.GRAY))
    }

    private fun drawAnalog(canvas: Canvas, now: LocalDateTime) {
        val centerX = 256f
        val centerY = 240f
        val radius = 186f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(centerX, centerY, radius, ring)

        val marks = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
        }
        repeat(12) { i ->
            val angle = Math.toRadians((i * 30 - 90).toDouble())
            val x = cos(angle).toFloat()
            val y = sin(angle).toFloat()
            canvas.drawLine(
                centerX + x * (radius - 28f), centerY + y * (radius - 28f),
                centerX + x * (radius - 11f), centerY + y * (radius - 11f),
                marks
            )
        }

        val hourAngle = Math.toRadians(
            ((now.hour % 12) * 30 + now.minute * 0.5 - 90)
        )
        val minuteAngle = Math.toRadians((now.minute * 6 - 90).toDouble())
        val hands = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
        }
        hands.strokeWidth = 11f
        canvas.drawLine(
            centerX, centerY,
            centerX + cos(hourAngle).toFloat() * 102f,
            centerY + sin(hourAngle).toFloat() * 102f,
            hands
        )
        hands.strokeWidth = 7f
        canvas.drawLine(
            centerX, centerY,
            centerX + cos(minuteAngle).toFloat() * 146f,
            centerY + sin(minuteAngle).toFloat() * 146f,
            hands
        )
        canvas.drawCircle(centerX, centerY, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        })
        canvas.drawText(now.format(timeFormat), centerX, 478f, paint(42f, Color.LTGRAY, true))
    }
}

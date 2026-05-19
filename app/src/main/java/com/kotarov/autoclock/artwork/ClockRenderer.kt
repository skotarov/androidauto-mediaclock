package com.kotarov.autoclock.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

object ClockRenderer {
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun renderClock(now: LocalDateTime, size: Int = 1024): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(10, 10, 12))

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.22f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(210, 210, 210)
            textSize = size * 0.055f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(150, 150, 150)
            textSize = size * 0.04f
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.08f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        drawCenteredText(canvas, now.format(timeFormatter), size / 2f, size * 0.48f, timePaint)
        drawCenteredText(canvas, now.format(dateFormatter), size / 2f, size * 0.59f, datePaint)
        drawCenteredText(canvas, "CLOCK", size / 2f, size * 0.78f, brandPaint)
        return bitmap
    }

    fun renderWeather(now: LocalDateTime, weatherText: String = "--°C", size: Int = 1024): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(10, 10, 12))

        val weatherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.20f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(210, 210, 210)
            textSize = size * 0.06f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(150, 150, 150)
            textSize = size * 0.05f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        drawCenteredText(canvas, weatherText, size / 2f, size * 0.46f, weatherPaint)
        drawCenteredText(canvas, "WEATHER", size / 2f, size * 0.60f, labelPaint)
        drawCenteredText(canvas, now.format(timeFormatter), size / 2f, size * 0.78f, timePaint)
        return bitmap
    }

    fun renderClockWeather(now: LocalDateTime, weatherText: String = "--°C", size: Int = 1024): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(10, 10, 12))

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.18f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val weatherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(225, 225, 225)
            textSize = size * 0.11f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(150, 150, 150)
            textSize = size * 0.04f
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.08f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        drawCenteredText(canvas, now.format(timeFormatter), size / 2f, size * 0.42f, timePaint)
        drawCenteredText(canvas, weatherText, size / 2f, size * 0.58f, weatherPaint)
        drawCenteredText(canvas, "CLOCK + WEATHER", size / 2f, size * 0.78f, labelPaint)
        return bitmap
    }

    fun renderAnalog(now: LocalDateTime, size: Int = 1024): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(10, 10, 12))

        val center = size / 2f
        val radius = size * 0.38f
        val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 28, 32); style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = size * 0.012f }
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
        val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(210, 210, 210)
            textSize = size * 0.05f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        canvas.drawCircle(center, center, radius, facePaint)
        canvas.drawCircle(center, center, radius, strokePaint)
        for (i in 0 until 60) {
            val angle = Math.toRadians((i * 6 - 90).toDouble())
            val isHour = i % 5 == 0
            tickPaint.strokeWidth = if (isHour) size * 0.01f else size * 0.004f
            val inner = radius - if (isHour) size * 0.055f else size * 0.03f
            val outer = radius - size * 0.012f
            canvas.drawLine(center + cos(angle).toFloat() * inner, center + sin(angle).toFloat() * inner, center + cos(angle).toFloat() * outer, center + sin(angle).toFloat() * outer, tickPaint)
        }

        val minute = now.minute
        val hour = now.hour % 12
        val minuteAngle = Math.toRadians((minute * 6 - 90).toDouble())
        val hourAngle = Math.toRadians(((hour * 30) + (minute * 0.5) - 90).toDouble())
        handPaint.strokeWidth = size * 0.018f
        canvas.drawLine(center, center, center + cos(hourAngle).toFloat() * radius * 0.5f, center + sin(hourAngle).toFloat() * radius * 0.5f, handPaint)
        handPaint.strokeWidth = size * 0.012f
        canvas.drawLine(center, center, center + cos(minuteAngle).toFloat() * radius * 0.72f, center + sin(minuteAngle).toFloat() * radius * 0.72f, handPaint)
        canvas.drawCircle(center, center, size * 0.018f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        drawCenteredText(canvas, now.format(timeFormatter), center, size * 0.88f, textPaint)
        return bitmap
    }

    private fun drawCenteredText(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        canvas.drawText(text, x, y - bounds.exactCenterY(), paint)
    }
}

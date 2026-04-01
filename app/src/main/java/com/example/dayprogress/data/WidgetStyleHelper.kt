package com.example.dayprogress.data

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable

object WidgetStyleHelper {

    fun createBackgroundBitmap(
        backgroundColor: Int,
        borderColor: Int,
        borderThickness: Int,
        borderEnabled: Boolean,
        cornerRadius: Float = 12f,
        widthDp: Int = 200,
        heightDp: Int = 100
    ): Bitmap {
        val density = Resources.getSystem().displayMetrics.density
        val width = (widthDp * density).toInt().coerceAtLeast(1)
        val height = (heightDp * density).toInt().coerceAtLeast(1)

        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(backgroundColor)
            this.cornerRadius = cornerRadius * density

            if (borderEnabled && borderThickness > 0) {
                setStroke((borderThickness * density).toInt().coerceAtLeast(1), borderColor)
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            shape.setBounds(0, 0, width, height)
            shape.draw(canvas)
        }
    }

    fun createProgressBitmap(
        progress: Int,
        filledStartColor: Int,
        filledEndColor: Int,
        unfilledColor: Int,
        cornerRadiusDp: Float = 8f,
        widthDp: Int = 240,
        heightDp: Int = 12
    ): Bitmap {
        val density = Resources.getSystem().displayMetrics.density
        val width = (widthDp * density).toInt().coerceAtLeast(1)
        val height = (heightDp * density).toInt().coerceAtLeast(1)
        val radius = cornerRadiusDp * density
        val clampedProgress = progress.coerceIn(0, 100)
        val progressWidth = (width * (clampedProgress / 100f)).toInt()

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = unfilledColor
            style = Paint.Style.FILL
        }
        val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                0f,
                filledStartColor,
                filledEndColor,
                Shader.TileMode.CLAMP
            )
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val fullRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(fullRect, radius, radius, backgroundPaint)

            if (progressWidth > 0) {
                val progressRect = RectF(0f, 0f, progressWidth.toFloat(), height.toFloat())
                canvas.drawRoundRect(progressRect, radius, radius, progressPaint)
            }
        }
    }
}

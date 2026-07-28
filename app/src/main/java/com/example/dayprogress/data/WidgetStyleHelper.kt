package com.example.dayprogress.data

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.createBitmap

object WidgetStyleHelper {

    fun createBackgroundBitmap(
        backgroundColor: Int,
        borderColor: Int,
        borderThickness: Int,
        borderEnabled: Boolean,
        cornerRadius: Float = 2f,
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

        return createBitmap(width, height).also { bitmap ->
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
        markers: List<WidgetCheckpointMarker> = emptyList(),
        cornerRadiusDp: Float = 2f,
        widthDp: Int = 240,
        heightDp: Int = 12
    ): Bitmap {
        val density = Resources.getSystem().displayMetrics.density
        val width = (widthDp * density).toInt().coerceAtLeast(1)
        val height = (heightDp * density).toInt().coerceAtLeast(1)
        val horizontalInset = (2f * density).coerceAtLeast(1f)
        val verticalInset = (1f * density).coerceAtLeast(1f)
        val contentLeft = horizontalInset
        val contentTop = verticalInset
        val contentRight = (width.toFloat() - horizontalInset).coerceAtLeast(contentLeft + 1f)
        val contentBottom = (height.toFloat() - verticalInset).coerceAtLeast(contentTop + 1f)
        val contentWidth = (contentRight - contentLeft).coerceAtLeast(1f)
        val contentHeight = (contentBottom - contentTop).coerceAtLeast(1f)
        val radius = minOf(contentHeight / 2f, cornerRadiusDp * density)
        val clampedProgress = progress.coerceIn(0, 100)
        val progressWidth = contentWidth * (clampedProgress / 100f)

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = unfilledColor
            style = Paint.Style.FILL
        }
        val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                contentLeft,
                0f,
                contentRight,
                0f,
                filledStartColor,
                filledEndColor,
                Shader.TileMode.CLAMP
            )
        }

        return createBitmap(width, height).also { bitmap ->
            val canvas = Canvas(bitmap)
            val fullRect = RectF(contentLeft, contentTop, contentRight, contentBottom)
            canvas.drawRoundRect(fullRect, radius, radius, backgroundPaint)

            if (progressWidth > 0f) {
                val progressRect = RectF(contentLeft, contentTop, contentLeft + progressWidth, contentBottom)
                canvas.drawRoundRect(progressRect, radius, radius, progressPaint)
            }

            markers.forEach { marker ->
                val markerColor = when (marker.status) {
                    CheckpointStatus.DONE -> 0xFF69DB7C.toInt()
                    CheckpointStatus.NOTIFIED,
                    CheckpointStatus.SNOOZED -> 0xFFFFD166.toInt()
                    CheckpointStatus.SKIPPED,
                    CheckpointStatus.MISSED -> return@forEach
                    CheckpointStatus.SCHEDULED,
                    null -> 0xFFFFFFFF.toInt()
                }
                val markerX = contentLeft + contentWidth * (marker.percent.coerceIn(0f, 100f) / 100f)
                val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xAA000000.toInt()
                    strokeWidth = (3f * density).coerceAtLeast(2f)
                    strokeCap = Paint.Cap.ROUND
                }
                val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = markerColor
                    strokeWidth = (1.5f * density).coerceAtLeast(1f)
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(markerX, contentTop, markerX, contentBottom, outlinePaint)
                canvas.drawLine(markerX, contentTop, markerX, contentBottom, markerPaint)
            }
        }
    }
}

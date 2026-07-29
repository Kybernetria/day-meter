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
import kotlin.math.roundToInt

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
        val width = (widthDp * density).toInt().coerceIn(1, MAX_RASTER_WIDTH_PX)
        val height = (heightDp * density).toInt().coerceIn(1, MAX_RASTER_HEIGHT_PX)

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
        val width = (widthDp * density).toInt().coerceIn(1, MAX_RASTER_WIDTH_PX)
        val height = (heightDp * density).toInt().coerceIn(1, MAX_PROGRESS_HEIGHT_PX)
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

            val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xAA000000.toInt()
                strokeWidth = (3f * density).coerceAtLeast(2f)
                strokeCap = Paint.Cap.ROUND
            }
            val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = (1.5f * density).coerceAtLeast(1f)
                strokeCap = Paint.Cap.ROUND
            }
            markers
                .filter { it.status !in setOf(CheckpointStatus.SKIPPED, CheckpointStatus.MISSED) }
                .groupBy { (contentLeft + contentWidth * (it.percent.coerceIn(0f, 100f) / 100f)).roundToInt() }
                .forEach { (markerPixel, coincidentMarkers) ->
                    val marker = coincidentMarkers.maxByOrNull { markerPriority(it.status) } ?: return@forEach
                    markerPaint.color = when (marker.status) {
                        CheckpointStatus.NOTIFIED, CheckpointStatus.SNOOZED -> 0xFFFFD166.toInt()
                        CheckpointStatus.DONE -> 0xFF69DB7C.toInt()
                        else -> 0xFFFFFFFF.toInt()
                    }
                    val markerX = markerPixel.toFloat()
                    canvas.drawLine(markerX, contentTop, markerX, contentBottom, outlinePaint)
                    canvas.drawLine(markerX, contentTop, markerX, contentBottom, markerPaint)
                }
        }
    }

    private fun markerPriority(status: CheckpointStatus?): Int = when (status) {
        CheckpointStatus.NOTIFIED, CheckpointStatus.SNOOZED -> 4
        CheckpointStatus.DONE -> 3
        CheckpointStatus.SCHEDULED -> 2
        else -> 1
    }

    private const val MAX_RASTER_WIDTH_PX = 800
    private const val MAX_RASTER_HEIGHT_PX = 320
    private const val MAX_PROGRESS_HEIGHT_PX = 96
}

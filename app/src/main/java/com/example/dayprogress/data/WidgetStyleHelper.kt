package com.example.dayprogress.data

import android.content.res.Resources
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import kotlin.math.ceil
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt

object WidgetStyleHelper {

    data class SurfaceSize(val widthDp: Int, val heightDp: Int)

    /** Match the wrap-content surface, so rounded corners and borders aren't stretched. */
    fun measureSurface(
        resources: Resources,
        widgetType: Int,
        barHeightDp: Int,
        primaryText: String,
        primarySizeSp: Float,
        fontFamily: String,
        showCheckpoint: Boolean,
        maxWidthDp: Int,
        maxHeightDp: Int
    ): SurfaceSize {
        val metrics = resources.displayMetrics
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, primarySizeSp, metrics)
            typeface = Typeface.create(fontFamily.takeUnless { it == "default" }, Typeface.BOLD)
        }
        fun lineHeight(): Int = ceil((paint.fontMetricsInt.descent - paint.fontMetricsInt.ascent) / metrics.density).toInt()
        val width = if (widgetType == 1) ceil(paint.measureText(primaryText) / metrics.density).toInt() + 8 else maxWidthDp
        var height = 4 // 2dp above and below the content
        if (widgetType != 0) height += lineHeight()
        if (widgetType != 1) height += barHeightDp + if (widgetType == 2) 2 else 0
        if (showCheckpoint) {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, metrics)
            paint.typeface = Typeface.DEFAULT
            height += lineHeight() + 4
        }
        return SurfaceSize(width.coerceIn(1, maxWidthDp), height.coerceIn(1, maxHeightDp))
    }

    fun createBackgroundBitmap(
        backgroundColor: Int,
        borderColor: Int,
        borderThickness: Int,
        borderEnabled: Boolean,
        cornerRadius: Float = 6f,
        widthDp: Int = 200,
        heightDp: Int = 100
    ): Bitmap {
        val density = Resources.getSystem().displayMetrics.density
        val width = (widthDp * density).toInt().coerceIn(1, MAX_RASTER_WIDTH_PX)
        val height = (heightDp * density).toInt().coerceIn(1, MAX_RASTER_HEIGHT_PX)
        val alpha = Color.alpha(backgroundColor)
        val radius = minOf(cornerRadius * density, height / 2f, width / 2f).coerceAtLeast(0f)
        val shape = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.setAlphaComponent(ColorUtils.blendARGB(backgroundColor, Color.WHITE, 0.015f), alpha),
                backgroundColor
            )
        ).apply {
            this.cornerRadius = radius
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
        cornerRadiusDp: Float = 6f,
        widthDp: Int = 240,
        heightDp: Int = 12,
        barStyle: Int = 1
    ): Bitmap {
        val density = Resources.getSystem().displayMetrics.density
        val width = (widthDp * density).toInt().coerceIn(1, MAX_RASTER_WIDTH_PX)
        val height = (heightDp * density).toInt().coerceIn(1, MAX_PROGRESS_HEIGHT_PX)
        val horizontalInset = minOf(2f * density, width / 4f)
        val verticalInset = minOf(1f * density, height / 4f)
        val contentLeft = horizontalInset
        val contentTop = verticalInset
        val contentRight = width - horizontalInset
        val contentBottom = height - verticalInset
        val contentWidth = contentRight - contentLeft
        val contentHeight = contentBottom - contentTop
        val radius = minOf(contentHeight / 2f, cornerRadiusDp.coerceAtLeast(0f) * density)
        val progressWidth = contentWidth * (progress.coerceIn(0, 100) / 100f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        return createBitmap(width, height).also { bitmap ->
            val canvas = Canvas(bitmap)
            val fullRect = RectF(contentLeft, contentTop, contentRight, contentBottom)
            val rail = Path().apply { addRoundRect(fullRect, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(rail)
            paint.color = unfilledColor
            // Keep the track out of the filled region, including its antialiased edge.
            // Otherwise a transparent fill leaves a visible halo of the track beneath it.
            canvas.drawRect(contentLeft + progressWidth, contentTop, contentRight, contentBottom, paint)

            if (progressWidth > 0f) {
                paint.shader = LinearGradient(
                    contentLeft, 0f, contentRight, 0f,
                    filledStartColor, filledEndColor, Shader.TileMode.CLAMP
                )
                canvas.drawRect(contentLeft, contentTop, contentLeft + progressWidth, contentBottom, paint)
                paint.shader = null
            }

            val divisions = when (barStyle.coerceIn(0, 2)) {
                1 -> (widthDp / 12).coerceIn(10, 24)
                2 -> (widthDp / 6).coerceIn(20, 48)
                else -> 0
            }
            if (divisions > 0) {
                val step = contentWidth / divisions
                val gap = minOf(density, step / 4f)
                paint.color = Color.TRANSPARENT
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                for (index in 1 until divisions) {
                    val x = contentLeft + index * step
                    canvas.drawRect(x - gap / 2, contentTop, x + gap / 2, contentBottom, paint)
                }
                paint.xfermode = null
            }

            canvas.restore()

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
                .filter { it.status != CheckpointStatus.SKIPPED && it.status != CheckpointStatus.MISSED }
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

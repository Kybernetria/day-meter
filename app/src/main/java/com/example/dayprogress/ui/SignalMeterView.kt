package com.example.dayprogress.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.appcompat.R as AppCompatR

/** A static, allocation-free draw pass; redraws only when the day signal changes. */
class SignalMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = TypedValue().also {
        context.theme.resolveAttribute(AppCompatR.attr.colorPrimary, it, true)
    }.data
    private val track = ColorUtils.setAlphaComponent(accent, 32)
    private val gap = 3f * resources.displayMetrics.density

    var progress: Int = 0
        set(value) {
            val bounded = value.coerceIn(0, 100)
            if (field != bounded) {
                field = bounded
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = 40
        val step = width.toFloat() / count
        val segmentGap = gap.coerceAtMost(step / 2)
        for (index in 0 until count) {
            paint.color = if (index * 100 < progress * count) accent else track
            val left = index * step
            val top = if (index % 5 == 0) 0f else height * 0.24f
            canvas.drawRect(left, top, left + step - segmentGap, height.toFloat(), paint)
        }
    }
}

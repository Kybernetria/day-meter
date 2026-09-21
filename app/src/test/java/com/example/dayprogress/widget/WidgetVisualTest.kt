package com.example.dayprogress.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.R
import com.example.dayprogress.data.CheckpointStatus
import com.example.dayprogress.data.WidgetCheckpointMarker
import com.example.dayprogress.data.WidgetStyleHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetVisualTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val cyan = 0xFF40E0D0.toInt()

    @Test
    fun backgroundPreservesTransparentAndTranslucentSurfaces() {
        for (alpha in listOf(0, 64, 128, 255)) {
            val bitmap = WidgetStyleHelper.createBackgroundBitmap(
                Color.argb(alpha, 10, 20, 30), Color.WHITE, 2, false,
                widthDp = 200, heightDp = 48
            )
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertEquals(alpha, pixels.maxOf(Color::alpha))
            assertEquals(alpha, Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)))
        }
    }

    @Test
    fun transparentSurfaceStillAllowsExplicitBorder() {
        val bitmap = WidgetStyleHelper.createBackgroundBitmap(0, Color.WHITE, 2, true, widthDp = 200, heightDp = 48)
        assertEquals(0, Color.alpha(bitmap.getPixel(100, 24)))
        assertTrue(Color.alpha(bitmap.getPixel(100, 1)) > 0)
    }

    @Test
    fun railClampsProgressAndRespectsFillTransparency() {
        assertTrue(rail(-20).sameAs(rail(0)))
        assertTrue(rail(180).sameAs(rail(100)))
        val transparent = WidgetStyleHelper.createProgressBitmap(100, 0, 0, Color.WHITE)
        val pixels = IntArray(transparent.width * transparent.height)
        transparent.getPixels(pixels, 0, transparent.width, 0, 0, transparent.width, transparent.height)
        assertTrue(pixels.all { Color.alpha(it) == 0 })
        val half = rail(50)
        assertEquals(cyan, half.getPixel(20, half.height / 2))
        assertEquals(Color.DKGRAY, half.getPixel(220, half.height / 2))
    }

    @Test
    fun barStylesHaveDistinctDensityWithoutChangingRasterSize() {
        val smooth = WidgetStyleHelper.createProgressBitmap(60, cyan, 0xFFFF71CF.toInt(), Color.DKGRAY, widthDp = 240, heightDp = 12, barStyle = 0)
        val sectioned = WidgetStyleHelper.createProgressBitmap(60, cyan, 0xFFFF71CF.toInt(), Color.DKGRAY, widthDp = 240, heightDp = 12, barStyle = 1)
        val micro = WidgetStyleHelper.createProgressBitmap(60, cyan, 0xFFFF71CF.toInt(), Color.DKGRAY, widthDp = 240, heightDp = 12, barStyle = 2)
        assertEquals(smooth.width, sectioned.width)
        assertEquals(smooth.height, micro.height)
        assertFalse(smooth.sameAs(sectioned))
        assertFalse(sectioned.sameAs(micro))
    }

    @Test
    fun checkpointMarkersRemainVisibleAndKeepPriority() {
        val done = WidgetCheckpointMarker(50f, CheckpointStatus.DONE, "Done")
        val notified = WidgetCheckpointMarker(50f, CheckpointStatus.NOTIFIED, "Break")
        val bitmap = rail(50, listOf(done, notified))
        assertTrue(bitmap.sameAs(rail(50, listOf(notified))))
        val markerPixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        assertTrue("Marker pixel: ${Integer.toHexString(markerPixel)}", Color.red(markerPixel) > Color.blue(markerPixel) && Color.green(markerPixel) > Color.blue(markerPixel))
        assertFalse(bitmap.sameAs(rail(50)))
        assertTrue(rail(50).sameAs(rail(50, listOf(done.copy(status = CheckpointStatus.MISSED)))))
        assertTrue(rail(50).sameAs(rail(50, listOf(done.copy(status = CheckpointStatus.SKIPPED)))))
    }

    @Test
    fun combinedLayoutsKeepReadoutAndRailSeparateWithinExistingFootprint() {
        for (fontScale in listOf(1f, 2f)) {
            val config = Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
            val scaledContext = context.createConfigurationContext(config)
            for (layout in listOf(R.layout.widget_combined_small, R.layout.widget_combined, R.layout.widget_combined_large)) {
                for (width in listOf(150, 200, 320)) {
                    val view = inflateWidget(layout, width, 48, scaledContext)
                    val text = view.findViewById<TextView>(R.id.progress_text)
                    val bar = view.findViewById<ImageView>(R.id.progress_bar_image)
                    assertTrue("Readout clips at $fontScale / $layout", text.top >= 0)
                    assertTrue(text.bottom <= bar.top)
                    val content = view.findViewById<View>(R.id.widget_content)
                    val background = view.findViewById<View>(R.id.widget_background_image)
                    assertTrue("Rail clips at $fontScale / $layout", content.top + bar.bottom <= view.height)
                    assertTrue(content.top >= 0)
                    assertEquals(content.top, background.top)
                    assertEquals(content.bottom, background.bottom)
                    assertTrue(content.height - bar.bottom <= 2)
                    assertEquals(width, view.width)
                    assertEquals(48, view.height)
                }
            }
        }
    }

    @Test
    fun barBackgroundHugsContentEvenInTallLauncherSlots() {
        for ((layout, barHeight) in listOf(
            R.layout.widget_progress_bar_small to 8,
            R.layout.widget_progress_bar to 14,
            R.layout.widget_progress_bar_large to 20
        )) {
            val view = inflateWidget(layout, 320, 160)
            val background = view.findViewById<View>(R.id.widget_background_image)
            assertEquals(barHeight + 4, background.height)
            assertEquals((160 - background.height) / 2, background.top)
            assertEquals(160, view.findViewById<View>(R.id.widget_root).height)
        }
        val textOnly = inflateWidget(R.layout.widget_text_only, 320, 160)
        val background = textOnly.findViewById<View>(R.id.widget_background_image)
        val text = textOnly.findViewById<View>(R.id.progress_text)
        assertEquals(text.height + 4, background.height)
        assertEquals(text.width + 8, background.width)
    }

    @Test
    fun surfaceRasterMatchesWrappedTextAndBar() {
        val layouts = listOf(
            Triple(R.layout.widget_combined_small, 6, 11f),
            Triple(R.layout.widget_combined, 10, 12f),
            Triple(R.layout.widget_combined_large, 14, 14f),
            Triple(R.layout.widget_text_only, 0, 22f)
        )
        for (scale in listOf(1f, 2f)) {
            val config = Configuration(context.resources.configuration).apply { fontScale = scale }
            val scaled = context.createConfigurationContext(config)
            for ((layout, barHeight, fontSize) in layouts) {
                val type = if (layout == R.layout.widget_text_only) 1 else 2
                val view = inflateWidget(layout, 320, 160, scaled)
                val content = view.findViewById<View>(R.id.widget_content)
                val size = WidgetStyleHelper.measureSurface(scaled.resources, type, barHeight, "62%", fontSize,
                    "default", false, 320, 160)
                assertTrue("Surface height $layout / $scale: ${size.heightDp} vs ${content.height}",
                    kotlin.math.abs(size.heightDp - content.height) <= 1)
                assertTrue(kotlin.math.abs(size.widthDp - content.width) <= 1)
            }
        }
    }

    @Test
    fun checkpointCaptionOnlyAppearsWithEnoughRoom() {
        assertFalse(WidgetDisplayFormatter.canShowCheckpoint(320, 48, 1f))
        assertFalse(WidgetDisplayFormatter.canShowCheckpoint(200, 80, 1f))
        assertTrue(WidgetDisplayFormatter.canShowCheckpoint(320, 64, 1f))
        assertFalse(WidgetDisplayFormatter.canShowCheckpoint(320, 64, 2f))
        assertTrue(WidgetDisplayFormatter.canShowCheckpoint(320, 80, 2f))
    }

    @Test
    fun allRemoteViewsLayoutsInflateAndRenderPreviewSheet() {
        val layouts = listOf(
            R.layout.widget_combined_small to 6,
            R.layout.widget_combined to 10,
            R.layout.widget_combined_large to 14,
            R.layout.widget_progress_bar_small to 8,
            R.layout.widget_progress_bar to 14,
            R.layout.widget_progress_bar_large to 20,
            R.layout.widget_text_only to 0
        )
        val sheet = Bitmap.createBitmap(360, layouts.size * 84, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFF202935.toInt())
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 10f }
        for ((index, entry) in layouts.withIndex()) {
            val (layout, barHeight) = entry
            val view = inflateWidget(layout, 320, 48)
            view.findViewById<ImageView>(R.id.widget_background_image).setImageBitmap(
                WidgetStyleHelper.createBackgroundBitmap(Color.BLACK, Color.WHITE, 2, false,
                    widthDp = view.findViewById<View>(R.id.widget_content).width,
                    heightDp = view.findViewById<View>(R.id.widget_content).height)
            )
            view.findViewById<ImageView>(R.id.progress_bar_image)?.setImageBitmap(
                WidgetStyleHelper.createProgressBitmap(62, cyan, 0xFF87ACFF.toInt(), 0xFF263847.toInt(),
                    markers = listOf(WidgetCheckpointMarker(75f, CheckpointStatus.SCHEDULED, "Break")),
                    widthDp = 312, heightDp = barHeight)
            )
            canvas.drawText(context.resources.getResourceEntryName(layout), 20f, index * 84f + 15f, labelPaint)
            canvas.save()
            canvas.translate(20f, index * 84f + 24f)
            view.draw(canvas)
            canvas.restore()
        }
        val file = File("build/reports/widget-preview.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { assertTrue(sheet.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun rail(progress: Int, markers: List<WidgetCheckpointMarker> = emptyList()) =
        WidgetStyleHelper.createProgressBitmap(progress, cyan, cyan, Color.DKGRAY, markers, barStyle = 0)

    private fun inflateWidget(layout: Int, width: Int, height: Int, ctx: Context = context): View {
        val views = RemoteViews(ctx.packageName, layout)
        if (layout in listOf(R.layout.widget_combined_small, R.layout.widget_combined, R.layout.widget_combined_large, R.layout.widget_text_only)) {
            views.setTextViewText(R.id.progress_text, "62%")
        }
        val view = views.apply(ctx, FrameLayout(ctx))
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        return view
    }
}

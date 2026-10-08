package com.example.dayprogress.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.example.dayprogress.R
import com.example.dayprogress.data.AppPreferences
import com.google.android.material.textfield.TextInputEditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Captures the actual notification-launched picker, including custom validation at large font. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SnoozeVisualTest {
    @Test fun presetsAndCustomMinutesRemainReadableAtNormalAndLargeFont() {
        for (fontScale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(fontScale)
            val fixture = postedSnoozeFixture()
            val controller = Robolectric.buildActivity(SnoozeActivity::class.java, fixture.launchIntent()).setup().visible()
            val activity = controller.get()
            val root = activity.window.decorView
            layout(root)
            assertReadableText(root)
            assertActionVisible(activity.findViewById(R.id.snooze_cancel), root)
            assertActionVisible(activity.findViewById(R.id.snooze_confirm), root)
            capture(root, "snooze-presets-${fontScale.toInt()}x")
            activity.findViewById<RadioButton>(R.id.snooze_custom_option).performClick()
            val choices = activity.findViewById<RadioGroup>(R.id.snooze_choices)
            assertEquals(R.id.snooze_custom_option, choices.checkedRadioButtonId)
            val checked = (0 until choices.childCount).map(choices::getChildAt)
                .filterIsInstance<RadioButton>().filter(RadioButton::isChecked)
            assertEquals("Exactly one snooze duration is selected", 1, checked.size)
            assertEquals(R.id.snooze_custom_option, checked.single().id)
            activity.findViewById<TextInputEditText>(R.id.snooze_custom_minutes).setText("37")
            layout(root)
            assertReadableText(root)
            assertActionVisible(activity.findViewById(R.id.snooze_confirm), root)
            capture(root, "snooze-custom-${fontScale.toInt()}x")
            layout(root, availableHeight = 420)
            assertActionVisible(activity.findViewById(R.id.snooze_confirm), root)
            capture(root, "snooze-custom-compact-${fontScale.toInt()}x")
            activity.findViewById<TextInputEditText>(R.id.snooze_custom_minutes).setText("0")
            activity.findViewById<Button>(R.id.snooze_confirm).performClick()
            layout(root)
            assertFalse(activity.isFinishing)
            assertReadableText(root)
            assertActionVisible(activity.findViewById(R.id.snooze_confirm), root)
            capture(root, "snooze-custom-error-${fontScale.toInt()}x")
            activity.findViewById<Button>(R.id.snooze_cancel).performClick()
            controller.pause().stop().destroy()

            AppPreferences(fixture.context).snoozePresets = listOf(1, 7, 37, 120, 720, 1440)
            val configured = Robolectric.buildActivity(SnoozeActivity::class.java, fixture.launchIntent()).setup().visible()
            val configuredRoot = configured.get().window.decorView
            layout(configuredRoot)
            assertReadableText(configuredRoot)
            assertActionVisible(configured.get().findViewById(R.id.snooze_confirm), configuredRoot)
            capture(configuredRoot, "snooze-configured-presets-${fontScale.toInt()}x")
            configured.get().findViewById<Button>(R.id.snooze_cancel).performClick()
            configured.pause().stop().destroy()
        }
    }

    private fun layout(root: View, availableHeight: Int = 800) {
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(351, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        }
    }

    private fun assertActionVisible(button: View, root: View) {
        assertTrue("Snooze actions need 48dp touch targets", button.height >= 48)
        // The floating picker scrolls when large fonts or the keyboard reduce its viewport.
        button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
        var parent = button.parent as? ViewGroup
        while (parent != null) {
            val visibleButton = Rect(0, 0, button.width, button.height)
            parent.offsetDescendantRectToMyCoords(button, visibleButton)
            assertTrue("Snooze action is clipped by ${parent.javaClass.simpleName}: $visibleButton",
                visibleButton.top >= parent.scrollY && visibleButton.bottom <= parent.scrollY + parent.height)
            if (parent == root) break
            parent = parent.parent as? ViewGroup
        }
    }

    private fun assertReadableText(root: View) {
        val textViews = descendants(root).filterIsInstance<TextView>()
            .filter { it.visibility == View.VISIBLE && it.text.isNotBlank() && it.width > 0 }
        assertTrue(textViews.isNotEmpty())
        textViews.forEach { text ->
            val lines = text.layout ?: return@forEach
            assertTrue("Text height clips '${text.text}'", text.height - text.compoundPaddingTop - text.compoundPaddingBottom >= lines.height)
            for (line in 0 until lines.lineCount) assertEquals("Text ellipsized '${text.text}'", 0, lines.getEllipsisCount(line))
        }
    }

    private fun descendants(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()

    private fun capture(root: View, name: String) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        // Native renderer animations do not always advance like a device RenderThread.
        // Draw the real controls in their final checked/error states for review.
        descendants(root).forEach(View::jumpDrawablesToCurrentState)
        root.draw(Canvas(bitmap))
        val directory = File(System.getProperty("daymeter.visual.output") ?: "build/reports/ux")
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}

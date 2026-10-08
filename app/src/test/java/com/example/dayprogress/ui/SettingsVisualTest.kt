package com.example.dayprogress.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Button
import android.widget.ScrollView
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.R
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.data.Checkpoint
import com.example.dayprogress.data.CheckpointStore
import com.example.dayprogress.data.CheckpointStatus
import com.example.dayprogress.data.DayIdFormatter
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
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Real activity, preference rows and dialogs, drawn by Android's native graphics renderer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsVisualTest {
    @Test
    fun settingsAndCheckpointEditorRemainReadableAtNormalAndLargeFont() {
        for (fontScale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(fontScale)
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            // Keep Start Day Now valid when CI runs after the default 22:00 end.
            if (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) >= 22) {
                AppPreferences(context).dayEnd = 23 * 60 + 59
            }
            val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible()
            val activity = controller.get()
            activity.supportFragmentManager.executePendingTransactions()
            val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
            val root = activity.window.decorView
            settle(root)
            assertEquals(390, root.width)
            assertEquals(844, root.height)
            capture(root, "settings-overview-${fontScale.toInt()}x")

            scrollTo(fragment, "manual_start_time", root)
            val startRow = preferenceRow(fragment, "manual_start_time")
            assertReadableText(startRow)
            capture(root, "settings-day-start-${fontScale.toInt()}x")

            scrollTo(fragment, "start_day_now", root)
            assertTrue(preferenceRow(fragment, "start_day_now").performClick())
            settle(root)
            assertTrue(AppPreferences(context).manualStartTime >= 0L)
            scrollTo(fragment, AppPreferences.KEY_IS_MANUAL_LOCKED, root)
            assertTrue(preferenceRow(fragment, AppPreferences.KEY_IS_MANUAL_LOCKED).performClick())
            settle(root)
            assertTrue(AppPreferences(context).isManualLocked)
            scrollTo(fragment, "start_status", root)
            capture(root, "settings-daily-start-${fontScale.toInt()}x")

            scrollTo(fragment, "add_checkpoint", root)
            val addRow = preferenceRow(fragment, "add_checkpoint")
            assertTrue("Checkpoint row must have a touch target of at least 48dp", addRow.height >= 48)
            assertTrue("Click the actual preference row", addRow.performClick())
            shadowOf(Looper.getMainLooper()).idle()
            val dialog: Dialog = ShadowDialog.getLatestDialog()
            assertTrue("Editor dialog must open", dialog.isShowing)
            val dialogRoot = dialog.window!!.decorView
            layoutDialog(dialogRoot)
            assertNotNull(dialogRoot.findViewById<View>(R.id.checkpoint_label))
            assertNotNull(dialogRoot.findViewById<View>(R.id.checkpoint_notification_mode))
            assertReadableText(dialogRoot)
            capture(dialogRoot, "checkpoint-editor-${fontScale.toInt()}x")
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun habitChainShowsBlockedReadyAndDoneWithReachableDailyActions() {
        for (fontScale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(fontScale)
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            val store = CheckpointStore(context)
            val first = Checkpoint(label = "Drink water", triggerValue = 8 * 60)
            val second = Checkpoint(label = "Stretch", triggerValue = 9 * 60, predecessorId = first.id)
            assertTrue(store.saveCheckpoint(first))
            assertTrue(store.saveCheckpoint(second))
            val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible()
            val activity = controller.get()
            activity.supportFragmentManager.executePendingTransactions()
            val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
            val root = activity.window.decorView
            settle(root)
            val secondKey = "checkpoint_item_${second.id}"
            scrollTo(fragment, secondKey, root)
            capture(root, "chain-list-blocked-${fontScale.toInt()}x")
            var dialog = openPreferenceDialog(fragment, secondKey, root)
            var dialogRoot = dialog.window!!.decorView
            assertTrue(dialogRoot.findViewById<TextView>(R.id.checkpoint_today_status).text.toString().contains("waiting for Drink water"))
            assertFalse("Blocked successor cannot be completed", dialogRoot.findViewById<Button>(R.id.checkpoint_done_today).isEnabled)
            capture(dialogRoot, "chain-blocked-${fontScale.toInt()}x")
            val scroll = descendants(dialogRoot).filterIsInstance<ScrollView>().first()
            val predecessor = dialogRoot.findViewById<View>(R.id.checkpoint_predecessor)
            scroll.scrollTo(0, (predecessor.top - 8).coerceAtLeast(0))
            shadowOf(Looper.getMainLooper()).idle()
            capture(dialogRoot, "chain-editor-options-${fontScale.toInt()}x")
            assertReadableText(dialogRoot)
            dialog.dismiss()

            dialog = openPreferenceDialog(fragment, "checkpoint_item_${first.id}", root)
            val firstDone = dialog.findViewById<Button>(R.id.checkpoint_done_today)
            assertTrue(firstDone.isEnabled)
            assertTrue(firstDone.height >= 48)
            assertTrue("Complete predecessor through its actual editor button", firstDone.performClick())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(CheckpointStatus.DONE, store.getState(first.id, DayIdFormatter.format(System.currentTimeMillis()))!!.status)

            dialog = openPreferenceDialog(fragment, secondKey, root)
            dialogRoot = dialog.window!!.decorView
            val secondDone = dialogRoot.findViewById<Button>(R.id.checkpoint_done_today)
            assertTrue("Completing predecessor unlocks successor", secondDone.isEnabled)
            assertTrue(secondDone.height >= 48)
            assertReadableText(dialogRoot)
            capture(dialogRoot, "chain-ready-${fontScale.toInt()}x")
            assertTrue(secondDone.performClick())
            shadowOf(Looper.getMainLooper()).idle()
            dialog = openPreferenceDialog(fragment, secondKey, root)
            dialogRoot = dialog.window!!.decorView
            assertFalse(dialogRoot.findViewById<Button>(R.id.checkpoint_done_today).isEnabled)
            assertEquals(CheckpointStatus.DONE, store.getState(second.id, DayIdFormatter.format(System.currentTimeMillis()))!!.status)
            capture(dialogRoot, "chain-done-${fontScale.toInt()}x")
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    private fun openPreferenceDialog(fragment: SettingsFragment, key: String, root: View): Dialog {
        scrollTo(fragment, key, root)
        assertTrue(preferenceRow(fragment, key).performClick())
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowDialog.getLatestDialog().also { dialog ->
            assertTrue(dialog.isShowing)
            val decor = dialog.window!!.decorView
            // Modal stays within the 390x844 phone viewport, including at large font.
            layoutDialog(decor)
            for (id in listOf(android.R.id.button1, android.R.id.button2)) {
                val button = dialog.findViewById<Button>(id)
                var child: View = button
                var parent = child.parent as? ViewGroup
                while (parent != null) {
                    assertTrue("Dialog action '${button.text}' is clipped by ${parent.javaClass.simpleName}",
                        child.top >= parent.scrollY && child.bottom <= parent.scrollY + parent.height)
                    child = parent
                    parent = child.parent as? ViewGroup
                }
            }
        }
    }

    private fun layoutDialog(root: View) {
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(366, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun scrollTo(fragment: SettingsFragment, key: String, root: View) {
        val adapter = fragment.listView.adapter as PreferenceGroupAdapter
        val position = adapter.getPreferenceAdapterPosition(key)
        assertTrue("Preference $key exists", position >= 0)
        (fragment.listView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 8)
        settle(root)
    }

    private fun preferenceRow(fragment: SettingsFragment, key: String): View {
        val adapter = fragment.listView.adapter as PreferenceGroupAdapter
        val position = adapter.getPreferenceAdapterPosition(key)
        return requireNotNull(fragment.listView.findViewHolderForAdapterPosition(position)) {
            "Preference $key must be laid out after scrolling"
        }.itemView
    }

    private fun settle(root: View) {
        shadowOf(Looper.getMainLooper()).idle()
        layout(root, 390, 844)
        shadowOf(Looper.getMainLooper()).idle()
        layout(root, 390, 844)
    }

    private fun layout(root: View, width: Int, height: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }

    private fun assertReadableText(root: View) {
        val textViews = descendants(root).filterIsInstance<TextView>()
            .filter { it.visibility == View.VISIBLE && it.text.isNotBlank() && it.width > 0 }
        assertTrue("Screen must contain readable text", textViews.isNotEmpty())
        textViews.forEach { text ->
            val textLayout = text.layout ?: return@forEach
            assertTrue("Text height clips '${text.text}'", text.height - text.compoundPaddingTop - text.compoundPaddingBottom >= textLayout.height)
            for (line in 0 until textLayout.lineCount) {
                assertEquals("Text ellipsized '${text.text}'", 0, textLayout.getEllipsisCount(line))
            }
        }
    }

    private fun descendants(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()

    private fun capture(root: View, name: String) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val directory = File(System.getProperty("daymeter.visual.output") ?: "build/reports/ux")
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}

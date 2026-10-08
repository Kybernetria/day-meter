package com.example.dayprogress.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.R
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.data.CheckpointNotificationMode
import com.example.dayprogress.data.CheckpointStore
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationVisualTest {
    @Test
    fun reminderSettingsAndFourAlertChoicesAreReadableAndPersistUserChoices() {
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            val prefs = AppPreferences(context)
            assertEquals(listOf(5, 10, 15, 30, 60), prefs.snoozePresets)
            assertFalse(prefs.reminderPersistent)
            assertFalse(prefs.showLockScreenDetails)
            val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible()
            val activity = controller.get()
            activity.supportFragmentManager.executePendingTransactions()
            val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
            val root = activity.window.decorView
            layout(root, 390, 844)
            scrollTo(fragment, "notification_category", root)
            capture(root, "notifications-defaults-${scale.toInt()}x")
            scrollTo(fragment, AppPreferences.KEY_SNOOZE_PRESETS, root)
            assertReadableText(row(fragment, AppPreferences.KEY_SNOOZE_PRESETS))
            assertTrue(row(fragment, AppPreferences.KEY_SNOOZE_PRESETS).performClick())
            shadowOf(Looper.getMainLooper()).idle()
            val presetDialog = ShadowDialog.getLatestDialog()
            val presetDecor = presetDialog.window!!.decorView
            layoutDialog(presetDecor)
            assertReadableText(presetDecor)
            capture(presetDecor, "snooze-preset-settings-${scale.toInt()}x")
            presetDialog.findViewById<EditText>(R.id.snooze_presets_input).setText("7, 25, 90")
            assertTrue(presetDialog.findViewById<View>(android.R.id.button1).performClick())
            assertEquals(listOf(7, 25, 90), prefs.snoozePresets)
            scrollTo(fragment, AppPreferences.KEY_SNOOZE_PRESETS, root)
            assertReadableText(row(fragment, AppPreferences.KEY_SNOOZE_PRESETS))
            scrollTo(fragment, AppPreferences.KEY_REMINDER_PERSISTENT, root)
            assertReadableText(row(fragment, AppPreferences.KEY_REMINDER_PERSISTENT))
            assertTrue(row(fragment, AppPreferences.KEY_REMINDER_PERSISTENT).performClick())
            assertTrue(prefs.reminderPersistent)
            scrollTo(fragment, AppPreferences.KEY_SHOW_LOCK_SCREEN_DETAILS, root)
            assertReadableText(row(fragment, AppPreferences.KEY_SHOW_LOCK_SCREEN_DETAILS))
            assertTrue(row(fragment, AppPreferences.KEY_SHOW_LOCK_SCREEN_DETAILS).performClick())
            assertTrue(prefs.showLockScreenDetails)
            scrollTo(fragment, "notification_category", root)
            capture(root, "notifications-customized-${scale.toInt()}x")
            scrollTo(fragment, "notification_channel_settings", root)
            assertReadableText(row(fragment, "notification_channel_settings"))
            capture(root, "notifications-android-controls-${scale.toInt()}x")

            scrollTo(fragment, "add_checkpoint", root)
            assertTrue(row(fragment, "add_checkpoint").performClick())
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog()
            val decor = dialog.window!!.decorView
            layoutDialog(decor)
            val scroll = descendants(decor).filterIsInstance<ScrollView>().first()
            val spinner = decor.findViewById<Spinner>(R.id.checkpoint_notification_mode)
            scroll.scrollTo(0, (spinner.top - 40).coerceAtLeast(0))
            shadowOf(Looper.getMainLooper()).idle()
            capture(decor, "notification-editor-controls-${scale.toInt()}x")
            assertTrue("Open the actual alert-style picker", spinner.performClick())
            shadowOf(Looper.getMainLooper()).idle()
            val popup = requireNotNull(shadowOf(context as Application).latestPopupWindow)
            val popupView = popup.contentView
            popupView.measure(View.MeasureSpec.makeMeasureSpec(spinner.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.AT_MOST))
            popupView.layout(0, 0, popupView.measuredWidth, popupView.measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
            val choices = descendants(popupView).filterIsInstance<ListView>().first()
            assertEquals(4, choices.adapter.count)
            assertEquals(4, choices.childCount)
            assertReadableText(popupView)
            capture(popupView, "notification-style-choices-${scale.toInt()}x", popup.background)
            assertTrue(choices.performItemClick(choices.getChildAt(3), 3, choices.adapter.getItemId(3)))
            assertEquals(3, spinner.selectedItemPosition)
            layoutDialog(decor)
            scroll.scrollTo(0, (spinner.top - 40).coerceAtLeast(0))
            assertReadableText(spinner)
            capture(decor, "notification-prominent-selected-${scale.toInt()}x")
            assertTrue(dialog.findViewById<View>(android.R.id.button1).performClick())
            assertEquals(CheckpointNotificationMode.PROMINENT, CheckpointStore(context).getCheckpoints().single().notificationMode)
            controller.pause().stop().destroy()
        }
    }

    private fun scrollTo(fragment: SettingsFragment, key: String, root: View) {
        val position = (fragment.listView.adapter as PreferenceGroupAdapter).getPreferenceAdapterPosition(key)
        assertTrue("Preference $key exists", position >= 0)
        (fragment.listView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 8)
        layout(root, 390, 844)
    }

    private fun row(fragment: SettingsFragment, key: String): View {
        val position = (fragment.listView.adapter as PreferenceGroupAdapter).getPreferenceAdapterPosition(key)
        return requireNotNull(fragment.listView.findViewHolderForAdapterPosition(position)).itemView
    }

    private fun layout(root: View, width: Int, height: Int) {
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
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

    private fun assertReadableText(root: View) {
        val texts = descendants(root).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.text.isNotBlank() && it.width > 0 }
        assertTrue(texts.isNotEmpty())
        texts.forEach { text ->
            val lines = text.layout ?: return@forEach
            assertTrue("Text clips '${text.text}'", text.height - text.compoundPaddingTop - text.compoundPaddingBottom >= lines.height)
            for (line in 0 until lines.lineCount) assertEquals("Text ellipsized '${text.text}'", 0, lines.getEllipsisCount(line))
        }
    }

    private fun descendants(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()

    private fun capture(root: View, name: String, background: Drawable? = null) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        background?.let { drawable ->
            val bounds = android.graphics.Rect(drawable.bounds)
            drawable.setBounds(0, 0, root.width, root.height)
            drawable.draw(canvas)
            drawable.bounds = bounds
        }
        root.draw(canvas)
        val directory = File(System.getProperty("daymeter.visual.output") ?: "build/reports/ux")
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}

package com.example.dayprogress.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import com.example.dayprogress.data.AppPreferences
import org.junit.Assert.assertSame
import com.google.android.material.card.MaterialCardView
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ConsoleThemeTest {
    @Test
    fun everyPaletteInflatesTheConsole() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        for (theme in listOf(
            R.style.Theme_DayProgressWidget,
            R.style.Theme_DayProgressWidget_Violet,
            R.style.Theme_DayProgressWidget_Ember
        )) {
            val context = ContextThemeWrapper(base, theme)
            val view = LayoutInflater.from(context).inflate(R.layout.activity_settings, null)
            assertNotNull(view.findViewById<FrameLayout>(R.id.settings_container))
            assertNotNull(view.findViewById<FrameLayout>(R.id.preview_container))
            val preview = view.findViewById<MaterialCardView>(R.id.preview_card)
            assertEquals(2, (preview.getChildAt(0) as LinearLayout).childCount)
        }
    }

    @Test
    @Config(sdk = [35])
    fun activityLoadsEachPaletteAndReusesPreviewWithoutChangingWidgetColors() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        for (palette in listOf("neon", "violet", "ember")) {
            storage.edit().clear().putString("interface_palette", palette).commit()
            val prefs = AppPreferences(context)
            prefs.progressColor = 0xFF123456.toInt()
            val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
            try {
                val activity = controller.get()
                val preview = activity.findViewById<FrameLayout>(R.id.preview_container)
                assertEquals(1, preview.childCount)
                assertNotNull(preview.findViewById<View>(R.id.progress_bar_image))
                val original = preview.getChildAt(0)
                activity.updatePreview()
                assertSame(original, preview.getChildAt(0))
                assertEquals(0xFF123456.toInt(), prefs.progressColor)
                val fragment = activity.supportFragmentManager
                    .findFragmentById(R.id.settings_container) as SettingsFragment
                assertNotNull(fragment.findPreference<androidx.preference.ListPreference>("interface_palette"))
                assertEquals(null, fragment.findPreference<androidx.preference.Preference>("theme"))
                val follow = fragment.findPreference<androidx.preference.SwitchPreferenceCompat>(AppPreferences.KEY_WIDGET_FOLLOW_THEME)!!
                val customColors = fragment.findPreference<androidx.preference.PreferenceCategory>("custom_widget_colors")!!
                assertEquals(true, customColors.isVisible)
                follow.callChangeListener(true)
                assertEquals(true, prefs.widgetFollowTheme)
                assertEquals(false, customColors.isVisible)
                follow.callChangeListener(false)
                assertEquals(true, customColors.isVisible)
                assertEquals(0xFF123456.toInt(), prefs.progressColor)
                val displayMode = fragment.findPreference<androidx.preference.ListPreference>(AppPreferences.KEY_WIDGET_TYPE)!!
                val barStyle = fragment.findPreference<androidx.preference.ListPreference>(AppPreferences.KEY_BAR_STYLE)!!
                displayMode.callChangeListener("1")
                assertEquals(false, barStyle.isVisible)
                displayMode.callChangeListener("2")
                assertEquals(true, barStyle.isVisible)
            } finally {
                controller.pause().stop().destroy()
            }
        }
        storage.edit().clear().commit()
    }

}

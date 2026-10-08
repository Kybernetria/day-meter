package com.example.dayprogress.ui

import android.app.TimePickerDialog
import android.app.AppOpsManager
import android.content.Context
import android.widget.TimePicker
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.R
import com.example.dayprogress.data.AppPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsStartModeTest {
    private lateinit var context: Context
    private lateinit var prefs: AppPreferences
    private lateinit var controller: ActivityController<SettingsActivity>
    private lateinit var fragment: SettingsFragment

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context)
        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        prefs.ignoreBefore = (nowMinutes + 23 * 60) % (24 * 60)
        prefs.dayEnd = (nowMinutes + 60) % (24 * 60)
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(),
            context.packageName, AppOpsManager.MODE_IGNORED)
        controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        fragment = controller.get().supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun row(key: String) = fragment.findPreference<Preference>(key)!!
    private fun click(key: String) {
        val preference = row(key)
        assertTrue(preference.onPreferenceClickListener!!.onPreferenceClick(preference))
    }

    @Test
    fun settingsLeadWithBehaviorAndSeparatePermissionGroups() {
        val screen = fragment.preferenceScreen
        assertEquals(listOf("day_window_category", "day_start_category", "automatic_detection_category",
            "checkpoint_category", "notification_category", "widget_display_category"),
            (0..5).map { screen.getPreference(it).key })
        assertEquals("notification_category", row("notification_status").parent!!.key)
        assertEquals("automatic_detection_category", row("usage_access_status").parent!!.key)
        for (index in 0 until screen.preferenceCount) {
            val category = screen.getPreference(index) as PreferenceCategory
            for (child in 0 until category.preferenceCount) {
                assertFalse("Settings titles must wrap at enlarged font sizes",
                    category.getPreference(child).isSingleLineTitle)
            }
        }
        assertFalse(fragment.findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_IS_MANUAL_LOCKED)!!.isEnabled)
    }

    @Test
    fun startNowTurnsOffDailyRepeatAndRefreshesStatus() {
        click("start_day_now")
        val repeat = fragment.findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_IS_MANUAL_LOCKED)!!
        assertTrue(repeat.isEnabled)
        assertTrue(repeat.callChangeListener(true))
        assertTrue(prefs.isManualLocked)
        assertEquals(context.getString(R.string.settings_edit_daily_start), row(AppPreferences.KEY_MANUAL_START_TIME).title)
        click("start_day_now")
        assertFalse(prefs.isManualLocked)
        assertFalse(repeat.isChecked)
        assertTrue(row("start_status").summary.toString().contains("Today at"))
        assertEquals(context.getString(R.string.label_manual_start), row(AppPreferences.KEY_MANUAL_START_TIME).title)
    }

    @Test
    fun dailyPickerExplicitlyEditsPresetAndAcceptsFutureTime() {
        click("start_day_now")
        fragment.findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_IS_MANUAL_LOCKED)!!.callChangeListener(true)
        click(AppPreferences.KEY_MANUAL_START_TIME)
        val dialog = ShadowDialog.getLatestDialog() as TimePickerDialog
        assertEquals(context.getString(R.string.settings_edit_daily_start), shadowOf(dialog).title)
        val pickerId = context.resources.getIdentifier("timePicker", "id", "android")
        val now = Calendar.getInstance()
        val selected = (now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE) + 30) % (24 * 60)
        dialog.findViewById<TimePicker>(pickerId).apply { hour = selected / 60; minute = selected % 60 }
        dialog.getButton(TimePickerDialog.BUTTON_POSITIVE).performClick()
        assertEquals(selected, prefs.manualStartMinutes)
        assertTrue(prefs.isManualLocked)
        assertTrue(row("start_status").summary.toString().contains("Daily at"))
    }

    @Test
    fun automaticActionReportsMissingPermissionAndClearsManualState() {
        click("start_day_now")
        fragment.findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_IS_MANUAL_LOCKED)!!.callChangeListener(true)
        click("use_automatic_start")
        assertEquals(-1L, prefs.manualStartTime)
        assertFalse(prefs.isManualLocked)
        assertFalse(row("clear_manual_start").isEnabled)
        assertFalse(row(AppPreferences.KEY_IS_MANUAL_LOCKED).isEnabled)
        assertEquals(context.getString(R.string.settings_status_automatic_permission), row("start_status").summary)
        assertEquals(context.getString(R.string.settings_automatic_permission_feedback), ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(controller.get()).nextStartedActivity)
    }

    @Test
    fun clearManualRetainsDetectedStartAndReportsIt() {
        click("start_day_now")
        prefs.detectedStartTime = prefs.manualStartTime - 60_000
        click("clear_manual_start")
        assertEquals(-1L, prefs.manualStartTime)
        assertTrue(row("start_status").summary.toString().contains("start detected"))
        assertEquals(context.getString(R.string.settings_automatic_detected_feedback), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun resumeReflectsManualStartExpiringWithNewLogicalDay() {
        click("start_day_now")
        controller.pause()
        // Simulate returning with a manual override persisted from an earlier logical day.
        prefs.lastResetDate = "previous-day"
        prefs.manualStartDayId = "previous-day"
        controller.resume()
        assertEquals(-1L, prefs.manualStartTime)
        assertFalse(row("clear_manual_start").isEnabled)
        assertEquals(context.getString(R.string.manual_start_not_set), row(AppPreferences.KEY_MANUAL_START_TIME).summary)
    }
}

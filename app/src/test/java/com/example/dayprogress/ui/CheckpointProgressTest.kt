package com.example.dayprogress.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.R
import com.example.dayprogress.data.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.android.controller.ActivityController
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CheckpointProgressTest {
    private lateinit var context: Context
    private lateinit var store: CheckpointStore
    private lateinit var controller: ActivityController<SettingsActivity>
    private lateinit var fragment: SettingsFragment
    private lateinit var first: Checkpoint
    private lateinit var second: Checkpoint
    private var now = 0L
    private lateinit var dayId: String

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        now = System.currentTimeMillis()
        dayId = DayIdFormatter.format(now)
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        store = CheckpointStore(context)
        first = Checkpoint(label = "Drink water", triggerValue = 0)
        second = Checkpoint(label = "Stretch", triggerValue = 0, predecessorId = first.id)
        assertTrue(store.saveCheckpoint(first)); assertTrue(store.saveCheckpoint(second))
        controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        fragment = controller.get().supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
    }
    @After fun cleanup() { controller.pause().stop().destroy() }

    @Test fun completionWithoutNotificationUnlocksLateChildAndRecordsTime() {
        assertFalse(fragment.completeCheckpointToday(second, CheckpointStatus.DONE))
        assertTrue(fragment.completeCheckpointToday(first, CheckpointStatus.DONE))
        val done = store.getState(first.id, dayId)!!
        assertTrue(done.completedAtMillis >= now && done.completedAtMillis <= System.currentTimeMillis())
        assertEquals(CheckpointStatus.DONE, done.status)
        assertTrue(CheckpointEngine(context).getDueCheckpoints(System.currentTimeMillis()).due.any { it.checkpoint.id == second.id })
        assertTrue(fragment.completeCheckpointToday(second, CheckpointStatus.DONE))
        assertFalse(fragment.completeCheckpointToday(second, CheckpointStatus.DONE))
    }
    @Test fun skipDoesNotUnlockButCanBeCompletedLater() {
        assertTrue(fragment.completeCheckpointToday(first, CheckpointStatus.SKIPPED))
        assertFalse(fragment.completeCheckpointToday(second, CheckpointStatus.DONE))
        assertTrue(fragment.completeCheckpointToday(first, CheckpointStatus.DONE))
        assertTrue(fragment.completeCheckpointToday(second, CheckpointStatus.DONE))
    }
    @Test fun disablingPredecessorClearsPendingDescendantButKeepsCompletedDescendant() {
        store.putState(CheckpointState(first.id, dayId, CheckpointStatus.DONE, completedAtMillis = now))
        store.putState(CheckpointState(second.id, dayId, CheckpointStatus.NOTIFIED, notifiedAtMillis = now))
        val third = Checkpoint(label = "Read", triggerValue = 0, predecessorId = second.id)
        assertTrue(store.saveCheckpoint(third))
        store.putState(CheckpointState(third.id, dayId, CheckpointStatus.DONE, completedAtMillis = now))
        val row = fragment.findPreference<androidx.preference.Preference>("checkpoint_item_${first.id}")!!
        row.onPreferenceClickListener!!.onPreferenceClick(row)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.findViewById<android.widget.CheckBox>(R.id.checkpoint_enabled)!!.isChecked = false
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(store.getCheckpoints().first { it.id == first.id }.enabled)
        assertNull(store.getState(second.id, dayId))
        assertEquals(CheckpointStatus.DONE, store.getState(third.id, dayId)?.status)
        assertTrue(CheckpointEngine(context).isBlocked(second, dayId))
    }
    @Test fun editorCannotCompleteADifferentOccurrenceDate() {
        assertFalse(fragment.completeCheckpointToday(first, CheckpointStatus.DONE, "2000-01-01"))
        assertNull(store.getState(first.id, dayId))
    }
    @Test fun oldEditorSnapshotCannotCompleteChangedOrDisabledCheckpoint() {
        assertTrue(store.saveCheckpoint(first.copy(label = "Renamed")))
        assertFalse(fragment.completeCheckpointToday(first, CheckpointStatus.DONE))
        assertTrue(store.saveCheckpoint(first.copy(enabled = false)))
        assertFalse(fragment.completeCheckpointToday(first.copy(enabled = false), CheckpointStatus.DONE))
        assertNull(store.getState(first.id, dayId))
    }
    @Test fun blockedRowsExplainTheirPredecessorAndDisabledState() {
        val row = fragment.findPreference<androidx.preference.Preference>("checkpoint_item_${second.id}")!!
        assertTrue(row.summary.toString().contains("waiting for Drink water"))
        assertTrue(store.saveCheckpoint(first.copy(enabled = false)))
        // Rebuild on screen recreation to pick up changes from a different editor.
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        fragment = controller.get().supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
        assertTrue(fragment.findPreference<androidx.preference.Preference>("checkpoint_item_${second.id}")!!.summary.toString().contains("is disabled"))
    }
}

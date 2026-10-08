package com.example.dayprogress.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.data.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BootRecoveryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun rejectedBootWorkIsDurableAndBounded() {
        repeat(3) { BootRecovery.recordAndSchedule(context, IntentAction.BOOT) }
        val preferences = AppPreferences(context)
        assertEquals(3, preferences.bootRecoveryAttempts)
        assertTrue(preferences.bootRecoveryAction == IntentAction.BOOT)

        BootRecovery.recordAndSchedule(context, IntentAction.BOOT)
        assertTrue(preferences.bootRecoveryTerminal)
        assertEquals(3, preferences.bootRecoveryAttempts)
    }

    private object IntentAction {
        const val BOOT = "android.intent.action.BOOT_COMPLETED"
    }
}

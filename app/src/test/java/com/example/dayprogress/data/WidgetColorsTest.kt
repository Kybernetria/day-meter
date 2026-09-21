package com.example.dayprogress.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class WidgetColorsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun themeModeIsOptInAndRestoresAllCustomColors() {
        val prefs = AppPreferences(context)
        prefs.progressColor = 0xFF102030.toInt()
        prefs.progressGradientEndColor = 0xFF405060.toInt()
        prefs.progressUnfilledColor = 0xFF708090.toInt()
        prefs.backgroundColor = 0x44123456
        prefs.textColor = 0xFFEEDDCC.toInt()
        prefs.borderColor = 0xFFABCDEF.toInt()
        assertFalse(prefs.widgetFollowTheme)
        val original = WidgetColors.resolve(prefs)
        prefs.widgetFollowTheme = true
        val palettes = listOf("neon", "violet", "ember").map {
            prefs.interfacePalette = it
            WidgetColors.resolve(prefs)
        }
        assertEquals(3, palettes.map { it.fill }.toSet().size)
        assertTrue(palettes.all { it.background == 0 })
        assertEquals(0xFFFF71CF.toInt(), palettes[0].fillEnd)
        assertEquals(0xFFFF86AE.toInt(), palettes[1].fillEnd)
        assertEquals(0xFFD3EC88.toInt(), palettes[2].fillEnd)
        prefs.widgetFollowTheme = false
        assertEquals(original, WidgetColors.resolve(AppPreferences(context)))
    }

    @Test
    fun unknownPaletteFallsBackToNeon() {
        val prefs = AppPreferences(context)
        prefs.widgetFollowTheme = true
        val neon = WidgetColors.resolve(prefs)
        prefs.interfacePalette = "missing"
        assertEquals(neon, WidgetColors.resolve(prefs))
    }

    @Test
    fun oldTransparentThemeMigratesOnceAndRemainsEditable() {
        val storage = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        storage.edit().putString("theme", "3").putInt(AppPreferences.KEY_BACKGROUND_COLOR, -1).commit()
        val prefs = AppPreferences(context)
        assertEquals(0, WidgetColors.resolve(prefs).background)
        assertFalse(storage.contains("theme"))
        prefs.backgroundColor = 0xAA123456.toInt()
        assertEquals(0xAA123456.toInt(), AppPreferences(context).backgroundColor)
    }
}

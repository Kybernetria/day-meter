package com.example.dayprogress.ui

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.dayprogress.R
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.data.CheckpointEngine
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.data.UsageDetector
import com.example.dayprogress.data.WidgetStyleHelper
import com.example.dayprogress.data.WidgetColors
import com.example.dayprogress.databinding.ActivitySettingsBinding
import com.example.dayprogress.reminder.ReminderNotifier
import com.example.dayprogress.reminder.ReminderScheduler
import com.example.dayprogress.widget.DayProgressWidgetProvider
import com.example.dayprogress.worker.AlarmScheduler
import com.example.dayprogress.widget.WidgetDisplayFormatter

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences
    private var lastUsageAccess: Boolean? = null
    private var lastNotificationReady: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val palette = AppPreferences(this).interfacePalette
        setTheme(when (palette) {
            "violet" -> R.style.Theme_DayProgressWidget_Violet
            "ember" -> R.style.Theme_DayProgressWidget_Ember
            else -> R.style.Theme_DayProgressWidget
        })
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        try {
            binding = ActivitySettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)
            ViewCompat.setOnApplyWindowInsetsListener(binding.activityRoot) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            prefs = AppPreferences(this)
            findViewById<FrameLayout>(R.id.preview_container).addOnLayoutChangeListener {
                _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) updatePreview()
            }

            setSupportActionBar(binding.toolbar)
            supportActionBar?.title = getString(R.string.console_title)
            applyAppColors()

            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = ColorUtils.calculateLuminance(getBaseSurfaceColor()) > 0.5
                isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(getBaseSurfaceColor()) > 0.5
            }
            initializeApp(savedInstanceState)
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Fatal error in onCreate", e)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) {
            applyAppColors()
            updatePreview()
            (supportFragmentManager.findFragmentById(R.id.settings_container) as? SettingsFragment)
                ?.refreshPermissionState()
            val usageAccess = UsageDetector.hasUsageStatsPermission(this)
            val notificationReady = ReminderNotifier.notificationsReady(this)
            if (lastUsageAccess != usageAccess || lastNotificationReady != notificationReady) {
                AlarmScheduler.scheduleWidgetUpdates(this)
                ReminderScheduler.reschedule(this)
                DayProgressWidgetProvider.refreshWidgetsInBackground(this)
            }
            lastUsageAccess = usageAccess
            lastNotificationReady = notificationReady
        }
    }

    private fun initializeApp(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commitAllowingStateLoss()
        }
    }

    fun updatePreview() {
        try {
            val nowMillis = System.currentTimeMillis()
            val repository = DayRepository(this)
            val status = repository.getDayStatus(nowMillis)
            val checkpointEngine = CheckpointEngine(this)
            val markers = checkpointEngine.getWidgetMarkers(nowMillis)
            val display = WidgetDisplayFormatter.format(
                this,
                status,
                checkpointEngine.getNextVisibleOccurrence(nowMillis),
                markers,
                prefs.widgetType == 1 || (prefs.widgetType == 2 && prefs.barSize == 2)
            )
            val previewContainer = findViewById<FrameLayout>(R.id.preview_container) ?: return

            val layoutId = getLayoutId(prefs.widgetType, prefs.barSize)
            val previewHeightPx = dpToPx(getPreviewHeightDp(prefs.widgetType, prefs.barSize))
            if (previewContainer.layoutParams.height != previewHeightPx) {
                previewContainer.layoutParams = previewContainer.layoutParams.apply {
                    height = previewHeightPx
                }
            }
            val previewWidthDp = if (previewContainer.width > 0) {
                (previewContainer.width / resources.displayMetrics.density).toInt().coerceAtLeast(1)
            } else {
                200
            }
            val previewHeightDp = getPreviewHeightDp(prefs.widgetType, prefs.barSize)
            // Reuse the preview hierarchy unless its layout actually changes.
            val existing = previewContainer.getChildAt(0)
            val widgetView = if (existing?.tag == layoutId) existing else {
                previewContainer.removeAllViews()
                LayoutInflater.from(this).inflate(layoutId, previewContainer, false).also {
                    it.tag = layoutId
                    previewContainer.addView(it)
                }
            }

            val colors = WidgetColors.resolve(prefs)
            val nextText = display.nextCheckpoint.takeIf {
                prefs.widgetType == 2 && prefs.barSize == 2 &&
                    WidgetDisplayFormatter.canShowCheckpoint(previewWidthDp, previewHeightDp, resources.configuration.fontScale)
            }
            val surface = WidgetStyleHelper.measureSurface(
                resources, prefs.widgetType, getBarHeightDp(prefs.widgetType, prefs.barSize),
                display.primary, getProgressTextSizeSp(prefs.widgetType, prefs.barSize, display.primary.length),
                prefs.fontFamily, nextText != null, previewWidthDp, previewHeightDp
            )
            widgetView.findViewById<ImageView>(R.id.widget_background_image)?.setImageBitmap(
                WidgetStyleHelper.createBackgroundBitmap(
                    backgroundColor = colors.background,
                    borderColor = colors.border,
                    borderThickness = prefs.borderThickness,
                    borderEnabled = prefs.borderEnabled,
                    widthDp = surface.widthDp,
                    heightDp = surface.heightDp
                )
            )

            if (prefs.widgetType == 0 || prefs.widgetType == 2) {
                widgetView.findViewById<ImageView>(R.id.progress_bar_image)?.setImageBitmap(
                    WidgetStyleHelper.createProgressBitmap(
                        progress = status.progress,
                        filledStartColor = colors.fill,
                        filledEndColor = colors.fillEnd,
                        unfilledColor = colors.track,
                        markers = markers,
                        widthDp = (previewWidthDp - 8).coerceAtLeast(1),
                        heightDp = getBarHeightDp(prefs.widgetType, prefs.barSize),
                        barStyle = prefs.barStyle
                    )
                )
            }

            if (prefs.widgetType == 1 || prefs.widgetType == 2) {
                widgetView.findViewById<TextView>(R.id.progress_text)?.apply {
                    text = buildProgressText(display.primary)
                    setTextColor(colors.text)
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_SP,
                        getProgressTextSizeSp(prefs.widgetType, prefs.barSize, display.primary.length)
                    )
                    typeface = Typeface.create(resolveTypeface(prefs.fontFamily), Typeface.BOLD)
                }
            }

            widgetView.findViewById<TextView>(R.id.next_checkpoint_text)?.apply {
                text = nextText.orEmpty()
                visibility = if (nextText == null) View.GONE else View.VISIBLE
                setTextColor(colors.text)
            }
            widgetView.findViewById<View>(R.id.widget_root)?.contentDescription = display.contentDescription

            applyAppColors()
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Error updating preview", e)
        }
    }

    private fun applyAppColors() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun getBaseSurfaceColor(): Int {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.colorBackground, value, true)
        return value.data
    }

    private fun getLayoutId(widgetType: Int, barSize: Int): Int {
        return when (widgetType) {
            0 -> when (barSize) {
                0 -> R.layout.widget_progress_bar_small
                2 -> R.layout.widget_progress_bar_large
                else -> R.layout.widget_progress_bar
            }
            1 -> R.layout.widget_text_only
            else -> when (barSize) {
                0 -> R.layout.widget_combined_small
                2 -> R.layout.widget_combined_large
                else -> R.layout.widget_combined
            }
        }
    }

    private fun getBarHeightDp(widgetType: Int, barSize: Int): Int {
        return if (widgetType == 0) {
            when (barSize) {
                0 -> 8
                2 -> 20
                else -> 14
            }
        } else {
            when (barSize) {
                0 -> 6
                2 -> 14
                else -> 10
            }
        }
    }

    private fun getPreviewHeightDp(widgetType: Int, barSize: Int): Int {
        return when (widgetType) {
            0 -> when (barSize) {
                0 -> 24
                2 -> 36
                else -> 28
            }
            1 -> 28
            else -> when (barSize) {
                0 -> 40
                2 -> 56
                else -> 48
            }
        }.coerceAtLeast(48)
    }

    private fun getProgressTextSizeSp(widgetType: Int, barSize: Int, textLength: Int): Float {
        if (textLength > 8) return if (widgetType == 1) 16f else 11f
        return when (widgetType) {
            1 -> when (barSize) {
                0 -> 20f
                2 -> 26f
                else -> 23f
            }
            else -> when (barSize) {
                0 -> 11f
                2 -> 14f
                else -> 12f
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }

    private fun buildProgressText(text: String): CharSequence {
        if (prefs.fontFamily == "default") {
            return text
        }
        return SpannableString(text).apply {
            setSpan(TypefaceSpan(prefs.fontFamily), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun resolveTypeface(fontFamily: String): Typeface {
        return when (fontFamily) {
            "sans-serif" -> Typeface.SANS_SERIF
            "serif" -> Typeface.SERIF
            "monospace" -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }
    }
}

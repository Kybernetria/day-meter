package com.example.dayprogress.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.example.dayprogress.R
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.data.WidgetStyleHelper
import com.example.dayprogress.databinding.ActivitySettingsBinding
import com.example.dayprogress.worker.AlarmScheduler

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences

    private val usageStatsResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasUsageStatsPermission()) {
            initializeApp(null)
        } else {
            showPermissionDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivitySettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)
            prefs = AppPreferences(this)

            setSupportActionBar(binding.toolbar)
            supportActionBar?.title = getString(R.string.settings_title)
            applyAppColors()

            if (hasUsageStatsPermission()) {
                initializeApp(savedInstanceState)
                checkAlarmPermission()
            } else {
                showPermissionDialog()
            }
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Fatal error in onCreate", e)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) {
            applyAppColors()
            updatePreview()
        }
    }

    private fun checkAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AlarmScheduler.canScheduleExactAlarms(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.alarm_permission_title)
                .setMessage(R.string.alarm_permission_message)
                .setPositiveButton(R.string.permission_grant) { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    startActivity(intent)
                }
                .setNegativeButton(R.string.alarm_permission_skip, null)
                .show()
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.usage_permission_title)
            .setMessage(R.string.usage_permission_desc)
            .setPositiveButton(R.string.permission_grant) { _, _ ->
                try {
                    usageStatsResultLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (e: Exception) {
                    Log.e("SettingsActivity", "Error opening usage stats settings", e)
                }
            }
            .setNegativeButton(R.string.permission_exit) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun initializeApp(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commitAllowingStateLoss()
        }
        updatePreview()
    }

    fun updatePreview() {
        try {
            val repository = DayRepository(this)
            val progress = repository.calculateProgress()
            val previewContainer = findViewById<FrameLayout>(R.id.preview_container) ?: return

            val layoutId = getLayoutId(prefs.widgetType, prefs.barSize)
            previewContainer.layoutParams = previewContainer.layoutParams.apply {
                height = dpToPx(getPreviewHeightDp(prefs.widgetType, prefs.barSize))
            }
            previewContainer.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 2f * resources.displayMetrics.density
                if (prefs.theme == 3) {
                    setColor(Color.TRANSPARENT)
                    setStroke(1, ColorUtils.setAlphaComponent(prefs.progressColor, 72))
                } else {
                    setColor(ColorUtils.setAlphaComponent(prefs.backgroundColor, 24))
                    setStroke(1, ColorUtils.setAlphaComponent(prefs.progressColor, 40))
                }
            }

            previewContainer.removeAllViews()
            val widgetView = LayoutInflater.from(this).inflate(layoutId, previewContainer, false)

            val backgroundColor = if (prefs.theme == 3) 0 else prefs.backgroundColor
            widgetView.findViewById<ImageView>(R.id.widget_background_image)?.setImageBitmap(
                WidgetStyleHelper.createBackgroundBitmap(
                    backgroundColor = backgroundColor,
                    borderColor = prefs.borderColor,
                    borderThickness = prefs.borderThickness,
                    borderEnabled = prefs.borderEnabled
                )
            )

            if (prefs.widgetType == 0 || prefs.widgetType == 2) {
                widgetView.findViewById<ImageView>(R.id.progress_bar_image)?.setImageBitmap(
                    WidgetStyleHelper.createProgressBitmap(
                        progress = progress,
                        filledStartColor = prefs.progressColor,
                        filledEndColor = prefs.progressGradientEndColor,
                        unfilledColor = prefs.progressUnfilledColor,
                        heightDp = getBarHeightDp(prefs.widgetType, prefs.barSize)
                    )
                )
            }

            if (prefs.widgetType == 1 || prefs.widgetType == 2) {
                widgetView.findViewById<TextView>(R.id.progress_text)?.apply {
                    text = buildProgressText(progress)
                    setTextColor(prefs.textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, getProgressTextSizeSp(prefs.widgetType, prefs.barSize))
                    typeface = resolveTypeface(prefs.fontFamily)
                }
            }

            previewContainer.addView(widgetView)
            applyAppColors()
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Error updating preview", e)
        }
    }

    private fun applyAppColors() {
        val startColor = prefs.progressColor
        val endColor = prefs.progressGradientEndColor
        val midColor = ColorUtils.blendARGB(startColor, endColor, 0.5f)
        val onPrimaryColor = if (ColorUtils.calculateLuminance(midColor) > 0.5) {
            0xFF000000.toInt()
        } else {
            0xFFFFFFFF.toInt()
        }

        val surfaceColor = getBaseSurfaceColor()
        val onSurfaceColor = if (ColorUtils.calculateLuminance(surfaceColor) > 0.5) {
            Color.BLACK
        } else {
            Color.WHITE
        }
        val cardStrokeColor = if (surfaceColor == Color.TRANSPARENT) {
            ColorUtils.setAlphaComponent(midColor, 90)
        } else {
            ColorUtils.setAlphaComponent(onSurfaceColor, 28)
        }

        binding.activityRoot.setBackgroundColor(surfaceColor)

        binding.toolbar.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(startColor, endColor)
        )
        binding.toolbar.setTitleTextColor(onPrimaryColor)

        findViewById<MaterialCardView>(R.id.preview_card)?.apply {
            setCardBackgroundColor(surfaceColor)
            strokeColor = cardStrokeColor
        }

        findViewById<MaterialCardView>(R.id.settings_card)?.apply {
            setCardBackgroundColor(surfaceColor)
            strokeColor = cardStrokeColor
        }

        findViewById<TextView>(R.id.preview_title)?.setTextColor(onSurfaceColor)
        findViewById<TextView>(R.id.preview_subtitle)?.setTextColor(ColorUtils.setAlphaComponent(onSurfaceColor, 220))

        window.statusBarColor = ColorUtils.blendARGB(midColor, 0xFF000000.toInt(), 0.2f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.navigationBarColor = ColorUtils.blendARGB(midColor, 0xFF000000.toInt(), 0.1f)
        }
    }

    private fun getBaseSurfaceColor(): Int {
        return when (prefs.theme) {
            1 -> Color.WHITE
            2 -> Color.BLACK
            3 -> Color.TRANSPARENT
            else -> {
                val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (nightMode == Configuration.UI_MODE_NIGHT_YES) Color.BLACK else Color.WHITE
            }
        }
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
        }
    }

    private fun getProgressTextSizeSp(widgetType: Int, barSize: Int): Float {
        return when (widgetType) {
            1 -> when (barSize) {
                0 -> 20f
                2 -> 26f
                else -> 23f
            }
            else -> when (barSize) {
                0 -> 10f
                2 -> 14f
                else -> 12f
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }

    private fun buildProgressText(progress: Int): CharSequence {
        val text = getString(R.string.progress_percent, progress)
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

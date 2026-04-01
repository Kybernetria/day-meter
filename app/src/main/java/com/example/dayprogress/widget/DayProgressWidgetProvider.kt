package com.example.dayprogress.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.Log
import android.widget.RemoteViews
import com.example.dayprogress.R
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.data.WidgetStyleHelper
import com.example.dayprogress.ui.SettingsActivity
import com.example.dayprogress.worker.AlarmScheduler
import com.example.dayprogress.worker.WorkManagerHelper

class DayProgressWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        DayRepository(context).detectDayStartIfNeeded()
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
        AlarmScheduler.scheduleWidgetUpdates(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_DOUBLE_TAP) {
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            handleDoubleTap(context, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WorkManagerHelper.scheduleUsageCheck(context)
        AlarmScheduler.scheduleWidgetUpdates(context)
        updateAllWidgets(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, DayProgressWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
        if (appWidgetIds.isEmpty()) {
            WorkManagerHelper.cancelUsageCheck(context)
            AlarmScheduler.cancelWidgetUpdates(context)
        }
    }

    private fun handleDoubleTap(context: Context, appWidgetId: Int) {
        val prefs = context.getSharedPreferences("widget_taps", Context.MODE_PRIVATE)
        val lastTap = prefs.getLong("last_tap_$appWidgetId", 0L)
        val now = System.currentTimeMillis()

        if (now - lastTap < 500) {
            val intent = Intent(context, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } else {
            prefs.edit().putLong("last_tap_$appWidgetId", now).apply()
        }
    }

    companion object {
        private const val TAG = "DayProgressWidget"
        const val ACTION_WIDGET_DOUBLE_TAP = "com.example.dayprogress.ACTION_WIDGET_DOUBLE_TAP"

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            if (!isSystemUiResponsive(context)) {
                Log.w(TAG, "System UI not responsive, deferring update for $appWidgetId")
                scheduleRetryUpdate(context, appWidgetId)
                return
            }

            try {
                val repository = DayRepository(context)
                val prefs = repository.getPreferences()
                val progress = repository.calculateProgress()

                val layoutId = getLayoutId(prefs.widgetType, prefs.barSize)

                val views = RemoteViews(context.packageName, layoutId)
                val backgroundColor = if (prefs.theme == 3) 0 else prefs.backgroundColor

                views.setImageViewBitmap(
                    R.id.widget_background_image,
                    WidgetStyleHelper.createBackgroundBitmap(
                        backgroundColor = backgroundColor,
                        borderColor = prefs.borderColor,
                        borderThickness = prefs.borderThickness,
                        borderEnabled = prefs.borderEnabled
                    )
                )

                if (prefs.widgetType == 0 || prefs.widgetType == 2) {
                    views.setImageViewBitmap(
                        R.id.progress_bar_image,
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
                    views.setTextViewText(R.id.progress_text, buildProgressText(context, progress, prefs.fontFamily))
                    views.setTextColor(R.id.progress_text, prefs.textColor)
                    views.setFloat(R.id.progress_text, "setTextSize", prefs.fontSize.toFloat())
                }

                val intent = Intent(context, DayProgressWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_DOUBLE_TAP
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    appWidgetId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating widget $appWidgetId", e)
            }
        }

        fun updateAllWidgets(context: Context) {
            try {
                DayRepository(context).detectDayStartIfNeeded()
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, DayProgressWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                for (appWidgetId in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating all widgets", e)
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
                    0 -> 12
                    2 -> 24
                    else -> 18
                }
            } else {
                when (barSize) {
                    0 -> 8
                    2 -> 16
                    else -> 12
                }
            }
        }

        private fun buildProgressText(context: Context, progress: Int, fontFamily: String): CharSequence {
            val text = context.getString(R.string.progress_percent, progress)
            if (fontFamily == "default") {
                return text
            }
            return SpannableString(text).apply {
                setSpan(TypefaceSpan(fontFamily), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        private fun isSystemUiResponsive(context: Context): Boolean {
            return try {
                AppWidgetManager.getInstance(context) != null
            } catch (e: Exception) {
                false
            }
        }

        private fun scheduleRetryUpdate(context: Context, appWidgetId: Int) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                } catch (e: Exception) {
                    Log.e(TAG, "Retry update failed for $appWidgetId", e)
                }
            }, 5000)
        }
    }
}

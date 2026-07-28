package com.example.dayprogress.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import com.example.dayprogress.R
import com.example.dayprogress.data.CheckpointEngine
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.data.WidgetStyleHelper
import com.example.dayprogress.reminder.ReminderScheduler
import com.example.dayprogress.ui.SettingsActivity
import com.example.dayprogress.worker.AlarmScheduler

class DayProgressWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        DayRepository(context).detectDayStartIfNeeded()
        appWidgetIds.forEach { updateAppWidget(context, appWidgetManager, it) }
        AlarmScheduler.scheduleWidgetUpdates(context)
        ReminderScheduler.reschedule(context)
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

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        updateAllWidgets(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        if (!AlarmScheduler.hasWidgets(context)) {
            AlarmScheduler.cancelWidgetUpdates(context)
        }
    }

    companion object {
        private const val TAG = "DayProgressWidget"

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            try {
                val repository = DayRepository(context)
                val prefs = repository.getPreferences()
                val status = repository.getDayStatus()
                val checkpointEngine = CheckpointEngine(context)
                val markers = checkpointEngine.getWidgetMarkers()
                val nextCheckpoint = checkpointEngine.getNextVisibleOccurrence()
                val expandedText = prefs.widgetType == 1 || (prefs.widgetType == 2 && prefs.barSize == 2)
                val display = WidgetDisplayFormatter.format(context, status, nextCheckpoint, markers.size, expandedText)
                val layoutId = getLayoutId(prefs.widgetType, prefs.barSize)
                val views = RemoteViews(context.packageName, layoutId)
                val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200).coerceIn(100, 400)
                val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 48).coerceIn(18, 160)
                val backgroundColor = if (prefs.theme == 3) 0 else prefs.backgroundColor

                views.setImageViewBitmap(
                    R.id.widget_background_image,
                    WidgetStyleHelper.createBackgroundBitmap(
                        backgroundColor = backgroundColor,
                        borderColor = prefs.borderColor,
                        borderThickness = prefs.borderThickness,
                        borderEnabled = prefs.borderEnabled,
                        widthDp = widthDp,
                        heightDp = heightDp
                    )
                )

                if (prefs.widgetType == 0 || prefs.widgetType == 2) {
                    views.setImageViewBitmap(
                        R.id.progress_bar_image,
                        WidgetStyleHelper.createProgressBitmap(
                            progress = status.progress,
                            filledStartColor = prefs.progressColor,
                            filledEndColor = prefs.progressGradientEndColor,
                            unfilledColor = prefs.progressUnfilledColor,
                            markers = markers,
                            widthDp = widthDp,
                            heightDp = getBarHeightDp(prefs.widgetType, prefs.barSize)
                        )
                    )
                }

                if (prefs.widgetType == 1 || prefs.widgetType == 2) {
                    views.setTextViewText(R.id.progress_text, styledText(display.primary, prefs.fontFamily))
                    views.setTextColor(R.id.progress_text, prefs.textColor)
                    views.setTextViewTextSize(
                        R.id.progress_text,
                        TypedValue.COMPLEX_UNIT_SP,
                        getProgressTextSizeSp(prefs.widgetType, prefs.barSize, display.primary.length)
                    )
                }

                if (prefs.widgetType == 2 && prefs.barSize == 2) {
                    val nextText = display.nextCheckpoint
                    views.setViewVisibility(R.id.next_checkpoint_text, if (nextText == null) View.GONE else View.VISIBLE)
                    views.setTextViewText(R.id.next_checkpoint_text, nextText.orEmpty())
                    views.setTextColor(R.id.next_checkpoint_text, prefs.textColor)
                }

                views.setContentDescription(R.id.widget_root, display.contentDescription)
                views.setOnClickPendingIntent(R.id.widget_root, openSettingsIntent(context, appWidgetId))
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
                appWidgetManager.getAppWidgetIds(componentName).forEach {
                    updateAppWidget(context, appWidgetManager, it)
                }
                AlarmScheduler.scheduleWidgetUpdates(context)
                ReminderScheduler.reschedule(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating all widgets", e)
            }
        }

        private fun openSettingsIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, SettingsActivity::class.java).apply {
                data = "daymeter://widget/$appWidgetId".toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun getLayoutId(widgetType: Int, barSize: Int): Int = when (widgetType) {
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

        private fun getBarHeightDp(widgetType: Int, barSize: Int): Int = if (widgetType == 0) {
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

        private fun styledText(text: String, fontFamily: String): CharSequence {
            if (fontFamily == "default") return text
            return SpannableString(text).apply {
                setSpan(TypefaceSpan(fontFamily), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}

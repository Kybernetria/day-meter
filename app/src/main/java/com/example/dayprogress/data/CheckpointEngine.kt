package com.example.dayprogress.data

import android.content.Context
import java.util.Calendar
import kotlin.math.roundToLong

data class WidgetCheckpointMarker(
    val percent: Float,
    val status: CheckpointStatus?,
    val label: String
)

data class CheckpointSchedule(
    val triggerAtMillis: Long,
    val isDetectionPoll: Boolean = false,
    val occurrences: List<CheckpointOccurrence> = emptyList()
)

data class DueCheckpointResult(
    val due: List<CheckpointOccurrence>,
    val missed: List<CheckpointOccurrence>
)

private data class ScheduledCandidate(
    val scheduleAtMillis: Long,
    val occurrence: CheckpointOccurrence
)

class CheckpointEngine(private val context: Context) {
    private val repository = DayRepository(context)
    private val store = CheckpointStore(context)

    fun getWidgetMarkers(nowMillis: Long = System.currentTimeMillis()): List<WidgetCheckpointMarker> {
        val checkpoints = store.getCheckpoints().filter { it.enabled && it.showOnWidget }
        if (checkpoints.isEmpty()) return emptyList()

        val states = store.getStates()
        val window = repository.getCurrentDayWindow(nowMillis)
        val start = repository.getEffectiveStartTime(nowMillis)
        val markers = mutableListOf<WidgetCheckpointMarker>()

        checkpoints.forEach { checkpoint ->
            val occurrence = when (checkpoint.triggerType) {
                CheckpointTriggerType.PERCENT -> {
                    if (start == -1L) return@forEach
                    val dueAt = percentageDueTime(start, window.dayEndMillis, checkpoint.triggerValue)
                    val dueCalendar = Calendar.getInstance().apply { timeInMillis = dueAt }
                    if (!checkpoint.appliesOn(dueCalendar.get(Calendar.DAY_OF_WEEK))) return@forEach
                    CheckpointOccurrence(
                        checkpoint = checkpoint,
                        occurrenceDayId = formatDayId(dueAt),
                        dueAtMillis = dueAt,
                        markerPercent = checkpoint.triggerValue.toFloat()
                    )
                }

                CheckpointTriggerType.CLOCK -> {
                    if (start == -1L) return@forEach
                    val target = repository.resolveClockTimeInWindow(window, checkpoint.triggerValue)
                    if (target !in start..window.dayEndMillis) return@forEach
                    val targetCalendar = Calendar.getInstance().apply { timeInMillis = target }
                    if (!checkpoint.appliesOn(targetCalendar.get(Calendar.DAY_OF_WEEK))) return@forEach
                    val duration = window.dayEndMillis - start
                    if (duration <= 0L) return@forEach
                    CheckpointOccurrence(
                        checkpoint = checkpoint,
                        occurrenceDayId = formatDayId(target),
                        dueAtMillis = target,
                        markerPercent = ((target - start).toDouble() / duration.toDouble() * 100.0).toFloat().coerceIn(0f, 100f)
                    )
                }
            }

            val status = states[occurrence.key]?.status
            if (status != CheckpointStatus.SKIPPED && status != CheckpointStatus.MISSED) {
                markers += WidgetCheckpointMarker(
                    percent = occurrence.markerPercent ?: return@forEach,
                    status = status,
                    label = checkpoint.displayLabel()
                )
            }
        }

        return markers.sortedBy(WidgetCheckpointMarker::percent)
    }

    fun getNextVisibleOccurrence(nowMillis: Long = System.currentTimeMillis()): CheckpointOccurrence? {
        val checkpoints = store.getCheckpoints().filter(Checkpoint::enabled)
        if (checkpoints.isEmpty()) return null
        val states = store.getStates()

        val persisted = states.values.asSequence()
            .filter {
                it.status in setOf(CheckpointStatus.SCHEDULED, CheckpointStatus.SNOOZED) &&
                    it.snoozeAtMillis > nowMillis
            }
            .mapNotNull { state ->
                checkpoints.firstOrNull { it.id == state.checkpointId }?.let {
                    CheckpointOccurrence(it, state.occurrenceDayId, state.snoozeAtMillis)
                }
            }

        return (persisted + candidateOccurrences(checkpoints, nowMillis, 0..8).asSequence())
            .filter { occurrence ->
                val state = states[occurrence.key]
                occurrence.dueAtMillis > nowMillis && (
                    state == null || state.status in setOf(CheckpointStatus.SCHEDULED, CheckpointStatus.SNOOZED)
                    )
            }
            .minWithOrNull(compareBy<CheckpointOccurrence>({ it.dueAtMillis }, { it.checkpoint.id }))
    }

    fun getNextSchedule(nowMillis: Long = System.currentTimeMillis()): CheckpointSchedule? {
        val checkpoints = store.getCheckpoints().filter(Checkpoint::enabled)
        if (checkpoints.isEmpty()) return null
        val states = store.getStates()
        val candidates = mutableListOf<ScheduledCandidate>()

        states.values
            .filter { it.status in setOf(CheckpointStatus.SCHEDULED, CheckpointStatus.SNOOZED) }
            .forEach { state ->
                checkpoints.firstOrNull { it.id == state.checkpointId }?.let { checkpoint ->
                    val scheduleAt = state.snoozeAtMillis.takeIf { it > nowMillis } ?: (nowMillis + 1_000L)
                    candidates += ScheduledCandidate(
                        scheduleAt,
                        CheckpointOccurrence(checkpoint, state.occurrenceDayId, state.snoozeAtMillis)
                    )
                }
            }

        candidateOccurrences(checkpoints, nowMillis, 0..8)
            .filter { states[it.key] == null }
            .forEach { occurrence ->
                val scheduleAt = when {
                    occurrence.dueAtMillis > nowMillis -> occurrence.dueAtMillis
                    nowMillis - occurrence.dueAtMillis <= LATE_GRACE_MILLIS -> nowMillis + 1_000L
                    else -> return@forEach
                }
                candidates += ScheduledCandidate(scheduleAt, occurrence)
            }

        val nextDue = candidates.minOfOrNull(ScheduledCandidate::scheduleAtMillis)
        val detectionPoll = nextDetectionPoll(checkpoints, nowMillis)
        return when {
            nextDue == null -> detectionPoll?.let { CheckpointSchedule(it, isDetectionPoll = true) }
            detectionPoll != null && detectionPoll < nextDue -> CheckpointSchedule(detectionPoll, isDetectionPoll = true)
            else -> CheckpointSchedule(
                triggerAtMillis = nextDue!!,
                occurrences = candidates
                    .filter { it.scheduleAtMillis == nextDue }
                    .map(ScheduledCandidate::occurrence)
            )
        }
    }

    fun getDueCheckpoints(nowMillis: Long = System.currentTimeMillis()): DueCheckpointResult {
        val checkpoints = store.getCheckpoints().filter(Checkpoint::enabled)
        val states = store.getStates()
        val due = mutableListOf<CheckpointOccurrence>()
        val missed = mutableListOf<CheckpointOccurrence>()

        states.values
            .filter {
                it.status in setOf(CheckpointStatus.SCHEDULED, CheckpointStatus.SNOOZED) &&
                    it.snoozeAtMillis <= nowMillis + DUE_EARLY_TOLERANCE_MILLIS
            }
            .forEach { state ->
                checkpoints.firstOrNull { it.id == state.checkpointId }?.let { checkpoint ->
                    val occurrence = CheckpointOccurrence(checkpoint, state.occurrenceDayId, state.snoozeAtMillis)
                    if (state.status == CheckpointStatus.SNOOZED || nowMillis - state.snoozeAtMillis <= LATE_GRACE_MILLIS) {
                        due += occurrence
                    } else {
                        missed += occurrence
                    }
                }
            }

        candidateOccurrences(checkpoints, nowMillis, -1..0).forEach { occurrence ->
            if (states[occurrence.key] != null || occurrence.dueAtMillis > nowMillis + DUE_EARLY_TOLERANCE_MILLIS) {
                return@forEach
            }
            if (nowMillis - occurrence.dueAtMillis <= LATE_GRACE_MILLIS) {
                due += occurrence
            } else {
                missed += occurrence
            }
        }

        return DueCheckpointResult(
            due = due.distinctBy(CheckpointOccurrence::key).sortedBy(CheckpointOccurrence::dueAtMillis),
            missed = missed.distinctBy(CheckpointOccurrence::key)
        )
    }

    private fun candidateOccurrences(
        checkpoints: List<Checkpoint>,
        nowMillis: Long,
        fixedDayOffsets: IntRange
    ): List<CheckpointOccurrence> {
        val occurrences = mutableListOf<CheckpointOccurrence>()
        val fixed = checkpoints.filter { it.triggerType == CheckpointTriggerType.CLOCK }
        if (fixed.isNotEmpty()) {
            val baseDate = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            fixedDayOffsets.forEach { offset ->
                fixed.forEach { checkpoint ->
                    val dueCalendar = (baseDate.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_MONTH, offset)
                        set(Calendar.HOUR_OF_DAY, checkpoint.triggerValue / 60)
                        set(Calendar.MINUTE, checkpoint.triggerValue % 60)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (checkpoint.appliesOn(dueCalendar.get(Calendar.DAY_OF_WEEK))) {
                        occurrences += CheckpointOccurrence(
                            checkpoint = checkpoint,
                            occurrenceDayId = formatDayId(dueCalendar.timeInMillis),
                            dueAtMillis = dueCalendar.timeInMillis
                        )
                    }
                }
            }
        }

        val percentage = checkpoints.filter { it.triggerType == CheckpointTriggerType.PERCENT }
        if (percentage.isNotEmpty()) {
            val window = repository.getCurrentDayWindow(nowMillis)
            val start = repository.getEffectiveStartTime(nowMillis)
            if (start != -1L && window.dayEndMillis > start) {
                percentage.forEach { checkpoint ->
                    val dueAt = percentageDueTime(start, window.dayEndMillis, checkpoint.triggerValue)
                    val dueCalendar = Calendar.getInstance().apply { timeInMillis = dueAt }
                    if (checkpoint.appliesOn(dueCalendar.get(Calendar.DAY_OF_WEEK))) {
                        occurrences += CheckpointOccurrence(
                            checkpoint = checkpoint,
                            occurrenceDayId = formatDayId(dueAt),
                            dueAtMillis = dueAt,
                            markerPercent = checkpoint.triggerValue.toFloat()
                        )
                    }
                }
            }
        }

        return occurrences
    }

    private fun nextDetectionPoll(checkpoints: List<Checkpoint>, nowMillis: Long): Long? {
        if (checkpoints.none { it.triggerType == CheckpointTriggerType.PERCENT }) return null
        val window = repository.getCurrentDayWindow(nowMillis)
        val start = repository.getEffectiveStartTime(nowMillis)
        if (start == -1L && nowMillis in window.ignoreBeforeMillis until window.dayEndMillis) {
            if (!UsageDetector.hasUsageStatsPermission(context)) return null
            return nowMillis + DETECTION_POLL_MILLIS
        }
        if (start == -1L && nowMillis < window.ignoreBeforeMillis) {
            if (!UsageDetector.hasUsageStatsPermission(context) && !repository.getPreferences().isManualLocked) return null
            return window.ignoreBeforeMillis
        }

        val ignoreBefore = repository.getPreferences().ignoreBefore
        return (0..2).asSequence().map { dayOffset ->
            Calendar.getInstance().apply {
                timeInMillis = nowMillis
                add(Calendar.DAY_OF_MONTH, dayOffset)
                set(Calendar.HOUR_OF_DAY, ignoreBefore / 60)
                set(Calendar.MINUTE, ignoreBefore % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }.firstOrNull { it > nowMillis + 1_000L }
    }

    private fun formatDayId(timeMillis: Long): String = DayIdFormatter.format(timeMillis)

    companion object {
        const val LATE_GRACE_MILLIS = 60 * 60 * 1000L
        private const val DUE_EARLY_TOLERANCE_MILLIS = 30_000L
        private const val DETECTION_POLL_MILLIS = 5 * 60 * 1000L

        fun percentageDueTime(startMillis: Long, endMillis: Long, percentage: Int): Long {
            require(percentage in 0..100)
            if (endMillis <= startMillis) return startMillis
            return startMillis + ((endMillis - startMillis) * (percentage / 100.0)).roundToLong()
        }
    }
}

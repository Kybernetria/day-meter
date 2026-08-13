package com.example.dayprogress.data

import android.content.Context
import androidx.core.content.edit
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class CheckpointTriggerType { CLOCK, PERCENT }
enum class CheckpointNotificationMode { GENTLE, SILENT }
enum class CheckpointStatus { SCHEDULED, NOTIFIED, SNOOZED, DONE, SKIPPED, MISSED }

data class Checkpoint(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val triggerType: CheckpointTriggerType = CheckpointTriggerType.CLOCK,
    /** Minute of day for CLOCK, whole percentage from 0 through 100 for PERCENT. */
    val triggerValue: Int = 12 * 60,
    /** Calendar.SUNDAY is bit 0 through Calendar.SATURDAY as bit 6. */
    val daysMask: Int = ALL_DAYS_MASK,
    val notificationMode: CheckpointNotificationMode = CheckpointNotificationMode.GENTLE,
    val showOnWidget: Boolean = true,
    val enabled: Boolean = true
) {
    fun appliesOn(calendarDayOfWeek: Int): Boolean {
        return calendarDayOfWeek in 1..7 && daysMask and (1 shl (calendarDayOfWeek - 1)) != 0
    }

    fun displayLabel(): String = label.ifBlank { "Checkpoint" }

    fun isValid(): Boolean {
        val valueIsValid = when (triggerType) {
            CheckpointTriggerType.CLOCK -> triggerValue in 0 until 24 * 60
            CheckpointTriggerType.PERCENT -> triggerValue in 0..100
        }
        return runCatching { UUID.fromString(id) }.isSuccess &&
            label.codePointCount(0, label.length) <= MAX_LABEL_CODE_POINTS &&
            daysMask in 1..ALL_DAYS_MASK &&
            valueIsValid
    }

    companion object {
        const val ALL_DAYS_MASK = 0b1111111
        const val MAX_LABEL_CODE_POINTS = 60
    }
}

data class CheckpointState(
    val checkpointId: String,
    val occurrenceDayId: String,
    val status: CheckpointStatus,
    val notifiedAtMillis: Long = -1L,
    val snoozeAtMillis: Long = -1L,
    val snoozeAtElapsedRealtime: Long = -1L
) {
    val occurrenceKey: String get() = key(checkpointId, occurrenceDayId)

    companion object {
        fun key(checkpointId: String, occurrenceDayId: String) = "$checkpointId@$occurrenceDayId"
    }
}

data class CheckpointOccurrence(
    val checkpoint: Checkpoint,
    val occurrenceDayId: String,
    val dueAtMillis: Long,
    val markerPercent: Float? = null
) {
    val key: String get() = CheckpointState.key(checkpoint.id, occurrenceDayId)
}

class CheckpointStore(context: Context) {
    private val prefs = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)

    fun getCheckpoints(): List<Checkpoint> = synchronized(LOCK) {
        safeStringSet(AppPreferences.KEY_CHECKPOINTS)
            .asSequence()
            .take(MAX_CHECKPOINTS + 1)
            .mapNotNull(::decodeCheckpoint)
            .filter(Checkpoint::isValid)
            .distinctBy(Checkpoint::id)
            .take(MAX_CHECKPOINTS)
            .sortedWith(compareBy<Checkpoint>({ it.triggerType.ordinal }, { it.triggerValue }, { it.label.lowercase() }))
            .toList()
    }

    fun saveCheckpoint(checkpoint: Checkpoint): Boolean = synchronized(LOCK) {
        if (!checkpoint.isValid()) return@synchronized false
        val checkpoints = getCheckpoints().toMutableList()
        val existingIndex = checkpoints.indexOfFirst { it.id == checkpoint.id }
        if (existingIndex >= 0) {
            checkpoints[existingIndex] = checkpoint
        } else {
            if (checkpoints.size >= MAX_CHECKPOINTS) return@synchronized false
            checkpoints += checkpoint
        }
        writeCheckpoints(checkpoints)
        true
    }

    fun deleteCheckpoint(checkpointId: String) = synchronized(LOCK) {
        writeCheckpoints(getCheckpoints().filterNot { it.id == checkpointId })
        val states = getStates().filterValues { it.checkpointId != checkpointId }
        writeStates(states.values)
    }

    fun hasEnabledCheckpoints(): Boolean = getCheckpoints().any { it.enabled }

    fun getStates(): Map<String, CheckpointState> = synchronized(LOCK) {
        val elapsedSnoozes = getElapsedSnoozes()
        safeStringSet(AppPreferences.KEY_CHECKPOINT_STATES)
            .asSequence()
            .take(MAX_STATE_RECORDS + 1)
            .mapNotNull(::decodeState)
            .map { state ->
                state.copy(
                    snoozeAtElapsedRealtime = elapsedSnoozes[state.occurrenceKey]
                        ?: state.snoozeAtElapsedRealtime
                )
            }
            .distinctBy(CheckpointState::occurrenceKey)
            .take(MAX_STATE_RECORDS)
            .associateBy(CheckpointState::occurrenceKey)
    }

    fun getState(checkpointId: String, occurrenceDayId: String): CheckpointState? {
        return getStates()[CheckpointState.key(checkpointId, occurrenceDayId)]
    }

    fun putState(state: CheckpointState) = putStates(listOf(state))

    fun putStates(newStates: Collection<CheckpointState>) = synchronized(LOCK) {
        if (newStates.isEmpty()) return@synchronized
        val states = getStates().toMutableMap()
        newStates.forEach { states[it.occurrenceKey] = it }
        writeStates(pruneStates(states.values))
    }

    fun adjustSnoozesAfterClockChange(nowWallMillis: Long, nowElapsedRealtime: Long) = synchronized(LOCK) {
        val adjusted = getStates().values.map { state ->
            if (state.status == CheckpointStatus.SNOOZED && state.snoozeAtElapsedRealtime >= 0L) {
                val remaining = (state.snoozeAtElapsedRealtime - nowElapsedRealtime).coerceAtLeast(0L)
                state.copy(snoozeAtMillis = nowWallMillis + remaining)
            } else state
        }
        writeStates(adjusted)
    }

    fun rebaseSnoozesAfterBoot(nowWallMillis: Long, nowElapsedRealtime: Long) = synchronized(LOCK) {
        val rebased = getStates().values.map { state ->
            if (state.status == CheckpointStatus.SNOOZED) {
                val remaining = (state.snoozeAtMillis - nowWallMillis).coerceAtLeast(0L)
                state.copy(snoozeAtElapsedRealtime = nowElapsedRealtime + remaining)
            } else state
        }
        writeStates(rebased)
    }

    fun removeState(checkpointId: String, occurrenceDayId: String) = synchronized(LOCK) {
        val states = getStates().toMutableMap()
        states.remove(CheckpointState.key(checkpointId, occurrenceDayId))
        writeStates(states.values)
    }

    fun clearStatesForCheckpoint(checkpointId: String) = synchronized(LOCK) {
        writeStates(getStates().values.filterNot { it.checkpointId == checkpointId })
    }

    fun clearScheduledStates() = synchronized(LOCK) {
        writeStates(getStates().values.filterNot { it.status == CheckpointStatus.SCHEDULED })
    }

    fun clearCheckpointData() = synchronized(LOCK) {
        prefs.edit {
            remove(AppPreferences.KEY_CHECKPOINTS)
            remove(AppPreferences.KEY_CHECKPOINT_STATES)
            remove(AppPreferences.KEY_CHECKPOINT_SNOOZE_ELAPSED)
            remove(AppPreferences.KEY_NOTIFICATION_IDS)
            remove(AppPreferences.KEY_NOTIFICATION_ID_NEXT)
        }
    }

    private fun safeStringSet(key: String): Set<String> {
        return try {
            prefs.getStringSet(key, emptySet()).orEmpty().toSet()
        } catch (_: ClassCastException) {
            prefs.edit { remove(key) }
            emptySet()
        }
    }

    private fun writeCheckpoints(checkpoints: Collection<Checkpoint>) {
        prefs.edit { putStringSet(AppPreferences.KEY_CHECKPOINTS, checkpoints.map(::encodeCheckpoint).toSet()) }
    }

    private fun writeStates(states: Collection<CheckpointState>) {
        // State transitions are durable obligations; do not leave the commit to a later apply().
        prefs.edit(commit = true) {
            putStringSet(AppPreferences.KEY_CHECKPOINT_STATES, states.map(::encodeState).toSet())
            putStringSet(
                AppPreferences.KEY_CHECKPOINT_SNOOZE_ELAPSED,
                states.filter { it.status == CheckpointStatus.SNOOZED && it.snoozeAtElapsedRealtime >= 0L }
                    .map { "${it.occurrenceKey}$SEPARATOR${it.snoozeAtElapsedRealtime}" }
                    .toSet()
            )
        }
    }

    private fun getElapsedSnoozes(): Map<String, Long> {
        return safeStringSet(AppPreferences.KEY_CHECKPOINT_SNOOZE_ELAPSED).mapNotNull { encoded ->
            val separator = encoded.lastIndexOf(SEPARATOR)
            if (separator <= 0) null else encoded.substring(separator + 1).toLongOrNull()?.let {
                encoded.substring(0, separator) to it
            }
        }.toMap()
    }

    private fun pruneStates(states: Collection<CheckpointState>): Collection<CheckpointState> {
        val retainedDayIds = states.map { it.occurrenceDayId }.distinct().sortedDescending().take(STATE_DAYS_TO_KEEP).toSet()
        return states.filter { it.occurrenceDayId in retainedDayIds }.take(MAX_STATE_RECORDS)
    }

    private fun encodeCheckpoint(checkpoint: Checkpoint): String = listOf(
        FORMAT_VERSION,
        checkpoint.id,
        checkpoint.triggerType.name,
        checkpoint.triggerValue,
        checkpoint.daysMask,
        checkpoint.notificationMode.name,
        if (checkpoint.showOnWidget) 1 else 0,
        if (checkpoint.enabled) 1 else 0,
        URLEncoder.encode(checkpoint.label, StandardCharsets.UTF_8.name())
    ).joinToString(SEPARATOR)

    private fun decodeCheckpoint(encoded: String): Checkpoint? = runCatching {
        if (encoded.length > MAX_RECORD_LENGTH) return@runCatching null
        val parts = encoded.split(SEPARATOR, limit = 9)
        if (parts.size != 9 || parts[0] != FORMAT_VERSION) return@runCatching null
        Checkpoint(
            id = parts[1],
            triggerType = CheckpointTriggerType.valueOf(parts[2]),
            triggerValue = parts[3].toInt(),
            daysMask = parts[4].toInt(),
            notificationMode = CheckpointNotificationMode.valueOf(parts[5]),
            showOnWidget = parts[6] == "1",
            enabled = parts[7] == "1",
            label = URLDecoder.decode(parts[8], StandardCharsets.UTF_8.name())
        ).takeIf(Checkpoint::isValid)
    }.getOrNull()

    private fun encodeState(state: CheckpointState): String = listOf(
        FORMAT_VERSION,
        state.checkpointId,
        state.occurrenceDayId,
        state.status.name,
        state.notifiedAtMillis,
        state.snoozeAtMillis
    ).joinToString(SEPARATOR)

    private fun decodeState(encoded: String): CheckpointState? = runCatching {
        if (encoded.length > MAX_RECORD_LENGTH) return@runCatching null
        val parts = encoded.split(SEPARATOR, limit = 7)
        if (parts.size !in 6..7 || parts[0] != FORMAT_VERSION) return@runCatching null
        UUID.fromString(parts[1])
        CheckpointState(
            checkpointId = parts[1],
            occurrenceDayId = parts[2].takeIf(DayIdFormatter::isValid) ?: return@runCatching null,
            status = CheckpointStatus.valueOf(parts[3]),
            notifiedAtMillis = parts[4].toLong(),
            snoozeAtMillis = parts[5].toLong(),
            snoozeAtElapsedRealtime = parts.getOrNull(6)?.toLong() ?: -1L
        )
    }.getOrNull()

    private companion object {
        const val FORMAT_VERSION = "1"
        const val SEPARATOR = "|"
        const val MAX_CHECKPOINTS = 50
        const val MAX_STATE_RECORDS = 700
        const val STATE_DAYS_TO_KEEP = 14
        const val MAX_RECORD_LENGTH = 1024
        val LOCK = Any()
    }
}

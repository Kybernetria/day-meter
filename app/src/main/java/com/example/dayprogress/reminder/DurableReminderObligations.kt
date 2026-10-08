package com.example.dayprogress.reminder

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.data.Checkpoint
import com.example.dayprogress.data.CheckpointOccurrence
import com.example.dayprogress.data.CheckpointState
import com.example.dayprogress.data.CheckpointStatus
import com.example.dayprogress.data.CheckpointStore
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/** A durable action payload. The payload is committed before an OS wake is requested. */
internal data class DurableCheckpointAction(
    val id: String,
    val action: String,
    val checkpointId: String,
    val occurrenceDayId: String,
    val deliveryToken: Long,
    val attempts: Int,
    val deadlineMillis: Long,
    val status: ActionStatus,
    val lastError: String = ""
)

internal enum class ActionStatus { PENDING, CLAIMED, TERMINAL }

internal object CriticalActionQueue {
    private const val TAG = "CriticalActionQueue"
    private const val MAX_PENDING = 64
    private const val MAX_RECORDS = 128
    private const val MAX_ATTEMPTS = 5
    private const val ACTION_WINDOW_MILLIS = 6 * 60 * 60 * 1000L
    private const val RETRY_DELAY_MILLIS = 1_000L
    private const val SEPARATOR = "|"
    private const val FORMAT = "1"
    private val lock = Any()

    fun enqueue(context: Context, source: Intent): DurableCheckpointAction? {
        val action = source.action?.takeIf { it in VALID_ACTIONS } ?: return null
        val checkpointId = source.getStringExtra(CheckpointActionReceiver.EXTRA_CHECKPOINT_ID)
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: return null
        val occurrenceDayId = source.getStringExtra(CheckpointActionReceiver.EXTRA_OCCURRENCE_DAY_ID)
            ?.takeIf { com.example.dayprogress.data.DayIdFormatter.isValid(it) } ?: return null
        val token = source.getLongExtra(CheckpointActionReceiver.EXTRA_DELIVERY_TOKEN, -1L)
            .takeIf { it >= 0L } ?: return null
        val id = source.getStringExtra(CheckpointActionReceiver.EXTRA_ACTION_ID)
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?: UUID.nameUUIDFromBytes("$checkpointId\u0000$occurrenceDayId\u0000$token\u0000$action".toByteArray())
                .toString()
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            val records = read(context).toMutableList()
            records.firstOrNull { it.id == id }?.let { return@synchronized it }
            val pendingCount = records.count { it.status != ActionStatus.TERMINAL }
            if (pendingCount >= MAX_PENDING) {
                val terminal = DurableCheckpointAction(
                    id = id,
                    action = action,
                    checkpointId = checkpointId,
                    occurrenceDayId = occurrenceDayId,
                    deliveryToken = token,
                    attempts = 0,
                    deadlineMillis = now + ACTION_WINDOW_MILLIS,
                    status = ActionStatus.TERMINAL,
                    lastError = "durable action queue is full"
                )
                if (records.size < MAX_RECORDS) {
                    records += terminal
                    write(context, records)
                }
                Log.e(TAG, "Critical action rejected because the durable queue is full")
                return@synchronized terminal
            }
            val pending = DurableCheckpointAction(
                id = id,
                action = action,
                checkpointId = checkpointId,
                occurrenceDayId = occurrenceDayId,
                deliveryToken = token,
                attempts = 0,
                deadlineMillis = now + ACTION_WINDOW_MILLIS,
                status = ActionStatus.PENDING
            )
            records += pending
            write(context, records)
            pending
        }
    }

    fun replayPending(context: Context) {
        val candidates = synchronized(lock) { read(context).filter { it.status != ActionStatus.TERMINAL } }
        candidates.forEach { candidate ->
            val claimed = synchronized(lock) { claim(context, candidate.id) } ?: return@forEach
            try {
                val changed = ReminderTransitions.run {
                    CheckpointActionHandler.process(
                        context,
                        Intent(context, CheckpointActionReceiver::class.java).apply {
                            action = claimed.action
                            putExtra(CheckpointActionReceiver.EXTRA_CHECKPOINT_ID, claimed.checkpointId)
                            putExtra(CheckpointActionReceiver.EXTRA_OCCURRENCE_DAY_ID, claimed.occurrenceDayId)
                            putExtra(CheckpointActionReceiver.EXTRA_DELIVERY_TOKEN, claimed.deliveryToken)
                        },
                        claimed.checkpointId,
                        claimed.occurrenceDayId,
                        claimed.deliveryToken
                    )
                }
                // A false result is also completion: the compare-and-apply guard means the
                // transition was already applied or the occurrence is no longer actionable.
                synchronized(lock) { complete(context, claimed.id) }
                if (changed) {
                    try {
                        if (!ReminderScheduler.reschedule(context)) {
                            ReminderScheduler.scheduleRecovery(context)
                        }
                    } catch (error: Exception) {
                        // This is a reminder scheduling obligation, never action replay.
                        Log.e(TAG, "Post-action reminder reschedule failed", error)
                        runCatching { ReminderScheduler.scheduleRecovery(context) }
                            .onFailure { recoveryError -> Log.e(TAG, "Reminder recovery failed", recoveryError) }
                    }
                    com.example.dayprogress.widget.DayProgressWidgetProvider.refreshWidgetsInBackground(context)
                }
            } catch (error: Exception) {
                synchronized(lock) { fail(context, claimed, error.message ?: error.javaClass.simpleName) }
                Log.e(TAG, "Critical action replay failed for ${claimed.id}", error)
            }
        }
        if (synchronized(lock) { read(context).any { it.status != ActionStatus.TERMINAL } }) {
            CheckpointActionReplay.scheduleWake(context)
        }
    }

    private fun claim(context: Context, id: String): DurableCheckpointAction? {
        val records = read(context).toMutableList()
        val index = records.indexOfFirst { it.id == id && it.status != ActionStatus.TERMINAL }
        if (index < 0) return null
        val current = records[index]
        val attempts = current.attempts + 1
        val claimed = current.copy(
            attempts = attempts,
            status = ActionStatus.CLAIMED
        )
        records[index] = claimed
        write(context, records)
        return claimed
    }

    private fun complete(context: Context, id: String) {
        val records = read(context).toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return
        // Retain a terminal receipt for observability and duplicate suppression.
        records[index] = records[index].copy(status = ActionStatus.TERMINAL, lastError = "completed")
        write(context, records)
    }

    private fun fail(context: Context, action: DurableCheckpointAction, error: String) {
        val records = read(context).toMutableList()
        val index = records.indexOfFirst { it.id == action.id }
        if (index < 0) return
        val now = System.currentTimeMillis()
        val terminal = action.attempts >= MAX_ATTEMPTS || now >= action.deadlineMillis
        records[index] = action.copy(
            status = if (terminal) ActionStatus.TERMINAL else ActionStatus.PENDING,
            lastError = error.take(240)
        )
        write(context, records)
        if (terminal) Log.e(TAG, "Critical action ${action.id} reached terminal state after ${action.attempts} attempts")
        else CheckpointActionReplay.scheduleWake(context, RETRY_DELAY_MILLIS)
    }

    private fun read(context: Context): List<DurableCheckpointAction> {
        val prefs = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        return try {
            prefs.getStringSet(AppPreferences.KEY_CHECKPOINT_ACTIONS, emptySet()).orEmpty()
                .take(MAX_RECORDS + 1)
                .mapNotNull(::decode)
                .distinctBy { it.id }
                .take(MAX_RECORDS)
        } catch (_: ClassCastException) {
            prefs.edit { remove(AppPreferences.KEY_CHECKPOINT_ACTIONS) }
            emptyList()
        }
    }

    private fun write(context: Context, records: Collection<DurableCheckpointAction>) {
        val prefs = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        val active = records.filter { it.status != ActionStatus.TERMINAL }.take(MAX_RECORDS)
        val terminalCapacity = (MAX_RECORDS - active.size).coerceAtLeast(0)
        val retained = active + records.filter { it.status == ActionStatus.TERMINAL }.takeLast(terminalCapacity)
        prefs.edit(commit = true) {
            putStringSet(AppPreferences.KEY_CHECKPOINT_ACTIONS, retained.map(::encode).toSet())
        }
    }

    private fun encode(action: DurableCheckpointAction): String = listOf(
        FORMAT, action.id, action.action, action.checkpointId, action.occurrenceDayId,
        action.deliveryToken, action.attempts, action.deadlineMillis, action.status.name,
        URLEncoder.encode(action.lastError, StandardCharsets.UTF_8.name())
    ).joinToString(SEPARATOR)

    private fun decode(value: String): DurableCheckpointAction? = runCatching {
        if (value.length > 1024) return@runCatching null
        val parts = value.split(SEPARATOR, limit = 10)
        if (parts.size != 10 || parts[0] != FORMAT) return@runCatching null
        UUID.fromString(parts[1])
        UUID.fromString(parts[3])
        require(com.example.dayprogress.data.DayIdFormatter.isValid(parts[4]))
        DurableCheckpointAction(
            parts[1], parts[2].takeIf { it in VALID_ACTIONS } ?: return@runCatching null,
            parts[3], parts[4], parts[5].toLong().takeIf { it >= 0L } ?: return@runCatching null,
            parts[6].toInt().takeIf { it in 0..MAX_ATTEMPTS } ?: return@runCatching null,
            parts[7].toLong().takeIf { it >= 0L } ?: return@runCatching null,
            ActionStatus.valueOf(parts[8]), URLDecoder.decode(parts[9], StandardCharsets.UTF_8.name())
        )
    }.getOrNull()

    private val VALID_ACTIONS = setOf(
        CheckpointActionReceiver.ACTION_DONE,
        CheckpointActionReceiver.ACTION_SKIP,
        CheckpointActionReceiver.ACTION_SNOOZE
    )
}

internal data class NotificationOutboxEntry(
    val id: String,
    val checkpointId: String,
    val occurrenceDayId: String,
    val dueAtMillis: Long,
    val deliveryToken: Long,
    val attempts: Int,
    val status: NotificationOutboxStatus,
    val lastError: String = ""
)

internal enum class NotificationOutboxStatus { PENDING, CLAIMED, TERMINAL }

internal object NotificationDeliveryOutbox {
    private const val MAX_RECORDS = 64
    private const val MAX_ATTEMPTS = 4
    private const val FORMAT = "1"
    private const val SEPARATOR = "|"
    private val lock = Any()

    fun prepare(context: Context, occurrence: CheckpointOccurrence, deliveryToken: Long): Boolean = synchronized(lock) {
        val records = read(context).toMutableList()
        val id = occurrence.key
        if (records.any { it.id == id && it.status != NotificationOutboxStatus.TERMINAL }) return@synchronized true
        if (records.count { it.status != NotificationOutboxStatus.TERMINAL } >= MAX_RECORDS) {
            Log.e("NotificationOutbox", "Notification outbox is full; delivery is terminal")
            return@synchronized false
        }
        records += NotificationOutboxEntry(
            id, occurrence.checkpoint.id, occurrence.occurrenceDayId, occurrence.dueAtMillis,
            deliveryToken, 0, NotificationOutboxStatus.PENDING
        )
        write(context, records)
        true
    }

    /**
     * Replays nonterminal deliveries and reports whether the durable obligation remains.
     * Direct callers request recovery scheduling by default; coordinators can defer that
     * decision until after their normal alarm reconciliation.
     */
    fun recoverPending(context: Context, scheduleRecovery: Boolean = true): Boolean {
        val candidates = synchronized(lock) { read(context).filter { it.status != NotificationOutboxStatus.TERMINAL } }
        candidates.forEach { entry -> deliver(context, entry) }
        val pending = hasPending(context)
        if (pending && scheduleRecovery && !ReminderScheduler.scheduleRecovery(context)) {
            Log.e("NotificationOutbox", "Pending notification has no accepted recovery wake")
        }
        return pending
    }

    fun hasPending(context: Context): Boolean = synchronized(lock) {
        read(context).any { it.status != NotificationOutboxStatus.TERMINAL }
    }

    private fun deliver(context: Context, entry: NotificationOutboxEntry) {
        val claimed = synchronized(lock) { claim(context, entry.id) } ?: return
        val checkpoint = CheckpointStore(context).getCheckpoints().firstOrNull { it.id == claimed.checkpointId }
        if (checkpoint == null) {
            synchronized(lock) { terminal(context, claimed, "checkpoint was deleted") }
            return
        }
        try {
            ReminderTransitions.run {
                val store = CheckpointStore(context)
                val state = store.getState(claimed.checkpointId, claimed.occurrenceDayId)
                if (state == null || state.status == CheckpointStatus.SCHEDULED || state.status == CheckpointStatus.SNOOZED) {
                    store.putState(
                        CheckpointState(
                            claimed.checkpointId,
                            claimed.occurrenceDayId,
                            CheckpointStatus.NOTIFIED,
                            notifiedAtMillis = claimed.deliveryToken
                        )
                    )
                }
            }
            val delivered = ReminderNotifier.show(
                context,
                CheckpointOccurrence(checkpoint, claimed.occurrenceDayId, claimed.dueAtMillis),
                claimed.deliveryToken
            )
            if (delivered) {
                synchronized(lock) { complete(context, claimed.id) }
            } else {
                ReminderTransitions.run {
                    val current = CheckpointStore(context).getState(claimed.checkpointId, claimed.occurrenceDayId)
                    if (current?.status == CheckpointStatus.NOTIFIED && current.notifiedAtMillis == claimed.deliveryToken) {
                        CheckpointStore(context).putState(current.copy(status = CheckpointStatus.MISSED, notifiedAtMillis = -1L))
                    }
                }
                synchronized(lock) { terminal(context, claimed, "notification permission or channel unavailable") }
            }
        } catch (error: Exception) {
            synchronized(lock) { fail(context, claimed, error.message ?: error.javaClass.simpleName) }
            Log.e("NotificationOutbox", "Notification delivery failed for ${claimed.id}", error)
        }
    }

    private fun claim(context: Context, id: String): NotificationOutboxEntry? {
        val records = read(context).toMutableList()
        val index = records.indexOfFirst { it.id == id && it.status != NotificationOutboxStatus.TERMINAL }
        if (index < 0) return null
        val claimed = records[index].copy(attempts = records[index].attempts + 1, status = NotificationOutboxStatus.CLAIMED)
        records[index] = claimed
        write(context, records)
        return claimed
    }

    private fun complete(context: Context, id: String) {
        val records = read(context).toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index >= 0) {
            records[index] = records[index].copy(status = NotificationOutboxStatus.TERMINAL, lastError = "delivered")
            write(context, records)
        }
    }

    private fun terminal(context: Context, entry: NotificationOutboxEntry, error: String) {
        val records = read(context).toMutableList()
        val index = records.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            records[index] = entry.copy(status = NotificationOutboxStatus.TERMINAL, lastError = error.take(240))
            write(context, records)
        }
    }

    private fun fail(context: Context, entry: NotificationOutboxEntry, error: String) {
        if (entry.attempts >= MAX_ATTEMPTS) terminal(context, entry, error) else {
            val records = read(context).toMutableList()
            val index = records.indexOfFirst { it.id == entry.id }
            if (index >= 0) {
                records[index] = entry.copy(status = NotificationOutboxStatus.PENDING, lastError = error.take(240))
                write(context, records)
            }
        }
    }

    private fun read(context: Context): List<NotificationOutboxEntry> {
        val prefs = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        return try {
            prefs.getStringSet(AppPreferences.KEY_NOTIFICATION_OUTBOX, emptySet()).orEmpty()
                .take(MAX_RECORDS + 1).mapNotNull(::decode).distinctBy { it.id }.take(MAX_RECORDS)
        } catch (_: ClassCastException) {
            prefs.edit { remove(AppPreferences.KEY_NOTIFICATION_OUTBOX) }
            emptyList()
        }
    }

    private fun write(context: Context, records: Collection<NotificationOutboxEntry>) {
        val active = records.filter { it.status != NotificationOutboxStatus.TERMINAL }.take(MAX_RECORDS)
        val terminalCapacity = (MAX_RECORDS - active.size).coerceAtLeast(0)
        val retained = active + records.filter { it.status == NotificationOutboxStatus.TERMINAL }.takeLast(terminalCapacity)
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) { putStringSet(AppPreferences.KEY_NOTIFICATION_OUTBOX, retained.map(::encode).toSet()) }
    }

    private fun encode(entry: NotificationOutboxEntry): String = listOf(
        FORMAT, entry.id, entry.checkpointId, entry.occurrenceDayId, entry.dueAtMillis,
        entry.deliveryToken, entry.attempts, entry.status.name,
        URLEncoder.encode(entry.lastError, StandardCharsets.UTF_8.name())
    ).joinToString(SEPARATOR)

    private fun decode(value: String): NotificationOutboxEntry? = runCatching {
        val parts = value.split(SEPARATOR, limit = 9)
        if (parts.size != 9 || parts[0] != FORMAT) return@runCatching null
        UUID.fromString(parts[2])
        require(com.example.dayprogress.data.DayIdFormatter.isValid(parts[3]))
        NotificationOutboxEntry(
            parts[1], parts[2], parts[3], parts[4].toLong(), parts[5].toLong(),
            parts[6].toInt().takeIf { it in 0..MAX_ATTEMPTS } ?: return@runCatching null,
            NotificationOutboxStatus.valueOf(parts[7]), URLDecoder.decode(parts[8], StandardCharsets.UTF_8.name())
        )
    }.getOrNull()
}

package com.slai.campus.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.IoDispatcher
import com.slai.campus.core.database.ScheduleDao
import com.slai.campus.core.session.SessionManager
import com.slai.campus.core.session.SessionStore
import com.slai.campus.core.common.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Class-based reminders.
 *
 * WorkManager is the wrong tool for this: its minimum period is 15 minutes and it is explicitly
 * documented as inexact. A reminder that fires after the lecture started is useless, so reminders use
 * [AlarmManager] and are re-derived from the cache every time the timetable changes.
 *
 * Two modes:
 *  - **standard** (default): `setAndAllowWhileIdle`, inexact, works with no special permission;
 *  - **exact**: `setExactAndAllowWhileIdle`, only when the user enables it *and* the OS allows it.
 *
 * The set of scheduled occurrence ids is persisted, so a course that disappears from the timetable
 * also loses its alarm.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    private val context: Context,
    private val scheduleDao: ScheduleDao,
    private val sessionManager: SessionManager,
    private val sessionStore: SessionStore,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val io: CoroutineDispatcher
) {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(AlarmManager::class.java)

    /** True when the OS would accept an exact alarm right now. */
    fun canScheduleExactAlarms(): Boolean {
        val manager = alarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /**
     * Re-derives every reminder from the local cache. Safe to call after every successful sync, after
     * a settings change, and after a reboot.
     */
    suspend fun rescheduleFromCache(): Int = withContext(io) {
        val config = sessionStore.reminderConfigOnce()
        if (!config.enabled) {
            cancelAll()
            return@withContext 0
        }

        val accountHash = sessionManager.current().accountHash
        if (accountHash.isNullOrBlank()) {
            cancelAll()
            return@withContext 0
        }

        val zone = ZoneId.systemDefault()
        val now = timeProvider.nowDateTime()
        val from = timeProvider.today()
        val rows = scheduleDao.rangeOnce(accountHash, from, from.plusDays(config.windowDays.toLong() - 1))

        val desired = rows.mapNotNull { row ->
            val start = row.date.atTime(row.startTime)
            val trigger = start.minusMinutes(config.leadMinutes.toLong())
            if (trigger.isBefore(now)) return@mapNotNull null
            ScheduledReminder(
                id = row.id,
                courseName = row.courseName,
                location = row.location,
                teacher = row.teacher,
                triggerAtMillis = trigger.atZone(zone).toInstant().toEpochMilli(),
                startTimeText = row.startTime.toString()
            )
        }

        val previouslyScheduled = sessionStore.scheduledReminderIds()
        val desiredIds = desired.map { it.id }.toSet()

        // Cancel alarms whose class no longer exists or has already passed.
        (previouslyScheduled - desiredIds).forEach { staleId ->
            alarmManager?.cancel(pendingIntent(staleId, null, null, null))
        }

        val exactAllowed = config.exact && canScheduleExactAlarms()
        var scheduled = 0
        desired.forEach { reminder ->
            val pending = pendingIntent(
                reminder.id,
                reminder.courseName,
                reminder.location,
                reminder.startTimeText
            )
            val manager = alarmManager ?: return@forEach
            runCatching {
                if (exactAllowed) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pending)
                } else {
                    // Inexact but allowed in Doze: the OS may shift it by a few minutes.
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pending)
                }
                scheduled++
            }.onFailure { AppLog.w("alarm scheduling failed: ${it.javaClass.simpleName}") }
        }

        sessionStore.setScheduledReminderIds(desiredIds)
        AppLog.i("reminders rescheduled: $scheduled (exact=$exactAllowed)")
        scheduled
    }

    suspend fun cancelAll() {
        val manager = alarmManager ?: return
        runCatching {
            sessionStore.scheduledReminderIds().forEach { id ->
                manager.cancel(pendingIntent(id, null, null, null))
            }
            sessionStore.setScheduledReminderIds(emptySet())
        }.onFailure { AppLog.w("cancelAll failed: ${it.javaClass.simpleName}") }
    }

    private fun pendingIntent(
        occurrenceId: String,
        courseName: String?,
        location: String?,
        startTime: String?
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_CLASS_REMINDER
            // A stable per-occurrence action keeps the extras out of the PendingIntent identity.
            putExtra(AlarmReceiver.EXTRA_OCCURRENCE_ID, occurrenceId)
            courseName?.let { putExtra(AlarmReceiver.EXTRA_COURSE, it) }
            location?.let { putExtra(AlarmReceiver.EXTRA_LOCATION, it) }
            startTime?.let { putExtra(AlarmReceiver.EXTRA_START, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            occurrenceId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private data class ScheduledReminder(
        val id: String,
        val courseName: String,
        val location: String?,
        val teacher: String?,
        val triggerAtMillis: Long,
        val startTimeText: String
    )
}

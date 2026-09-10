package com.slai.campus.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.slai.campus.core.common.AppLog
import com.slai.campus.core.session.SessionManager
import com.slai.campus.reminder.ReminderScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Re-derives every alarm from the cache.
 *
 * Runs after a reboot (alarms do not survive), after the app is updated, and after a settings change.
 * It never touches the network: the cache is the source of truth for what to remind about.
 */
@HiltWorker
class ReminderRescheduleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reminderScheduler: ReminderScheduler,
    private val sessionManager: SessionManager
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            sessionManager.hydrate()
            val count = reminderScheduler.rescheduleFromCache()
            AppLog.i("reminder reschedule worker scheduled $count alarm(s)")
            Result.success(workDataOf(KEY_COUNT to count))
        }.getOrElse { error ->
            AppLog.w("reminder reschedule failed: ${error.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        const val KEY_COUNT = "count"
        private const val NAME = "reminder_reschedule"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReminderRescheduleWorker>()
                .addTag(NAME)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

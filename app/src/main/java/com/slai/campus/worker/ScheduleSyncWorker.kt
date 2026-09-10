package com.slai.campus.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.workDataOf
import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.session.SessionManager
import com.slai.campus.domain.schedule.RefreshReason
import com.slai.campus.domain.schedule.ScheduleRepository
import com.slai.campus.reminder.ReminderScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Best-effort background freshness.
 *
 * The goal is *"尽力保持缓存新鲜"*, not "guarantee a refresh at minute X". WorkManager is explicitly
 * inexact and the minimum periodic interval is 15 minutes, so the schedule is a few hours apart and
 * constrained to a connected network. If the session is gone the worker stops immediately: the plan
 * forbids popping a login UI from the background.
 */
@HiltWorker
class ScheduleSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scheduleRepository: ScheduleRepository,
    private val sessionManager: SessionManager,
    private val reminderScheduler: ReminderScheduler
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val system = inputData.getString(KEY_SYSTEM)
            ?.let { SchoolSystem.fromKey(it) } ?: SchoolSystem.SIS

        if (!sessionManager.shouldSync(system)) {
            AppLog.i("background sync skipped: $system session unavailable")
            return Result.success(workDataOf(KEY_SKIPPED to "session unavailable"))
        }

        val result = scheduleRepository.refresh(RefreshReason.BACKGROUND)
        return when (result) {
            is com.slai.campus.domain.schedule.RefreshResult.Success -> {
                runCatching { reminderScheduler.rescheduleFromCache() }
                Result.success(workDataOf(KEY_RESULT to "success"))
            }
            com.slai.campus.domain.schedule.RefreshResult.SessionExpired ->
                // Nothing to retry: the user must log in again.
                Result.success(workDataOf(KEY_RESULT to "session expired"))
            is com.slai.campus.domain.schedule.RefreshResult.Offline -> {
                // Network may come back; let WorkManager retry with backoff.
                if (runAttemptCount < 3) Result.retry() else Result.success(workDataOf(KEY_RESULT to "offline"))
            }
            is com.slai.campus.domain.schedule.RefreshResult.ServerError -> {
                if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(KEY_RESULT to "server error"))
            }
            is com.slai.campus.domain.schedule.RefreshResult.SchemaChanged ->
                Result.failure(workDataOf(KEY_RESULT to "schema changed"))
            is com.slai.campus.domain.schedule.RefreshResult.Failed ->
                if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(KEY_RESULT to "failed"))
        }
    }

    companion object {
        const val KEY_SYSTEM = "system"
        const val KEY_RESULT = "result"
        const val KEY_SKIPPED = "skipped"

        private const val PERIODIC_NAME = "schedule_sync_periodic"

        /** Enqueues (or keeps) the periodic sync. Every 6 hours, Wi-Fi or mobile, battery-friendly. */
        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ScheduleSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                // Without an initial delay WorkManager runs the first period immediately, which
                // competes with the user's own first refresh and used to leave the UI spinning.
                .setInitialDelay(1, TimeUnit.HOURS)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.MINUTES
                )
                .setInputData(workDataOf(KEY_SYSTEM to SchoolSystem.SIS.key))
                .addTag(PERIODIC_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** One-off sync, used right after a successful login. */
        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScheduleSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(workDataOf(KEY_SYSTEM to SchoolSystem.SIS.key))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "schedule_sync_once",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
            WorkManager.getInstance(context).cancelUniqueWork("schedule_sync_once")
        }
    }
}

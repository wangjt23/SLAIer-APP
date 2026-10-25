package com.slai.campus.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.session.SessionManager
import com.slai.campus.domain.schedule.RefreshReason
import com.slai.campus.domain.schedule.ScheduleRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Kept for work requests persisted by older app versions.
 * New versions cancel those requests during startup and refresh the timetable on user request.
 */
@HiltWorker
class ScheduleSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scheduleRepository: ScheduleRepository,
    private val sessionManager: SessionManager
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

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
            WorkManager.getInstance(context).cancelUniqueWork("schedule_sync_once")
        }
    }
}

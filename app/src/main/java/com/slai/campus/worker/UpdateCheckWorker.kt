package com.slai.campus.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.slai.campus.core.common.AppLog
import com.slai.campus.domain.update.UpdateRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * 每天静默检查一次新版本。
 *
 * 结果是**只写状态、不弹通知**的：设置页「关于」会显示"有新版本"，用户看得到就够了。
 * 检查失败一律 `Result.success()` —— 更新检查失败没有重试的价值（限流要等一小时、
 * 断网要等用户回到有网环境），让 WorkManager 退避重试只会白耗电。
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val updateRepository: UpdateRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        updateRepository.check(manual = false)
        Result.success()
    }.getOrElse { error ->
        AppLog.w("update check worker failed: ${error.javaClass.simpleName}")
        Result.success()
    }

    companion object {
        private const val WORK_NAME = "update-check"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }.onFailure { AppLog.w("enqueue update check failed: ${it.javaClass.simpleName}") }
        }
    }
}

package com.slai.campus

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.slai.campus.core.common.AppLog
import com.slai.campus.reminder.NotificationHelper
import com.slai.campus.worker.ScheduleSyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Does three things and nothing else: initialises Hilt, wires WorkManager through Hilt (so workers
 * can inject repositories), and cancels legacy periodic timetable sync.
 */
@HiltAndroidApp
class CampusApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.VERBOSE_LOGGING) android.util.Log.DEBUG else android.util.Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        // Upgrade migration: timetable data stays cached until the user requests a refresh.
        com.slai.campus.worker.ReminderRescheduleWorker.enqueuePeriodic(this)
        runCatching { ScheduleSyncWorker.cancelAll(this) }
            .onFailure { AppLog.w("legacy sync cancellation failed: ${it.javaClass.simpleName}") }
    }
}

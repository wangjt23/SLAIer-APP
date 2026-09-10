package com.slai.campus.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.slai.campus.core.common.AppLog
import com.slai.campus.worker.ReminderRescheduleWorker

/**
 * Re-arms reminders after a reboot or an app update.
 *
 * Alarms do not survive a restart, so the cached timetable is re-read and every future reminder is
 * scheduled again. Work is handed to WorkManager rather than done inline, because a boot broadcast
 * has a short execution window.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        AppLog.i("boot/update received: rescheduling reminders")
        ReminderRescheduleWorker.enqueue(context)
    }
}

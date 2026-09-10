package com.slai.campus.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.slai.campus.core.common.AppLog

/**
 * Receives the class alarm and posts the notification.
 *
 * Deliberately does no network work: by the time an alarm fires the timetable is already cached, and
 * a receiver that blocks on IO would risk being killed.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CLASS_REMINDER) return

        val occurrenceId = intent.getStringExtra(EXTRA_OCCURRENCE_ID) ?: return
        val courseName = intent.getStringExtra(EXTRA_COURSE) ?: return
        val location = intent.getStringExtra(EXTRA_LOCATION)
        val startTime = intent.getStringExtra(EXTRA_START)

        AppLog.i("alarm fired for occurrence")
        NotificationHelper.showClassReminder(context, occurrenceId, courseName, location, startTime)
    }

    companion object {
        const val ACTION_CLASS_REMINDER = "com.slai.campus.action.CLASS_REMINDER"
        const val EXTRA_OCCURRENCE_ID = "occurrence_id"
        const val EXTRA_COURSE = "course"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_START = "start"
    }
}

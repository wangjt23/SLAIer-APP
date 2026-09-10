package com.slai.campus.core.common

import com.slai.campus.BuildConfig
import android.util.Log

/**
 * Logging facade with a hard rule from the design plan: release builds never log cookies, tokens,
 * credentials, full response bodies or anything that can be replayed against the school systems.
 *
 * Debug builds log freely because that is where the WebView / OkHttp debugging happens, but every
 * message still goes through [Redactor] so an accidental `Log.d` of a header dump cannot leak a
 * session into a bug report.
 */
object AppLog {

    private const val TAG = "SlaiCampus"

    fun d(message: String, throwable: Throwable? = null) {
        if (BuildConfig.VERBOSE_LOGGING) {
            Log.d(TAG, Redactor.redact(message), throwable)
        }
    }

    fun i(message: String) {
        Log.i(TAG, Redactor.redact(message))
    }

    fun w(message: String, throwable: Throwable? = null) {
        Log.w(TAG, Redactor.redact(message), throwable)
    }

    /**
     * Always logged, but always redacted. Used for state-machine transitions and failure
     * classification, which are the two things worth seeing in a release logcat.
     */
    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, Redactor.redact(message), throwable)
    }
}

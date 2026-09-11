package com.slai.campus.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.slai.campus.core.common.AppLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 系统安装器回来的结果。 */
sealed interface InstallEvent {
    data object Success : InstallEvent
    data object Cancelled : InstallEvent
    data class Failed(val message: String?) : InstallEvent
}

/**
 * 安装结果从 [UpdateInstallReceiver]（系统实例化）传给仓库。
 *
 * 用一个进程内的 object 而不是 Hilt：BroadcastReceiver 由系统创建，拿依赖图要么走
 * EntryPointAccessors 要么引入额外样板，而这里需要的只是一个进程内的事件口。
 */
object UpdateEvents {
    private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<InstallEvent> = _events.asSharedFlow()

    fun publish(event: InstallEvent) {
        _events.tryEmit(event)
    }
}

/**
 * 接收 `PackageInstaller` 的状态回调。
 *
 * `STATUS_PENDING_USER_ACTION` 必须处理：部分 ROM 不会自己弹确认框，而是把"要展示的 Intent"
 * 交给 App，由 App 拉起来。不处理的话表现就是"点了安装什么也没发生"。
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        AppLog.i("install status=$status")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent)
                if (confirm == null) {
                    UpdateEvents.publish(InstallEvent.Failed("系统没有给出安装确认界面"))
                } else {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { UpdateEvents.publish(InstallEvent.Failed(it.javaClass.simpleName)) }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> UpdateEvents.publish(InstallEvent.Success)

            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateEvents.publish(InstallEvent.Cancelled)

            else -> UpdateEvents.publish(InstallEvent.Failed(message ?: "status=$status"))
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.slai.campus.action.INSTALL_STATUS"
        private const val REQUEST_CODE = 4711

        /**
         * `PackageInstaller.commit()` 要的 IntentSender。
         *
         * `FLAG_MUTABLE` 是必须的（API 31+）：系统要往这个 Intent 里回填 status extras，
         * 不可变的 PendingIntent 会直接抛异常。该常量在旧系统上被忽略，所以 minSdk 26 也安全。
         */
        fun statusIntent(context: Context) = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        ).intentSender
    }
}

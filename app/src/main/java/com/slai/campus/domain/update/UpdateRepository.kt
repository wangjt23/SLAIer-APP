package com.slai.campus.domain.update

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 更新流程的状态机。UI 只按它渲染，不自己拼状态。 */
sealed interface UpdateState {

    /** 还没查过（或用户刚关掉了检查）。 */
    data object Idle : UpdateState

    data object Checking : UpdateState

    data class UpToDate(val checkedAt: Long) : UpdateState

    /** 有可用新版本，等用户决定。 */
    data class Available(val release: AppRelease) : UpdateState

    data class Downloading(val received: Long, val total: Long) : UpdateState {
        /** 0–100；服务端没给 Content-Length 时为 0。 */
        val percent: Int
            get() = if (total <= 0L) 0 else ((received * 100) / total).toInt().coerceIn(0, 100)
    }

    data object Verifying : UpdateState

    /** 包已下载并通过校验，可以直接调起安装。 */
    data class ReadyToInstall(val release: AppRelease) : UpdateState

    data object Installing : UpdateState

    data class Installed(val versionName: String) : UpdateState

    data class Failed(val reason: UpdateFailure, val detail: String? = null) : UpdateState
}

/**
 * 失败分类。
 *
 * 每一条都要能翻译成一句用户能照做的话 —— "更新失败"三个字对用户毫无用处
 * （限流要等一会儿、校外要换网络、签名不符说明这个包不是官方的，处理方式完全不同）。
 */
enum class UpdateFailure {
    /** GitHub 未认证 API 每小时 60 次/IP，校园网出口 NAT 下很容易撞上。 */
    RATE_LIMITED,
    UNREACHABLE,
    /** 发布里没有 APK 资产。 */
    NO_APK,
    /** 下载回来的包与 `SHA256SUMS.txt` 不符。 */
    CHECKSUM_MISMATCH,
    /** 包签名与本机已安装版本不一致 —— 不是官方包，或者装的是别的渠道的包。 */
    SIGNATURE_MISMATCH,
    /** 包里的 versionCode 不比当前大（校验和/标签对不上）。 */
    NOT_NEWER,
    DOWNLOAD_FAILED,
    /** 系统还没允许本应用"安装未知应用"。 */
    NEEDS_PERMISSION,
    INSTALL_FAILED,
    /** 用户取消了安装。 */
    CANCELLED,
    UNKNOWN
}

interface UpdateRepository {

    val state: StateFlow<UpdateState>

    /** 自动检查开关（默认开）；关掉后只有手动点「检查更新」才会请求。 */
    val autoCheckEnabled: Flow<Boolean>

    suspend fun setAutoCheckEnabled(enabled: Boolean)

    /** 有新版本时的版本号，供设置页/其它界面显示角标。 */
    val availableVersion: StateFlow<String?>

    /** 上次成功检查的时间（毫秒），没有则 null。 */
    val lastCheckedAt: StateFlow<Long?>

    /**
     * 检查更新。
     *
     * @param manual 用户主动点的：忽略开关与"最近查过"的限制，并且失败要有可见反馈。
     */
    suspend fun check(manual: Boolean)

    /** 启动时的静默检查：距上次超过 [STALE_AFTER_MILLIS] 才真的发请求。 */
    suspend fun checkIfStale()

    /** 下载 → 校验 → 调起安装。 */
    suspend fun downloadAndInstall()

    /** 「忽略此版本」：当前这个版本不再提示，直到出现更高的版本。 */
    suspend fun ignoreCurrentVersion()

    /** 打开 GitHub Release 页面（下载失败时的兜底）。 */
    fun releasePageUrl(): String

    /** 把状态退回「已是最新」之类的收尾（例如用户从安装界面返回）。 */
    fun acknowledge()

    companion object {
        /** 静默检查的最小间隔：一天。 */
        const val STALE_AFTER_MILLIS: Long = 24L * 60 * 60 * 1000

        const val RELEASE_PAGE_URL = "https://github.com/wangjt23/SLAIer-APP/releases/latest"
    }
}

package com.slai.campus.data.update

import com.slai.campus.BuildConfig
import com.slai.campus.core.common.AppDispatchers
import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.ApplicationScope
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.common.describe
import com.slai.campus.core.session.SessionStore
import com.slai.campus.domain.update.AppRelease
import com.slai.campus.domain.update.ChecksumFile
import com.slai.campus.domain.update.ReleaseDecision
import com.slai.campus.domain.update.UpdateDecision
import com.slai.campus.domain.update.UpdateFailure
import com.slai.campus.domain.update.UpdateRepository
import com.slai.campus.domain.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用内更新的编排：检查 → 下载 → 校验 → 安装。
 *
 * 分工是刻意的 —— 这个类**只编排**，不做版本比较（[ReleaseDecision]）、不解析 payload
 * （[ReleaseParser]）、不算哈希（[ApkDownloader]/`ChecksumFile`）、不碰安装细节（[ApkInstaller]）。
 * 那些都是纯逻辑或系统交互，各自能单测或单独替换。
 */
@Singleton
class UpdateRepositoryImpl @Inject constructor(
    private val dataSource: GitHubReleaseDataSource,
    private val downloader: ApkDownloader,
    private val verifier: ApkVerifier,
    private val installer: ApkInstaller,
    private val sessionStore: SessionStore,
    private val dispatchers: AppDispatchers,
    @ApplicationScope private val scope: CoroutineScope
) : UpdateRepository {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _availableVersion = MutableStateFlow<String?>(null)
    override val availableVersion: StateFlow<String?> = _availableVersion.asStateFlow()

    private val _lastCheckedAt = MutableStateFlow<Long?>(null)
    override val lastCheckedAt: StateFlow<Long?> = _lastCheckedAt.asStateFlow()

    override val autoCheckEnabled: Flow<Boolean> = sessionStore.updateCheckEnabled

    private val operationMutex = Mutex()
    private var pendingInstallId: Int? = null
    private var apkVerified = false

    init {
        // 安装结果从系统安装器回来，落到状态机上。
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            UpdateEvents.events.collect { result ->
                operationMutex.withLock {
                    // A cancelled/failed older installer must not overwrite a later check or install.
                    if (result.sessionId != pendingInstallId || _state.value !is UpdateState.Installing) return@withLock
                    pendingInstallId = null
                    when (val event = result.event) {
                        InstallEvent.Success -> {
                            downloader.clean()
                            _state.value = UpdateState.Installed(pendingRelease?.versionName.orEmpty())
                            pendingRelease = null
                            apkVerified = false
                        }

                        InstallEvent.Cancelled -> {
                            // 用户点了取消：把包留着，让他随时能再点一次安装。
                            pendingRelease?.let { _state.value = UpdateState.ReadyToInstall(it) }
                        }

                        is InstallEvent.Failed -> _state.value = UpdateState.Failed(UpdateFailure.INSTALL_FAILED, event.message)
                    }
                }
            }
        }
        scope.launch {
            val stored = sessionStore.updateLastCheckAt()
            _lastCheckedAt.update { current -> listOfNotNull(current, stored).maxOrNull() }
        }
    }

    /** 当前下载的版本；[apkVerified] 为 true 时可直接重试安装。 */
    private var pendingRelease: AppRelease? = null

    override suspend fun setAutoCheckEnabled(enabled: Boolean) {
        sessionStore.setUpdateCheckEnabled(enabled)
    }

    override suspend fun checkIfStale() = withOperation {
        if (!sessionStore.updateCheckEnabledNow()) return@withOperation
        val last = sessionStore.updateLastAttemptAt() ?: sessionStore.updateLastCheckAt()
        val stale = last == null || System.currentTimeMillis() - last >= UpdateRepository.STALE_AFTER_MILLIS
        if (stale) checkLocked(manual = false)
    }

    override suspend fun check(manual: Boolean) = withOperation { checkLocked(manual) }

    private suspend fun checkLocked(manual: Boolean) {
        if (!manual && !sessionStore.updateCheckEnabledNow()) return

        // 静默检查失败时要能退回原来的状态：否则界面会永远停在"正在检查"，连手动检查的按钮
        // 都被自己禁用掉（第一次实测就踩到了）。
        val previous = _state.value
        _state.value = UpdateState.Checking
        try {
            sessionStore.setUpdateLastAttemptAt(System.currentTimeMillis())
            val fetch = dataSource.fetchLatest()

            when (val result = fetch.result) {
                is RemoteResult.Success -> {
                    val checkedAt = System.currentTimeMillis()
                    sessionStore.setUpdateLastCheckAt(checkedAt)
                    _lastCheckedAt.value = checkedAt

                    val release = result.data
                    when (val decision = ReleaseDecision.decide(
                        release = release,
                        currentVersionName = BuildConfig.VERSION_NAME,
                        ignoredVersion = sessionStore.updateIgnoredVersion()
                    )) {
                        is UpdateDecision.Available -> {
                            if (pendingRelease != release) {
                                pendingRelease = null
                                apkVerified = false
                            }
                            _availableVersion.value = release.versionName
                            _state.value = UpdateState.Available(release)
                        }

                        is UpdateDecision.Ignored -> {
                            pendingRelease = null
                            apkVerified = false
                            _availableVersion.value = null
                            _state.value = UpdateState.UpToDate(checkedAt)
                        }

                        UpdateDecision.UpToDate -> {
                            pendingRelease = null
                            apkVerified = false
                            _availableVersion.value = null
                            _state.value = UpdateState.UpToDate(checkedAt)
                        }
                    }
                }

                is RemoteResult.ServerError -> {
                    // 403/429 = 限流：校园网出口 NAT 下全班共用一个 IP，撞上很正常，安静处理。
                    val rateLimited = result.code == 403 || result.code == 429
                    AppLog.w("update check failed: HTTP ${result.code}${if (rateLimited) " (rate limited)" else ""}")
                    _state.value = if (manual) {
                        UpdateState.Failed(
                            if (rateLimited) UpdateFailure.RATE_LIMITED else UpdateFailure.UNREACHABLE,
                            "HTTP ${result.code}"
                        )
                    } else {
                        previous
                    }
                }

                is RemoteResult.NetworkUnavailable -> {
                    AppLog.w("update check offline: ${result.reason}")
                    _state.value = if (manual) {
                        UpdateState.Failed(UpdateFailure.UNREACHABLE, result.reason)
                    } else {
                        previous
                    }
                }

                else -> {
                    _state.value = if (manual) {
                        UpdateState.Failed(UpdateFailure.UNKNOWN, result.describe())
                    } else {
                        previous
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.value = if (manual) UpdateState.Failed(UpdateFailure.UNKNOWN, error.javaClass.simpleName) else previous
        } finally {
            if (_state.value is UpdateState.Checking) _state.value = previous
        }
    }

    override suspend fun downloadAndInstall() = withOperation {
        val release = (state.value as? UpdateState.Available)?.release
            ?: pendingRelease
            ?: return@withOperation
        if (apkVerified && pendingRelease == release) {
            install()
            return@withOperation
        }
        val url = release.apkUrl
        val fileName = release.apkName ?: "slaier-${release.versionName}.apk"
        if (!release.hasApk || url == null) {
            _state.value = UpdateState.Failed(UpdateFailure.NO_APK)
            return@withOperation
        }

        pendingRelease = release
        apkVerified = false
        _state.value = UpdateState.Downloading(0, release.apkSize ?: -1L)
        val progressLock = Any()
        var acceptProgress = true
        try {
            val outcome = downloader.download(url, fileName) { received, total ->
                synchronized(progressLock) {
                    if (acceptProgress) _state.value = UpdateState.Downloading(received, total)
                }
            }

            if (outcome !is DownloadOutcome.Ok) {
                val reason = (outcome as? DownloadOutcome.Failed)?.reason
                _state.value = UpdateState.Failed(UpdateFailure.DOWNLOAD_FAILED, reason)
                return@withOperation
            }

            _state.value = UpdateState.Verifying

            // 校验和文件是可选的：拿不到时退化为"只验版本号与签名"，但会在日志里留下痕迹。
            val expected = release.sha256 ?: withContext(dispatchers.io) {
                release.checksumsUrl
                    ?.let { dataSource.fetchText(it) }
                    ?.let { ChecksumFile.sha256For(fileName, it) }
            }
            if (expected == null) AppLog.w("update: checksums unavailable, skipping integrity check")

            val currentVersionCode = BuildConfig.VERSION_CODE.toLong()

            when (val verdict = verifier.verify(outcome.apk.file, expected, outcome.apk.sha256, currentVersionCode)) {
                VerifyOutcome.Ok -> Unit
                is VerifyOutcome.Rejected -> {
                    AppLog.w("update rejected: ${verdict.failure} ${verdict.detail.orEmpty()}")
                    downloader.clean()
                    pendingRelease = null
                    _state.value = UpdateState.Failed(verdict.failure, verdict.detail)
                    return@withOperation
                }
            }

            apkVerified = true
            _state.value = UpdateState.ReadyToInstall(release)
            install()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.value = UpdateState.Failed(UpdateFailure.UNKNOWN, error.javaClass.simpleName)
        } finally {
            synchronized(progressLock) {
                acceptProgress = false
                when (_state.value) {
                    is UpdateState.Downloading, UpdateState.Verifying -> _state.value = UpdateState.Available(release)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun install() {
        val release = pendingRelease ?: return
        val fileName = release.apkName ?: "slaier-${release.versionName}.apk"
        _state.value = UpdateState.Installing
        try {
            when (val start = installer.install(downloader.targetFile(fileName)) { pendingInstallId = it }) {
                is InstallStart.Started -> Unit
                InstallStart.NeedsPermission -> _state.value = UpdateState.Failed(UpdateFailure.NEEDS_PERMISSION)
                is InstallStart.Failed -> _state.value = UpdateState.Failed(UpdateFailure.INSTALL_FAILED, start.reason)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.value = UpdateState.Failed(UpdateFailure.INSTALL_FAILED, error.javaClass.simpleName)
        } finally {
            if (_state.value is UpdateState.Installing && pendingInstallId == null) {
                _state.value = UpdateState.ReadyToInstall(release)
            }
        }
    }

    override suspend fun ignoreCurrentVersion() = withOperation {
        val release = (state.value as? UpdateState.Available)?.release ?: pendingRelease ?: return@withOperation
        sessionStore.setUpdateIgnoredVersion(release.versionName)
        AppLog.i("update ignored: ${release.versionName}")
        _availableVersion.value = null
        pendingRelease = null
        apkVerified = false
        downloader.clean()
        _state.value = UpdateState.Idle
    }

    override fun releasePageUrl(): String = UpdateRepository.RELEASE_PAGE_URL

    override fun acknowledge() {
        if (!operationMutex.tryLock()) return
        try {
            if (_state.value.inProgress) return
            if (_state.value is UpdateState.Installed) return
            if (_state.value is UpdateState.ReadyToInstall) return
            if (_state.value is UpdateState.Available) return
            _state.value = UpdateState.Idle
        } finally { operationMutex.unlock() }
    }

    /** Ignore overlapping UI/background operations; do not queue stale checks behind a download. */
    private suspend inline fun withOperation(block: () -> Unit) {
        if (!operationMutex.tryLock()) return
        try {
            if (!_state.value.inProgress) block()
        } finally { operationMutex.unlock() }
    }

    private val UpdateState.inProgress: Boolean
        get() = this is UpdateState.Checking || this is UpdateState.Downloading ||
            this is UpdateState.Verifying || this is UpdateState.Installing
}

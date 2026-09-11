package com.slai.campus.data.update

import android.content.Context
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    @ApplicationScope private val scope: CoroutineScope,
    @ApplicationContext private val context: Context
) : UpdateRepository {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _availableVersion = MutableStateFlow<String?>(null)
    override val availableVersion: StateFlow<String?> = _availableVersion.asStateFlow()

    private val _lastCheckedAt = MutableStateFlow<Long?>(null)
    override val lastCheckedAt: StateFlow<Long?> = _lastCheckedAt.asStateFlow()

    override val autoCheckEnabled: Flow<Boolean> = sessionStore.updateCheckEnabled

    init {
        // 安装结果从系统安装器回来，落到状态机上。
        scope.launch {
            UpdateEvents.events.collect { event ->
                when (event) {
                    InstallEvent.Success -> {
                        downloader.clean()
                        _state.value = _state.value.let { current ->
                            val release = (current as? UpdateState.Installing)?.let { pendingRelease }
                            UpdateState.Installed(release?.versionName ?: pendingRelease?.versionName.orEmpty())
                        }
                        pendingRelease = null
                    }

                    InstallEvent.Cancelled -> {
                        // 用户点了取消：把包留着，让他随时能再点一次安装。
                        pendingRelease?.let { _state.value = UpdateState.ReadyToInstall(it) }
                    }

                    is InstallEvent.Failed -> _state.value = UpdateState.Failed(UpdateFailure.INSTALL_FAILED, event.message)
                }
            }
        }
        scope.launch {
            _lastCheckedAt.value = sessionStore.updateLastCheckAt()
        }
    }

    /** 已下载并校验通过、正在等待安装的那个版本。 */
    private var pendingRelease: AppRelease? = null

    override suspend fun setAutoCheckEnabled(enabled: Boolean) {
        sessionStore.setUpdateCheckEnabled(enabled)
    }

    override suspend fun checkIfStale() {
        if (!sessionStore.updateCheckEnabledNow()) return
        val last = sessionStore.updateLastCheckAt()
        val stale = last == null || System.currentTimeMillis() - last >= UpdateRepository.STALE_AFTER_MILLIS
        if (stale) check(manual = false)
    }

    override suspend fun check(manual: Boolean) {
        if (!manual && !sessionStore.updateCheckEnabledNow()) return
        when (_state.value) {
            is UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Installing -> return
            else -> Unit
        }

        // 静默检查失败时要能退回原来的状态：否则界面会永远停在"正在检查"，连手动检查的按钮
        // 都被自己禁用掉（第一次实测就踩到了）。
        val previous = _state.value
        _state.value = UpdateState.Checking
        val etag = sessionStore.updateEtag()
        val fetch = dataSource.fetchLatest(etag)

        when (val result = fetch.result) {
            is RemoteResult.Success -> {
                fetch.etag?.let { sessionStore.setUpdateEtag(it) }
                val checkedAt = System.currentTimeMillis()
                sessionStore.setUpdateLastCheckAt(checkedAt)
                _lastCheckedAt.value = checkedAt

                val release = result.data
                if (release == null) {
                    // 304：这份 ETag 对应的发布没有变，沿用上一次的结论。
                    AppLog.i("update check: not modified")
                    _state.value = UpdateState.UpToDate(checkedAt)
                    return
                }

                when (val decision = ReleaseDecision.decide(
                    release = release,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    ignoredVersion = sessionStore.updateIgnoredVersion()
                )) {
                    is UpdateDecision.Available -> {
                        _availableVersion.value = release.versionName
                        _state.value = UpdateState.Available(release)
                    }

                    is UpdateDecision.Ignored -> {
                        _availableVersion.value = null
                        _state.value = UpdateState.UpToDate(checkedAt)
                    }

                    UpdateDecision.UpToDate -> {
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
    }

    override suspend fun downloadAndInstall() {
        val release = (state.value as? UpdateState.Available)?.release
            ?: pendingRelease
            ?: return
        val url = release.apkUrl
        val fileName = release.apkName ?: "slaier-${release.versionName}.apk"
        if (!release.hasApk || url == null) {
            _state.value = UpdateState.Failed(UpdateFailure.NO_APK)
            return
        }

        pendingRelease = release
        _state.value = UpdateState.Downloading(0, release.apkSize ?: -1L)

        val outcome = downloader.download(url, fileName) { received, total ->
            _state.value = UpdateState.Downloading(received, total)
        }

        if (outcome !is DownloadOutcome.Ok) {
            val reason = (outcome as? DownloadOutcome.Failed)?.reason
            _state.value = UpdateState.Failed(UpdateFailure.DOWNLOAD_FAILED, reason)
            return
        }

        _state.value = UpdateState.Verifying

        // 校验和文件是可选的：拿不到时退化为"只验版本号与签名"，但会在日志里留下痕迹。
        val expected = withContext(dispatchers.io) {
            release.checksumsUrl
                ?.let { dataSource.fetchText(it) }
                ?.let { ChecksumFile.sha256For(fileName, it) }
        }
        if (expected == null) AppLog.w("update: checksums unavailable, skipping integrity check")

        val currentVersionCode = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(BuildConfig.VERSION_CODE.toLong())

        when (val verdict = verifier.verify(outcome.apk.file, expected, outcome.apk.sha256, currentVersionCode)) {
            VerifyOutcome.Ok -> Unit
            is VerifyOutcome.Rejected -> {
                AppLog.w("update rejected: ${verdict.failure} ${verdict.detail.orEmpty()}")
                downloader.clean()
                pendingRelease = null
                _state.value = UpdateState.Failed(verdict.failure, verdict.detail)
                return
            }
        }

        _state.value = UpdateState.ReadyToInstall(release)
        install()
    }

    private suspend fun install() {
        val release = pendingRelease ?: return
        val fileName = release.apkName ?: "slaier-${release.versionName}.apk"
        _state.value = UpdateState.Installing
        when (val start = installer.install(downloader.targetFile(fileName))) {
            InstallStart.Started -> Unit
            InstallStart.NeedsPermission -> _state.value = UpdateState.Failed(UpdateFailure.NEEDS_PERMISSION)
            is InstallStart.Failed -> _state.value = UpdateState.Failed(UpdateFailure.INSTALL_FAILED, start.reason)
        }
    }

    override suspend fun ignoreCurrentVersion() {
        val release = (state.value as? UpdateState.Available)?.release ?: pendingRelease ?: return
        sessionStore.setUpdateIgnoredVersion(release.versionName)
        AppLog.i("update ignored: ${release.versionName}")
        _availableVersion.value = null
        _state.value = UpdateState.Idle
    }

    override fun releasePageUrl(): String = UpdateRepository.RELEASE_PAGE_URL

    override fun acknowledge() {
        if (_state.value is UpdateState.Installed) return
        if (_state.value is UpdateState.ReadyToInstall) return
        if (_state.value is UpdateState.Available) return
        _state.value = UpdateState.Idle
    }
}

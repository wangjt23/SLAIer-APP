package com.slai.campus.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slai.campus.domain.update.UpdateRepository
import com.slai.campus.domain.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UpdateUiState(
    val state: UpdateState = UpdateState.Idle,
    val autoCheck: Boolean = true,
    val lastCheckedAt: Long? = null,
    /** 系统是否允许本应用"安装未知应用"；false 时"下载并安装"这一步需要先去授权。 */
    val canInstall: Boolean = true
)

/**
 * 设置页「关于」里的更新区块。
 *
 * 只做三件事：把仓库的状态摊给 UI、把用户动作转成仓库调用、以及在需要时把用户送去系统页面
 * （"安装未知应用"授权、GitHub 下载页兜底）。真正的检查/下载/校验/安装都在仓库里。
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repository: UpdateRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val installAllowed = MutableStateFlow(canInstallNow())

    val state: StateFlow<UpdateUiState> = combine(
        repository.state,
        repository.autoCheckEnabled,
        repository.lastCheckedAt,
        installAllowed
    ) { updateState, autoCheck, lastChecked, allowed ->
        UpdateUiState(
            state = updateState,
            autoCheck = autoCheck,
            lastCheckedAt = lastChecked,
            canInstall = allowed
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdateUiState())

    /** 从系统「安装未知应用」页面返回时调用：授权状态是系统持有的，只能重新问一次。 */
    fun refreshInstallPermission() {
        installAllowed.value = canInstallNow()
    }

    fun check() {
        viewModelScope.launch { repository.check(manual = true) }
    }

    fun setAutoCheck(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoCheckEnabled(enabled) }
    }

    /** 下载 + 校验 + 调起安装；缺"安装未知应用"权限时返回 false，由调用方引导去授权。 */
    fun downloadAndInstall() {
        viewModelScope.launch {
            if (!canInstallNow()) {
                openUnknownSourcesSettings()
                return@launch
            }
            repository.downloadAndInstall()
        }
    }

    fun ignoreVersion() {
        viewModelScope.launch { repository.ignoreCurrentVersion() }
    }

    fun acknowledge() = repository.acknowledge()

    /** 兜底：直接打开 GitHub 的 Release 页面（下载失败或用户偏好手动装时用）。 */
    fun openReleasePage() {
        openUrl(repository.releasePageUrl())
    }

    fun openUnknownSourcesSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
            .onFailure {
                runCatching {
                    appContext.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    }

    private fun openUrl(url: String) {
        runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun canInstallNow(): Boolean =
        appContext.packageManager.canRequestPackageInstalls()
}

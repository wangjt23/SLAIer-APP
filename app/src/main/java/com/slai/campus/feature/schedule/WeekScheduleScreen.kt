package com.slai.campus.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.CircularProgressIndicator
import com.slai.campus.domain.schedule.RefreshResult
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.slai.campus.R
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.web.AppUrlProvider
import com.slai.campus.core.web.AppUrls
import com.slai.campus.domain.schedule.ClassOccurrence
import com.slai.campus.navigation.AppNavigator
import com.slai.campus.navigation.Tab
import com.slai.campus.ui.currentLocale
import com.slai.campus.ui.shortDatePatternFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Week view.
 *
 * Every occurrence already carries a concrete date, so this screen does no week arithmetic at all —
 * it only groups by day and labels the week.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekScheduleScreen(
    navigator: AppNavigator,
    viewModel: ScheduleViewModel = hiltViewModel(),
    urlsViewModel: WeekUrlsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val urls by urlsViewModel.urls.collectAsStateWithLifecycle()

    // 日期格式跟随语言，而不是写死中文：英文下要显示 "Sep 7 Mon"。
    val dayFormatter = DateTimeFormatter.ofPattern(shortDatePatternFor(currentLocale()), currentLocale())
    val title = state.weekIndex?.let { stringResource(R.string.schedule_week_of, it) }
        ?: stringResource(R.string.schedule_title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(
                            text = "${state.monday.format(dayFormatter)} – " +
                                "${state.monday.plusDays(6).format(dayFormatter)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::previousWeek) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.cd_previous_week))
                    }
                    IconButton(onClick = viewModel::nextWeek) {
                        Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.cd_next_week))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ScheduleSyncRow(state, state.refreshing, viewModel::refresh)
                Text(
                    stringResource(R.string.schedule_cache_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.needsLogin || !state.hasCache) {
                    urls?.let { appUrls ->
                        TextButton(onClick = {
                            navigator.openWeb(appUrls.sisEntry, "教务系统登录", SchoolSystem.SIS, true)
                        }) { Text(stringResource(R.string.action_relogin)) }
                    }
                }
            }
            if (!state.anchored) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.schedule_no_anchor_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(R.string.schedule_no_anchor_body),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { navigator.openTab(Tab.SETTINGS) }) {
                                Text(stringResource(R.string.schedule_go_settings))
                            }
                        }
                    }
                }
            }

            if (!state.hasAnyData && !state.hasCache) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.schedule_empty),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        // The reason, not just the symptom. "暂无课表数据" alone is what made a dead
                        // session indistinguishable from an empty semester.
                        Text(
                            text = state.emptyReason.text(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        urls?.let { appUrls ->
                            TextButton(
                                onClick = {
                                    val page = appUrls.sisSchedulePage
                                    navigator.openWeb(page.url, page.label, SchoolSystem.SIS, false)
                                }
                            ) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                                Text(stringResource(R.string.action_open_web))
                            }
                        }
                    }
                }
            }

            items(state.days, key = { it.date.toEpochDay() }) { day ->
                DayCard(day)
            }
        }
    }
}

@Composable
private fun DayCard(day: DaySection) {
    // 日期格式跟随语言：这里原本写死 "M月d日 EEE" + Locale.CHINA，英文界面下日期仍是中文。
    val formatter = DateTimeFormatter.ofPattern(shortDatePatternFor(currentLocale()), currentLocale())
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (day.isToday) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = day.date.format(formatter),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal
                )
                if (day.isToday) {
                    Text(
                        text = "今天",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (day.classes.isEmpty()) {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                day.classes.forEach { occurrence ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        /*
                         * 上课 + 下课时间。
                         *
                         * `endTime` 一直都有（由节次和学校下发的节次时刻算出，例如第 1-3 节
                         * 09:30–12:15），只是这一栏之前只画了 `startTime`，看起来就像"没有下课时间"。
                         * 改成和首页 `ClassCard` 一样的两行时间块，两个页面读起来一致。
                         */
                        Column {
                            Text(
                                text = occurrence.startTime.format(ClassOccurrence.HH_MM),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = occurrence.endTime.format(ClassOccurrence.HH_MM),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column {
                            Text(occurrence.courseName, style = MaterialTheme.typography.bodyLarge)
                            val details = listOfNotNull(
                                occurrence.location,
                                occurrence.teacher
                            ).filter { it.isNotBlank() }.joinToString(" · ")
                            if (details.isNotBlank()) {
                                Text(
                                    text = details,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Supplies the configured URLs to the week screen without blocking it. */
@HiltViewModel
class WeekUrlsViewModel @Inject constructor(
    urlProvider: AppUrlProvider
) : ViewModel() {
    val urls: StateFlow<AppUrls?> = urlProvider.urls
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * 把 [EmptyReason] 解析成当前语言的文案。
 *
 * 放在 UI 层而不是 ViewModel：ViewModel 没有 Context，拿不到 `stringResource`，
 * 之前正是把中文句子写死在 ViewModel 里，才让这个 App 变成单语言的。
 */
@Composable
private fun EmptyReason.text(): String = when (this) {
    EmptyReason.SessionExpired -> stringResource(R.string.reason_session_expired)
    EmptyReason.NeverSignedIn -> stringResource(R.string.reason_never_signed_in)
    EmptyReason.OfflineNoCache -> stringResource(R.string.reason_offline_no_cache)
    is EmptyReason.ServerError -> stringResource(R.string.reason_server_error, code)
    is EmptyReason.SchemaChanged -> stringResource(R.string.reason_schema_changed, detail)
    is EmptyReason.Failed -> stringResource(R.string.reason_failed, detail)
    EmptyReason.NeverSynced -> stringResource(R.string.reason_never_synced)
}

@Composable
private fun ScheduleSyncRow(
    state: WeekUiState,
    refreshing: Boolean,
    onRefresh: () -> Unit
) {
    val lastSync = state.syncState?.lastSuccessAt
    val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (state.lastRefresh) {
                    is RefreshResult.Offline -> Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    is RefreshResult.SchemaChanged -> Icon(
                        Icons.Default.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    else -> Unit
                }
                Text(
                    text = when {
                        refreshing && state.phase != com.slai.campus.domain.schedule.RefreshPhase.IDLE ->
                            state.phase.label
                        else -> statusText(state, refreshing)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            /*
             * 刷新是个"偶尔才用"的动作（课表一天变不了几次），所以这里只留一个 20dp 的小图标，
             * 不再用占满半行的实心按钮 —— 状态文字才是这一行真正要传达的东西。
             */
            IconButton(
                onClick = onRefresh,
                enabled = !refreshing,
                modifier = Modifier.size(36.dp)
            ) {
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.action_refresh),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        if (lastSync != null) {
            Text(
                text = stringResource(
                    R.string.home_last_sync,
                    lastSync.atZone(java.time.ZoneId.systemDefault()).format(formatter)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun statusText(state: WeekUiState, refreshing: Boolean): String = when {
    refreshing -> stringResource(R.string.state_syncing)
    state.needsLogin -> stringResource(R.string.schedule_login_to_refresh)
    state.lastRefresh is RefreshResult.Success -> stringResource(R.string.state_ok)
    state.lastRefresh is RefreshResult.Offline -> stringResource(R.string.state_offline)
    state.lastRefresh is RefreshResult.SchemaChanged -> stringResource(R.string.state_schema_changed)
    state.lastRefresh is RefreshResult.ServerError -> stringResource(R.string.state_server_error)
    state.lastRefresh is RefreshResult.SessionExpired -> stringResource(R.string.state_needs_login)
    state.lastRefresh is RefreshResult.Failed -> stringResource(R.string.state_unknown_error)
    state.hasCache -> stringResource(R.string.state_ok)
    else -> stringResource(R.string.state_never)
}

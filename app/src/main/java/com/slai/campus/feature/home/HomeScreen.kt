package com.slai.campus.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slai.campus.R
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.session.SessionSnapshot
import com.slai.campus.core.session.SessionState
import com.slai.campus.domain.attendance.AttendanceRecord
import com.slai.campus.domain.schedule.ClassOccurrence
import com.slai.campus.ui.components.CampusHero
import com.slai.campus.ui.currentLocale
import com.slai.campus.ui.datePatternFor
import com.slai.campus.domain.schedule.RefreshResult
import com.slai.campus.domain.schedule.ScheduleSource
import com.slai.campus.navigation.AppNavigator
import com.slai.campus.navigation.Tab
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home screen.
 *
 * Reads Room first and always: the screen renders the cached timetable before any network call is
 * even attempted, which is what makes the app usable offline and on a cold start.
 *
 * The status row distinguishes the five failure classes, because the plan requires the user to be
 * able to tell "no classes today" from "we could not ask the server".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navigator: AppNavigator,
    session: SessionSnapshot,
    webCompleting: Boolean,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val manualRefreshing by viewModel.manualRefreshing.collectAsStateWithLifecycle()
    // The button tracks the user's own request. A background sync must never disable it or make it
    // spin — that is exactly what made the first version look frozen.
    val refreshing = manualRefreshing
    val backgroundSyncing = state.isRefreshing && !manualRefreshing

    val today = java.time.LocalDate.now()
    // 日期格式与语言一致：中文 "9月10日 星期四"，英文 "Thursday, Sep 10"。
    val dateFormatter = DateTimeFormatter.ofPattern(datePatternFor(currentLocale()), currentLocale())
    // 点击回调不是 @Composable，字符串要在外面取好。
    val signInLabel = stringResource(R.string.schedule_sign_in)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "SLAIer",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = today.format(dateFormatter),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CampusHero(subtitle = stringResource(R.string.hero_subtitle))
            }

            if (state.needsLogin) {
                item { NeedsLoginCard(navigator, state) }
            }

            when {
                refreshing && state.today.isEmpty() -> item { LoadingCard(state.phase) }

                state.today.isEmpty() -> item {
                    EmptyTodayCard(
                        isFirstRun = state.isFirstRun,
                        onLogin = {
                            val entry = state.urls?.sisEntry
                            if (entry != null) {
                                navigator.openWeb(entry, signInLabel, SchoolSystem.SIS, true)
                            } else {
                                navigator.openTab(Tab.SETTINGS)
                            }
                        },
                        onOpenWeb = {
                            state.urls?.sisSchedulePage?.let {
                                navigator.openWeb(it.url, it.label, SchoolSystem.SIS, false)
                            }
                        }
                    )
                }

                else -> items(state.today, key = { it.id }) { occurrence ->
                    ClassCard(occurrence)
                }
            }

            item {
                SyncRow(
                    state = state,
                    refreshing = refreshing,
                    backgroundSyncing = backgroundSyncing,
                    onRefresh = viewModel::refresh
                )
            }

            item { HorizontalDivider() }

            item { SectionHeader(stringResource(R.string.home_attendance_title)) }

            item {
                AttendanceCard(
                    record = state.attendance,
                    minutes = state.attendanceMinutes,
                    inside = state.attendanceInside,
                    goalMinutes = state.attendanceGoalMinutes,
                    onRefresh = viewModel::refreshAttendance,
                    onOpenWeb = {
                        state.urls?.stuCheckInPage?.let {
                            navigator.openWeb(it.url, it.label, SchoolSystem.STU, false)
                        }
                    },
                    onOpenTab = { navigator.openTab(Tab.ATTENDANCE) }
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { navigator.openTab(Tab.SCHEDULE) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.action_week_schedule)) }

                    OutlinedButton(
                        onClick = {
                            state.urls?.sisHomePage?.let {
                                navigator.openWeb(it.url, it.label, SchoolSystem.SIS, false)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.action_open_sis)) }
                }
            }

            if (webCompleting) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.home_login_syncing),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun ClassCard(occurrence: ClassOccurrence) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(0.32f)) {
                Text(
                    text = occurrence.startTime.format(ClassOccurrence.HH_MM),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = occurrence.endTime.format(ClassOccurrence.HH_MM),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(modifier = Modifier.weight(0.68f)) {
                Text(text = occurrence.courseName, style = MaterialTheme.typography.titleMedium)
                val details = listOfNotNull(
                    occurrence.location?.takeIf { it.isNotBlank() },
                    occurrence.teacher?.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (details.isNotBlank()) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (occurrence.source != ScheduleSource.SIS_NATIVE) {
                    Text(
                        text = sourceLabel(occurrence.source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun sourceLabel(source: ScheduleSource): String = when (source) {
    ScheduleSource.SIS_NATIVE -> ""
    ScheduleSource.SIS_PROVIDER -> stringResource(R.string.source_provider)
    ScheduleSource.SIS_WEBVIEW_XHR -> stringResource(R.string.source_webview)
    ScheduleSource.SIS_WEBVIEW_DOM -> stringResource(R.string.source_dom)
    ScheduleSource.MANUAL -> stringResource(R.string.source_manual)
}

@Composable
private fun LoadingCard(phase: com.slai.campus.domain.schedule.RefreshPhase) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(phase.label.ifBlank { stringResource(R.string.state_syncing) })
        }
    }
}

@Composable
private fun EmptyTodayCard(isFirstRun: Boolean, onLogin: () -> Unit, onOpenWeb: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(if (isFirstRun) R.string.home_no_data else R.string.home_no_class),
                style = MaterialTheme.typography.titleMedium
            )
            if (isFirstRun) {
                Text(
                    text = stringResource(R.string.home_no_data_hint),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onLogin) { Text(stringResource(R.string.action_login)) }
            }
            TextButton(onClick = onOpenWeb) { Text(stringResource(R.string.action_open_web)) }
        }
    }
}

@Composable
private fun NeedsLoginCard(navigator: AppNavigator, state: HomeUiState) {
    val lastSync = state.syncState?.lastSuccessAt
    Card(
        modifier = Modifier.fillMaxWidth(),
        /*
         * 用品牌容器色而不是 errorContainer：这是一条"需要你操作"的提示，不是报错。
         * 深色模式下 errorContainer 是 #93000A，占掉三分之一屏幕会像崩了一样；
         * 警示信息保留在图标上就够了。
         */
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(R.string.state_needs_login),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                text = if (lastSync == null) {
                    stringResource(R.string.home_never_synced)
                } else {
                    stringResource(
                        R.string.home_last_sync,
                        lastSync.atZone(java.time.ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    )
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        state.urls?.sisEntry?.let {
                            navigator.openWeb(it, "教务系统登录", SchoolSystem.SIS, true)
                        }
                    }
                ) { Text(stringResource(R.string.action_relogin)) }

                OutlinedButton(onClick = { navigator.openTab(Tab.SCHEDULE) }) {
                    Text(stringResource(R.string.action_view_cache))
                }
            }
            TextButton(
                onClick = {
                    state.urls?.sisHomePage?.let {
                        navigator.openWeb(it.url, it.label, SchoolSystem.SIS, false)
                    }
                }
            ) { Text(stringResource(R.string.action_open_sis)) }
        }
    }
}

@Composable
private fun SyncRow(
    state: HomeUiState,
    refreshing: Boolean,
    backgroundSyncing: Boolean,
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
                        backgroundSyncing -> "后台同步中…"
                        else -> statusText(state, refreshing)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(onClick = onRefresh, enabled = !refreshing) {
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.size(6.dp))
                Text(stringResource(R.string.action_refresh))
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
private fun statusText(state: HomeUiState, refreshing: Boolean): String = when {
    refreshing -> stringResource(R.string.state_syncing)
    state.lastRefresh is RefreshResult.Success -> stringResource(R.string.state_ok)
    state.lastRefresh is RefreshResult.Offline -> stringResource(R.string.state_offline)
    state.lastRefresh is RefreshResult.SchemaChanged -> stringResource(R.string.state_schema_changed)
    state.lastRefresh is RefreshResult.ServerError -> stringResource(R.string.state_server_error)
    state.lastRefresh is RefreshResult.SessionExpired -> stringResource(R.string.state_needs_login)
    state.lastRefresh is RefreshResult.Failed -> stringResource(R.string.state_unknown_error)
    state.hasData -> stringResource(R.string.state_ok)
    else -> stringResource(R.string.state_never)
}

/**
 * Home attendance card.
 *
 * The number that matters is the accumulated check-in time for today against the college's daily
 * target, not "did I swipe in once" — a student may enter and leave several times.
 */
@Composable
private fun AttendanceCard(
    record: AttendanceRecord?,
    minutes: Int?,
    inside: Boolean,
    goalMinutes: Int,
    onRefresh: () -> Unit,
    onOpenWeb: () -> Unit,
    onOpenTab: () -> Unit
) {
    val progress = minutes?.let { (it.toFloat() / goalMinutes).coerceIn(0f, 1f) } ?: 0f
    val reached = (minutes ?: 0) >= goalMinutes
    val remaining = minutes?.let { (goalMinutes - it).coerceAtLeast(0) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (reached) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.home_attendance_today), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (inside) {
                    Text(stringResource(R.string.attendance_inside), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = minutes?.let { AttendanceRecord.formatMinutes(it, currentLocale()) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.home_progress, AttendanceRecord.formatMinutes(goalMinutes, currentLocale())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Text(
                text = when {
                    minutes == null -> stringResource(R.string.home_no_swipe_hint)
                    remaining == null -> stringResource(R.string.home_no_duration)
                    remaining == 0 -> stringResource(R.string.attendance_goal_reached)
                    else -> stringResource(R.string.attendance_remaining, AttendanceRecord.formatMinutes(remaining, currentLocale()))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onRefresh, label = { Text("刷新") })
                AssistChip(onClick = onOpenTab, label = { Text(stringResource(R.string.attendance_title)) })
                AssistChip(onClick = onOpenWeb, label = { Text(stringResource(R.string.action_open_stu)) })
            }
        }
    }
}

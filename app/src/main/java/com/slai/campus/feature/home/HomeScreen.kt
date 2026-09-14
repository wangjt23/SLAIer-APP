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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.slai.campus.domain.attendance.AttendanceRecord
import com.slai.campus.domain.schedule.ClassOccurrence
import com.slai.campus.ui.components.CampusHero
import com.slai.campus.ui.currentLocale
import com.slai.campus.ui.datePatternFor
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
 * Timetable sync controls live on the schedule screen; session expiry does not hide cached classes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navigator: AppNavigator,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val attendanceRefreshing by viewModel.attendanceRefreshing.collectAsStateWithLifecycle()
    val attendanceResult by viewModel.attendanceResult.collectAsStateWithLifecycle()
    val today = java.time.LocalDate.now()
    // 日期格式与语言一致：中文 "9月10日 星期四"，英文 "Thursday, Sep 10"。
    val dateFormatter = DateTimeFormatter.ofPattern(datePatternFor(currentLocale()), currentLocale())

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
        PullToRefreshBox(
            isRefreshing = attendanceRefreshing,
            onRefresh = viewModel::refreshAttendance,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    CampusHero(subtitle = stringResource(R.string.hero_subtitle))
                }

                // 课表区块只展示缓存，设置开关控制是否显示。
                if (state.showTimetable) {
                    when {
                        state.today.isEmpty() -> item {
                            EmptyTodayCard(
                                isFirstRun = state.isFirstRun,
                                onOpenSchedule = { navigator.openTab(Tab.SCHEDULE) },
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

                    item { HorizontalDivider() }
                }

                item { SectionHeader(stringResource(R.string.home_attendance_title)) }

                item {
                    AttendanceCard(
                        record = state.attendance,
                        minutes = state.attendanceMinutes,
                        inside = state.attendanceInside,
                        goalMinutes = state.attendanceGoalMinutes,
                        refreshing = attendanceRefreshing,
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
                    com.slai.campus.feature.attendance.AttendanceResultRow(attendanceResult, attendanceRefreshing)
                    if (attendanceResult is com.slai.campus.domain.attendance.AttendanceRefreshResult.SessionExpired) {
                        TextButton(onClick = {
                            state.urls?.stuEntry?.let { navigator.openWeb(it, "学生系统登录", SchoolSystem.STU, true) }
                        }) { Text(stringResource(R.string.action_relogin)) }
                    }
                }

                // 这两个入口都是课表相关的（本周课表 / 教务系统首页），隐藏课表时一并收起；
                // 课表 Tab 本身仍然保留，需要时从底部导航进。
                if (state.showTimetable) {
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
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
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
private fun EmptyTodayCard(isFirstRun: Boolean, onOpenSchedule: () -> Unit, onOpenWeb: () -> Unit) {
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
                    text = stringResource(R.string.home_cached_schedule_hint),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onOpenSchedule) { Text(stringResource(R.string.action_week_schedule)) }
            }
            TextButton(onClick = onOpenWeb) { Text(stringResource(R.string.action_open_web)) }
        }
    }
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
    refreshing: Boolean,
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
                    minutes == null && record?.leave == true -> stringResource(R.string.attendance_no_entry_records)
                    minutes == null -> stringResource(R.string.home_no_swipe_hint)
                    remaining == null -> stringResource(R.string.home_no_duration)
                    remaining == 0 -> stringResource(R.string.attendance_goal_reached)
                    else -> stringResource(R.string.attendance_remaining, AttendanceRecord.formatMinutes(remaining, currentLocale()))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onRefresh, enabled = !refreshing, label = { Text(stringResource(R.string.action_refresh)) })
                AssistChip(onClick = onOpenTab, label = { Text(stringResource(R.string.attendance_title)) })
                AssistChip(onClick = onOpenWeb, label = { Text(stringResource(R.string.action_open_stu)) })
            }
        }
    }
}

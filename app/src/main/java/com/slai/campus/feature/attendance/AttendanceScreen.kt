package com.slai.campus.feature.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.slai.campus.domain.attendance.AttendanceWeek
import com.slai.campus.domain.attendance.AttendanceRefreshResult
import com.slai.campus.domain.attendance.DailyAttendance
import com.slai.campus.ui.currentLocale
import com.slai.campus.ui.datePatternFor
import com.slai.campus.navigation.AppNavigator
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 考勤记录（闸机刷卡流水）。
 *
 * Replaces the old "打卡状态" card: the school publishes a real per-day ledger, so this screen shows
 * what actually happened — when the student entered, when they left, how long they stayed, and
 * whether the day counts as qualified — instead of guessing a three-state answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceScreen(
    navigator: AppNavigator,
    viewModel: AttendanceViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dayFormatter = DateTimeFormatter.ofPattern(datePatternFor(currentLocale(), withWeekday = false), currentLocale())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.attendance_title))
                        Text(
                            text = state.month.ifBlank { "—" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::previousMonth) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.cd_previous_month))
                    }
                    IconButton(onClick = viewModel::nextMonth) {
                        Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.cd_next_month))
                    }
                    IconButton(onClick = viewModel::refresh, enabled = !state.refreshing) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
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
            if (state.stuNeedsLogin) {
                item { NeedsLoginCard(navigator, state) }
            }

            item { TodayCard(state) }

            // 常驻提示：闸机记录不是实时的。"刚出闸却还显示在馆中"就来自这个延迟。
            item { DelayNote() }

            // 连不上学校时（多半是人已离开校园网），把"回校园网再刷"说清楚。
            if (state.lastResult?.needsCampusNetworkHint == true) {
                item { UnreachableCard() }
            }

            if (state.monthData.summary.hasAnything) {
                item { MonthSummaryCard(state) }
            }

            item {
                Text(
                    text = stringResource(R.string.attendance_month_detail),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            when {
                state.refreshing && !state.hasData -> item { LoadingRow() }

                !state.hasData -> item {
                    EmptyCard(
                        onRefresh = viewModel::refresh,
                        onOpenWeb = {
                            state.urls?.stuCheckInPage?.let {
                                navigator.openWeb(it.url, it.label, SchoolSystem.STU, false)
                            }
                        }
                    )
                }

                else -> {
                    /*
                     * 明细按**学校自己的周分组**渲染：学院的口径是"一周任意 5 天即可"，
                     * 所以周标题上直接给「计入 N/5 天」，超出的合格日在行内灰显说明。
                     */
                    val weeks = state.monthData.weeks
                    if (weeks.isEmpty()) {
                        // 兜底：学校没给周分组时退回平铺，至少不丢数据。
                        items(state.days, key = { it.date.toEpochDay() }) { record ->
                            DayCard(
                                record = record,
                                isToday = record.date == state.todayDate,
                                punchDay = state.punchDay(record.date),
                                punchMinutes = state.punchMinutes(record.date),
                                discarded = state.hasDiscarded(record.date),
                                notCounted = false,
                                now = state.nowDateTime,
                                formatter = dayFormatter
                            )
                        }
                    } else {
                        weeks.forEach { week ->
                            item(key = "week-${week.range}") { WeekHeader(week, dayFormatter) }
                            items(week.records, key = { it.date.toEpochDay() }) { record ->
                                DayCard(
                                    record = record,
                                    isToday = record.date == state.todayDate,
                                    punchDay = state.punchDay(record.date),
                                    punchMinutes = state.punchMinutes(record.date),
                                    discarded = state.hasDiscarded(record.date),
                                    notCounted = state.isNotCounted(record.date),
                                    now = state.nowDateTime,
                                    formatter = dayFormatter
                                )
                            }
                        }
                    }
                }
            }

            item {
                ResultRow(state.lastResult, state.refreshing)
            }

            item { HorizontalDivider() }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            state.urls?.stuCheckInPage?.let {
                                navigator.openWeb(it.url, it.label, SchoolSystem.STU, false)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.attendance_open_page), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.attendance_source_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The school's own monthly verdict, straight from the `stats` block:
 * 应达标天数 / 已达标天数 / 月度是否合格 / 周末可用次数。
 */
@Composable
private fun MonthSummaryCard(state: AttendanceUiState) {
    val summary = state.monthData.summary
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.attendance_school_stats), style = MaterialTheme.typography.titleSmall)
                summary.monthlyQualified?.let {
                    Text(
                        text = summary.qualificationMessage ?: if (it) "合格" else "不合格",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                /*
                 * 两格，数字全部来自学校，措辞与学院网站一致：
                 *   ① 有效打卡 / 应达标 = 网站的「有效打卡 N 天」 + 学校给出的应达标天数；
                 *   ② 剩余补打卡机会 = 网站的同名数字（漏卡后的补录额度）。
                 * 特意**不显示** `actualWorkdayPunches`（只数工作日，比网站少）——
                 * 那个数字会让用户以为 App 算错了。
                 */
                summary.effectiveDays?.let { effective ->
                    val required = summary.requiredDays
                    StatCell(
                        label = if (required != null) {
                            stringResource(R.string.attendance_effective_progress)
                        } else {
                            stringResource(R.string.attendance_effective_days)
                        },
                        value = if (required != null) {
                            stringResource(R.string.attendance_days_value, effective, required)
                        } else {
                            stringResource(R.string.attendance_days_value_short, effective)
                        }
                    )
                }
                summary.maxAllowedRestdayPunches?.let { left ->
                    StatCell(
                        label = stringResource(R.string.attendance_restday_left),
                        value = stringResource(R.string.attendance_days_value_short, left)
                    )
                }
            }

            val swiped = state.days.count { it.hasSwipes }
            if (swiped > 0) {
                Text(
                    text = "本月有记录 $swiped 天（合计 ${AttendanceRecord.formatMinutes(state.days.sumOf { it.checkedInMinutes() ?: 0 }, state.locale)}）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 一周的分组标题。
 *
 * 右侧的「计入 N/5 天」是**对学校判定做的折算**：学院口径是一周任意 5 天，
 * 所以一周最多计 5 天（不足 5 天就按实际合格天数）。判定本身仍然全部来自学校。
 */
@Composable
private fun WeekHeader(week: AttendanceWeek, formatter: DateTimeFormatter) {
    val full = week.countedCount >= AttendanceWeek.COUNTED_DAYS_PER_WEEK
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val start = week.start
        val end = week.end
        Column {
            /*
             * 标题直接用**本地化的日期范围**，不用 week.shortLabel：
             * 那个 label 是数据层自己拼的中文（"9月7日起"）—— 缓存里只有每日行，学校给的
             * 「第N周 (…)」并没有被持久化，所以英文界面下它会漏出中文，而且和下面的范围重复。
             */
            Text(
                text = if (start != null && end != null) {
                    "${start.format(formatter)} – ${end.format(formatter)}"
                } else {
                    week.shortLabel
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = stringResource(
                R.string.attendance_week_counted,
                week.countedCount,
                AttendanceWeek.COUNTED_DAYS_PER_WEEK
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (full) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TodayCard(state: AttendanceUiState) {
    val formatter = DateTimeFormatter.ofPattern(datePatternFor(currentLocale()), currentLocale())
    val today = state.today
    val goalText = AttendanceRecord.formatMinutes(state.dailyGoalMinutes, state.locale)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                state.goalReached -> MaterialTheme.colorScheme.primaryContainer
                today?.hasSwipes == true -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "今天 · ${state.todayDate.format(formatter)}",
                    style = MaterialTheme.typography.titleMedium
                )
                if (today?.isCurrentlyInside == true) {
                    Text(
                        text = "在馆中",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // The number the user actually cares about: accumulated check-in time today.
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = state.todayMinutesText ?: "0 分钟",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "/ $goalText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            LinearProgressIndicator(
                progress = { state.todayProgress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = state.remainingMinutes?.let { left ->
                        if (left > 0) stringResource(R.string.attendance_remaining, AttendanceRecord.formatMinutes(left, state.locale))
                        else stringResource(R.string.attendance_goal_reached)
                    } ?: stringResource(R.string.attendance_no_record_today),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                today?.durationText?.let {
                    Text(
                        text = stringResource(R.string.attendance_school_record, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (today == null) {
                Text(
                    text = stringResource(R.string.attendance_no_day_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // 今天的每一段在馆（由闸机流水配对算出）
                if (state.sessions.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.sessions.forEach { session ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        if (session.isOpen) Icons.Default.Login else Icons.Default.Logout,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = session.textAt(state.nowDateTime),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (session.isOpen) {
                                        Text(
                                            text = "在馆中",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    text = AttendanceRecord.formatMinutes(session.minutesAt(state.nowDateTime), state.locale),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Text(
                            text = "共 ${state.sessions.size} 段，累计 ${state.todayMinutesText ?: "0 分钟"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SwipeCell(
                            icon = Icons.Default.Login,
                            label = stringResource(R.string.attendance_first_in),
                            value = today.firstSwipe?.format(AttendanceRecord.HH_MM) ?: "—",
                            modifier = Modifier.weight(1f)
                        )
                        SwipeCell(
                            icon = Icons.Default.Logout,
                            label = stringResource(R.string.attendance_last_out),
                            value = today.lastSwipe?.format(AttendanceRecord.HH_MM) ?: "—",
                            modifier = Modifier.weight(1f)
                        )
                        SwipeCell(
                            icon = Icons.Default.Refresh,
                            label = stringResource(R.string.attendance_swipe_counts),
                            value = "${today.enterCount ?: 0}/${today.exitCount ?: 0}",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                val chips = chips(today, state.isNotCounted(today.date))
                if (chips.isNotEmpty()) {
                    Text(
                        text = chips.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Short labels for a day.
 *
 * `dayType` / `isHoliday` / `leave` come from the school's payload in Chinese; those are protocol
 * values and are shown as-is. Only the verdict we render ourselves (`qualified`) is localised.
 */
@Composable
private fun chips(record: AttendanceRecord, notCounted: Boolean = false): List<String> = buildList {
    record.dayType?.let { add(it) }
    record.isHoliday?.let { if (it != "非节假日") add(it) }
    if (record.leave == true) add("请假")
    record.qualified?.let {
        add(
            when {
                !it -> stringResource(R.string.attendance_school_fail)
                // 学院口径：一周任意 5 天即可，超出的合格日不累加，必须说明白。
                notCounted -> stringResource(R.string.attendance_school_ok_not_counted)
                else -> stringResource(R.string.attendance_school_ok)
            }
        )
    }
}

@Composable
private fun SwipeCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/**
 * One day of the month.
 *
 * The headline used to be `record.swipeRange`, i.e. the school's own `firstSwipe`/`lastSwipe` —
 * but `weekGroupedByMonth` returns those **empty for every day** on this deployment (verified), so
 * the card said 「无刷卡记录」 right next to the school's own 「在馆 10:23:57」, which reads as a
 * contradiction. The real in/out times live in the 闸机流水 (`listData`) and are paired by
 * `PunchPairing`, so the headline now comes from there and only falls back to the school's fields.
 */
@Composable
private fun DayCard(
    record: AttendanceRecord,
    isToday: Boolean,
    punchDay: DailyAttendance?,
    punchMinutes: Int?,
    discarded: Boolean,
    /** 学校判定合格，但本周已满 5 天，不再计入打卡天数。 */
    notCounted: Boolean,
    now: java.time.LocalDateTime,
    formatter: DateTimeFormatter
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = record.date.format(formatter),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                    )
                    if (isToday) {
                        Text("今天", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    text = dayHeadline(record, punchDay, punchMinutes, now),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            val details = buildList {
                addAll(chips(record, notCounted))
                // 进出时间：优先用闸机流水；没有流水时才退回学校给出的范围。
                when {
                    punchDay != null -> {
                        val shown = punchDay.sessions.take(4)
                        val range = shown.joinToString("，") { session ->
                            if (session.isOpen) {
                                "${session.from.toLocalTime().format(AttendanceRecord.HH_MM)} → 在馆中"
                            } else {
                                "${session.from.toLocalTime().format(AttendanceRecord.HH_MM)} → " +
                                    "${session.to!!.toLocalTime().format(AttendanceRecord.HH_MM)}"
                            }
                        }
                        val more = if (punchDay.sessions.size > shown.size) " 等 ${punchDay.sessions.size} 段" else ""
                        if (range.isNotBlank()) add("$range$more")
                    }
                    record.firstSwipe != null || record.lastSwipe != null -> add(record.swipeRange)
                    record.durationText != null && record.durationText != "0" ->
                        add(stringResource(R.string.attendance_inside_school, record.durationText))
                }
            }
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            /*
             * 进了没出、过了次日 05:00 仍未刷出 —— 这一段不计入时长。
             * 必须说明，否则用户会觉得"我明明在馆里，为什么是 0"。
             */
            if (discarded) {
                Text(
                    text = stringResource(R.string.attendance_discarded_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 一天最该看的那个数字：当天累计在馆时长。
 *
 * 今天用闸机流水实时算（学校的当日值恒为 0，未结算）；过去的日子两者一致，优先用流水 ——
 * 实测两者逐条吻合（9/1 539 分 = 08:59:11 …），只有 9/7 因为宿舍楼闸机而不同，见 [PunchPairing]。
 */
@Composable
private fun dayHeadline(
    record: AttendanceRecord,
    punchDay: DailyAttendance?,
    punchMinutes: Int?,
    now: java.time.LocalDateTime
): String = when {
    punchMinutes != null && punchMinutes > 0 -> AttendanceRecord.formatMinutes(punchMinutes, currentLocale())
    punchDay != null && punchDay.currentlyInsideAt(now) -> stringResource(R.string.attendance_inside)
    !record.durationText.isNullOrBlank() && record.durationText != "0" ->
        stringResource(R.string.attendance_inside_school, record.durationText!!)
    // dayType 是学校返回的中文值（协议数据，不能翻译），这里只翻译显示出来的文案。
    record.dayType == "周末" || record.dayType == "节假日" -> stringResource(R.string.attendance_rest_day)
    else -> stringResource(R.string.attendance_none)
}

@Composable
private fun LoadingRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.attendance_reading))
    }
}

@Composable
private fun EmptyCard(onRefresh: () -> Unit, onOpenWeb: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.attendance_empty), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.attendance_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefresh) { Text(stringResource(R.string.action_refresh)) }
                OutlinedButton(onClick = onOpenWeb) { Text(stringResource(R.string.action_open_web)) }
            }
        }
    }
}

@Composable
private fun NeedsLoginCard(navigator: AppNavigator, state: AttendanceUiState) {
    // 点击回调不是 @Composable，字符串要在外面取好。
    val stuLoginLabel = stringResource(R.string.attendance_stu_login)
    Card(
        modifier = Modifier.fillMaxWidth(),
        // 同首页：这是待操作提示，用品牌容器色，别铺满 errorContainer 的红。
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.attendance_stu_needs_login), style = MaterialTheme.typography.titleSmall)
            Button(
                onClick = {
                    state.urls?.stuEntry?.let { navigator.openWeb(it, stuLoginLabel, SchoolSystem.STU, true) }
                }
            ) { Text(stringResource(R.string.action_relogin)) }
        }
    }
}

/**
 * 学校闸机记录有 5–10 分钟延迟。
 *
 * 必须写在页面上：用户刷脸出闸后马上打开 App，看到的还是"在馆中"并在继续计时，
 * 不说清楚就会以为 App 算错了（实际是学校那边还没落这条记录）。
 */
@Composable
private fun DelayNote() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Default.Schedule,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.attendance_delay_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 有网但连不上学生系统时的提示。
 *
 * 典型场景：刷脸出闸 → 走出校园网 → 学生系统在校外不可达，于是"在馆中"一直挂着、
 * 时间一直涨。这时该说的不是"你离线了"，而是"回校园网再刷新"。
 */
@Composable
private fun UnreachableCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.attendance_unreachable_title),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Text(
                text = stringResource(R.string.attendance_unreachable_body),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ResultRow(result: AttendanceRefreshResult?, refreshing: Boolean) {
    val text = when {
        refreshing -> "正在同步…"
        result is AttendanceRefreshResult.Success -> "已同步：${result.days} 天"
        result is AttendanceRefreshResult.Offline -> "离线，显示缓存"
        result is AttendanceRefreshResult.Unreachable -> "连不上学生系统（可能是校外网络）"
        result is AttendanceRefreshResult.SessionExpired -> "需要重新登录"
        result is AttendanceRefreshResult.SchemaChanged -> "接口结构可能已变化"
        result is AttendanceRefreshResult.ServerError -> "服务器错误 ${result.code}"
        result is AttendanceRefreshResult.Failed -> "同步失败：${result.reason}"
        else -> ""
    }
    if (text.isNotBlank()) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

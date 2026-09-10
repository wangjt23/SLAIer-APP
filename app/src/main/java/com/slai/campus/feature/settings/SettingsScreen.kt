package com.slai.campus.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slai.campus.BuildConfig
import com.slai.campus.R
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.session.SessionSnapshot
import com.slai.campus.core.web.SisEndpoints
import com.slai.campus.navigation.AppNavigator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Settings.
 *
 * Two deliberate omissions: there is no field for a password, and no "remember me". Authentication
 * happens exclusively in the school's own WebView flow; this screen only configures the calendar
 * anchor, reminder behaviour and endpoint overrides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navigator: AppNavigator,
    session: SessionSnapshot,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 点击回调不是 @Composable，字符串要在外面取好。
    val sisLoginLabel = stringResource(R.string.schedule_sign_in)
    val stuLoginLabel = stringResource(R.string.attendance_stu_login)
    var showAnchorPicker by remember { mutableStateOf(false) }
    var weekInput by remember { mutableStateOf("") }
    var showWeekDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- sessions ------------------------------------------------------------------
            Text(stringResource(R.string.settings_section_session), style = MaterialTheme.typography.titleMedium)
            SessionCard(
                label = stringResource(R.string.settings_sis_session),
                stateText = viewModel.sessionLabel(state.session.sis),
                onProbe = { viewModel.probeSession(SchoolSystem.SIS) },
                onClear = { viewModel.clearSession(SchoolSystem.SIS) },
                onLogin = {
                    state.urls?.sisEntry?.let { navigator.openWeb(it, sisLoginLabel, SchoolSystem.SIS, true) }
                }
            )
            SessionCard(
                label = stringResource(R.string.settings_stu_session),
                stateText = viewModel.sessionLabel(state.session.stu),
                onProbe = { viewModel.probeSession(SchoolSystem.STU) },
                onClear = { viewModel.clearSession(SchoolSystem.STU) },
                onLogin = {
                    state.urls?.stuEntry?.let { navigator.openWeb(it, stuLoginLabel, SchoolSystem.STU, true) }
                }
            )

            HorizontalDivider()

            // ---- schedule ------------------------------------------------------------------
            Text(stringResource(R.string.settings_section_schedule), style = MaterialTheme.typography.titleMedium)

            SettingRow(
                title = stringResource(R.string.settings_show_timetable_on_home),
                summary = stringResource(R.string.settings_show_timetable_on_home_summary)
            ) {
                Switch(
                    checked = state.showTimetableOnHome,
                    onCheckedChange = viewModel::setShowTimetableOnHome
                )
            }

            SettingRow(
                title = stringResource(R.string.settings_first_week_monday),
                // 空格交给资源文件：中文"已确认：2026-09-07"、英文"Confirmed: 2026-09-07"。
                summary = state.firstWeekMonday?.let {
                    val date = it.format(DateTimeFormatter.ISO_DATE)
                    stringResource(
                        if (state.anchorConfirmed) R.string.settings_anchor_confirmed
                        else R.string.settings_anchor_estimated,
                        date
                    )
                } ?: stringResource(R.string.settings_anchor_unset)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showAnchorPicker = true }) { Text(stringResource(R.string.settings_pick_date)) }
                    OutlinedButton(onClick = { showWeekDialog = true }) { Text(stringResource(R.string.settings_by_current_week)) }
                }
            }

            var studentId by remember(state.studentIdHint) { mutableStateOf(state.studentIdHint.orEmpty()) }
            OutlinedTextField(
                value = studentId,
                onValueChange = { studentId = it },
                label = { Text(stringResource(R.string.settings_student_id)) },
                supportingText = { Text(stringResource(R.string.settings_student_id_summary)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { viewModel.setStudentIdHint(studentId) }) {
                Text(stringResource(R.string.common_save))
            }

            HorizontalDivider()

            // ---- reminders -----------------------------------------------------------------
            Text(stringResource(R.string.settings_section_reminder), style = MaterialTheme.typography.titleMedium)

            SettingRow(
                title = stringResource(R.string.settings_reminder_enabled),
                summary = if (state.notificationsAllowed) stringResource(R.string.settings_notif_granted) else stringResource(R.string.settings_notif_denied)
            ) {
                Switch(checked = state.reminders.enabled, onCheckedChange = viewModel::setRemindersEnabled)
            }

            SettingRow(
                title = stringResource(R.string.settings_reminder_lead),
                summary = stringResource(R.string.settings_lead_current, state.reminders.leadMinutes)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30).forEach { minutes ->
                        OutlinedButton(
                            onClick = { viewModel.setReminderLeadMinutes(minutes) },
                            enabled = state.reminders.leadMinutes != minutes
                        ) { Text("$minutes") }
                    }
                }
            }

            SettingRow(
                title = stringResource(R.string.settings_reminder_exact),
                summary = when {
                    !state.exactAlarmAllowed -> stringResource(R.string.settings_exact_denied)
                    state.reminders.exact -> stringResource(R.string.settings_exact_enabled)
                    else -> stringResource(R.string.settings_reminder_exact_summary)
                }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(
                        checked = state.reminders.exact,
                        onCheckedChange = viewModel::setReminderExact
                    )
                    if (!state.exactAlarmAllowed) {
                        TextButton(onClick = viewModel::openExactAlarmSettings) { Text(stringResource(R.string.settings_grant)) }
                    }
                }
            }

            HorizontalDivider()

            // ---- advanced ------------------------------------------------------------------
            Text(stringResource(R.string.settings_section_advanced), style = MaterialTheme.typography.titleMedium)

            var sisUrl by remember(state.urls) { mutableStateOf(state.urls?.sisBase.orEmpty()) }
            var stuUrl by remember(state.urls) { mutableStateOf(state.urls?.stuBase.orEmpty()) }

            // 填进来的地址可能是站点根，也可能是应用地址；这里把实际会用到的两个地址摊开给用户看，
            // 免得「登录能过但课表拉不到」这种问题只能靠猜。
            val sisResolved = remember(sisUrl) { SisEndpoints.of(sisUrl) }

            OutlinedTextField(
                value = sisUrl,
                onValueChange = { sisUrl = it },
                label = { Text(stringResource(R.string.settings_sis_base_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text(stringResource(R.string.settings_sis_base_url_summary)) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.settings_sis_resolved_entry, sisResolved.entry),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.settings_sis_resolved_base, sisResolved.base),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = stuUrl,
                onValueChange = { stuUrl = it },
                label = { Text(stringResource(R.string.settings_stu_base_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.setSisBaseUrl(sisUrl)
                    viewModel.setStuBaseUrl(stuUrl)
                }) { Text(stringResource(R.string.common_save)) }
                TextButton(onClick = {
                    viewModel.resetEndpoints()
                    sisUrl = BuildConfig.DEFAULT_SIS_BASE_URL
                    stuUrl = BuildConfig.DEFAULT_STU_BASE_URL
                }) { Text(stringResource(R.string.settings_reset_defaults)) }
            }

            OutlinedButton(
                onClick = navigator.openProviders,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                Text(stringResource(R.string.settings_api_providers), modifier = Modifier.padding(start = 8.dp))
            }

            OutlinedButton(
                onClick = navigator.openDiagnostics,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                Text(
                    stringResource(R.string.settings_diagnostics),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            HorizontalDivider()

            // ---- appearance ----------------------------------------------------------------
            Text(stringResource(R.string.settings_section_appearance), style = MaterialTheme.typography.titleMedium)

            ChoiceRow(
                title = stringResource(R.string.settings_theme),
                summary = stringResource(R.string.settings_theme_summary)
            ) {
                val options = listOf(
                    com.slai.campus.core.common.AppTheme.SYSTEM to R.string.theme_system,
                    com.slai.campus.core.common.AppTheme.LIGHT to R.string.theme_light,
                    com.slai.campus.core.common.AppTheme.DARK to R.string.theme_dark
                )
                SegmentedRow(
                    options = options,
                    selected = state.theme,
                    onSelect = viewModel::setTheme
                )
            }

            ChoiceRow(
                title = stringResource(R.string.settings_language),
                summary = stringResource(R.string.settings_language_summary)
            ) {
                val options = listOf(
                    com.slai.campus.core.common.AppLanguage.SYSTEM to R.string.language_system,
                    com.slai.campus.core.common.AppLanguage.CHINESE to R.string.language_zh,
                    com.slai.campus.core.common.AppLanguage.ENGLISH to R.string.language_en
                )
                SegmentedRow(
                    options = options,
                    selected = state.language,
                    onSelect = viewModel::setLanguage
                )
            }

            HorizontalDivider()

            // ---- about ---------------------------------------------------------------------
            Text(stringResource(R.string.settings_section_about), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyMedium
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.settings_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    if (showAnchorPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (state.firstWeekMonday ?: LocalDate.now())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showAnchorPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = pickerState.selectedDateMillis
                    viewModel.setFirstWeekMonday(
                        millis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    )
                    showAnchorPicker = false
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showAnchorPicker = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        ) {
            DatePicker(state = pickerState, title = { Text(stringResource(R.string.settings_first_week_monday)) })
        }
    }

    if (showWeekDialog) {
        AlertDialog(
            onDismissRequest = { showWeekDialog = false },
            title = { Text(stringResource(R.string.settings_which_week)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_which_week_hint))
                    OutlinedTextField(
                        value = weekInput,
                        onValueChange = { weekInput = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.settings_week_number)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    weekInput.toIntOrNull()?.takeIf { it in 1..30 }?.let(viewModel::setFromCurrentWeek)
                    showWeekDialog = false
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showWeekDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun SessionCard(
    label: String,
    stateText: String,
    onProbe: () -> Unit,
    onClear: () -> Unit,
    onLogin: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLogin) { Text(stringResource(R.string.action_login)) }
                OutlinedButton(onClick = onProbe) { Text(stringResource(R.string.settings_probe)) }
                TextButton(onClick = onClear) { Text(stringResource(R.string.settings_clear_session)) }
            }
            Text(
                text = stringResource(R.string.settings_clear_session_summary),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    summary: String,
    action: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        action()
    }
}

/** 一行设置：标题 + 说明，下面挂一个控件。 */
@Composable
private fun ChoiceRow(
    title: String,
    summary: String,
    control: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        control()
    }
}

/**
 * 三选一的分段按钮。
 *
 * 用分段按钮而不是下拉框：只有三个选项，全部摊开可以让用户一眼看到"跟随系统"是默认值 ——
 * 首次打开就已经是对的那个，不需要来这里找开关。
 */
@Composable
private fun <T> SegmentedRow(
    options: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, labelRes) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(stringResource(labelRes))
            }
        }
    }
}

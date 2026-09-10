package com.slai.campus.feature.provider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slai.campus.R
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.domain.provider.ApiProvider
import com.slai.campus.navigation.AppNavigator

/**
 * API provider management.
 *
 * The whole point: an endpoint is configuration, not code. Paste a provider (or let the capture tool
 * generate one), hit 测试, and see the raw status/body plus how many courses parsed. If the school
 * changes something, this screen is where it gets fixed — no rebuild needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderScreen(
    navigator: AppNavigator,
    onClose: () -> Unit,
    sisEntryUrl: String,
    viewModel: ProviderViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<ApiProvider?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("接口配置") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("怎么用", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "1. 点「从网页抓取」→ 在打开的页面里正常登录 → 进入你的课表页面 → " +
                            "点右上角 ✓ 完成。\n" +
                            "2. App 会自动识别课表请求并生成配置。\n" +
                            "3. 也可以点「新建模板」手工填写，再点「测试」验证。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            OutlinedTextField(
                value = state.captureStartUrl,
                onValueChange = viewModel::setCaptureStartUrl,
                label = { Text("抓取起始地址") },
                singleLine = true,
                supportingText = { Text("默认从登录入口开始；也可以直接填课表页地址") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val start = state.captureStartUrl.ifBlank { sisEntryUrl }
                        navigator.openCapture(start, "抓取课表请求", SchoolSystem.SIS)
                    },
                    enabled = !state.busy
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Text("从网页抓取", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = viewModel::testAll, enabled = !state.busy) { Text("测试全部") }
                OutlinedButton(onClick = viewModel::refreshNow, enabled = !state.busy) { Text("立即刷新") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::copyReport,
                    enabled = state.hasCapture
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Text("复制抓包报告", modifier = Modifier.padding(start = 6.dp))
                }
                if (state.hasCapture) {
                    Text(
                        text = state.captureSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }

            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("执行中…")
                }
            }

            HorizontalDivider()
            Text("已配置（${state.providers.size}）", style = MaterialTheme.typography.titleMedium)

            if (state.providers.isEmpty()) {
                Text(
                    "还没有配置。用「从网页抓取」自动生成，或点下面的「新建模板」手工填写。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.providers.forEach { provider ->
                ProviderCard(
                    provider = provider,
                    onTest = { viewModel.test(provider) },
                    onEdit = { viewModel.loadIntoEditor(provider) },
                    onToggle = { viewModel.setEnabled(provider.id, it) },
                    onDelete = { confirmDelete = provider }
                )
            }

            HorizontalDivider()
            Text("新建 / 编辑", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::newTemplate) { Text("新建模板") }
                OutlinedButton(onClick = { viewModel.onEditorChanged("") }) { Text("清空") }
            }

            OutlinedTextField(
                value = state.editorText,
                onValueChange = viewModel::onEditorChanged,
                label = { Text("provider JSON") },
                isError = state.editorError != null,
                supportingText = state.editorError?.let { { Text(it) } },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp)
            )

            Button(onClick = viewModel::saveFromEditor, enabled = state.editorText.isNotBlank()) {
                Text(stringResource(R.string.common_save))
            }

            if (state.log.isNotBlank()) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("输出", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = viewModel::clearLog) { Text("清空") }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = state.log,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }

    confirmDelete?.let { provider ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除配置") },
            text = { Text("确定删除「${provider.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(provider.id)
                    confirmDelete = null
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun ProviderCard(
    provider: ApiProvider,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(provider.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${provider.method} ${provider.url}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = provider.enabled, onCheckedChange = onToggle)
            }

            Text(
                text = "rowsPath=${provider.rowsPath.ifBlank { "(root)" }} · " +
                    "字段映射 ${provider.resolvedFieldMap().size} 项" +
                    if (provider.isLearned) " · 自动学习" else " · 手工",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            provider.note?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest) { Text("测试") }
                OutlinedButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

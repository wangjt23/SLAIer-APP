package com.slai.campus.feature.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slai.campus.R

/**
 * Diagnostic console.
 *
 * This screen is how the plan's step 8 ("验证原生读取课表的最小闭环") is completed on a real device:
 * the user logs in normally, then taps a button and sees the HTTP status, content type and a redacted
 * body preview of the *real* school response.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onClose: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenCapture: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val output by viewModel.output.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("campus-diagnostics", viewModel.exportReport())
                            )
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.diag_export))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.diag_note),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = viewModel::probeSessions) { Text(stringResource(R.string.diag_session_probe)) }
                Button(onClick = viewModel::testSisNative, enabled = !busy) {
                    Text(stringResource(R.string.diag_sis_probe))
                }
                Button(onClick = viewModel::testRealRefresh, enabled = !busy) {
                    Text("真实刷新")
                }
                OutlinedButton(onClick = viewModel::testSisWebView, enabled = !busy) {
                    Text(stringResource(R.string.diag_sis_webview))
                }
                Button(onClick = viewModel::testAttendance, enabled = !busy) {
                    Text("测试考勤接口")
                }
                Button(onClick = viewModel::testPunches, enabled = !busy) {
                    Text("测试闸机流水")
                }
                OutlinedButton(onClick = viewModel::testStuNative, enabled = !busy) {
                    Text(stringResource(R.string.diag_stu_probe))
                }
                OutlinedButton(onClick = viewModel::testStuWebView, enabled = !busy) {
                    Text("STU WebView")
                }
                OutlinedButton(onClick = viewModel::dumpConfig) { Text("配置") }
                Button(onClick = onOpenCapture) { Text("抓取课表请求") }
                OutlinedButton(onClick = onOpenProviders) { Text("接口配置") }
                TextButton(onClick = viewModel::clear) { Text(stringResource(R.string.diag_clear_output)) }
            }

            if (busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text(stringResource(R.string.common_loading))
                }
            }

            Card(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = output.ifBlank { "点击上方按钮开始诊断。\n\n" +
                        "推荐顺序：\n1. 会话探测\n2. SIS 原生接口\n3. 若失败，试 WebView 提取" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

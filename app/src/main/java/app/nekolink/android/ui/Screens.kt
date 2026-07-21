package app.nekolink.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nekolink.android.domain.AgentPhase
import app.nekolink.android.domain.UiStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    status: UiStatus,
    busy: Boolean,
    message: String?,
    onPair: (server: String, name: String, code: String) -> Unit,
) {
    var server by remember(status.serverBase) { mutableStateOf(status.serverBase) }
    var name by remember(status.displayName) { mutableStateOf(status.displayName) }
    var code by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("连接 NekoLink") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "填写服务器地址与 Admin 生成的注册码，完成本机配对。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("配对", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text("服务器地址") },
                        placeholder = { Text("http://127.0.0.1:8080") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("server_url"),
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("设备显示名") },
                        placeholder = { Text("我的手机") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("display_name"),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("注册码") },
                        placeholder = { Text("粘贴一次性注册码") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pairing_code"),
                    )
                    if (message != null) {
                        Text(
                            message,
                            color = if (message.contains("成功")) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.testTag("setup_message"),
                        )
                    }
                    Button(
                        onClick = { onPair(server, name, code) },
                        enabled = !busy && server.isNotBlank() && name.isNotBlank() && code.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pair_button"),
                    ) {
                        Text(if (busy) "配对中…" else "配对")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    status: UiStatus,
    busy: Boolean,
    message: String?,
    usageAccessGranted: Boolean,
    onPauseToggle: (Boolean) -> Unit,
    onSaveDisplayName: (String) -> Unit,
    onShowSystem: (Boolean) -> Unit,
    onAutostart: (Boolean) -> Unit,
    onUnpair: () -> Unit,
    onChangeServerConfirmed: (String) -> Unit,
    onShield: (Boolean) -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onStopService: () -> Unit,
) {
    var displayName by remember(status.displayName) { mutableStateOf(status.displayName) }
    var server by remember(status.serverBase) { mutableStateOf(status.serverBase) }
    var confirmShield by remember { mutableStateOf(false) }
    var confirmServer by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "状态摘要与本机配置。关闭应用不会停止前台服务上报（除非点停止服务）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )

            StatusCard(status)

            if (!usageAccessGranted) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("usage_permission_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("需要「使用情况访问」权限", fontWeight = FontWeight.SemiBold)
                        Text("未授权时前台/后台应用将降级为空，快照会标记 partialPermissions。")
                        Button(onClick = onOpenUsageAccess, modifier = Modifier.testTag("open_usage_access")) {
                            Text("打开使用情况设置")
                        }
                        OutlinedButton(onClick = onOpenNotificationAccess) {
                            Text("打开通知使用权（媒体，可选）")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text("服务器地址") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_server_url"),
                    )
                    OutlinedButton(
                        onClick = { confirmServer = true },
                        enabled = !busy && server.trim() != status.serverBase,
                        modifier = Modifier.testTag("change_server_button"),
                    ) {
                        Text("保存服务器地址（需重新配对）")
                    }
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("设备显示名") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_display_name"),
                    )
                    Button(
                        onClick = { onSaveDisplayName(displayName) },
                        enabled = !busy && displayName.isNotBlank(),
                        modifier = Modifier.testTag("save_display_name"),
                    ) {
                        Text("保存显示名")
                    }

                    SwitchRow(
                        label = "暂停本机上报",
                        checked = status.paused,
                        onCheckedChange = onPauseToggle,
                        testTag = "pause_switch",
                    )
                    SwitchRow(
                        label = "显示系统后台项",
                        checked = status.showSystemBackground,
                        onCheckedChange = onShowSystem,
                        testTag = "show_system_switch",
                    )
                    SwitchRow(
                        label = "开机自启（可选）",
                        checked = status.autostart,
                        onCheckedChange = onAutostart,
                        testTag = "autostart_switch",
                    )
                    SwitchRow(
                        label = "禁止视奸（全局）",
                        checked = status.privacyShield,
                        onCheckedChange = { enable ->
                            if (enable) confirmShield = true else onShield(false)
                        },
                        testTag = "privacy_shield_switch",
                    )
                }
            }

            if (message != null) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("settings_message"),
                )
            }

            OutlinedButton(
                onClick = { confirmUnpair = true },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("unpair_button"),
            ) {
                Text("解除配对")
            }
            OutlinedButton(
                onClick = onStopService,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stop_service_button"),
            ) {
                Text("停止服务")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmShield) {
        AlertDialog(
            onDismissRequest = { confirmShield = false },
            title = { Text("开启禁止视奸？") },
            text = {
                Text(
                    "将对全站访客硬遮罩，服务端不记录业务活动信息。此为 Subject 全局开关，不仅本机暂停。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmShield = false
                        onShield(true)
                    },
                    modifier = Modifier.testTag("confirm_shield_on"),
                ) { Text("确认开启") }
            },
            dismissButton = {
                TextButton(onClick = { confirmShield = false }) { Text("取消") }
            },
        )
    }

    if (confirmServer) {
        AlertDialog(
            onDismissRequest = { confirmServer = false },
            title = { Text("修改服务器地址？") },
            text = {
                Text("将解除当前配对并清除本机设备凭证，需用新注册码重新配对。显示名会保留。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmServer = false
                        onChangeServerConfirmed(server)
                    },
                    modifier = Modifier.testTag("confirm_change_server"),
                ) { Text("确认并重新配对") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmServer = false
                    server = status.serverBase
                }) { Text("取消") }
            },
        )
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("解除配对？") },
            text = { Text("将清除本地凭证并停止上报循环，回到引导页。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnpair = false
                        onUnpair()
                    },
                    modifier = Modifier.testTag("confirm_unpair"),
                ) { Text("解除配对") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun StatusCard(status: UiStatus) {
    val badge = phaseLabel(status)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    badge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag("phase_badge"),
                )
            }
            Kv("最近同步", status.lastSyncAt ?: "—")
            Kv("前台", status.foregroundSummary ?: "（无前台）")
            Kv("媒体", status.mediaSummary ?: "（无媒体）")
            Kv("设备 ID", status.deviceId ?: "—")
            if (status.lastError != null) {
                Text(
                    status.lastError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // device token intentionally not shown
        }
    }
}

@Composable
private fun Kv(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(0.35f),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

fun phaseLabel(status: UiStatus): String = when {
    status.privacyShield -> "禁止视奸中"
    status.phase == AgentPhase.RUNNING && !status.paused -> "上报中"
    status.phase == AgentPhase.PAUSED || status.paused -> "已暂停"
    status.phase == AgentPhase.ERROR -> "错误"
    status.phase == AgentPhase.SETUP -> "未配对"
    else -> "已停止"
}

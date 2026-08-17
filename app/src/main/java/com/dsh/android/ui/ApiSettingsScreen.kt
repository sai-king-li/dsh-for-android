package com.dsh.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dsh.android.data.SettingsStore
import com.dsh.android.node.NodeManager
import com.dsh.android.node.NodeService
import kotlinx.coroutines.launch

private const val DEEPSEEK_PLATFORM_URL = "https://platform.deepseek.com/api_keys"
private const val DEFAULT_API_BASE = "https://api.deepseek.com"

@Composable
fun ApiSettingsScreen(settings: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val apiKey by settings.apiKey.collectAsStateWithLifecycle(initialValue = "")
    val apiBaseUrl by settings.apiBaseUrl.collectAsStateWithLifecycle(initialValue = "")
    val nodeState by NodeManager.state.collectAsStateWithLifecycle()

    var keyInput by remember { mutableStateOf(apiKey) }
    var baseInput by remember { mutableStateOf(apiBaseUrl) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("连接 API", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "填入 DeepSeek 官方 API Key 即可与 DeepSeek 对话。密钥仅保存在本机，并通过 DEEPSEEK_API_KEY 注入 dsh 服务。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = {
                        keyInput = it
                        saved = false
                    },
                    label = { Text("DeepSeek API Key") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = baseInput,
                    onValueChange = {
                        baseInput = it
                        saved = false
                    },
                    label = { Text("API 地址（可选）") },
                    placeholder = { Text(DEFAULT_API_BASE) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row {
                    Button(onClick = {
                        scope.launch {
                            settings.setApiKey(keyInput)
                            settings.setApiBaseUrl(baseInput)
                            saved = true
                            // Restart the server so the new key takes effect.
                            NodeManager.stopServer()
                            NodeService.start(context)
                            NodeManager.startServer()
                        }
                    }) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("保存并重启服务")
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(DEEPSEEK_PLATFORM_URL))
                        )
                    }) {
                        Icon(Icons.Filled.Link, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("获取 API Key")
                    }
                }
                if (saved) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已保存，服务正在重启…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text("服务状态", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                StatusLine(nodeState)
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text("如何获取 API Key", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. 打开 DeepSeek 开放平台（platform.deepseek.com）并注册登录。\n" +
                        "2. 进入「API Keys」页面，点击「创建 API Key」。\n" +
                        "3. 复制生成的密钥（sk- 开头），粘贴到上方输入框并保存。\n" +
                        "4. 密钥只保存在这台设备上，不会上传到任何其他服务器。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "提示：可在开放平台的「充值」页面购买额度；对话按 token 计费。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun StatusLine(nodeState: NodeManager.NodeState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (color, text) = when (nodeState.status) {
            NodeManager.Status.RUNNING -> MaterialTheme.colorScheme.primary to
                "运行中 · ${nodeState.url}"
            NodeManager.Status.ERROR -> MaterialTheme.colorScheme.error to
                "错误 · ${nodeState.message}"
            NodeManager.Status.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant to
                "已停止"
            NodeManager.Status.INSTALLING, NodeManager.Status.PREPARING -> MaterialTheme.colorScheme.tertiary to
                "安装中 ${nodeState.pct}% · ${nodeState.phase}"
            NodeManager.Status.STARTING -> MaterialTheme.colorScheme.tertiary to "启动中…"
            NodeManager.Status.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant to "未启动"
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontFamily = FontFamily.Monospace,
        )
    }
}

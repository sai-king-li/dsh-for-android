package com.dsh.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsh.android.node.NodeManager

@Composable
fun HelpScreen(nodeState: NodeManager.NodeState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("帮助与说明", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Section("关于 dsh") {
            Bullet("dsh（DeepSeek Harness）是 DeepSeek 的开放智能体框架：一个对话式的编程助手，具备文件读写、搜索、任务规划等能力。")
            Bullet("本应用在安卓设备内置 Node.js 运行环境，首次打开时自动执行 dsh 启动命令（npm 安装 @deepseek-ai/dsh 并运行 web 服务），随后在内置网页中呈现完整界面。")
            Bullet("项目主页：github.com/deepseek-ai/deepseek-harness")
        }

        Spacer(Modifier.height(12.dp))
        Section("使用说明") {
            Bullet("第一步 · 首次打开：保持联网，应用会自动安装 dsh 运行环境（约 50–150 MB），完成后自动进入对话界面。")
            Bullet("第二步 · 连接 API：进入「API 连接」页，粘贴 DeepSeek API Key 并保存；服务会自动重启以生效。")
            Bullet("第三步 · 开始对话：在「对话」页与 dsh 交流，它会自动规划并使用工具完成任务。")
            Bullet("停止/重启：对话页右上角可停止服务；通知栏也有「停止」按钮。再次打开应用会自动恢复。")
        }

        Spacer(Modifier.height(12.dp))
        Section("常见问题") {
            Faq("首次启动需要联网吗？", "需要。首次启动通过 npm 安装 dsh 包；安装完成后，只要不清理应用数据，之后的启动可以离线进行。")
            Faq("API Key 在哪里获取？", "打开「API 连接」页，点击「获取 API Key」直达 DeepSeek 开放平台（platform.deepseek.com）。")
            Faq("为什么无法处理图片附件？", "图片处理依赖原生图像库（sharp），目前没有安卓构建，因此手机端不支持图片附件，纯文本对话不受影响。")
            Faq("手机端没有 bash，部分工具不可用？", "应用以「危险全权限」模式运行，文件类工具可正常工作；需要 bash 的终端类工具在手机上不可用，这是平台限制。")
            Faq("数据保存在哪里？", "全部数据保存在应用私有目录（files 目录），包括 API Key、会话记录与 .dsh 配置，不会上传到第三方。")
        }

        Spacer(Modifier.height(12.dp))
        Section("运行日志") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = nodeState.log.takeLast(40).joinToString("\n").ifBlank { "（暂无日志）" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Section("关于") {
            Bullet("Dsh for Android v1.0.0")
            Bullet("内置 Node.js 24 LTS（Termux 构建）与 npm")
            Bullet("dsh 版本：0.1.0-rc.6（可在 bootstrap 中通过 DSH_ANDROID_DSH_VERSION 调整）")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text(
        text = "•  $text",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Faq(question: String, answer: String) {
    Text(question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(2.dp))
    Text(
        answer,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp),
    )
    HorizontalDivider(Modifier.padding(bottom = 10.dp))
}

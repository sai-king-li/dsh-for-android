package com.dsh.android.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.android.node.NodeManager
import com.dsh.android.node.NodeManager.NodeState
import com.dsh.android.node.NodeManager.Status
import com.dsh.android.node.NodeService

@Composable
fun ChatTab(nodeState: NodeState) {
    when (nodeState.status) {
        Status.RUNNING -> ChatWebView(url = nodeState.url)
        Status.IDLE, Status.PREPARING, Status.INSTALLING, Status.STARTING ->
            StatusPanel(nodeState)
        Status.STOPPED, Status.ERROR ->
            StatusPanel(nodeState, showActions = true)
    }
}

@Composable
private fun StatusPanel(nodeState: NodeState, showActions: Boolean = false) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        when (nodeState.status) {
            Status.ERROR -> {
                Text("出错了", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    nodeState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Status.STOPPED -> {
                Text("服务已停止", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    nodeState.message.ifBlank { "点击下方按钮重新启动 dsh" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Status.IDLE -> {
                Text("准备启动", style = MaterialTheme.typography.titleLarge)
            }
            else -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    nodeState.phase.ifBlank { "启动中…" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (nodeState.status == Status.INSTALLING || nodeState.status == Status.PREPARING) {
                    LinearProgressIndicator(
                        progress = { nodeState.pct / 100f },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                    )
                }
                Text(
                    nodeState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        // Recent log tail (compact).
        if (nodeState.log.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = nodeState.log.takeLast(6).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Row {
            if (showActions) {
                Button(onClick = {
                    NodeService.start(context)
                    NodeManager.startServer()
                }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("重新启动")
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { NodeManager.stopServer() }) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("停止")
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChatWebView(url: String) {
    val context = LocalContext.current
    var canGoBack by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = 0 // WebSettings.MIXED_CONTENT_NEVER (loopback is plain http)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false // keep navigation inside the embedded UI
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    canGoBack = view?.canGoBack() == true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    canGoBack = view?.canGoBack() == true
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
        }
    }

    LaunchedEffect(url, refreshKey) {
        if (url.isNotBlank()) {
            webView.loadUrl(url)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Compact toolbar: address + reload + stop.
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = url.ifBlank { "dsh" },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { refreshKey++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
                IconButton(onClick = {
                    // Stop the server and the foreground service together.
                    NodeService.stop(context)
                }) {
                    Icon(Icons.Filled.Stop, contentDescription = "停止")
                }
            }
        }
        Box(Modifier.weight(1f)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}

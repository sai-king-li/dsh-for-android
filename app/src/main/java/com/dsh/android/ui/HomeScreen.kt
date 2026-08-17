package com.dsh.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dsh.android.data.SettingsStore
import com.dsh.android.node.NodeManager
import com.dsh.android.node.NodeService

@Composable
fun HomeScreen(settings: SettingsStore) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val nodeState by NodeManager.state.collectAsStateWithLifecycle()

    // Auto-start the embedded server unless the user turned it off.
    val autoStart by settings.autoStart.collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(autoStart) {
        if (autoStart && nodeState.status == NodeManager.Status.IDLE) {
            NodeService.start(context)
            NodeManager.startServer()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TabItem(Icons.Filled.Chat, "对话", 0, tab) { tab = 0 }
                TabItem(Icons.Filled.Key, "API 连接", 1, tab) { tab = 1 }
                TabItem(Icons.Filled.HelpOutline, "帮助", 2, tab) { tab = 2 }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> ChatTab(nodeState)
                1 -> ApiSettingsScreen(settings = settings)
                else -> HelpScreen(nodeState = nodeState)
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(icon: ImageVector, label: String, index: Int, current: Int, onClick: () -> Unit) {
    NavigationBarItem(
        selected = current == index,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
    )
}

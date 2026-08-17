package com.dsh.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dsh.android.data.SettingsStore
import com.dsh.android.node.NodeManager
import com.dsh.android.node.NodeService
import com.dsh.android.ui.HomeScreen
import com.dsh.android.ui.OnboardingScreen
import com.dsh.android.ui.theme.DshTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(applicationContext)
        NodeManager.init(applicationContext)

        requestNotificationPermissionIfNeeded()
        setContent {
            DshTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DshRoot(settings)
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun DshRoot(settings: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showOnboarding by remember { mutableStateOf(false) }
    val onboarded by settings.onboarded.collectAsStateWithLifecycle(initialValue = false)

    LaunchedEffect(onboarded) {
        showOnboarding = !onboarded
    }

    if (showOnboarding) {
        OnboardingScreen(onFinish = {
            scope.launch {
                settings.setOnboarded(true)
                NodeManager.startServer()
                NodeService.start(context)
                showOnboarding = false
            }
        })
    } else {
        HomeScreen(settings = settings)
    }
}

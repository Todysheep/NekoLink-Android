package app.nekolink.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import app.nekolink.android.collector.AndroidCollector
import app.nekolink.android.ui.AppViewModel
import app.nekolink.android.ui.NekoTheme
import app.nekolink.android.ui.SettingsScreen
import app.nekolink.android.ui.SetupScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotifIfNeeded()
        setContent {
            NekoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by vm.state.collectAsState()
                    if (!state.status.paired) {
                        SetupScreen(
                            status = state.status,
                            busy = state.busy,
                            message = state.message,
                            onPair = { server, name, code -> vm.pair(server, name, code) },
                        )
                    } else {
                        SettingsScreen(
                            status = state.status,
                            busy = state.busy,
                            message = state.message,
                            usageAccessGranted = state.usageAccessGranted,
                            notificationListenerGranted = state.notificationListenerGranted,
                            onPauseToggle = { vm.setPaused(it) },
                            onSaveDisplayName = { vm.saveDisplayName(it) },
                            onShowSystem = { vm.setShowSystemBackground(it) },
                            onAutostart = { vm.setAutostart(it) },
                            onUnpair = { vm.unpair() },
                            onChangeServerConfirmed = { vm.changeServerUrlConfirmed(it) },
                            onShield = { vm.setPrivacyShield(it) },
                            onOpenUsageAccess = {
                                startActivity(AndroidCollector.usageAccessSettingsIntent())
                            },
                            onOpenNotificationAccess = {
                                startActivity(
                                    AndroidCollector.notificationListenerSettingsIntent(this@MainActivity),
                                )
                            },
                            onStopService = {
                                vm.stopServiceAndFinish()
                                finish()
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
        vm.refreshPrivacyShieldFromBoard()
    }

    private fun requestNotifIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

package com.carmap.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.launcher.MapIntentLauncher
import com.carmap.bridge.model.DeviceRole
import com.carmap.bridge.network.BlePermissionsHelper
import com.carmap.bridge.network.CarBridgeConnectionManager
import com.carmap.bridge.service.BoxReceiverService
import com.carmap.bridge.ui.components.CockpitFloatingNavBar
import com.carmap.bridge.ui.components.CockpitNavTab
import com.carmap.bridge.ui.screens.BoxDashboardScreen
import com.carmap.bridge.ui.screens.PhoneSenderScreen
import com.carmap.bridge.ui.screens.SettingsScreen
import com.carmap.bridge.ui.theme.CarMapTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private var onBlePermResult: (() -> Unit)? = null
    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onBlePermResult?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            val state by BridgeRepository.state.collectAsState()
            val scope = rememberCoroutineScope()
            val lifecycleOwner = LocalLifecycleOwner.current
            var selectedTab by rememberSaveable {
                mutableIntStateOf(if (state.role == DeviceRole.CAR_BOX_RECEIVER) 1 else 0)
            }
            var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(this)) }

            onBlePermResult = {
                CarBridgeConnectionManager.refreshBle(BridgeRepository.state.value.bleEnabled)
                BoxReceiverService.refreshBle(this@MainActivity)
            }

            LaunchedEffect(Unit) {
                val isEmulator = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                    Build.PRODUCT.contains("sdk", ignoreCase = true) ||
                    Build.HARDWARE.contains("ranchu", ignoreCase = true)
                val shouldAutoStartBox = BridgeRepository.state.value.role == DeviceRole.CAR_BOX_RECEIVER || isEmulator
                if (shouldAutoStartBox && !BridgeRepository.state.value.boxServiceRunning) {
                    BoxReceiverService.start(this@MainActivity)
                }
            }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        canDrawOverlays = Settings.canDrawOverlays(this@MainActivity)
                        CarBridgeConnectionManager.probeNow()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                CarBridgeConnectionManager.retainDiscovery(this@MainActivity)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    CarBridgeConnectionManager.releaseDiscovery()
                }
            }

            val tabs = remember {
                listOf(
                    CockpitNavTab("Gửi đi", Icons.AutoMirrored.Rounded.Send),
                    CockpitNavTab("Trên Xe", Icons.Rounded.DirectionsCar),
                    CockpitNavTab("Cài đặt", Icons.Rounded.Settings)
                )
            }

            val installedMaps = remember { MapIntentLauncher.isAnyMapAppInstalled(this) }

            CarMapTheme(themeMode = state.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        AnimatedContent(
                            targetState = selectedTab,
                            transitionSpec = {
                                fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                            },
                            label = "carMapTabTransition",
                            modifier = Modifier.fillMaxSize()
                        ) { tab ->
                            when (tab) {
                                0 -> PhoneSenderScreen(
                                    state = state,
                                    onSendLink = { rawText, target ->
                                        scope.launch {
                                            val targetBox = state.pairedBox ?: state.discoveredBoxes.firstOrNull()
                                            BridgeRepository.setStatusBanner(
                                                if (targetBox != null) "Đang gửi tới ${targetBox.name}..."
                                                else "Đang tìm Box để gửi..."
                                            )
                                            val res = CarBridgeConnectionManager.sendOrQueue(
                                                context = this@MainActivity,
                                                primaryBox = targetBox,
                                                rawText = rawText,
                                                preferredTarget = target
                                            )
                                            res.onFailure {
                                                BridgeRepository.setStatusBanner(it.message ?: "Gửi thất bại")
                                            }
                                        }
                                    },
                                    onOpenSystemShareSheet = { rawText ->
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, rawText)
                                        }
                                        startActivity(Intent.createChooser(sendIntent, "Chia sẻ đường liên kết"))
                                    },
                                    onOpenSettings = { selectedTab = 2 }
                                )

                                1 -> BoxDashboardScreen(
                                    state = state,
                                    canDrawOverlays = canDrawOverlays,
                                    onToggleBoxService = { enable ->
                                        if (enable) BoxReceiverService.start(this@MainActivity)
                                        else BoxReceiverService.stop(this@MainActivity)
                                    },
                                    onOpenSettings = { selectedTab = 2 },
                                    onReplayRecord = { rec ->
                                        val spec = MapIntentLauncher.buildLaunchSpec(
                                            context = this@MainActivity,
                                            parsed = rec.parsed,
                                            preferredTarget = rec.targetProvider
                                        )
                                        val openedReal = if (!state.simulatePreviewOnEmulator && spec.isPackageInstalled) {
                                            MapIntentLauncher.launchRealMapApp(this@MainActivity, spec)
                                        } else {
                                            false
                                        }
                                        if (!openedReal) {
                                            NavigationDispatchActivity.launch(
                                                context = this@MainActivity,
                                                parsed = rec.parsed,
                                                spec = spec,
                                                senderName = rec.peerName,
                                                remoteIp = "Replay"
                                            )
                                        }
                                    }
                                )

                                else -> SettingsScreen(
                                    state = state,
                                    canDrawOverlays = canDrawOverlays,
                                    installedMaps = installedMaps,
                                    bleStatus = BlePermissionsHelper.inspectStatus(
                                        context = this@MainActivity,
                                        advertisingActive = state.bleAdvertising,
                                        scanningActive = state.bleScanning
                                    ),
                                    onToggleBoxService = { enable ->
                                        if (enable) BoxReceiverService.start(this@MainActivity)
                                        else BoxReceiverService.stop(this@MainActivity)
                                    },
                                    onToggleBle = { enable ->
                                        BridgeRepository.setBleEnabled(enable)
                                        CarBridgeConnectionManager.refreshBle(enable)
                                        BoxReceiverService.refreshBle(this@MainActivity)
                                    },
                                    onRequestBlePermissions = {
                                        blePermissionLauncher.launch(BlePermissionsHelper.requiredPermissions())
                                    },
                                    onRequestOverlayPermission = {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:$packageName")
                                        )
                                        startActivity(intent)
                                    },
                                    onRequestNotificationPermission = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                )
                            }
                        }

                        CockpitFloatingNavBar(
                            tabs = tabs,
                            selectedIndex = selectedTab,
                            settingsHasBadge = !canDrawOverlays,
                            onSelect = { selectedTab = it },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

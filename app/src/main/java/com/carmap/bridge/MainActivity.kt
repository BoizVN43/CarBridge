package com.carmap.bridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
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
import com.carmap.bridge.model.LinkKind
import com.carmap.bridge.network.BlePermissionsHelper
import com.carmap.bridge.network.CarBridgeConnectionManager
import com.carmap.bridge.parser.MediaLinkHelper
import com.carmap.bridge.service.BoxReceiverService
import com.carmap.bridge.ui.components.AppUpdateDialog
import com.carmap.bridge.ui.components.CockpitFloatingNavBar
import com.carmap.bridge.ui.components.CockpitNavTab
import com.carmap.bridge.ui.screens.BoxDashboardScreen
import com.carmap.bridge.ui.screens.PhoneSenderScreen
import com.carmap.bridge.ui.screens.RoleSelectionScreen
import com.carmap.bridge.ui.screens.SettingsScreen
import com.carmap.bridge.ui.theme.CarMapTheme
import com.carmap.bridge.update.AppUpdater
import com.carmap.bridge.util.PairingCodeHelper
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

    private var lastDismissedClipboard: String? = null
    private val clipboardSuggestionState = mutableStateOf<String?>(null)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            inspectClipboardForSuggestion()
        }
    }

    private fun inspectClipboardForSuggestion() {
        val state = BridgeRepository.state.value
        if (!state.roleConfigured || state.role != DeviceRole.PHONE_SENDER) return
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (text.isBlank() || text == lastDismissedClipboard) return
            if (PairingCodeHelper.parsePairingUri(text) != null || MediaLinkHelper.isLikelyMapOrMediaLink(text)) {
                clipboardSuggestionState.value = text
            }
        }
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
            val otaState by AppUpdater.state.collectAsState()
            val availableUpdate by AppUpdater.availableUpdate.collectAsState()
            val clipboardSuggestion by clipboardSuggestionState
            val scope = rememberCoroutineScope()
            val lifecycleOwner = LocalLifecycleOwner.current
            var selectedTab by rememberSaveable { mutableIntStateOf(0) }
            var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(this)) }

            onBlePermResult = {
                CarBridgeConnectionManager.refreshBle(BridgeRepository.state.value.bleEnabled)
                BoxReceiverService.refreshBle(this@MainActivity)
            }

            LaunchedEffect(Unit) {
                AppUpdater.cleanupOldCachedApks(this@MainActivity)
                AppUpdater.loadCachedAvailability(this@MainActivity)
                AppUpdater.checkForUpdate(
                    context = this@MainActivity,
                    silent = true,
                    autoShowDialog = true
                )
            }

            LaunchedEffect(state.roleConfigured, state.role) {
                if (state.roleConfigured && state.role == DeviceRole.CAR_BOX_RECEIVER && !state.boxServiceRunning) {
                    BoxReceiverService.start(this@MainActivity)
                }
            }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        canDrawOverlays = Settings.canDrawOverlays(this@MainActivity)
                        CarBridgeConnectionManager.probeNow()
                        inspectClipboardForSuggestion()
                        scope.launch {
                            AppUpdater.checkForUpdate(
                                context = this@MainActivity,
                                silent = true,
                                autoShowDialog = true
                            )
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                CarBridgeConnectionManager.retainDiscovery(this@MainActivity)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    CarBridgeConnectionManager.releaseDiscovery()
                }
            }

            val tabs = remember(state.role) {
                if (state.role == DeviceRole.PHONE_SENDER) {
                    listOf(
                        CockpitNavTab("Gửi đi", Icons.AutoMirrored.Rounded.Send),
                        CockpitNavTab("Cài đặt", Icons.Rounded.Settings)
                    )
                } else {
                    listOf(
                        CockpitNavTab("Trên Xe", Icons.Rounded.DirectionsCar),
                        CockpitNavTab("Cài đặt", Icons.Rounded.Settings)
                    )
                }
            }

            val installedMaps = remember { MapIntentLauncher.isAnyMapAppInstalled(this) }

            val handleSelectRole: (DeviceRole) -> Unit = { role ->
                BridgeRepository.setRole(role)
                selectedTab = 0
                if (role == DeviceRole.CAR_BOX_RECEIVER) {
                    BoxReceiverService.start(this@MainActivity)
                } else {
                    BoxReceiverService.stop(this@MainActivity)
                }
            }

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
                        if (!state.roleConfigured) {
                            RoleSelectionScreen(onSelectRole = handleSelectRole)
                        } else {
                            AnimatedContent(
                                targetState = Pair(state.role, selectedTab),
                                transitionSpec = {
                                    fadeIn(tween(150)) togetherWith fadeOut(tween(110))
                                },
                                label = "carBridgeRoleTabTransition",
                                modifier = Modifier.fillMaxSize()
                            ) { (role, tab) ->
                                if (tab == 0) {
                                    if (role == DeviceRole.PHONE_SENDER) {
                                        PhoneSenderScreen(
                                            state = state,
                                            clipboardSuggestion = clipboardSuggestion,
                                            onDismissClipboard = {
                                                lastDismissedClipboard = clipboardSuggestion
                                                clipboardSuggestionState.value = null
                                            },
                                            onAcceptClipboardPairing = { rawCode ->
                                                PairingCodeHelper.parsePairingUri(rawCode)?.let { parsed ->
                                                    if (parsed.pin.isNotBlank()) {
                                                        BridgeRepository.updateSettings(pin = parsed.pin)
                                                    }
                                                    BridgeRepository.upsertDiscoveredBox(parsed.box)
                                                    BridgeRepository.selectPairedBox(this@MainActivity, parsed.box)
                                                }
                                            },
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
                                            onSendClipboardText = { text ->
                                                scope.launch {
                                                    CarBridgeConnectionManager.sendClipboardToBox(this@MainActivity, text)
                                                }
                                            },
                                            onSendRemoteCommand = { cmd ->
                                                scope.launch {
                                                    CarBridgeConnectionManager.sendRemoteCommand(this@MainActivity, cmd)
                                                }
                                            },
                                            onPingBox = {
                                                scope.launch {
                                                    CarBridgeConnectionManager.pingActiveBox(this@MainActivity)
                                                }
                                            },
                                            onClearPendingQueue = {
                                                CarBridgeConnectionManager.clearPendingOutbox()
                                            },
                                            onOpenSystemShareSheet = { rawText ->
                                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, rawText)
                                                }
                                                startActivity(Intent.createChooser(sendIntent, "Chia sẻ đường liên kết"))
                                            },
                                            onOpenSettings = { selectedTab = 1 }
                                        )
                                    } else {
                                        BoxDashboardScreen(
                                            state = state,
                                            canDrawOverlays = canDrawOverlays,
                                            onToggleBoxService = { enable ->
                                                if (enable) BoxReceiverService.start(this@MainActivity)
                                                else BoxReceiverService.stop(this@MainActivity)
                                            },
                                            onOpenSettings = { selectedTab = 1 },
                                            onReplayRecord = { rec ->
                                                when (rec.parsed.kind) {
                                                    LinkKind.MEDIA -> {
                                                        runCatching {
                                                            startActivity(
                                                                Intent(Intent.ACTION_VIEW, Uri.parse(rec.parsed.resolvedUrl))
                                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                            )
                                                        }
                                                    }
                                                    LinkKind.CLIPBOARD -> {
                                                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                        cm?.setPrimaryClip(ClipData.newPlainText("CarBridge", rec.parsed.rawInput))
                                                        Toast.makeText(this@MainActivity, "Đã chép lại vào Clipboard", Toast.LENGTH_SHORT).show()
                                                    }
                                                    else -> {
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
                                                }
                                            }
                                        )
                                    }
                                } else {
                                    SettingsScreen(
                                        state = state,
                                        canDrawOverlays = canDrawOverlays,
                                        installedMaps = installedMaps,
                                        bleStatus = BlePermissionsHelper.inspectStatus(
                                            context = this@MainActivity,
                                            advertisingActive = state.bleAdvertising,
                                            scanningActive = state.bleScanning
                                        ),
                                        availableUpdate = availableUpdate,
                                        onSwitchRole = handleSelectRole,
                                        onCheckUpdate = {
                                            if (availableUpdate != null) {
                                                AppUpdater.openUpdateDialog()
                                            } else {
                                                scope.launch {
                                                    AppUpdater.checkForUpdate(
                                                        context = this@MainActivity,
                                                        silent = false,
                                                        forceBypassCooldown = true
                                                    )
                                                }
                                            }
                                        },
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
                                selectedIndex = selectedTab.coerceIn(0, tabs.lastIndex),
                                settingsHasBadge = (state.role == DeviceRole.CAR_BOX_RECEIVER && !canDrawOverlays) || availableUpdate != null,
                                onSelect = { selectedTab = it },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        AppUpdateDialog(
                            state = otaState,
                            onDismiss = { AppUpdater.dismissDialog() }
                        )
                    }
                }
            }
        }
    }
}

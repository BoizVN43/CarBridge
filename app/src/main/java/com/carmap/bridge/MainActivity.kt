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
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.launcher.MapIntentLauncher
import com.carmap.bridge.model.DeviceRole
import com.carmap.bridge.model.LinkKind
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.network.BlePermissionsHelper
import com.carmap.bridge.network.CarBridgeConnectionManager
import com.carmap.bridge.parser.MediaLinkHelper
import com.carmap.bridge.service.BoxReceiverService
import com.carmap.bridge.service.BoxWatchdogJobService
import com.carmap.bridge.ui.components.AppUpdateDialog
import com.carmap.bridge.ui.components.CockpitFloatingNavBar
import com.carmap.bridge.ui.components.CockpitNavTab
import com.carmap.bridge.ui.screens.BoxDashboardScreen
import com.carmap.bridge.ui.screens.PhoneSenderScreen
import com.carmap.bridge.ui.screens.RoleSelectionScreen
import com.carmap.bridge.ui.screens.SettingsScreen
import com.carmap.bridge.ui.theme.CarMapTheme
import com.carmap.bridge.update.AppUpdater
import com.carmap.bridge.util.CarBridgeNotifier
import com.carmap.bridge.util.PairingCodeHelper
import com.carmap.bridge.util.RootHelper
import com.carmap.bridge.widget.CarBridgeWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private var onNotifPermResult: ((Boolean) -> Unit)? = null
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        onNotifPermResult?.invoke(granted)
    }

    private var onBlePermResult: (() -> Unit)? = null
    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onBlePermResult?.invoke()
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.any { it }
        if (granted) {
            val saved = CarBridgeConnectionManager.saveParkingPinNow(this, "Vị trí đỗ xe")
            BridgeRepository.setStatusBanner(
                if (saved) "Đã ghim vị trí đỗ xe hiện tại" else "Chưa lấy được tín hiệu GPS, hãy bật Vị trí"
            )
        }
    }

    private val voiceRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isNotBlank()) {
            lifecycleScope.launch {
                val state = BridgeRepository.state.value
                BridgeRepository.setStatusBanner("Giọng nói: \"$spoken\" — đang gửi lên Box...")
                val res = CarBridgeConnectionManager.sendOrQueue(
                    context = this@MainActivity,
                    primaryBox = state.pairedBox ?: state.discoveredBoxes.firstOrNull(),
                    rawText = spoken,
                    preferredTarget = MapProvider.AUTO
                )
                res.onFailure { err ->
                    val msg = err.message ?: "Chưa kết nối được với Android Box"
                    BridgeRepository.setStatusBanner(msg)
                    CarBridgeNotifier.notifyBoxNotConnected(this@MainActivity, spoken, msg)
                }
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            CarBridgeConnectionManager.sendFileUriToBox(this@MainActivity, uri)
        }
    }

    private var lastDismissedClipboard: String? = null
    private val clipboardSuggestionState = mutableStateOf<String?>(null)
    private var pendingSendClipboardOnFocus: Boolean = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingActionIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (pendingSendClipboardOnFocus) {
                pendingSendClipboardOnFocus = false
                sendClipboardNow()
            }
            val state = BridgeRepository.state.value
            if (state.roleConfigured && state.role == DeviceRole.CAR_BOX_RECEIVER) {
                runCatching {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
                    BridgeRepository.updateCachedBoxClipboard(text)
                }
            } else {
                inspectClipboardForSuggestion()
            }
        }
    }

    private fun sendClipboardNow() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (clip.isNotBlank()) {
            lifecycleScope.launch {
                CarBridgeConnectionManager.sendClipboardToBox(this@MainActivity, clip)
            }
        } else {
            Toast.makeText(this, "Clipboard đang trống", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói địa chỉ hoặc địa điểm muốn đến...")
        }
        runCatching { voiceRecognizerLauncher.launch(intent) }.onFailure {
            Toast.makeText(this, "Thiết bị chưa cài nhận diện giọng nói Google", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleIncomingActionIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(CarBridgeNotifier.EXTRA_OPEN_OTA, false)) {
            intent.removeExtra(CarBridgeNotifier.EXTRA_OPEN_OTA)
            AppUpdater.loadCachedAvailability(this)
            if (AppUpdater.availableUpdate.value != null) {
                AppUpdater.openUpdateDialog()
            } else {
                lifecycleScope.launch {
                    AppUpdater.checkForUpdate(this@MainActivity, silent = false, forceBypassCooldown = true)
                }
            }
        }
        if (intent.getBooleanExtra(CarBridgeNotifier.EXTRA_START_VOICE, false)) {
            intent.removeExtra(CarBridgeNotifier.EXTRA_START_VOICE)
            startVoiceSearch()
        }
        if (intent.getBooleanExtra(CarBridgeNotifier.EXTRA_SEND_CLIPBOARD, false)) {
            intent.removeExtra(CarBridgeNotifier.EXTRA_SEND_CLIPBOARD)
            if (hasWindowFocus()) {
                sendClipboardNow()
            } else {
                pendingSendClipboardOnFocus = true
            }
        }
        val savedLink = intent.getStringExtra(CarBridgeWidgetProvider.EXTRA_SEND_SAVED_LINK)
        if (!savedLink.isNullOrBlank()) {
            intent.removeExtra(CarBridgeWidgetProvider.EXTRA_SEND_SAVED_LINK)
            lifecycleScope.launch {
                val st = BridgeRepository.state.value
                val res = CarBridgeConnectionManager.sendOrQueue(
                    context = this@MainActivity,
                    primaryBox = st.pairedBox ?: st.discoveredBoxes.firstOrNull(),
                    rawText = savedLink,
                    preferredTarget = MapProvider.AUTO
                )
                res.onFailure { err ->
                    val msg = err.message ?: "Chưa kết nối được với Android Box"
                    BridgeRepository.setStatusBanner(msg)
                    CarBridgeNotifier.notifyBoxNotConnected(this@MainActivity, "Địa điểm đã ghim", msg)
                }
            }
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

        handleIncomingActionIntent(intent)

        setContent {
            val state by BridgeRepository.state.collectAsState()
            val otaState by AppUpdater.state.collectAsState()
            val availableUpdate by AppUpdater.availableUpdate.collectAsState()
            val clipboardSuggestion by clipboardSuggestionState
            val scope = rememberCoroutineScope()
            val lifecycleOwner = LocalLifecycleOwner.current
            var selectedTab by rememberSaveable { mutableIntStateOf(0) }
            var sendFailedPopupMessage by remember { mutableStateOf<String?>(null) }
            var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
            var isBatteryIgnored by remember {
                mutableStateOf(
                    (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                        ?.isIgnoringBatteryOptimizations(packageName) == true
                )
            }
            var hasNotificationPerm by remember {
                mutableStateOf(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                    } else true
                )
            }

            onNotifPermResult = { granted -> hasNotificationPerm = granted }

            onBlePermResult = {
                val curState = BridgeRepository.state.value
                if (curState.roleConfigured && curState.role == DeviceRole.CAR_BOX_RECEIVER) {
                    BoxReceiverService.refreshBle(this@MainActivity)
                } else {
                    CarBridgeConnectionManager.refreshBle(curState.bleEnabled || curState.proximityOnly2m)
                }
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

            LaunchedEffect(state.roleConfigured, state.role, state.rootModeEnabled) {
                if (state.roleConfigured && state.role == DeviceRole.CAR_BOX_RECEIVER) {
                    if (!state.boxServiceRunning) {
                        BoxReceiverService.start(this@MainActivity)
                    }
                    if (state.rootModeEnabled) {
                        val hasRoot = RootHelper.checkRootAccess()
                        BridgeRepository.setRootDetected(hasRoot)
                    }
                }
            }

            DisposableEffect(lifecycleOwner, state.roleConfigured, state.role) {
                val shouldDiscover = state.roleConfigured && state.role == DeviceRole.PHONE_SENDER
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        canDrawOverlays = Settings.canDrawOverlays(this@MainActivity)
                        isBatteryIgnored = (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                            ?.isIgnoringBatteryOptimizations(packageName) == true
                        hasNotificationPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) ==
                                PackageManager.PERMISSION_GRANTED
                        } else true
                        if (shouldDiscover) {
                            CarBridgeConnectionManager.probeNow()
                            inspectClipboardForSuggestion()
                        }
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
                if (shouldDiscover) {
                    CarBridgeConnectionManager.retainDiscovery(this@MainActivity)
                }
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    if (shouldDiscover) {
                        CarBridgeConnectionManager.releaseDiscovery()
                    }
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

            var installedMaps by remember { mutableStateOf<Map<MapProvider, Boolean>>(emptyMap()) }
            LaunchedEffect(Unit) {
                installedMaps = withContext(Dispatchers.IO) {
                    MapIntentLauncher.isAnyMapAppInstalled(this@MainActivity)
                }
            }
            val bleStatus = remember(state.bleAdvertising, state.bleScanning, state.bleEnabled) {
                BlePermissionsHelper.inspectStatus(
                    context = this@MainActivity,
                    advertisingActive = state.bleAdvertising,
                    scanningActive = state.bleScanning
                )
            }

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
                                                    res.onFailure { err ->
                                                        val errMsg = err.message ?: "Chưa kết nối được với Android Box"
                                                        BridgeRepository.setStatusBanner(errMsg)
                                                        sendFailedPopupMessage = errMsg
                                                        CarBridgeNotifier.notifyBoxNotConnected(
                                                            context = this@MainActivity,
                                                            destinationTitle = rawText.lines().firstOrNull().orEmpty().take(48),
                                                            reason = errMsg
                                                        )
                                                    }
                                                }
                                            },
                                            onStartVoiceSearch = { startVoiceSearch() },
                                            onSendClipboardText = { text ->
                                                scope.launch {
                                                    CarBridgeConnectionManager.sendClipboardToBox(this@MainActivity, text)
                                                }
                                            },
                                            onPullClipboardFromBox = {
                                                scope.launch {
                                                    CarBridgeConnectionManager.pullClipboardFromBox(this@MainActivity)
                                                }
                                            },
                                            onPickFileToSend = {
                                                filePickerLauncher.launch(
                                                    arrayOf(
                                                        "application/vnd.android.package-archive",
                                                        "image/*"
                                                    )
                                                )
                                            },
                                            onSaveParkingPin = {
                                                val ok = CarBridgeConnectionManager.saveParkingPinNow(this@MainActivity, "Vị trí đỗ xe")
                                                if (ok) {
                                                    BridgeRepository.setStatusBanner("Đã ghim vị trí đỗ xe hiện tại")
                                                } else {
                                                    locationPermissionLauncher.launch(
                                                        arrayOf(
                                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                                        )
                                                    )
                                                }
                                            },
                                            onFindParkedCar = {
                                                val pin = state.parkingPin ?: return@PhoneSenderScreen
                                                val uri = Uri.parse("google.navigation:q=${pin.lat},${pin.lng}&mode=w")
                                                val navIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                                                    setPackage("com.google.android.apps.maps")
                                                }
                                                runCatching { startActivity(navIntent) }.onFailure {
                                                    runCatching {
                                                        startActivity(
                                                            Intent(
                                                                Intent.ACTION_VIEW,
                                                                Uri.parse("geo:${pin.lat},${pin.lng}?q=${pin.lat},${pin.lng}(Xe)")
                                                            )
                                                        )
                                                    }
                                                }
                                            },
                                            onSendBoxCommand = { cmd, label ->
                                                scope.launch {
                                                    val res = CarBridgeConnectionManager.sendBoxSystemCommand(
                                                        context = this@MainActivity,
                                                        cmd = cmd,
                                                        label = label
                                                    )
                                                    val msg = res.getOrElse { it.message ?: "$label thất bại" }
                                                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
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
                                                    LinkKind.MEDIA, LinkKind.WEB -> {
                                                        runCatching {
                                                            startActivity(
                                                                Intent(Intent.ACTION_VIEW, Uri.parse(rec.parsed.resolvedUrl))
                                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                            )
                                                        }
                                                    }
                                                    LinkKind.FILE -> {
                                                        val f = File(rec.parsed.resolvedUrl)
                                                        if (f.exists()) {
                                                            if (f.name.endsWith(".apk", ignoreCase = true)) {
                                                                AppUpdater.launchApkInstaller(this@MainActivity, f)
                                                            } else {
                                                                runCatching {
                                                                    val uri = FileProvider.getUriForFile(
                                                                        this@MainActivity,
                                                                        "${packageName}.fileprovider",
                                                                        f
                                                                    )
                                                                    startActivity(
                                                                        Intent(Intent.ACTION_VIEW).apply {
                                                                            setDataAndType(uri, "image/*")
                                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                                        }
                                                                    )
                                                                }
                                                            }
                                                        } else {
                                                            Toast.makeText(this@MainActivity, "Tệp không còn trong bộ nhớ đệm", Toast.LENGTH_SHORT).show()
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
                                        isBatteryIgnored = isBatteryIgnored,
                                        hasNotificationPerm = hasNotificationPerm,
                                        installedMaps = installedMaps,
                                        bleStatus = bleStatus,
                                        availableUpdate = availableUpdate,
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
                                        onToggleAutoStartOnBoot = { enable ->
                                            BridgeRepository.setAutoStartOnBoot(enable)
                                            if (enable) BoxWatchdogJobService.schedule(this@MainActivity)
                                            else BoxWatchdogJobService.cancel(this@MainActivity)
                                        },
                                        onToggleBle = { enable ->
                                            BridgeRepository.setBleEnabled(enable)
                                            val curState = BridgeRepository.state.value
                                            if (curState.roleConfigured && curState.role == DeviceRole.CAR_BOX_RECEIVER) {
                                                BoxReceiverService.refreshBle(this@MainActivity)
                                            } else {
                                                CarBridgeConnectionManager.refreshBle(enable || curState.proximityOnly2m)
                                            }
                                        },
                                        onToggleProximity2m = { enable ->
                                            BridgeRepository.setProximityOnly2m(enable)
                                            if (enable && !BridgeRepository.state.value.bleEnabled) {
                                                BridgeRepository.setBleEnabled(true)
                                            }
                                            CarBridgeConnectionManager.refreshBle(enable || BridgeRepository.state.value.bleEnabled)
                                            CarBridgeConnectionManager.probeNow()
                                        },
                                        onToggleAutoConnectOnCarBt = { enable ->
                                            BridgeRepository.setAutoConnectOnCarBt(enable)
                                        },
                                        onReconnectNow = {
                                            scope.launch {
                                                CarBridgeConnectionManager.probeNow()
                                                val reconnected = CarBridgeConnectionManager.fastReconnectSavedEndpoints(this@MainActivity)
                                                BridgeRepository.setStatusBanner(
                                                    if (reconnected != null) {
                                                        "Đã kết nối lại ${reconnected.name} (${reconnected.channel.label})"
                                                    } else {
                                                        "Đang dò lại Android Box qua LAN / BLE..."
                                                    }
                                                )
                                            }
                                        },
                                        onRequestBlePermissions = {
                                            blePermissionLauncher.launch(BlePermissionsHelper.requiredPermissions())
                                        },
                                        onRequestOverlayPermission = {
                                            runCatching {
                                                val intent = Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:$packageName")
                                                )
                                                startActivity(intent)
                                            }.onFailure {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "ROM Box không có mục cấp quyền Overlay — hãy bật Chế độ Root nếu cần",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        },
                                        onRequestNotificationPermission = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        },
                                        onRequestBatteryOptimization = {
                                            runCatching {
                                                val intent = Intent(
                                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                    Uri.parse("package:$packageName")
                                                )
                                                startActivity(intent)
                                            }.onFailure {
                                                runCatching {
                                                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                                }
                                            }
                                        }
                                    )
                                }
                            }

                            val boxNeedsOverlay = state.role == DeviceRole.CAR_BOX_RECEIVER &&
                                !canDrawOverlays &&
                                !(state.rootModeEnabled && state.rootDetected)
                            CockpitFloatingNavBar(
                                tabs = tabs,
                                selectedIndex = selectedTab.coerceIn(0, tabs.lastIndex),
                                settingsHasBadge = boxNeedsOverlay || availableUpdate != null,
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

                        sendFailedPopupMessage?.let { errText ->
                            AlertDialog(
                                onDismissRequest = { sendFailedPopupMessage = null },
                                title = { Text("Chưa kết nối được với Android Box") },
                                text = {
                                    Text("$errText\n\nHãy đảm bảo CarBridge đang bật trên Android Box và 2 máy cùng Wi-Fi hoặc bật Bluetooth.")
                                },
                                confirmButton = {
                                    TextButton(onClick = { sendFailedPopupMessage = null }) {
                                        Text("Đã hiểu")
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            sendFailedPopupMessage = null
                                            selectedTab = 1
                                        }
                                    ) {
                                        Text("Mở Cài đặt")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

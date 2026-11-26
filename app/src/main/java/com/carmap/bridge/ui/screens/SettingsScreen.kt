package com.carmap.bridge.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.data.BridgeUiState
import com.carmap.bridge.model.DeviceRole
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.ThemeMode
import com.carmap.bridge.network.BleStatus
import com.carmap.bridge.ui.components.BoxQrPairingDialog
import com.carmap.bridge.ui.components.CockpitPill
import com.carmap.bridge.ui.components.PhoneQrPairInputDialog
import com.carmap.bridge.ui.components.QrCameraScannerDialog
import com.carmap.bridge.ui.components.SettingsActionRow
import com.carmap.bridge.ui.components.SettingsDivider
import com.carmap.bridge.ui.components.SettingsGroupCard
import com.carmap.bridge.ui.theme.AdaptiveMetrics
import com.carmap.bridge.ui.theme.CockpitAccents
import com.carmap.bridge.ui.theme.LocalAdaptiveMetrics
import com.carmap.bridge.ui.theme.LocalCockpitAccents
import com.carmap.bridge.update.AppUpdater
import com.carmap.bridge.update.RemoteReleaseInfo

@Composable
fun SettingsScreen(
    state: BridgeUiState,
    canDrawOverlays: Boolean,
    isBatteryIgnored: Boolean,
    installedMaps: Map<MapProvider, Boolean>,
    bleStatus: BleStatus,
    availableUpdate: RemoteReleaseInfo?,
    onCheckUpdate: () -> Unit,
    onToggleBoxService: (Boolean) -> Unit,
    onToggleAutoStartOnBoot: (Boolean) -> Unit,
    onToggleBle: (Boolean) -> Unit,
    onRequestBlePermissions: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestBatteryOptimization: () -> Unit
) {
    val context = LocalContext.current
    val accents = LocalCockpitAccents.current
    val adaptive = LocalAdaptiveMetrics.current
    var manualIp by remember { mutableStateOf("") }
    val installedVersion = remember { AppUpdater.getInstalledVersion(context) }
    val isPhone = state.role == DeviceRole.PHONE_SENDER

    val hasNotificationPerm = remember(state.boxServiceRunning) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        if (adaptive.isTwoColumn) {
            Column(
                modifier = Modifier
                    .widthIn(max = 1140.dp)
                    .fillMaxSize()
                    .padding(horizontal = adaptive.screenPadding)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
            ) {
                SettingsHeader(installedVersion.versionName, state.role, adaptive, accents)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = adaptive.navBottomPadding),
                        verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
                    ) {
                        OtaUpdateCard(
                            versionName = installedVersion.versionName,
                            availableUpdate = availableUpdate,
                            accents = accents,
                            onCheckUpdate = onCheckUpdate
                        )
                        if (isPhone) {
                            PhoneConnectionSettingsCard(
                                state = state,
                                manualIp = manualIp,
                                accents = accents,
                                adaptive = adaptive,
                                onManualIpChange = { manualIp = it },
                                onPairManual = {
                                    if (manualIp.isNotBlank()) {
                                        BridgeRepository.addManualBox(context, manualIp, "Android Box ($manualIp)")
                                        manualIp = ""
                                    }
                                },
                                onToggleBle = onToggleBle
                            )
                        } else {
                            BoxIdentitySettingsCard(state, adaptive, onToggleBle)
                            AppearanceCard(state, adaptive)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = adaptive.navBottomPadding),
                        verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
                    ) {
                        if (isPhone) {
                            PhonePermissionsCard(
                                bleStatus = bleStatus,
                                accents = accents,
                                onRequestBlePermissions = onRequestBlePermissions
                            )
                            AppearanceCard(state, adaptive)
                        } else {
                            AutoLaunchSettingsCard(state, accents, adaptive, onToggleBoxService, onToggleAutoStartOnBoot)
                            BoxPermissionsAndAppsCard(
                                canDrawOverlays = canDrawOverlays,
                                hasNotificationPerm = hasNotificationPerm,
                                isBatteryIgnored = isBatteryIgnored,
                                installedMaps = installedMaps,
                                bleStatus = bleStatus,
                                accents = accents,
                                onRequestOverlayPermission = onRequestOverlayPermission,
                                onRequestNotificationPermission = onRequestNotificationPermission,
                                onRequestBatteryOptimization = onRequestBatteryOptimization,
                                onRequestBlePermissions = onRequestBlePermissions
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = adaptive.screenPadding,
                    top = 6.dp,
                    end = adaptive.screenPadding,
                    bottom = adaptive.navBottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
            ) {
                item { SettingsHeader(installedVersion.versionName, state.role, adaptive, accents) }
                item {
                    OtaUpdateCard(
                        versionName = installedVersion.versionName,
                        availableUpdate = availableUpdate,
                        accents = accents,
                        onCheckUpdate = onCheckUpdate
                    )
                }
                if (isPhone) {
                    item {
                        PhoneConnectionSettingsCard(
                            state = state,
                            manualIp = manualIp,
                            accents = accents,
                            adaptive = adaptive,
                            onManualIpChange = { manualIp = it },
                            onPairManual = {
                                if (manualIp.isNotBlank()) {
                                    BridgeRepository.addManualBox(context, manualIp, "Android Box ($manualIp)")
                                    manualIp = ""
                                }
                            },
                            onToggleBle = onToggleBle
                        )
                    }
                    item {
                        PhonePermissionsCard(
                            bleStatus = bleStatus,
                            accents = accents,
                            onRequestBlePermissions = onRequestBlePermissions
                        )
                    }
                } else {
                    item { BoxIdentitySettingsCard(state, adaptive, onToggleBle) }
                    item { AutoLaunchSettingsCard(state, accents, adaptive, onToggleBoxService, onToggleAutoStartOnBoot) }
                    item {
                        BoxPermissionsAndAppsCard(
                            canDrawOverlays = canDrawOverlays,
                            hasNotificationPerm = hasNotificationPerm,
                            isBatteryIgnored = isBatteryIgnored,
                            installedMaps = installedMaps,
                            bleStatus = bleStatus,
                            accents = accents,
                            onRequestOverlayPermission = onRequestOverlayPermission,
                            onRequestNotificationPermission = onRequestNotificationPermission,
                            onRequestBatteryOptimization = onRequestBatteryOptimization,
                            onRequestBlePermissions = onRequestBlePermissions
                        )
                    }
                }
                item { AppearanceCard(state, adaptive) }
            }
        }
    }
}

@Composable
private fun SettingsHeader(
    versionName: String,
    role: DeviceRole,
    adaptive: AdaptiveMetrics,
    accents: CockpitAccents
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Cài đặt",
                fontSize = adaptive.sp(20f),
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = if (role == DeviceRole.PHONE_SENDER) "Chế độ Điện thoại" else "Chế độ Android Box",
                fontSize = adaptive.sp(11.5f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surface) {
            Text(
                text = "v$versionName",
                fontSize = adaptive.sp(11f),
                fontWeight = FontWeight.Bold,
                color = accents.emerald,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun OtaUpdateCard(
    versionName: String,
    availableUpdate: RemoteReleaseInfo?,
    accents: CockpitAccents,
    onCheckUpdate: () -> Unit
) {
    SettingsGroupCard(title = "CẬP NHẬT OTA") {
        SettingsActionRow(
            icon = Icons.Rounded.SystemUpdate,
            tint = if (availableUpdate != null) accents.emerald else accents.cyan,
            bg = if (availableUpdate != null) accents.emeraldContainer else accents.cyanContainer,
            title = if (availableUpdate != null) {
                "Có bản mới v${availableUpdate.versionName}"
            } else {
                "Kiểm tra cập nhật trực tuyến"
            },
            subtitle = if (availableUpdate != null) {
                "Nhấn để tải và cài đè từ GitHub Releases"
            } else {
                "Hiện tại: CarBridge v$versionName · GitHub Releases"
            },
            trailingText = if (availableUpdate != null) "Cập nhật" else "Kiểm tra",
            trailingHighlight = availableUpdate != null,
            onClick = onCheckUpdate
        )
    }
}

@Composable
private fun AppearanceCard(
    state: BridgeUiState,
    adaptive: AdaptiveMetrics
) {
    SettingsGroupCard(title = "GIAO DIỆN") {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemeMode.entries.forEach { mode ->
                    val selected = state.themeMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { BridgeRepository.setThemeMode(mode) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode.label,
                            style = TextStyle(
                                fontFeatureSettings = "tnum",
                                fontSize = adaptive.sp(12f),
                                lineHeight = adaptive.sp(14f),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            textAlign = TextAlign.Center,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneConnectionSettingsCard(
    state: BridgeUiState,
    manualIp: String,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    onManualIpChange: (String) -> Unit,
    onPairManual: () -> Unit,
    onToggleBle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    SettingsGroupCard(title = "KẾT NỐI ANDROID BOX") {
        Text(
            text = "Box tìm thấy gần đây (LAN / BLE)",
            fontSize = adaptive.sp(11.5f),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(5.dp))
        if (state.discoveredBoxes.isEmpty()) {
            Text(
                text = "Chưa tìm thấy Box nào. Hãy mở CarBridge trên Android Box.",
                fontSize = adaptive.sp(11.5f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.discoveredBoxes.forEach { box ->
                    val selected = state.pairedBox?.host == box.host && state.pairedBox.channel == box.channel
                    CockpitPill(
                        onClick = { BridgeRepository.selectPairedBox(context, box) },
                        shape = RoundedCornerShape(999.dp),
                        color = if (selected) accents.emeraldContainer else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "${box.name} (${box.host})",
                            fontSize = adaptive.sp(11.5f),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) accents.emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = manualIp,
                onValueChange = onManualIpChange,
                label = { Text("IP Box (tùy chọn)") },
                placeholder = { Text("VD: 192.168.1.20") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onPairManual,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF10B981),
                    contentColor = Color.White
                )
            ) {
                Text("Ghép", fontWeight = FontWeight.Bold)
            }
        }

        SettingsDivider()
        var showCameraScanner by remember { mutableStateOf(false) }
        var showQrInput by remember { mutableStateOf(false) }
        if (showCameraScanner) {
            QrCameraScannerDialog(
                onScanned = { parsed ->
                    if (parsed.pin.isNotBlank()) {
                        BridgeRepository.updateSettings(pin = parsed.pin)
                    }
                    BridgeRepository.upsertDiscoveredBox(parsed.box)
                    BridgeRepository.selectPairedBox(context, parsed.box)
                },
                onFallbackManual = { showQrInput = true },
                onDismiss = { showCameraScanner = false }
            )
        }
        if (showQrInput) {
            PhoneQrPairInputDialog(
                onParsed = { parsed ->
                    if (parsed.pin.isNotBlank()) {
                        BridgeRepository.updateSettings(pin = parsed.pin)
                    }
                    BridgeRepository.upsertDiscoveredBox(parsed.box)
                    BridgeRepository.selectPairedBox(context, parsed.box)
                },
                onDismiss = { showQrInput = false }
            )
        }
        SettingsActionRow(
            icon = Icons.Rounded.QrCode2,
            tint = accents.emerald,
            bg = accents.emeraldContainer,
            title = "Quét mã QR ghép Box bằng Camera",
            subtitle = "Đưa camera vào mã QR trên màn hình Android Box để ghép ngay",
            trailingText = "Quét QR",
            trailingHighlight = true,
            onClick = { showCameraScanner = true }
        )

        SettingsDivider()
        SettingsToggleRow(
            title = "Kết nối Bluetooth LE (BLE)",
            subtitle = "Dùng khi 2 máy dùng SIM 4G riêng không chung Wi-Fi",
            checked = state.bleEnabled,
            adaptive = adaptive,
            onCheckedChange = onToggleBle
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Ưu tiên BLE thay vì LAN",
            subtitle = "Gửi qua Bluetooth trước thay vì Wi-Fi",
            checked = state.preferBleOverLan,
            adaptive = adaptive,
            onCheckedChange = { BridgeRepository.setPreferBleOverLan(it) }
        )
    }
}

@Composable
private fun PhonePermissionsCard(
    bleStatus: BleStatus,
    accents: CockpitAccents,
    onRequestBlePermissions: () -> Unit
) {
    SettingsGroupCard(title = "QUYỀN KẾT NỐI") {
        SettingsActionRow(
            icon = Icons.Rounded.Bluetooth,
            tint = if (bleStatus.permissionsGranted && bleStatus.hardwareSupported) accents.emerald else accents.amber,
            bg = if (bleStatus.permissionsGranted && bleStatus.hardwareSupported) accents.emeraldContainer else accents.amberContainer,
            title = "Bluetooth LE",
            subtitle = bleStatus.note,
            trailingText = when {
                !bleStatus.hardwareSupported -> "Không có HW"
                !bleStatus.permissionsGranted -> "Cấp quyền"
                bleStatus.scanningActive -> "Đang quét"
                else -> "Sẵn sàng"
            },
            trailingHighlight = bleStatus.permissionsGranted && bleStatus.hardwareSupported,
            onClick = onRequestBlePermissions
        )
    }
}

@Composable
private fun BoxIdentitySettingsCard(
    state: BridgeUiState,
    adaptive: AdaptiveMetrics,
    onToggleBle: (Boolean) -> Unit
) {
    SettingsGroupCard(title = "CẤU HÌNH ANDROID BOX") {
        OutlinedTextField(
            value = state.boxName,
            onValueChange = { BridgeRepository.updateSettings(boxName = it) },
            label = { Text("Tên Android Box trên xe") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.pairingPin,
            onValueChange = { BridgeRepository.updateSettings(pin = it.take(6)) },
            label = { Text("Mã PIN ghép nối nhanh") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        SettingsDivider()
        var showBoxQr by remember { mutableStateOf(false) }
        val accents = LocalCockpitAccents.current
        if (showBoxQr) {
            BoxQrPairingDialog(
                boxName = state.boxName,
                pin = state.pairingPin,
                onDismiss = { showBoxQr = false }
            )
        }
        SettingsActionRow(
            icon = Icons.Rounded.QrCode2,
            tint = accents.emerald,
            bg = accents.emeraldContainer,
            title = "Hiện mã QR ghép nối",
            subtitle = "Để điện thoại quét ghép IP & PIN nhanh trong 1 giây",
            trailingText = "Hiện QR",
            trailingHighlight = true,
            onClick = { showBoxQr = true }
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Phát sóng Bluetooth LE (BLE)",
            subtitle = "Cho phép điện thoại gửi link khi không chung Wi-Fi",
            checked = state.bleEnabled,
            adaptive = adaptive,
            onCheckedChange = onToggleBle
        )
    }
}

@Composable
private fun AutoLaunchSettingsCard(
    state: BridgeUiState,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    onToggleBoxService: (Boolean) -> Unit,
    onToggleAutoStartOnBoot: (Boolean) -> Unit
) {
    SettingsGroupCard(title = "TỰ KHỞI ĐỘNG & MỞ BẢN ĐỒ") {
        SettingsToggleRow(
            title = "Tự khởi động ngầm khi Box bật nguồn",
            subtitle = state.lastBootStartNote?.let { "Tự bật gần nhất: $it · Có Watchdog giữ nền" }
                ?: "Tự chạy ngầm khi nổ máy / bật Box + Watchdog tự phục hồi",
            checked = state.autoStartOnBoot,
            adaptive = adaptive,
            onCheckedChange = onToggleAutoStartOnBoot
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Dịch vụ lắng nghe trên Box",
            subtitle = "Chờ link gửi tới từ điện thoại",
            checked = state.boxServiceRunning,
            adaptive = adaptive,
            onCheckedChange = onToggleBoxService
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Tự động mở bản đồ khi nhận link",
            subtitle = "Gửi là màn hình xe tự nhảy vào dẫn đường ngay",
            checked = state.autoOpenOnBox,
            adaptive = adaptive,
            onCheckedChange = { BridgeRepository.updateSettings(autoOpen = it) }
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Màn hình xem trước trên xe",
            subtitle = "Tắt để nhảy thẳng vào Google Maps / Waze / Vietmap",
            checked = state.simulatePreviewOnEmulator,
            adaptive = adaptive,
            onCheckedChange = { BridgeRepository.updateSettings(simulatePreview = it) }
        )
        SettingsDivider()

        Text(
            text = "App bản đồ ưu tiên mặc định trên Box",
            fontSize = adaptive.sp(11.5f),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            listOf(MapProvider.AUTO, MapProvider.GOOGLE_MAPS, MapProvider.WAZE, MapProvider.VIETMAP).forEach { target ->
                val active = state.boxPreferredTarget == target
                CockpitPill(
                    onClick = { BridgeRepository.updateSettings(boxPreferredTarget = target) },
                    shape = RoundedCornerShape(999.dp),
                    color = if (active) accents.emeraldContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = target.displayName,
                        fontSize = adaptive.sp(11f),
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) accents.emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxPermissionsAndAppsCard(
    canDrawOverlays: Boolean,
    hasNotificationPerm: Boolean,
    isBatteryIgnored: Boolean,
    installedMaps: Map<MapProvider, Boolean>,
    bleStatus: BleStatus,
    accents: CockpitAccents,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onRequestBlePermissions: () -> Unit
) {
    SettingsGroupCard(title = "QUYỀN HỆ THỐNG & ỨNG DỤNG") {
        SettingsActionRow(
            icon = Icons.Rounded.Layers,
            tint = if (canDrawOverlays) accents.emerald else accents.amber,
            bg = if (canDrawOverlays) accents.emeraldContainer else accents.amberContainer,
            title = "Hiển thị trên ứng dụng khác",
            subtitle = "Bắt buộc trên Android 10+ để tự bật bản đồ từ nền",
            trailingText = if (canDrawOverlays) "Đã cấp" else "Cấp quyền",
            trailingHighlight = canDrawOverlays,
            onClick = onRequestOverlayPermission
        )
        SettingsDivider()
        SettingsActionRow(
            icon = Icons.Rounded.BatteryChargingFull,
            tint = if (isBatteryIgnored) accents.emerald else accents.amber,
            bg = if (isBatteryIgnored) accents.emeraldContainer else accents.amberContainer,
            title = "Tự khởi động & chạy ngầm bền bỉ",
            subtitle = "Không tối ưu pin để Box tự bật ngầm khi nổ máy",
            trailingText = if (isBatteryIgnored) "Đã bật" else "Cấp quyền",
            trailingHighlight = isBatteryIgnored,
            onClick = onRequestBatteryOptimization
        )
        SettingsDivider()
        val ctx = LocalContext.current
        SettingsActionRow(
            icon = Icons.Rounded.PowerSettingsNew,
            tint = accents.cyan,
            bg = accents.cyanContainer,
            title = "Cài đặt tự chạy (Auto-Start ROM Box)",
            subtitle = "Mở mục cho phép chạy ngầm trên ROM Android Box",
            trailingText = "Mở",
            trailingHighlight = false,
            onClick = { com.carmap.bridge.util.OemAutoStartHelper.openAutoStartSettings(ctx) }
        )
        SettingsDivider()
        SettingsActionRow(
            icon = Icons.Rounded.NotificationsActive,
            tint = if (hasNotificationPerm) accents.emerald else accents.coral,
            bg = if (hasNotificationPerm) accents.emeraldContainer else accents.coralContainer,
            title = "Thông báo dịch vụ nền",
            subtitle = "Giữ Box Receiver hoạt động liên tục",
            trailingText = if (hasNotificationPerm) "Đã bật" else "Xin quyền",
            trailingHighlight = hasNotificationPerm,
            onClick = onRequestNotificationPermission
        )
        SettingsDivider()
        SettingsActionRow(
            icon = Icons.Rounded.Map,
            tint = accents.cyan,
            bg = accents.cyanContainer,
            title = "Ứng dụng bản đồ trên Box",
            subtitle = installedMaps.entries.joinToString(" · ") { (k, v) ->
                "${k.displayName}: ${if (v) "Có" else "Chưa cài"}"
            },
            trailingText = "${installedMaps.count { it.value }}/3 app",
            trailingHighlight = installedMaps.any { it.value },
            onClick = {}
        )
        SettingsDivider()
        SettingsActionRow(
            icon = Icons.Rounded.Bluetooth,
            tint = if (bleStatus.permissionsGranted && bleStatus.hardwareSupported) accents.emerald else accents.amber,
            bg = if (bleStatus.permissionsGranted && bleStatus.hardwareSupported) accents.emeraldContainer else accents.amberContainer,
            title = "Bluetooth LE",
            subtitle = bleStatus.note,
            trailingText = when {
                !bleStatus.hardwareSupported -> "Không có HW"
                !bleStatus.permissionsGranted -> "Cấp quyền"
                bleStatus.advertisingActive -> "Đang phát"
                else -> "Sẵn sàng"
            },
            trailingHighlight = bleStatus.permissionsGranted && bleStatus.hardwareSupported,
            onClick = onRequestBlePermissions
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    adaptive: AdaptiveMetrics,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, fontSize = adaptive.sp(13.5f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = adaptive.sp(11f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

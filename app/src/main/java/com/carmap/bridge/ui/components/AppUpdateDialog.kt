package com.carmap.bridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.model.DeviceRole
import com.carmap.bridge.ui.theme.LocalCockpitAccents
import com.carmap.bridge.update.AppUpdater
import com.carmap.bridge.update.OtaUpdateState
import com.carmap.bridge.util.RootHelper
import kotlinx.coroutines.launch

@Composable
fun AppUpdateDialog(
    state: OtaUpdateState,
    onDismiss: () -> Unit
) {
    if (state is OtaUpdateState.Idle) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accents = LocalCockpitAccents.current
    val installed = remember { AppUpdater.getInstalledVersion(context) }
    var permissionNotice by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (state !is OtaUpdateState.Downloading) onDismiss()
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            when (state) {
                                is OtaUpdateState.Error -> accents.coralContainer
                                else -> accents.emeraldContainer
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (state) {
                            is OtaUpdateState.UpToDate -> Icons.Rounded.CheckCircle
                            is OtaUpdateState.Error -> Icons.Rounded.ErrorOutline
                            is OtaUpdateState.Downloading -> Icons.Rounded.CloudDownload
                            else -> Icons.Rounded.SystemUpdate
                        },
                        contentDescription = null,
                        tint = when (state) {
                            is OtaUpdateState.Error -> accents.coral
                            else -> accents.emerald
                        },
                        modifier = Modifier.size(19.dp)
                    )
                }
                Text(
                    text = when (state) {
                        is OtaUpdateState.Checking -> "Đang kiểm tra cập nhật..."
                        is OtaUpdateState.UpToDate -> "Bạn đang dùng bản mới nhất"
                        is OtaUpdateState.UpdateAvailable -> "Có bản cập nhật mới!"
                        is OtaUpdateState.Downloading -> "Đang tải bản cập nhật..."
                        is OtaUpdateState.ReadyToInstall -> "Sẵn sàng cài đặt"
                        is OtaUpdateState.Error -> "Cập nhật OTA"
                        OtaUpdateState.Idle -> ""
                    },
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        },
        text = {
            when (state) {
                is OtaUpdateState.Checking -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = accents.emerald
                        )
                        Text(
                            text = "Đang kiểm tra phiên bản mới hơn v${installed.versionName} trên GitHub Releases...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is OtaUpdateState.UpToDate -> {
                    Text(
                        text = "CarBridge v${state.currentVersion} hiện đã là phiên bản mới nhất.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is OtaUpdateState.UpdateAvailable -> {
                    val rel = state.release
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = accents.emeraldContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "v${installed.versionName} → v${rel.versionName}",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = accents.emerald
                                )
                                val sizeStr = AppUpdater.formatBytesMb(rel.apkSizeBytes)
                                if (sizeStr.isNotBlank()) {
                                    Text(
                                        text = sizeStr,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = rel.notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Text(
                            text = "Cài đè trực tiếp — giữ nguyên toàn bộ cấu hình ghép nối và vai trò thiết bị.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is OtaUpdateState.Downloading -> {
                    val pct = if (state.progress >= 0f) (state.progress * 100).toInt() else -1
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "CarBridge v${state.release.versionName}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = if (pct >= 0) "$pct%" else "Đang tải...",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                color = accents.emerald
                            )
                        }
                        if (state.progress >= 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(state.progress.coerceAtLeast(0.03f))
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(
                                            Brush.horizontalGradient(listOf(Color(0xFF10B981), Color(0xFF38BDF8)))
                                        )
                                )
                            }
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = accents.emerald
                            )
                        }
                        val dlMb = AppUpdater.formatBytesMb(state.downloadedBytes)
                        val totMb = AppUpdater.formatBytesMb(state.totalBytes)
                        Text(
                            text = if (totMb.isNotBlank()) "Đã tải $dlMb / $totMb" else "Đã tải $dlMb...",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is OtaUpdateState.ReadyToInstall -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Đã tải xong CarBridge v${state.release.versionName} (${AppUpdater.formatBytesMb(state.apkFile.length())}).",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (permissionNotice || !AppUpdater.canInstallPackages(context)) {
                            Text(
                                text = "Lần đầu cập nhật OTA: bấm \"Cài đặt ngay\" → bật \"Cho phép từ nguồn này\" cho CarBridge → quay lại bấm \"Cài đặt ngay\".",
                                style = MaterialTheme.typography.bodySmall,
                                color = accents.amber
                            )
                        } else {
                            Text(
                                text = "Bấm \"Cài đặt ngay\" để cập nhật đè (giữ nguyên toàn bộ cài đặt).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                is OtaUpdateState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OtaUpdateState.Idle -> Unit
            }
        },
        confirmButton = {
            when (state) {
                is OtaUpdateState.UpdateAvailable -> {
                    Button(
                        onClick = {
                            scope.launch {
                                val file = AppUpdater.downloadUpdate(context, state.release)
                                if (file != null) {
                                    val st = BridgeRepository.state.value
                                    val silentOk = if (st.role == DeviceRole.CAR_BOX_RECEIVER &&
                                        st.rootModeEnabled &&
                                        st.rootSilentInstall &&
                                        RootHelper.isRootGranted
                                    ) {
                                        Toast.makeText(context, "Đang cài đè im lặng qua Root...", Toast.LENGTH_SHORT).show()
                                        val (ok, _) = RootHelper.silentInstallApk(file)
                                        ok
                                    } else {
                                        false
                                    }
                                    if (!silentOk) {
                                        val launched = AppUpdater.launchApkInstaller(context, file)
                                        if (!launched) permissionNotice = true
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accents.emerald,
                            contentColor = if (accents.isDark) Color(0xFF042116) else Color.White
                        ),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("Tải & Cập nhật", fontWeight = FontWeight.ExtraBold)
                    }
                }

                is OtaUpdateState.ReadyToInstall -> {
                    Button(
                        onClick = {
                            scope.launch {
                                val st = BridgeRepository.state.value
                                val silentOk = if (st.role == DeviceRole.CAR_BOX_RECEIVER &&
                                    st.rootModeEnabled &&
                                    st.rootSilentInstall &&
                                    RootHelper.isRootGranted
                                ) {
                                    Toast.makeText(context, "Đang cài đè im lặng qua Root...", Toast.LENGTH_SHORT).show()
                                    val (ok, _) = RootHelper.silentInstallApk(state.apkFile)
                                    ok
                                } else {
                                    false
                                }
                                if (!silentOk) {
                                    val launched = AppUpdater.launchApkInstaller(context, state.apkFile)
                                    if (!launched) permissionNotice = true
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accents.emerald,
                            contentColor = if (accents.isDark) Color(0xFF042116) else Color.White
                        ),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("Cài đặt ngay", fontWeight = FontWeight.ExtraBold)
                    }
                }

                is OtaUpdateState.Downloading -> Unit

                else -> {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("Đóng", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        dismissButton = {
            if (state is OtaUpdateState.UpdateAvailable || state is OtaUpdateState.ReadyToInstall) {
                TextButton(onClick = onDismiss) {
                    Text("Để sau")
                }
            }
        }
    )
}

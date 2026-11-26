package com.carmap.bridge.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.carmap.bridge.R
import com.carmap.bridge.model.DeviceRole
import com.carmap.bridge.ui.theme.AdaptiveMetrics
import com.carmap.bridge.ui.theme.CockpitAccents
import com.carmap.bridge.ui.theme.LocalAdaptiveMetrics
import com.carmap.bridge.ui.theme.LocalCockpitAccents

@Composable
fun RoleSelectionScreen(
    onSelectRole: (DeviceRole) -> Unit
) {
    val accents = LocalCockpitAccents.current
    val adaptive = LocalAdaptiveMetrics.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = adaptive.screenPadding, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (adaptive.isTwoColumn) 760.dp else 440.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "CarBridge",
                modifier = Modifier
                    .size(adaptive.dp(68f))
                    .clip(RoundedCornerShape(18.dp))
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Chào mừng đến CarBridge",
                    fontSize = adaptive.sp(21f),
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Chọn thiết bị này là Điện thoại gửi bản đồ hay Android Box trên xe để tối ưu giao diện và cài đặt.",
                    fontSize = adaptive.sp(12.5f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(4.dp))

            if (adaptive.isTwoColumn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RoleChoiceCard(
                        icon = Icons.Rounded.Smartphone,
                        tint = accents.emerald,
                        bg = accents.emeraldContainer,
                        badge = "BÊN GỬI",
                        title = "Điện thoại",
                        subtitle = "Gửi link Google Maps, Waze, Vietmap hoặc chia sẻ 1 chạm sang màn hình xe.",
                        accents = accents,
                        adaptive = adaptive,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelectRole(DeviceRole.PHONE_SENDER) }
                    )
                    RoleChoiceCard(
                        icon = Icons.Rounded.DirectionsCar,
                        tint = accents.cyan,
                        bg = accents.cyanContainer,
                        badge = "BÊN NHẬN",
                        title = "Android Box trên xe",
                        subtitle = "Lắng nghe liên tục qua LAN & Bluetooth LE, tự động mở ứng dụng dẫn đường.",
                        accents = accents,
                        adaptive = adaptive,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelectRole(DeviceRole.CAR_BOX_RECEIVER) }
                    )
                }
            } else {
                RoleChoiceCard(
                    icon = Icons.Rounded.Smartphone,
                    tint = accents.emerald,
                    bg = accents.emeraldContainer,
                    badge = "BÊN GỬI",
                    title = "Điện thoại",
                    subtitle = "Gửi link Google Maps, Waze, Vietmap hoặc chia sẻ 1 chạm sang màn hình xe.",
                    accents = accents,
                    adaptive = adaptive,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelectRole(DeviceRole.PHONE_SENDER) }
                )
                RoleChoiceCard(
                    icon = Icons.Rounded.DirectionsCar,
                    tint = accents.cyan,
                    bg = accents.cyanContainer,
                    badge = "BÊN NHẬN",
                    title = "Android Box trên xe",
                    subtitle = "Lắng nghe liên tục qua LAN & Bluetooth LE, tự động mở ứng dụng dẫn đường.",
                    accents = accents,
                    adaptive = adaptive,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelectRole(DeviceRole.CAR_BOX_RECEIVER) }
                )
            }

            Text(
                text = "Bạn vẫn có thể đổi lại vai trò thiết bị bất cứ lúc nào trong Cài đặt.",
                fontSize = adaptive.sp(11.5f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RoleChoiceCard(
    icon: ImageVector,
    tint: Color,
    bg: Color,
    badge: String,
    title: String,
    subtitle: String,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(adaptive.dp(44f))
                    .clip(CircleShape)
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(adaptive.dp(22f))
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = badge,
                    fontSize = adaptive.sp(10f),
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = adaptive.sp(0.8f),
                    color = tint
                )
                Text(
                    text = title,
                    fontSize = adaptive.sp(15.5f),
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = adaptive.sp(12f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = accents.emerald,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

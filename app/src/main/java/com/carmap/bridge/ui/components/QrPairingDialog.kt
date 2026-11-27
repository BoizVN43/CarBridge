package com.carmap.bridge.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.network.LanBroadcastHelper
import com.carmap.bridge.ui.theme.LocalCockpitAccents
import com.carmap.bridge.util.PairingCodeHelper
import com.carmap.bridge.util.ParsedPairingCode
import com.carmap.bridge.util.QrCodeMatrix

@Composable
fun BoxQrPairingDialog(
    boxName: String,
    pin: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val accents = LocalCockpitAccents.current
    val localIp = remember { LanBroadcastHelper.localIpv4Address() ?: "192.168.1.10" }
    val pairingUri = remember(boxName, pin, localIp) {
        PairingCodeHelper.buildPairingUri(
            boxId = BridgeRepository.deviceInstanceId,
            boxName = boxName,
            host = localIp,
            pin = pin
        )
    }
    val qrMatrix = remember(pairingUri) { QrCodeMatrix.encode(pairingUri) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = accents.emerald)
                Spacer(Modifier.width(8.dp))
                Text("Mã QR ghép nối Box", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (qrMatrix != null) {
                    Box(
                        modifier = Modifier
                            .size(204.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(180.dp)) {
                            val n = qrMatrix.size
                            val cell = size.width / n
                            for (r in 0 until n) {
                                for (c in 0 until n) {
                                    if (qrMatrix[r][c]) {
                                        drawRect(
                                            color = Color.Black,
                                            topLeft = Offset(c * cell, r * cell),
                                            size = Size(cell + 0.5f, cell + 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "$boxName · $localIp",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "PIN: ${pin.ifBlank { "Không dùng" }}",
                            fontSize = 11.5.sp,
                            color = accents.emerald,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    text = "Mở CarBridge trên điện thoại → bấm nút QR để quét trực tiếp bằng camera và ghép ngay trong 1 giây.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("CarBridge Pair", pairingUri))
                    Toast.makeText(context, "Đã chép mã ghép nhanh", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = accents.emerald,
                    contentColor = Color(0xFF042116)
                ),
                shape = RoundedCornerShape(999.dp)
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Chép mã", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Đóng") }
        }
    )
}

@Composable
fun PhoneQrPairInputDialog(
    onParsed: (ParsedPairingCode) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val accents = LocalCockpitAccents.current
    var input by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = accents.emerald)
                Spacer(Modifier.width(8.dp))
                Text("Ghép nhanh bằng mã QR", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Quét mã QR trên màn hình Android Box bằng Camera / Google Lens, sau đó dán mã carbridge://pair... vào đây:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        errorMsg = null
                    },
                    placeholder = { Text("VD: carbridge://pair?host=192.168.1.20&pin=2689") },
                    minLines = 2,
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                        if (clip.isNotBlank()) {
                            input = clip
                            errorMsg = null
                        } else {
                            Toast.makeText(context, "Clipboard đang trống", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Dán từ Clipboard", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
                if (!errorMsg.isNullOrBlank()) {
                    Text(errorMsg!!, fontSize = 11.5.sp, color = accents.coral)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = PairingCodeHelper.parsePairingUri(input)
                    if (parsed != null) {
                        onParsed(parsed)
                        onDismiss()
                    } else {
                        errorMsg = "Mã không hợp lệ. Cần có dạng carbridge://pair?host=..."
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = accents.emerald,
                    contentColor = Color(0xFF042116)
                ),
                shape = RoundedCornerShape(999.dp)
            ) {
                Text("Ghép ngay", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        }
    )
}

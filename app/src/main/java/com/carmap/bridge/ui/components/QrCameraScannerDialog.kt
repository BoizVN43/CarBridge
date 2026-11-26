package com.carmap.bridge.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.carmap.bridge.util.PairingCodeHelper
import com.carmap.bridge.util.ParsedPairingCode
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrCameraScannerDialog(
    onScanned: (ParsedPairingCode) -> Unit,
    onFallbackManual: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var torchEnabled by remember { mutableStateOf(false) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var statusHint by remember { mutableStateOf("Đưa mã QR trên màn hình Android Box vào khung") }
    val handled = remember { AtomicBoolean(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPerm = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPerm) {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (hasCameraPerm) {
                val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
                DisposableEffect(Unit) {
                    onDispose { analyzerExecutor.shutdown() }
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }

                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            runCatching {
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()

                                val reader = MultiFormatReader().apply {
                                    setHints(
                                        mapOf(
                                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                            DecodeHintType.TRY_HARDER to true
                                        )
                                    )
                                }

                                analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                                    if (handled.get()) {
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }
                                    val text = decodeQrFromProxy(reader, imageProxy)
                                    imageProxy.close()
                                    if (!text.isNullOrBlank()) {
                                        val parsed = PairingCodeHelper.parsePairingUri(text)
                                        if (parsed != null && handled.compareAndSet(false, true)) {
                                            previewView.post {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onScanned(parsed)
                                                onDismiss()
                                            }
                                        } else {
                                            previewView.post {
                                                statusHint = "Mã QR không phải của CarBridge, hãy quét mã trên Box"
                                            }
                                        }
                                    }
                                }

                                provider.unbindAll()
                                val cam = provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis
                                )
                                cameraRef = cam
                            }.onFailure {
                                statusHint = "Không mở được camera: ${it.localizedMessage ?: "lỗi phần cứng"}"
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    }
                )

                // Crisp viewfinder scrim + emerald corner brackets
                QrViewfinderOverlay(modifier = Modifier.fillMaxSize())
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Rounded.QrCodeScanner,
                        contentDescription = null,
                        tint = Color(0xFF34D399),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "Cần quyền Camera để quét mã QR trên Android Box",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF10B981),
                            contentColor = Color(0xFF042116)
                        ),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("Cấp quyền Camera", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Top bar: Close + Flash toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0x88000000))
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Đóng", tint = Color.White)
                }

                Text(
                    text = "Quét mã QR Android Box",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                IconButton(
                    onClick = {
                        val next = !torchEnabled
                        torchEnabled = next
                        cameraRef?.cameraControl?.enableTorch(next)
                    },
                    enabled = cameraRef?.cameraInfo?.hasFlashUnit() == true,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0x88000000))
                ) {
                    Icon(
                        imageVector = if (torchEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                        contentDescription = "Đèn flash",
                        tint = if (torchEnabled) Color(0xFFFBBF24) else Color.White
                    )
                }
            }

            // Bottom hint + fallback manual paste button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 44.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xB30B0F17)
                ) {
                    Text(
                        text = statusHint,
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                Surface(
                    onClick = {
                        onDismiss()
                        onFallbackManual()
                    },
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0x33FFFFFF)
                ) {
                    Text(
                        text = "Hoặc dán mã ghép thủ công",
                        color = Color(0xFF34D399),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QrViewfinderOverlay(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        drawRect(Color(0x99000000))
        val boxSize = minOf(size.width, size.height) * 0.64f
        val left = (size.width - boxSize) / 2f
        val top = (size.height - boxSize) / 2f
        val radius = 24.dp.toPx()

        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(boxSize, boxSize),
            cornerRadius = CornerRadius(radius, radius),
            blendMode = BlendMode.Clear
        )

        val strokeWidth = 4.dp.toPx()
        val cornerLen = 30.dp.toPx()
        val emerald = Color(0xFF34D399)

        // Top-left
        drawLine(emerald, Offset(left, top + cornerLen), Offset(left, top + radius / 2), strokeWidth, StrokeCap.Round)
        drawLine(emerald, Offset(left + radius / 2, top), Offset(left + cornerLen, top), strokeWidth, StrokeCap.Round)
        // Top-right
        drawLine(emerald, Offset(left + boxSize - cornerLen, top), Offset(left + boxSize - radius / 2, top), strokeWidth, StrokeCap.Round)
        drawLine(emerald, Offset(left + boxSize, top + radius / 2), Offset(left + boxSize, top + cornerLen), strokeWidth, StrokeCap.Round)
        // Bottom-left
        drawLine(emerald, Offset(left, top + boxSize - cornerLen), Offset(left, top + boxSize - radius / 2), strokeWidth, StrokeCap.Round)
        drawLine(emerald, Offset(left + radius / 2, top + boxSize), Offset(left + cornerLen, top + boxSize), strokeWidth, StrokeCap.Round)
        // Bottom-right
        drawLine(emerald, Offset(left + boxSize - cornerLen, top + boxSize), Offset(left + boxSize - radius / 2, top + boxSize), strokeWidth, StrokeCap.Round)
        drawLine(emerald, Offset(left + boxSize, top + boxSize - cornerLen), Offset(left + boxSize, top + boxSize - radius / 2), strokeWidth, StrokeCap.Round)

        drawRoundRect(
            color = Color(0x4434D399),
            topLeft = Offset(left, top),
            size = Size(boxSize, boxSize),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = 1.5.dp.toPx())
        )
    }
}

private fun decodeQrFromProxy(reader: MultiFormatReader, image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    val width = image.width
    val height = image.height
    val rowStride = plane.rowStride

    // Fast path: if rowStride == width, pass directly; otherwise compact rows
    val yData = if (rowStride == width) {
        data
    } else {
        val compact = ByteArray(width * height)
        for (r in 0 until height) {
            val srcOffset = r * rowStride
            if (srcOffset + width <= data.size) {
                System.arraycopy(data, srcOffset, compact, r * width, width)
            }
        }
        compact
    }

    val source = PlanarYUVLuminanceSource(yData, width, height, 0, 0, width, height, false)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        reader.decodeWithState(bitmap).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}

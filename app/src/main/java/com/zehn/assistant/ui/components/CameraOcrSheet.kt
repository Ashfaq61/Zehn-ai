package com.zehn.assistant.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.zehn.assistant.ui.theme.ZehnColors
import java.io.File

@Composable
fun CameraVisionDialog(
    onDismiss: () -> Unit,
    onCapturedPhoto: (Uri) -> Unit,
    onScannedOcrOrQr: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA)
    }

    var detectedOcr by remember { mutableStateOf("") }
    var detectedQr by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ZehnColors.Background)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Vision, OCR & QR Scanner", style = MaterialTheme.typography.titleLarge, color = ZehnColors.FrostWhite)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close camera", tint = ZehnColors.FrostWhite)
                }
            }
            Spacer(Modifier.height(10.dp))

            if (!hasPermission) {
                ClayGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Camera permission is required for live OCR, QR scanning, and photo analysis.", color = ZehnColors.FrostWhite)
                    Spacer(Modifier.height(12.dp))
                    ClayActionButton(text = "Grant Camera", onClick = { permLauncher.launch(Manifest.permission.CAMERA) })
                }
                return@Dialog
            }

            val cameraController = remember {
                LifecycleCameraController(context).apply {
                    setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
                }
            }
            val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
            val barcodeScanner = remember {
                BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
                )
            }

            DisposableEffect(lifecycleOwner) {
                cameraController.bindToLifecycle(lifecycleOwner)
                cameraController.setImageAnalysisAnalyzer(
                    ContextCompat.getMainExecutor(context),
                    MlKitAnalyzer(
                        listOf(textRecognizer, barcodeScanner),
                        CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                        ContextCompat.getMainExecutor(context)
                    ) { result ->
                        val ocr = result?.getValue(textRecognizer)?.text?.trim().orEmpty()
                        if (ocr.isNotEmpty()) detectedOcr = ocr.take(280)
                        val qr = result?.getValue(barcodeScanner)?.firstOrNull()?.rawValue.orEmpty()
                        if (qr.isNotEmpty()) detectedQr = qr
                    }
                )
                onDispose {
                    cameraController.clearImageAnalysisAnalyzer()
                    textRecognizer.close()
                    barcodeScanner.close()
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
            ) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply { controller = cameraController }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (detectedQr.isNotBlank() || detectedOcr.isNotBlank()) {
                    ClayGlassCard(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        if (detectedQr.isNotBlank()) {
                            Text("QR / Barcode: $detectedQr", color = ZehnColors.Cyan, style = MaterialTheme.typography.labelMedium)
                        }
                        if (detectedOcr.isNotBlank()) {
                            Text("OCR Preview: ${detectedOcr.take(140)}", color = ZehnColors.FrostWhite, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ClayActionButton(
                    text = "Analyze Photo",
                    icon = Icons.Filled.CameraAlt,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val file = File(context.cacheDir, "zehn_cam_${System.currentTimeMillis()}.jpg")
                        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
                        cameraController.takePicture(
                            opts,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    onCapturedPhoto(Uri.fromFile(file))
                                    onDismiss()
                                }

                                override fun onError(exception: ImageCaptureException) {}
                            }
                        )
                    }
                )
                ClayActionButton(
                    text = "Use OCR / QR",
                    icon = Icons.Filled.QrCodeScanner,
                    modifier = Modifier.weight(1f),
                    enabled = detectedQr.isNotBlank() || detectedOcr.isNotBlank(),
                    onClick = {
                        val combined = buildString {
                            if (detectedQr.isNotBlank()) appendLine("QR/Barcode: $detectedQr")
                            if (detectedOcr.isNotBlank()) append("OCR Text:\n$detectedOcr")
                        }
                        onScannedOcrOrQr(combined)
                        onDismiss()
                    }
                )
            }
        }
    }
}

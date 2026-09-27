package eu.kanade.presentation.more.settings.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.kanade.tachiyomi.data.sync.SyncQrDecoder
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.concurrent.Executors

/**
 * QR scanner for the sync "quick connect" flow, using CameraX for capture and
 * the pure-Java ZXing decoder — no Google Play Services dependency.
 *
 * Decodes frames on a single-threaded executor with ZXing's YUV and RGBA
 * luminance sources, whichever matches the analyzer output format.
 */
@Composable
fun SyncQrScannerDialog(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned by rememberUpdatedState(onScanned)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) onDismiss()
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(MR.strings.action_scan_qr)) },
        text = {
            if (hasPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    CameraPreview(
                        lifecycleOwner = lifecycleOwner,
                        onDecoded = { value ->
                            currentOnScanned(value)
                        },
                    )
                }
            } else {
                Text(text = stringResource(MR.strings.sync_qr_permission_required))
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun CameraPreview(
    lifecycleOwner: LifecycleOwner,
    onDecoded: (String) -> Unit,
) {
    val context = LocalContext.current
    val currentOnDecoded by rememberUpdatedState(onDecoded)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Only report the first successful decode; later frames are ignored.
    val decoded = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )

    DisposableEffect(lifecycleOwner, previewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var provider: ProcessCameraProvider? = null

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .apply {
                setAnalyzer(analyzerExecutor) { imageProxy ->
                    try {
                        if (!decoded.get()) {
                            // Never let a decode error kill the analysis pipeline.
                            val value = runCatching { decodeFrame(imageProxy) }.getOrNull()
                            if (value != null && decoded.compareAndSet(false, true)) {
                                mainExecutor.execute { currentOnDecoded(value) }
                            }
                        }
                    } finally {
                        imageProxy.close()
                    }
                }
            }

        val preview = Preview.Builder().build().apply {
            surfaceProvider = previewView.surfaceProvider
        }

        cameraProviderFuture.addListener(
            {
                try {
                    provider = cameraProviderFuture.get()
                    provider?.unbindAll()
                    provider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (_: Exception) {
                    // Camera unavailable (no permission/hardware): leave preview blank.
                }
            },
            mainExecutor,
        )

        onDispose {
            analysis.clearAnalyzer()
            provider?.unbindAll()
            analyzerExecutor.shutdown()
        }
    }
}

private fun decodeFrame(imageProxy: ImageProxy): String? {
    val width = imageProxy.width
    val height = imageProxy.height
    val planes = imageProxy.planes
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    buffer.rewind()
    val data = ByteArray(buffer.remaining())
    buffer.get(data)

    // ImageProxy.format reports the android.media.Image format (FLEX_RGBA_8888 =
    // 42, YUV_420_888 = 35), not the ImageAnalysis.OUTPUT_IMAGE_FORMAT_* constant,
    // so dispatch on the plane layout instead: RGBA is one plane with a 4-byte
    // pixel stride, while YUV_420_888 is three planes whose first is the
    // grayscale Y channel.
    return if (planes.size == 1 && plane.pixelStride >= 4) {
        SyncQrDecoder.decodeRgba(data, width, height, plane.rowStride, plane.pixelStride)
    } else {
        SyncQrDecoder.decodeLuminance(data, width, height, plane.rowStride)
    }
}

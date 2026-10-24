package eu.kanade.presentation.more.sync

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.sync.SyncQrPayload
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * Scans a Kioku Sync pairing QR code and hands the decoded payload back to
 * the caller. The payload's shape is validated here; the pairing itself is
 * the caller's business.
 */
class SyncQrScannerScreen(
    private val onScanned: (payload: SyncQrPayload) -> Unit,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        // A code the camera read but could not use is called out briefly,
        // rather than leaving the scan looking like nothing happened.
        var invalidScanTick by remember { mutableStateOf(0) }
        LaunchedEffect(invalidScanTick) {
            if (invalidScanTick != 0) {
                delay(3.seconds)
                invalidScanTick = 0
            }
        }

        var hasPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }
        var permissionDenied by remember { mutableStateOf(false) }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasPermission = granted
            permissionDenied = !granted
        }
        LaunchedEffect(Unit) {
            if (!hasPermission && !permissionDenied) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        // One result, handed over before this screen pops; the channel keeps the
        // analyzer thread from re-delivering while that happens.
        val results = remember { Channel<SyncQrPayload>(capacity = 1) }
        LaunchedEffect(Unit) {
            results.receiveAsListFirst()?.let { payload ->
                onScanned(payload)
                navigator.pop()
            }
        }
        DisposableEffect(Unit) {
            onDispose { results.cancel() }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.sync_scan_qr_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                if (hasPermission) {
                    QrCameraPreview(
                        lifecycleOwner = lifecycleOwner,
                        onResult = { text ->
                            val payload = SyncQrPayload.parse(text)
                            if (payload != null) {
                                results.trySend(payload)
                            } else {
                                invalidScanTick++
                            }
                        },
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(MR.strings.sync_scan_qr_permission),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                if (invalidScanTick != 0) {
                    Text(
                        text = stringResource(MR.strings.sync_scan_qr_invalid),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(24.dp)
                            .background(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    @Composable
    private fun QrCameraPreview(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        onResult: (String) -> Unit,
    ) {
        val context = LocalContext.current
        val reader = remember {
            MultiFormatReader().apply {
                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = Executors.newSingleThreadExecutor()

                val mainExecutor = ContextCompat.getMainExecutor(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener(
                    {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        // Frames arrive continuously: report each distinct code once, and hand
                        // it to the main thread since the analyzer must not touch UI state.
                        var lastDecoded: String? = null
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { image ->
                            val text = decode(image, reader)
                            if (text != null && text != lastDecoded) {
                                lastDecoded = text
                                mainExecutor.execute { onResult(text) }
                            } else if (text == null) {
                                lastDecoded = null
                            }
                            image.close()
                        }
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                        } catch (_: Exception) {
                            // No camera (or in use); the empty preview stands.
                        }
                    },
                    mainExecutor,
                )

                previewView
            },
        )
    }

    /** Decodes one camera frame; rotated so the QR is upright. */
    private fun decode(image: ImageProxy, reader: MultiFormatReader): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val dataWidth = plane.rowStride
        val dataHeight = bytes.size / plane.rowStride

        // Rotate 90° per the sensor orientation so portrait scanning works.
        val rotated = rotateYuv90(bytes, dataWidth, dataHeight)
        val width = dataHeight
        val height = dataWidth

        val source = PlanarYUVLuminanceSource(
            rotated,
            width,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun rotateYuv90(data: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(data.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[x * height + (height - 1 - y)] = data[y * width + x]
            }
        }
        return out
    }
}

/** Receives the first result off the channel, or null if cancelled. */
private suspend fun <T> Channel<T>.receiveAsListFirst(): T? = try {
    receive()
} catch (_: Exception) {
    null
}

package com.storyteller.ui.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller.domain.model.PageImage
import java.util.concurrent.Executors

private const val TAG = "CaptureScreen"

@Composable
fun CaptureScreen(
    onNavigateToReader: () -> Unit,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    val executor = remember { Executors.newSingleThreadExecutor() }

    // The executor backs a single non-daemon thread parked on queue.take(), which is
    // a GC root - without an explicit shutdown it is never collected. It is scoped to
    // CaptureScreen's own lifetime (not to Framing) because it must survive the
    // Framing <-> Captured transitions that happen on every retake within one visit
    // to this screen; it is only safe to shut down when the screen itself is torn
    // down, i.e. once, on navigation away.
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    // Once the user has denied twice (or picked "never ask again"), Android stops
    // showing the dialog at all and returns denied immediately - the in-app "Allow
    // camera" button becomes visibly inert and the app is unusable forever. The
    // only reliable signal for that is shouldShowRequestPermissionRationale going
    // false right after a denial, so it is sampled in the result callback rather
    // than read during composition: re-emitting the same PermissionRequired state
    // would not recompose anything (StateFlow conflates equal values), so a
    // composition-time read would never notice the second denial.
    val activity = remember(context) { context.findActivity() }
    var permanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onPermissionResult(granted)
        permanentlyDenied = !granted &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
    }

    LaunchedEffect(Unit) { permissionLauncher.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize()) {
        when (val current = state) {
            CaptureUiState.PermissionRequired -> PermissionRequest(
                permanentlyDenied = permanentlyDenied,
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { context.startActivity(appSettingsIntent(context.packageName)) },
            )

            CaptureUiState.Framing -> {
                // Created once per entry into Framing; the LaunchedEffect below binds
                // the camera to it exactly once rather than on every recomposition.
                val previewView = remember { PreviewView(context) }

                // Tracks the provider once awaitInstance() resolves, purely so onDispose
                // below can find it - bindToLifecycle binds to the Activity lifecycle,
                // not this composable's, so nothing else would ever unbind the camera
                // when the user leaves this screen (e.g. to listen to narration on the
                // reader), and the camera indicator would stay lit the whole time.
                var boundProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

                LaunchedEffect(previewView) {
                    // ProcessCameraProvider.getInstance(ctx).get() blocks the calling
                    // thread; awaitInstance() suspends instead, so this never risks an
                    // ANR even though it runs off a LaunchedEffect on the main thread.
                    val provider: ProcessCameraProvider = ProcessCameraProvider.awaitInstance(context)
                    val preview = CameraPreview.Builder().build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                    boundProvider = provider
                }

                DisposableEffect(previewView) {
                    // If the user leaves before awaitInstance() resolves, boundProvider
                    // is still null here - the coroutine above gets cancelled with
                    // nothing bound yet, so there is nothing to unbind and no throw.
                    onDispose { boundProvider?.unbindAll() }
                }

                AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })

                Button(
                    onClick = {
                        imageCapture.takePicture(
                            executor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    // image.close() must run on every path, success or
                                    // failure, or the capture pipeline stalls after a
                                    // few shots.
                                    try {
                                        val buffer = image.planes[0].buffer
                                        buffer.rewind()
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)
                                        viewModel.onCaptured(bytes)
                                    } finally {
                                        image.close()
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.e(TAG, "image capture failed", exception)
                                }
                            },
                        )
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
                ) { Text("Take photo") }
            }

            is CaptureUiState.Captured -> CapturedPage(
                image = current.image,
                onRetake = viewModel::onRetake,
                onConfirm = { confirmAndNavigate(viewModel, onNavigateToReader) },
            )
        }
    }
}

/**
 * The review branch. It must actually SHOW the photo: the spec rules out automatic
 * blur detection precisely because the user judges the preview visually and
 * retakes, and before this the Captured branch rendered two buttons over an empty
 * Box - PreviewView had left the composition with Framing and the camera was
 * unbound, so the screen went blank at the shutter and the child was asked to
 * choose Retake or Read with nothing to judge.
 *
 * PageImage.bytes is already downscaled to 1568px or less, so decoding it for
 * display is cheap - but it is still remembered on the image rather than decoded
 * again on every recomposition.
 */
@Composable
internal fun CapturedPage(
    image: PageImage,
    onRetake: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val preview = remember(image) {
            BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)?.asImageBitmap()
        }
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = "The page you just photographed",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }

        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            OutlinedButton(onClick = onRetake) { Text("Retake") }
            Button(onClick = onConfirm) { Text("Read this page") }
        }
    }
}

/**
 * Stateless so both halves can be tested without a camera or a real permission
 * grant. [permanentlyDenied] is the dead end the spec's failure table calls out:
 * re-requesting is a no-op once Android has stopped asking, so the only honest
 * affordance left is a deep link into the app's own settings page.
 */
@Composable
internal fun PermissionRequest(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Storyteller needs the camera to read a page.")
        if (permanentlyDenied) {
            Text("Camera access is switched off. Turn it on in Settings to read a page.")
            Button(onClick = onOpenSettings) { Text("Open settings") }
        } else {
            Button(onClick = onRequest) { Text("Allow camera") }
        }
    }
}

/** The app's own settings page - where a permanently denied camera can be re-enabled. */
internal fun appSettingsIntent(packageName: String): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    )

/**
 * LocalContext under Compose can be a ContextWrapper rather than the Activity, and
 * shouldShowRequestPermissionRationale only exists on Activity.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The pipeline must already be running before the reader composes, so it is
 * started before navigating - never the other way round, and never with an
 * await in between. Pulled out of the onClick lambda so this ordering has a
 * unit test ([CaptureViewModelTest]) independent of Compose.
 */
internal fun confirmAndNavigate(viewModel: CaptureViewModel, onNavigateToReader: () -> Unit) {
    viewModel.onConfirm()
    onNavigateToReader()
}

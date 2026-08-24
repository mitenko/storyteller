package com.storyteller.ui.capture

import android.Manifest
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onPermissionResult(granted) }

    LaunchedEffect(Unit) { permissionLauncher.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize()) {
        when (state) {
            CaptureUiState.PermissionRequired -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Storyteller needs the camera to read a page.")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Allow camera")
                }
            }

            CaptureUiState.Framing -> {
                // Created once per entry into Framing; the LaunchedEffect below binds
                // the camera to it exactly once rather than on every recomposition.
                val previewView = remember { PreviewView(context) }

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

            is CaptureUiState.Captured -> Row(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(onClick = viewModel::onRetake) { Text("Retake") }
                Button(
                    onClick = {
                        // The pipeline must already be running before the reader
                        // composes, so start it before navigating - not after
                        // awaiting anything.
                        viewModel.onConfirm()
                        onNavigateToReader()
                    },
                ) { Text("Read this page") }
            }
        }
    }
}

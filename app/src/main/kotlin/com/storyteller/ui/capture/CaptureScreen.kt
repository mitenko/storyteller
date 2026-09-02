package com.storyteller.ui.capture

import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.storyteller.R
import com.storyteller.domain.model.PageImage

private const val TAG = "CaptureScreen"

@Composable
fun CaptureScreen(
    onNavigateToReader: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val bytesReader = remember(context) { contentResolverBytesReader(context.contentResolver) }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        // The URI is read here, inside the callback, while the scanner's grant is
        // still live. Storing it in state and reading it later fails - and fails
        // only on a real device.
        when (val outcome = scanOutcomeOf(result.resultCode, result.data)) {
            is ScanOutcome.Cancelled -> viewModel.onScanCancelled()
            is ScanOutcome.Failed -> viewModel.onScanFailed(outcome.reason)
            is ScanOutcome.Page -> try {
                viewModel.onScanned(bytesReader.read(outcome.uri))
            } catch (e: Throwable) {
                Log.e(TAG, "could not read the scanned page", e)
                viewModel.onScanFailed("The scanned page could not be opened. Try again.")
            }
        }
    }

    val startScan: () -> Unit = {
        val host = activity
        if (host == null) {
            viewModel.onScanFailed("The scanner could not start. Try again.")
        } else {
            GmsDocumentScanning.getClient(PAGE_SCANNER_OPTIONS)
                .getStartScanIntent(host)
                .addOnSuccessListener { sender ->
                    scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
                }
                .addOnFailureListener { e ->
                    // Play Services may still be fetching the scanner module on
                    // first use, so this is retryable rather than terminal.
                    Log.e(TAG, "could not start the document scanner", e)
                    viewModel.onScanFailed("The scanner is not ready yet. Try again.")
                }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val current = state) {
            CaptureUiState.Idle -> ScanPrompt(onScan = startScan)
            is CaptureUiState.Failed -> ScanFailed(reason = current.reason, onRetry = startScan)
            is CaptureUiState.Captured -> CapturedPage(
                image = current.image,
                onRetake = viewModel::onRetake,
                onConfirm = { confirmAndNavigate(viewModel, onNavigateToReader) },
            )
        }

        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        ) {
            Icon(painter = painterResource(R.drawable.ic_settings), contentDescription = "Settings")
        }
    }
}

/** The idle screen: one button that opens the scanner. */
@Composable
internal fun ScanPrompt(onScan: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Hold the phone over a page.")
        Button(onClick = onScan) {
            Icon(
                painter = painterResource(R.drawable.ic_photo_camera),
                contentDescription = "Read a page",
            )
        }
    }
}

/**
 * Shown when a scan could not produce a page. Says what went wrong rather than
 * returning to an unchanged screen: with CameraX gone there is no other capture
 * path, so a silent failure would leave the app looking simply inert.
 */
@Composable
internal fun ScanFailed(reason: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(reason)
        Button(onClick = onRetry) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = "Try again",
            )
        }
    }
}

/**
 * The review branch. It must actually SHOW the page: the user judges it visually
 * and retakes, so two buttons over an empty Box is not a review screen.
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
                contentDescription = "The page you just scanned",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }

        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            OutlinedButton(onClick = onRetake) {
                Icon(painter = painterResource(R.drawable.ic_refresh), contentDescription = "Retake")
            }
            Button(onClick = onConfirm) {
                Icon(painter = painterResource(R.drawable.ic_check), contentDescription = "Read this page")
            }
        }
    }
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

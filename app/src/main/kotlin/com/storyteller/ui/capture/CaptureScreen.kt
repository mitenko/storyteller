package com.storyteller.ui.capture

import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.storyteller.R

private const val TAG = "CaptureScreen"

@Composable
fun CaptureScreen(
    onNavigateToReader: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
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

    // Scaffold, for the window insets rather than for a bar. This screen carried a
    // bare Box until the icons were found sitting under the status bar and the camera
    // cutout on a Pixel - 16dp from the top of the WINDOW, not from below the system
    // bars. ReaderFrame's kdoc already states the rule this screen was the last to
    // follow: Scaffold owns the insets so content does not have to guess at them.
    //
    // No topBar: the capture screen is deliberately bare, one instruction and one
    // button, and a title above it would say nothing a child can read.
    Scaffold { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val current = state) {
                CaptureUiState.Idle -> ScanPrompt(onScan = startScan)
                is CaptureUiState.Failed -> ScanFailed(reason = current.reason, onRetry = startScan)
                // No review step. The ML Kit scanner already ends in its own
                // confirm-or-retake screen, so a second "is this page alright?" made a
                // child approve the same photograph twice to hear one page read. The
                // scan now goes straight to the reader.
                //
                // Keyed on the image so a NEW scan re-fires this; onHandedOff() then
                // returns the screen to Idle, without which coming back for the next
                // page would bounce straight into the reader again.
                is CaptureUiState.Captured -> LaunchedEffect(current.image) {
                    confirmAndNavigate(viewModel, onNavigateToReader)
                    viewModel.onHandedOff()
                }
            }

            Row(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                IconButton(onClick = onOpenLibrary) {
                    Icon(painter = painterResource(R.drawable.ic_library), contentDescription = "Pages you have read")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(painter = painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                }
            }
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
 * The pipeline must already be running before the reader composes, so it is
 * started before navigating - never the other way round, and never with an
 * await in between. Pulled out of the onClick lambda so this ordering has a
 * unit test ([CaptureViewModelTest]) independent of Compose.
 */
internal fun confirmAndNavigate(viewModel: CaptureViewModel, onNavigateToReader: () -> Unit) {
    viewModel.onConfirm()
    onNavigateToReader()
}

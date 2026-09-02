# Document-Scanner Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the CameraX capture path with the ML Kit Document Scanner, so the vision call receives a rectified page instead of a photograph of a desk.

**Architecture:** Two new seams in `ui/capture` — `PageScanner` (the only file importing ML Kit) and `PageBytesReader` (the only file touching `ContentResolver`) — keep Play Services out of the composable and the view model. `CaptureScreen` shrinks to a launch button plus the existing confirm UI. Everything downstream of `PageImage` is untouched.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, ML Kit Document Scanner (Play Services), Robolectric, JUnit 4.

**Spec:** [`docs/superpowers/specs/2026-08-31-storyteller-document-scanner-capture-design.md`](../specs/2026-08-31-storyteller-document-scanner-capture-design.md)

## Global Constraints

- Scanner options are exactly: `SCANNER_MODE_FULL`, `setPageLimit(1)`, `setResultFormats(RESULT_FORMAT_JPEG)`, `setGalleryImportAllowed(false)`.
- Dependency: `com.google.android.gms:play-services-mlkit-document-scanner:16.0.0`.
- `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36`. Do not change these.
- Robolectric is pinned project-wide to `sdk = 34` in `app/src/test/resources/robolectric.properties`. **Do not bump it** — SDK 36 needs Java 21 and this toolchain is Java 17.
- `buildToolsVersion` is deliberately unset. Do not pin it.
- Only `PageScanner.kt` may import `com.google.mlkit.*`. Only `PageBytesReader.kt` may touch `ContentResolver`.
- Commits in this repo use the author already configured (`mitenko`). **Do not add a `Co-Authored-By` AI trailer.**
- Run Gradle in the foreground and wait for the real exit code. If you hit `BindException: Address already in use`, run `./gradlew --stop` and retry; a long build is not a hang.

---

### Task 1: The scanner gateway

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts:86-88`
- Create: `app/src/main/kotlin/com/storyteller/ui/capture/PageScanner.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/capture/PageScannerTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `sealed interface ScanOutcome` with `ScanOutcome.Page(val uri: Uri)`, `ScanOutcome.Cancelled`, `ScanOutcome.Failed(val reason: String)`; `val PAGE_SCANNER_OPTIONS: GmsDocumentScannerOptions`; `fun scanOutcomeOf(resultCode: Int, data: Intent?): ScanOutcome`.

This task only adds the dependency; it does not remove CameraX. The build stays green because nothing is rewired yet.

- [ ] **Step 1: Add the dependency**

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
mlkitDocumentScanner = "16.0.0"
```

and to `[libraries]`:

```toml
mlkit-document-scanner = { group = "com.google.android.gms", name = "play-services-mlkit-document-scanner", version.ref = "mlkitDocumentScanner" }
```

In `app/build.gradle.kts`, immediately after the three `camerax` lines (`:86-88`), add:

```kotlin
    implementation(libs.mlkit.document.scanner)
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/kotlin/com/storyteller/ui/capture/PageScannerTest.kt`:

```kotlin
package com.storyteller.ui.capture

import android.app.Activity
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers every branch of the activity result that does NOT need a real
 * GmsDocumentScanningResult. A genuine success payload cannot be fabricated
 * off-device - Play Services builds it - so ScanOutcome.Page is verified on
 * device per the spec's section 9, not here. Everything that can fail without
 * Play Services is pinned down here, because those are the paths a user hits
 * when the module is missing and the ones no manual walkthrough will reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PageScannerTest {

    @Test fun `backing out of the scanner is cancellation, not failure`() {
        assertEquals(ScanOutcome.Cancelled, scanOutcomeOf(Activity.RESULT_CANCELED, null))
    }

    @Test fun `cancellation is still cancellation when an intent comes back with it`() {
        assertEquals(ScanOutcome.Cancelled, scanOutcomeOf(Activity.RESULT_CANCELED, Intent()))
    }

    @Test fun `a success code with no data is a failure, not a silent no-op`() {
        val outcome = scanOutcomeOf(Activity.RESULT_OK, null)
        assertTrue("expected Failed, got $outcome", outcome is ScanOutcome.Failed)
    }

    @Test fun `a success code with an empty intent is a failure`() {
        val outcome = scanOutcomeOf(Activity.RESULT_OK, Intent())
        assertTrue("expected Failed, got $outcome", outcome is ScanOutcome.Failed)
    }

    @Test fun `an unexpected result code is a failure`() {
        val outcome = scanOutcomeOf(42, Intent())
        assertTrue("expected Failed, got $outcome", outcome is ScanOutcome.Failed)
    }

    @Test fun `every failure carries a reason a person could read`() {
        val outcome = scanOutcomeOf(Activity.RESULT_OK, null) as ScanOutcome.Failed
        assertTrue("reason was blank", outcome.reason.isNotBlank())
    }

    @Test fun `the scanner is configured for exactly one JPEG page with no gallery`() {
        // Guards the four settings the spec fixes; a silent default change here
        // would alter what the child sees with no other test noticing.
        assertEquals(1, PAGE_SCANNER_OPTIONS.pageLimit)
        assertEquals(false, PAGE_SCANNER_OPTIONS.isGalleryImportAllowed)
    }
}
```

- [ ] **Step 3: Run it to make sure it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.ui.capture.PageScannerTest"`
Expected: FAIL — `Unresolved reference: scanOutcomeOf` / `ScanOutcome` / `PAGE_SCANNER_OPTIONS`.

- [ ] **Step 4: Write the implementation**

Create `app/src/main/kotlin/com/storyteller/ui/capture/PageScanner.kt`:

```kotlin
package com.storyteller.ui.capture

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * What came back from the scanner, with cancellation separated from failure.
 *
 * They are separated because they mean opposite things to a child: backing out
 * is a decision and must be silent, while a scanner that could not run is an
 * error and must say so. Collapsing the two is how the bubble-crop bug stayed
 * invisible for weeks - see docs/issues/2026-08-31-bubble-crops-are-wrong.md.
 */
sealed interface ScanOutcome {
    data class Page(val uri: Uri) : ScanOutcome
    data object Cancelled : ScanOutcome
    data class Failed(val reason: String) : ScanOutcome
}

/**
 * SCANNER_MODE_FULL is chosen for automatic capture - it fires when the page is
 * square in frame, so a child only has to hold the phone over the book - and for
 * the ML cleaning that erases the thumbs holding the book open. Gallery import is
 * off: the app reads the book in front of the child, and the extra button is a
 * place to get lost. Page limit is 1; a book is read one page at a time.
 */
val PAGE_SCANNER_OPTIONS: GmsDocumentScannerOptions = GmsDocumentScannerOptions.Builder()
    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
    .setPageLimit(1)
    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
    .setGalleryImportAllowed(false)
    .build()

/**
 * Maps a raw activity result onto [ScanOutcome].
 *
 * Every path that is not an actual page is an explicit branch: there is no
 * fallback capture left once CameraX is gone, so a result this function cannot
 * make sense of must surface as [ScanOutcome.Failed] rather than quietly
 * returning the user to an unchanged screen.
 */
fun scanOutcomeOf(resultCode: Int, data: Intent?): ScanOutcome {
    if (resultCode == Activity.RESULT_CANCELED) return ScanOutcome.Cancelled
    if (resultCode != Activity.RESULT_OK) {
        return ScanOutcome.Failed("The scanner stopped unexpectedly. Try again.")
    }
    if (data == null) return ScanOutcome.Failed("The scanner returned no page. Try again.")

    val result = runCatching { GmsDocumentScanningResult.fromActivityResultIntent(data) }
        .getOrNull()
        ?: return ScanOutcome.Failed("The scanner returned no page. Try again.")

    val uri = result.pages?.firstOrNull()?.imageUri
        ?: return ScanOutcome.Failed("The scanner returned no page. Try again.")

    return ScanOutcome.Page(uri)
}
```

- [ ] **Step 5: Run the tests and make sure they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.ui.capture.PageScannerTest"`
Expected: PASS, 7 tests.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/kotlin/com/storyteller/ui/capture/PageScanner.kt \
        app/src/test/kotlin/com/storyteller/ui/capture/PageScannerTest.kt
git commit -m "feat: add the document-scanner gateway"
```

---

### Task 2: Reading the result URI to bytes

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/ui/capture/PageBytesReader.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/capture/PageBytesReaderTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `fun interface PageBytesReader { fun read(uri: Uri): ByteArray }` and `fun contentResolverBytesReader(resolver: ContentResolver): PageBytesReader`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/storyteller/ui/capture/PageBytesReaderTest.kt`:

```kotlin
package com.storyteller.ui.capture

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scanner hands back a URI that is granted to this app for one result only.
 * These tests pin the reader's contract: it either returns bytes or throws, and
 * it never returns an empty array that would reach the vision call as a valid
 * but blank page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PageBytesReaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `reads the bytes behind a readable uri`() {
        val bytes = ByteArray(256) { it.toByte() }
        val file = File.createTempFile("page", ".jpg", context.cacheDir).apply { writeBytes(bytes) }

        val read = contentResolverBytesReader(context.contentResolver).read(Uri.fromFile(file))

        assertArrayEquals(bytes, read)
    }

    @Test fun `a uri with nothing behind it throws rather than returning empty`() {
        val missing = Uri.fromFile(File(context.cacheDir, "does-not-exist.jpg"))

        assertThrows(IOException::class.java) {
            contentResolverBytesReader(context.contentResolver).read(missing)
        }
    }

    @Test fun `a resolver that yields no stream throws`() {
        val unresolvable = Uri.parse("content://com.storyteller.absent/page")

        assertThrows(IOException::class.java) {
            contentResolverBytesReader(context.contentResolver).read(unresolvable)
        }
    }

    @Test fun `an empty file throws rather than passing a blank page downstream`() {
        val file = File.createTempFile("empty", ".jpg", context.cacheDir)

        assertThrows(IOException::class.java) {
            contentResolverBytesReader(context.contentResolver).read(Uri.fromFile(file))
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.ui.capture.PageBytesReaderTest"`
Expected: FAIL — `Unresolved reference: contentResolverBytesReader`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/storyteller/ui/capture/PageBytesReader.kt`:

```kotlin
package com.storyteller.ui.capture

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException

/**
 * Turns the scanner's result URI into bytes.
 *
 * A `fun interface` so the view model and the screen can be tested with a fake:
 * every failure below is a real path a child can hit, and none of them should
 * need Play Services to exercise.
 */
fun interface PageBytesReader {
    /** @throws IOException if [uri] cannot be read, or holds nothing. */
    fun read(uri: Uri): ByteArray
}

/**
 * The real reader.
 *
 * Must be called while the scanner's URI grant is still live - that is, from
 * inside the activity-result callback. The grant is scoped to the single result,
 * so a URI stashed in state and read later will fail, and it will fail only on a
 * real device.
 *
 * An empty read throws rather than returning an empty array: an empty JPEG would
 * travel all the way to the vision call and come back as an unexplained parse
 * failure, which is exactly the kind of silent degradation this app has already
 * been bitten by once.
 */
fun contentResolverBytesReader(resolver: ContentResolver): PageBytesReader = PageBytesReader { uri ->
    val bytes = try {
        resolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: SecurityException) {
        throw IOException("no permission to read the scanned page", e)
    } ?: throw IOException("could not open the scanned page at $uri")

    if (bytes.isEmpty()) throw IOException("the scanned page was empty")
    bytes
}
```

- [ ] **Step 4: Run the tests and make sure they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.ui.capture.PageBytesReaderTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui/capture/PageBytesReader.kt \
        app/src/test/kotlin/com/storyteller/ui/capture/PageBytesReaderTest.kt
git commit -m "feat: read the scanned page's uri to bytes behind a testable seam"
```

---

### Task 3: The capture state machine and screen

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/ui/capture/CaptureUiState.kt`
- Modify: `app/src/main/kotlin/com/storyteller/ui/capture/CaptureViewModel.kt`
- Modify: `app/src/main/kotlin/com/storyteller/ui/capture/CaptureScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml:4`
- Modify: `app/build.gradle.kts:86-88`, `gradle/libs.versions.toml`
- Test: `app/src/test/kotlin/com/storyteller/ui/capture/CaptureViewModelTest.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/capture/CaptureScreenTest.kt`

**Interfaces:**
- Consumes: `ScanOutcome`, `PAGE_SCANNER_OPTIONS`, `scanOutcomeOf` (Task 1); `PageBytesReader`, `contentResolverBytesReader` (Task 2).
- Produces: `CaptureUiState.Idle`, `CaptureUiState.Captured(image)`, `CaptureUiState.Failed(reason)`; `CaptureViewModel.onScanned(jpeg: ByteArray)`, `.onScanCancelled()`, `.onScanFailed(reason: String)`, `.onRetake()`, `.onConfirm()`.

These three files change together because they are one state machine: `CaptureUiState`'s cases are exhaustively matched in `CaptureScreen` and produced by `CaptureViewModel`, so no smaller split leaves a compiling build.

- [ ] **Step 1: Write the failing view-model test**

Replace `app/src/test/kotlin/com/storyteller/ui/capture/CaptureViewModelTest.kt` entirely:

```kotlin
package com.storyteller.ui.capture

import android.graphics.Bitmap
import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PipelineState
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class RecordingPipeline : ReadingPipeline {
    override val state = MutableStateFlow<PipelineState>(PipelineState.Idle) as StateFlow<PipelineState>
    val started = mutableListOf<PageImage>()
    var resets = 0
    override fun start(image: PageImage) { started += image }
    override fun retry() = Unit
    override fun reset() { resets++ }
}

@RunWith(RobolectricTestRunner::class)
class CaptureViewModelTest {

    private fun jpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            .toByteArray()
    }

    @Test fun `starts idle, with no permission gate in the way`() = runTest {
        assertEquals(CaptureUiState.Idle, CaptureViewModel(RecordingPipeline()).uiState.value)
    }

    @Test fun `a scanned page moves to Captured`() = runTest {
        val vm = CaptureViewModel(RecordingPipeline())
        vm.onScanned(jpeg())
        assertTrue(vm.uiState.value is CaptureUiState.Captured)
    }

    @Test fun `confirm starts the pipeline with the scanned image`() = runTest {
        val pipeline = RecordingPipeline()
        val vm = CaptureViewModel(pipeline)
        vm.onScanned(jpeg())
        vm.onConfirm()

        assertEquals(1, pipeline.started.size)
        assertEquals("image/jpeg", pipeline.started.single().mimeType)
        assertTrue(pipeline.started.single().bytes.isNotEmpty())
    }

    @Test fun `confirm before any scan does nothing`() = runTest {
        val pipeline = RecordingPipeline()
        CaptureViewModel(pipeline).onConfirm()
        assertTrue(pipeline.started.isEmpty())
    }

    @Test fun `retake returns to Idle and resets the pipeline so a stale page cannot be shown`() = runTest {
        val pipeline = RecordingPipeline()
        val vm = CaptureViewModel(pipeline)
        vm.onScanned(jpeg())
        vm.onRetake()

        assertEquals(CaptureUiState.Idle, vm.uiState.value)
        assertEquals(1, pipeline.resets)
    }

    @Test fun `a failed scan surfaces the reason rather than failing silently`() = runTest {
        val vm = CaptureViewModel(RecordingPipeline())
        vm.onScanFailed("The scanner could not start.")
        assertEquals(CaptureUiState.Failed("The scanner could not start."), vm.uiState.value)
    }

    @Test fun `a failed scan discards an earlier page so it cannot be confirmed by mistake`() = runTest {
        val pipeline = RecordingPipeline()
        val vm = CaptureViewModel(pipeline)
        vm.onScanned(jpeg())
        vm.onScanFailed("The scanner could not start.")

        assertTrue(vm.uiState.value is CaptureUiState.Failed)
        vm.onConfirm()
        assertTrue("a discarded page must not be confirmable", pipeline.started.isEmpty())
        assertEquals("replacing a captured page must reset the pipeline", 1, pipeline.resets)
    }

    @Test fun `cancelling from Idle changes nothing and does not reset the pipeline`() = runTest {
        val pipeline = RecordingPipeline()
        val vm = CaptureViewModel(pipeline)
        vm.onScanCancelled()

        assertEquals(CaptureUiState.Idle, vm.uiState.value)
        assertEquals("backing out of a scan has nothing to reset", 0, pipeline.resets)
    }

    @Test fun `cancelling keeps a page already on screen`() = runTest {
        val pipeline = RecordingPipeline()
        val vm = CaptureViewModel(pipeline)
        vm.onScanned(jpeg())
        vm.onScanCancelled()

        assertTrue(vm.uiState.value is CaptureUiState.Captured)
        assertEquals(0, pipeline.resets)
    }

    @Test fun `cancelling clears a failure banner`() = runTest {
        val vm = CaptureViewModel(RecordingPipeline())
        vm.onScanFailed("The scanner could not start.")
        vm.onScanCancelled()
        assertEquals(CaptureUiState.Idle, vm.uiState.value)
    }

    @Test fun `confirmAndNavigate starts the pipeline before navigating away`() = runTest {
        val pipeline = RecordingPipeline()
        val vm = CaptureViewModel(pipeline)
        vm.onScanned(jpeg())

        var pipelineWasRunningWhenNavigated = false
        confirmAndNavigate(vm) {
            pipelineWasRunningWhenNavigated = pipeline.started.isNotEmpty()
        }

        assertTrue(pipelineWasRunningWhenNavigated)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.ui.capture.CaptureViewModelTest"`
Expected: FAIL — `Unresolved reference: Idle` / `onScanned` / `onScanFailed`.

- [ ] **Step 3: Rewrite the state**

Replace `app/src/main/kotlin/com/storyteller/ui/capture/CaptureUiState.kt`:

```kotlin
package com.storyteller.ui.capture

import com.storyteller.domain.model.PageImage

sealed interface CaptureUiState {
    /** Ready to scan. No camera permission gate: the scanner runs in its own process. */
    data object Idle : CaptureUiState

    data class Captured(val image: PageImage) : CaptureUiState

    /**
     * The scan could not produce a page. Carries a reason because there is no
     * fallback capture path left - a child left on an unchanged screen has no way
     * to know anything went wrong.
     */
    data class Failed(val reason: String) : CaptureUiState
}
```

- [ ] **Step 4: Rewrite the view model**

Replace `app/src/main/kotlin/com/storyteller/ui/capture/CaptureViewModel.kt`:

```kotlin
package com.storyteller.ui.capture

import androidx.lifecycle.ViewModel
import com.storyteller.domain.ReadingPipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val pipeline: ReadingPipeline,
) : ViewModel() {

    private val _uiState = MutableStateFlow<CaptureUiState>(CaptureUiState.Idle)
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    /**
     * [jpeg] is the scanner's rectified page. Downscaling happens here, at the
     * boundary, exactly as it did for CameraX captures.
     *
     * `rotationDegrees = 0` because the scanner returns an upright image - it has
     * already rectified the page - where CameraX handed back a sensor-oriented
     * frame with the correction in metadata. This is verified on device; see the
     * spec's section 7.2.
     */
    fun onScanned(jpeg: ByteArray) {
        discardAnyCapturedPage()
        _uiState.value = CaptureUiState.Captured(downscaleToPageImage(jpeg, rotationDegrees = 0))
    }

    /**
     * The user backed out of the scanner. A decision, not an error, so it is
     * silent: a page already on screen stays there and the pipeline is left alone.
     * Only a stale failure banner is cleared.
     */
    fun onScanCancelled() {
        if (_uiState.value is CaptureUiState.Failed) _uiState.value = CaptureUiState.Idle
    }

    fun onScanFailed(reason: String) {
        discardAnyCapturedPage()
        _uiState.value = CaptureUiState.Failed(reason)
    }

    fun onRetake() {
        discardAnyCapturedPage()
        _uiState.value = CaptureUiState.Idle
    }

    /** Starts the pipeline; the caller navigates to the reader afterwards. */
    fun onConfirm() {
        val captured = _uiState.value as? CaptureUiState.Captured ?: return
        pipeline.start(captured.image)
    }

    /**
     * Replacing or dropping a captured page must reset the pipeline, or a read
     * started for the previous page keeps running and the reader shows it.
     *
     * Gated on Captured deliberately: backing out of a scanner opened from an idle
     * screen has nothing to reset, and resetting there would kill an unrelated
     * in-flight read for no reason.
     */
    private fun discardAnyCapturedPage() {
        if (_uiState.value is CaptureUiState.Captured) pipeline.reset()
    }
}
```

- [ ] **Step 5: Run the view-model tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.ui.capture.CaptureViewModelTest"`
Expected: still FAILS to compile, because `CaptureScreen.kt` references the removed states. That is expected; Step 6 fixes it.

- [ ] **Step 6: Rewrite the screen**

Replace `app/src/main/kotlin/com/storyteller/ui/capture/CaptureScreen.kt` entirely:

```kotlin
package com.storyteller.ui.capture

import android.graphics.BitmapFactory
import android.util.Log
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
import androidx.compose.ui.viewinterop.LocalActivity
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
```

- [ ] **Step 7: Rewrite the screen test**

Replace `app/src/test/kotlin/com/storyteller/ui/capture/CaptureScreenTest.kt` entirely:

```kotlin
package com.storyteller.ui.capture

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.storyteller.domain.model.PageImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the three branches through their stateless composables, so none of
 * them needs Play Services, a scanner, or Hilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun pageImage() = PageImage(ByteArray(64) { it.toByte() }, "image/jpeg")

    @Test fun `the idle screen offers a way to scan`() {
        compose.setContent { ScanPrompt(onScan = {}) }
        compose.onNodeWithContentDescription("Read a page").assertIsDisplayed()
    }

    @Test fun `the idle screen reports the scan request once per tap`() {
        var scans = 0
        compose.setContent { ScanPrompt(onScan = { scans++ }) }
        compose.onNodeWithContentDescription("Read a page").performClick()
        assertEquals(1, scans)
    }

    @Test fun `a failure shows its reason rather than an unchanged screen`() {
        compose.setContent { ScanFailed(reason = "The scanner is not ready yet.", onRetry = {}) }
        compose.onNodeWithText("The scanner is not ready yet.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Try again").assertIsDisplayed()
    }

    @Test fun `retry from a failure asks for another scan`() {
        var retries = 0
        compose.setContent { ScanFailed(reason = "nope", onRetry = { retries++ }) }
        compose.onNodeWithContentDescription("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test fun `the review branch shows the page it is asking about`() {
        compose.setContent { CapturedPage(pageImage(), onRetake = {}, onConfirm = {}) }
        compose.onNodeWithContentDescription("The page you just scanned").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retake").assertIsDisplayed()
        compose.onNodeWithContentDescription("Read this page").assertIsDisplayed()
    }

    @Test fun `review buttons report retake and confirm separately`() {
        var retakes = 0
        var confirms = 0
        compose.setContent {
            CapturedPage(pageImage(), onRetake = { retakes++ }, onConfirm = { confirms++ })
        }
        compose.onNodeWithContentDescription("Retake").performClick()
        compose.onNodeWithContentDescription("Read this page").performClick()
        assertEquals(1, retakes)
        assertEquals(1, confirms)
    }
}
```

- [ ] **Step 8: Drop CameraX and the CAMERA permission**

In `app/build.gradle.kts`, delete these three lines (`:86-88`):

```kotlin
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
```

In `gradle/libs.versions.toml`, delete the `camerax = "1.5.0"` version entry and the three `camerax-*` library entries.

In `app/src/main/AndroidManifest.xml`, delete line 4:

```xml
    <uses-permission android:name="android.permission.CAMERA" />
```

Leave the `uses-feature` line alone — it governs Play Store device filtering and is still correct with gallery import off.

- [ ] **Step 9: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. If it fails with `IOException: Unable to delete directory .../test-results/.../output.bin`, that is an orphaned test-worker JVM, not a code failure: run `./gradlew --stop`, delete `app/build/test-results` and `app/build/reports`, re-run.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui/capture/ \
        app/src/test/kotlin/com/storyteller/ui/capture/ \
        app/src/main/AndroidManifest.xml app/build.gradle.kts gradle/libs.versions.toml
git commit -m "feat: scan a rectified page instead of photographing a desk"
```

---

### Task 4: Answer the three unverified device facts

**Files:**
- Modify: `docs/superpowers/specs/2026-08-31-storyteller-document-scanner-capture-design.md` §7
- Possibly modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: the installed build from Task 3.
- Produces: a §7 that states measured answers instead of open questions.

Spec §7 names three things settled only by running the app. This task runs it. **The plan is not complete until §7 contains answers.**

- [ ] **Step 1: Clean install**

§7.1 is only meaningful on a clean install, because the current device already holds a granted CAMERA permission from the existing build.

```bash
adb -s 59251JEBF12416 uninstall com.storyteller
./gradlew installDebug
adb -s 59251JEBF12416 shell monkey -p com.storyteller -c android.intent.category.LAUNCHER 1
```

- [ ] **Step 2: Scan a page**

Scan one graphic-novel page through the app, confirm it, and let the read finish.

- [ ] **Step 3: Record whether the scan worked without CAMERA (§7.1)**

If the scan completed, the host app does not need the permission — record that in §7.1.

If it failed, restore `<uses-permission android:name="android.permission.CAMERA" />` to `AndroidManifest.xml`, reinstate a permission request before `startScan`, rebuild, and correct §7.1 to say the permission is required.

- [ ] **Step 4: Pull the bundle**

```bash
python scripts/diagnostics.py pull --serial 59251JEBF12416
```

- [ ] **Step 5: Check orientation and resolution (§7.2, §7.3)**

```bash
python - <<'PY'
import json, glob, os
d = sorted(glob.glob('diagnostics-pulled/page-*'))[-1]
m = json.load(open(os.path.join(d, 'meta.json')))
print(d)
print('upload  %sx%s' % (m['uploadWidth'], m['uploadHeight']))
print('display %sx%s' % (m['displayWidth'], m['displayHeight']))
print('same orientation:',
      (m['uploadWidth'] < m['uploadHeight']) == (m['displayWidth'] < m['displayHeight']))
PY
```

Then open `page-display.jpg` and `page-upload.jpg` and confirm both are upright and show the page filling the frame.

- **§7.2 passes** if both copies are upright and agree in orientation. If either is sideways, `rotationDegrees = 0` in `CaptureViewModel.onScanned` is wrong — find the scanner's actual orientation and pass it.
- **§7.3 passes** if `displayWidth`/`displayHeight` is large enough for sharp crops. The previous CameraX display copy was 3000x4000. **If the scanner returns materially less, raise it — do not absorb it.** A soft display copy degrades the bubble crops this whole effort exists to serve.

- [ ] **Step 6: Confirm the page fills the frame**

This is the change's actual success criterion. Open `overlay.png` and confirm the page occupies essentially the whole image, rather than the ~65% it did in bundle `page-1788205074358`.

- [ ] **Step 7: Re-run the OCR measurement**

Follow `docs/issues/2026-08-31-bubble-box-accuracy-measured.md` §1 against the new bundle. **Improved IoU is not the success criterion** — §3 predicts it will still be bad. Record whatever it is.

- [ ] **Step 8: Rewrite §7 with the answers, and commit**

Replace §7's three open questions with what was measured. Retitle it from "Three facts unverified on device" to "Three facts, verified on device", with the date and the device.

```bash
git add docs/superpowers/specs/2026-08-31-storyteller-document-scanner-capture-design.md app/src/main/AndroidManifest.xml
git commit -m "docs: record what the scanner actually returns on device"
```

---

## Self-Review

**Spec coverage.** §1 Task 3. §2 no code. §3 no code; its consequence is enforced in Task 4 Step 7. §4 Tasks 1–3, URI-ownership rule in Task 3 Step 6. §5 Task 1 Step 4, guarded by Task 1's options test. §6 all four files across Tasks 1–3. §7 Task 4 in full. §8 Task 1 Step 4 (`scanOutcomeOf` branches) and Task 3 Steps 3–4 (stale-state and reset), tested in Tasks 1 and 3. §9 Tasks 1, 2 and 3 for the unit tables; the on-device list is Task 4. §10 nothing built.

**Placeholder scan.** No TBD/TODO. Every code step carries the code. Task 4's steps are procedural by nature but each names the exact command and the exact pass condition.

**Type consistency.** `ScanOutcome.Page/Cancelled/Failed(reason)` is produced in Task 1 and consumed in Task 3 Step 6 with matching names. `PageBytesReader.read(uri)` produced in Task 2, called in Task 3 Step 6. `CaptureUiState.Idle/Captured/Failed(reason)` produced in Task 3 Step 3, matched exhaustively in Step 6 and asserted in Steps 1 and 7. `onScanned`/`onScanCancelled`/`onScanFailed`/`onRetake`/`onConfirm` consistent across Steps 1, 4 and 6.

**Two known risks, both surfaced rather than hidden.** `PageScannerTest` cannot cover the `ScanOutcome.Page` success path — a real `GmsDocumentScanningResult` cannot be built off-device — so Task 4 Step 2 is its only coverage, and the test file says so. And `LocalActivity` is used in Task 3 Step 6 to get the host activity for `getStartScanIntent`; if it is unavailable in this Compose BOM (`2026.06.01`), substitute the existing `Context.findActivity()` helper, which is in the current `CaptureScreen.kt` and should be carried over rather than deleted.

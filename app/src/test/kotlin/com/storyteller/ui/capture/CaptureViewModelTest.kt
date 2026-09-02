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

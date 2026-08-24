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

    @Test fun `starts in PermissionRequired until permission is granted`() = runTest {
        val vm = CaptureViewModel(RecordingPipeline())
        assertEquals(CaptureUiState.PermissionRequired, vm.uiState.value)
        vm.onPermissionResult(granted = true)
        assertEquals(CaptureUiState.Framing, vm.uiState.value)
    }

    @Test fun `denied permission stays in PermissionRequired`() = runTest {
        val vm = CaptureViewModel(RecordingPipeline())
        vm.onPermissionResult(granted = false)
        assertEquals(CaptureUiState.PermissionRequired, vm.uiState.value)
    }

    @Test fun `capture moves to Captured and retake returns to Framing`() = runTest {
        val vm = CaptureViewModel(RecordingPipeline())
        vm.onPermissionResult(true)
        vm.onCaptured(jpeg())
        assertTrue(vm.uiState.value is CaptureUiState.Captured)
        vm.onRetake()
        assertEquals(CaptureUiState.Framing, vm.uiState.value)
    }

    @Test fun `confirm starts the pipeline with a downscaled image`() = runTest {
        val pipeline = RecordingPipeline()
        val vm = CaptureViewModel(pipeline)
        vm.onPermissionResult(true)
        vm.onCaptured(jpeg())
        vm.onConfirm()

        assertEquals(1, pipeline.started.size)
        assertEquals("image/jpeg", pipeline.started.single().mimeType)
        assertTrue(pipeline.started.single().bytes.isNotEmpty())
    }

    @Test fun `confirm before capture does nothing`() = runTest {
        val pipeline = RecordingPipeline()
        val vm = CaptureViewModel(pipeline)
        vm.onPermissionResult(true)
        vm.onConfirm()
        assertTrue(pipeline.started.isEmpty())
    }

    @Test fun `retake resets the pipeline so a stale page cannot be shown`() = runTest {
        val pipeline = RecordingPipeline()
        val vm = CaptureViewModel(pipeline)
        vm.onPermissionResult(true)
        vm.onCaptured(jpeg())
        vm.onRetake()
        assertEquals(1, pipeline.resets)
    }

    @Test fun `confirmAndNavigate starts the pipeline before navigating away`() = runTest {
        val pipeline = RecordingPipeline()
        val vm = CaptureViewModel(pipeline)
        vm.onPermissionResult(true)
        vm.onCaptured(jpeg())

        var pipelineWasRunningWhenNavigated = false
        confirmAndNavigate(vm) {
            pipelineWasRunningWhenNavigated = pipeline.started.isNotEmpty()
        }

        assertTrue(pipelineWasRunningWhenNavigated)
    }
}

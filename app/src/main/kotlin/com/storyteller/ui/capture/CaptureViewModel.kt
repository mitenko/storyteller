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

    private val _uiState = MutableStateFlow<CaptureUiState>(CaptureUiState.PermissionRequired)
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    fun onPermissionResult(granted: Boolean) {
        _uiState.value = if (granted) CaptureUiState.Framing else CaptureUiState.PermissionRequired
    }

    /**
     * [jpeg] is the raw CameraX capture; downscaling and uprighting happen here, at
     * the boundary. [rotationDegrees] is ImageProxy.imageInfo.rotationDegrees - the
     * only record of which way up the frame is, since the re-encode drops EXIF.
     */
    fun onCaptured(jpeg: ByteArray, rotationDegrees: Int = 0) {
        _uiState.value = CaptureUiState.Captured(downscaleToPageImage(jpeg, rotationDegrees))
    }

    fun onRetake() {
        pipeline.reset()
        _uiState.value = CaptureUiState.Framing
    }

    /** Starts the pipeline; the caller navigates to the reader afterwards. */
    fun onConfirm() {
        val captured = _uiState.value as? CaptureUiState.Captured ?: return
        pipeline.start(captured.image)
    }
}

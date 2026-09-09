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
     * The captured page has been handed to the pipeline and the reader is showing
     * it, so this screen goes back to Idle.
     *
     * It must NOT go through [onRetake], which looks equivalent and is not:
     * [onRetake] calls [discardAnyCapturedPage], and resetting the pipeline here
     * would kill the very read the reader has just navigated to.
     *
     * The reason this exists at all is that [CaptureViewModel] is scoped to the
     * nav back-stack entry, so it survives the trip to the reader. Left in
     * [CaptureUiState.Captured], the screen would auto-advance again the moment a
     * child came back for the next page, bouncing them straight into the reader
     * they just left.
     */
    fun onHandedOff() {
        if (_uiState.value is CaptureUiState.Captured) _uiState.value = CaptureUiState.Idle
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

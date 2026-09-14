package com.storyteller.domain

import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.SpeechUnit
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface, not just a class, so ViewModel tests can emit arbitrary states
 * without assembling three fakes to provoke each one.
 */
interface ReadingPipeline {
    val state: StateFlow<PipelineState>
    fun start(image: PageImage)
    fun retry()
    fun reset()

    /**
     * Reads a page that is already parsed - from the library - skipping the vision
     * call and nothing else.
     *
     * Deliberately routed through the same preparation as a fresh read rather than
     * emitting Ready directly: prefetch, playlist growth, failure mapping,
     * cancellation and both reading modes all keep working, and a stored page
     * behaves identically to a fresh one from the reader's point of view.
     */
    fun openStored(units: List<SpeechUnit>, image: PageImage)
}

package com.storyteller.domain

import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PipelineState
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
}

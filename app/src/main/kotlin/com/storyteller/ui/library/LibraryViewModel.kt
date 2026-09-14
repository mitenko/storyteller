package com.storyteller.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.repository.StoredPageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A shelf of the pages a child has already read, so re-opening one costs nothing. */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: StoredPageRepository,
    private val pipeline: ReadingPipeline,
    // Qualified, not just "the" CoroutineDispatcher: a plain injected
    // CoroutineDispatcher would collide with any other dispatcher binding this
    // graph ever grows, and it is what lets a test hand this the SAME
    // TestDispatcher installed as Dispatchers.Main, so the whole hop through
    // withContext(ioDispatcher) is driven deterministically by
    // advanceUntilIdle() rather than racing a real background thread.
    @Named("ioDispatcher") private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = repository.observeLibrary()
        .map { pages ->
            LibraryUiState(
                pages.map { page ->
                    LibraryItem(
                        id = page.id,
                        photo = page.photo,
                        readAt = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(page.readAt)),
                    )
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState(emptyList()))

    /**
     * Loads the stored page and starts the pipeline the same way [CaptureViewModel]
     * does for a fresh scan; the caller navigates to the reader afterwards.
     *
     * The [PageImage] built here has empty upload `bytes` and zero `width`/`height`
     * — that is correct, not a bug: a stored page never reaches the vision call
     * ([ReadingPipeline.openStored] skips it), and those fields only matter to code
     * on that path. Re-encoding the photo to fill them in would defeat the entire
     * point of storing the page in the first place.
     *
     * The photo is read on [ioDispatcher], not on whatever dispatcher this
     * coroutine started on: `viewModelScope` runs on `Dispatchers.Main.immediate`,
     * and this file is a full-size stored JPEG (1-3 MB) — reading it inline would
     * be exactly the main-thread jank the thumbnail decode elsewhere in this
     * screen was written to avoid.
     *
     * The read is also allowed to fail. Eviction at the library's page cap, or the
     * child deleting this very page, can remove the photograph between
     * [StoredPageRepository.open] returning and this read running, and
     * `File.readBytes()` then throws [IOException]. Per the spec's error-handling
     * table, a photograph missing from disk means the page opens text-only, the
     * same outcome as a page whose crops fail to decode ([cropBubble][com.storyteller.ui.reader.cropBubble]
     * already returns null and falls back to text for that case) — so this catches
     * the read failure and hands the pipeline an empty image rather than letting
     * the exception escape and crash the app. A library entry must never fail to
     * open outright: a page that cannot show its picture is still a page a child
     * can hear.
     */
    fun open(id: String) {
        // Called SYNCHRONOUSLY, before the launch below and therefore before
        // repository.open() has a chance to suspend, for the same reason
        // CaptureViewModel.onConfirm calls pipeline.start synchronously:
        // LibraryScreen navigates to the reader the instant this function
        // returns, without waiting for it. ReadingPipeline is
        // @ActivityRetainedScoped and outlives the reader screen, so without
        // this the newly created ReaderViewModel attaches to a StateFlow
        // still holding Ready from whatever page was read before this one.
        // In Auto mode that stale Ready gets queued as if it were the new
        // page, and the new page's own units then arrive as Preparing - a
        // state ReaderViewModel does not treat as a reset point - so they
        // are silently diffed against the previous page's unit count and
        // can be dropped entirely. Resetting here, before navigation can
        // attach anything to the stale state, closes that window.
        pipeline.reset()
        viewModelScope.launch {
            val page = repository.open(id) ?: return@launch
            val displayBytes = try {
                withContext(ioDispatcher) { page.photo.readBytes() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ByteArray(0)
            }
            pipeline.openStored(
                page.units,
                PageImage(bytes = ByteArray(0), mimeType = "image/jpeg", displayBytes = displayBytes),
            )
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}

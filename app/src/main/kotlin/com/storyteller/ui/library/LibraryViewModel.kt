package com.storyteller.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.repository.StoredPageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A shelf of the pages a child has already read, so re-opening one costs nothing. */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: StoredPageRepository,
    private val pipeline: ReadingPipeline,
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
     */
    fun open(id: String) {
        viewModelScope.launch {
            val page = repository.open(id) ?: return@launch
            pipeline.openStored(
                page.units,
                PageImage(bytes = ByteArray(0), mimeType = "image/jpeg", displayBytes = page.photo.readBytes()),
            )
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}

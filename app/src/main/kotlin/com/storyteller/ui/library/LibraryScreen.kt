package com.storyteller.ui.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onOpenPage: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LibraryContent(
        state = state,
        onOpen = { id ->
            viewModel.open(id)
            onOpenPage()
        },
        onDelete = viewModel::delete,
        onBack = onBack,
    )
}

/**
 * Stateless with respect to its caller - tests can drive it directly without
 * Hilt or a real repository - but it keeps the pending-delete confirmation as
 * its own local [remember]ed state, the same way a screen composable owns any
 * UI-only state that its caller has no reason to know about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryContent(
    state: LibraryUiState,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit = {},
) {
    // The item a long-press is offering to delete, awaiting confirmation. This
    // is a children's app and the card's only other gesture is a tap to open
    // it, so a long-press must ask before it deletes the page, its photograph,
    // and its unshared audio outright.
    var pendingDelete by remember { mutableStateOf<LibraryItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pages you have read") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No pages yet.")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    LibraryCard(item = item, onOpen = onOpen, onRequestDelete = { pendingDelete = item })
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this page?") },
            text = { Text("This removes the page read on ${item.readAt}, its photo, and its audio.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(item.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/** One stored page: its thumbnail and the day it was read. Long-press offers to delete it. */
@Composable
private fun LibraryCard(item: LibraryItem, onOpen: (String) -> Unit, onRequestDelete: () -> Unit) {
    // Decoded off the composition thread, exactly as PanelCard decodes a panel
    // crop: a grid of full-size JPEGs decoded inline on the main thread would drop
    // frames while it scrolls.
    val bitmap by produceState<ImageBitmap?>(null, item.photo) {
        value = item.photo?.let { file ->
            withContext(Dispatchers.Default) { decodeThumbnail(file) }
        }?.asImageBitmap()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpen(item.id) },
                onLongClick = onRequestDelete,
            )
            .semantics { contentDescription = "Read this page again" },
    ) {
        Column {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    contentScale = ContentScale.Crop,
                )
            }
            Text(item.readAt, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Sampled down for a grid cell rather than decoded full-size: a shelf of a dozen
 * stored pages decoding their original JPEGs at full resolution is exactly the
 * frame-drop this function exists to avoid.
 */
private fun decodeThumbnail(file: File, targetSize: Int = 240): android.graphics.Bitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
    val sampleSize = generateSequence(1) { it * 2 }.first { longEdge / (it * 2) < targetSize }
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}

data class LibraryUiState(val items: List<LibraryItem>)

/** [photo] is the stored page's own photograph, decoded into a thumbnail by the card. */
data class LibraryItem(val id: String, val photo: File?, val readAt: String)

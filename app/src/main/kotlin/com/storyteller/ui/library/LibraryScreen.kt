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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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

/** Stateless, so tests can drive it directly without Hilt or a real repository. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryContent(
    state: LibraryUiState,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit = {},
) {
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
                    LibraryCard(item = item, onOpen = onOpen, onDelete = onDelete)
                }
            }
        }
    }
}

/** One stored page: its thumbnail and the day it was read. Long-press deletes it. */
@Composable
private fun LibraryCard(item: LibraryItem, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
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
                onLongClick = { onDelete(item.id) },
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

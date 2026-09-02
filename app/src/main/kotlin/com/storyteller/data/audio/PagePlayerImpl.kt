package com.storyteller.data.audio

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.repository.PagePlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A growing Media3 playlist that plays a page through in reading order.
 *
 * [play] starts playback with whatever units are ready; [append] adds more to the
 * end of the same live playlist as synthesis for the rest of the page completes,
 * so the reader hears line 1 while lines 2+ are still being fetched. ExoPlayer
 * absorbs additions to a playing playlist without restarting, which is exactly
 * what makes that overlap safe.
 *
 * Because [append] can lag behind playback, the playlist running dry
 * ([Player.STATE_ENDED]) is ambiguous on its own: it might mean the page is
 * genuinely done, or it might mean synthesis for the next unit simply hasn't
 * landed yet. [endOfPage] resolves that ambiguity by telling this player no more
 * units are coming, so [Player.STATE_ENDED] is only trusted as [PlaybackState.Finished]
 * once it has been called; until then, running dry leaves the state as-is and a
 * later [append] resumes playback.
 *
 * [PlaybackState] intentionally carries no unit index (see its kdoc) — iteration 1
 * does not highlight the current line, so nothing here reports one.
 */
class PagePlayerImpl(context: Context) : PagePlayer {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Set by [endOfPage]; gates whether STATE_ENDED is trusted as Finished. */
    private var pageComplete = false

    // Written only from the player's own (main) looper thread inside
    // onMediaItemTransition below, so no synchronization is needed as long as
    // callers also read it via that same thread (see PagePlayerImplTest, which
    // reads through InstrumentationRegistry.runOnMainSync).
    private val visitedIndices = mutableListOf<Int>()

    /** Test-only: the sequence of playlist indices that became current, in order. */
    val visitedMediaItemIndicesForTest: List<Int> get() = visitedIndices.toList()

    /** Test-only: mirrors [Player.getMediaItemCount] without exposing the player itself. */
    val mediaItemCountForTest: Int get() = player.mediaItemCount

    // Looper is pinned explicitly rather than left to the constructing thread's
    // default: Task 10 resolves this as a Hilt @Singleton, so whichever thread
    // first touches the dependency graph would otherwise decide the player's
    // thread affinity implicitly, far from here.
    private val player = ExoPlayer.Builder(context).setLooper(Looper.getMainLooper()).build().apply {
        addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    _state.value = when (playbackState) {
                        Player.STATE_ENDED -> if (pageComplete) PlaybackState.Finished else _state.value
                        Player.STATE_READY -> if (isPlaying) PlaybackState.Playing(currentMediaItemIndex) else _state.value
                        else -> _state.value
                    }
                }

                // Without this, a playback exception (undecodable file, missing
                // file, codec failure) drives the player to STATE_IDLE, which the
                // onPlaybackStateChanged branch above leaves alone as "else ->
                // _state.value" — Playing would stay Playing forever with no
                // Finished and no failure signal. PlaybackState has no error case
                // (that is Task 12's call to make, not this task's), so the honest
                // minimum here is to stop leaving the caller hanging: move to
                // Finished and log rather than let the reader spin indefinitely.
                override fun onPlayerError(error: PlaybackException) {
                    Log.e("PagePlayerImpl", "playback error", error)
                    _state.value = PlaybackState.Finished
                }

                // Test-only bookkeeping: records which playlist index became
                // current, in order, so a test can assert reading order was
                // preserved instead of only checking the eventual item count.
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    visitedIndices.add(currentMediaItemIndex)
                    // Republish so a consumer following along knows which item is
                    // sounding now; without this the index only ever reports the
                    // item play() started on.
                    if (_state.value is PlaybackState.Playing) {
                        _state.value = PlaybackState.Playing(currentMediaItemIndex)
                    }
                }
            },
        )
    }

    override fun play(units: List<PreparedUnit>) {
        pageComplete = false
        player.setMediaItems(units.map { it.mediaItem() })
        player.prepare()
        player.play()
        _state.value = PlaybackState.Playing(player.currentMediaItemIndex)
    }

    /** Appended at the end, so reading order is preserved as units arrive. */
    override fun append(unit: PreparedUnit) {
        player.addMediaItem(unit.mediaItem())
    }

    /** No more units are coming for the current page; see class kdoc. */
    override fun endOfPage() {
        pageComplete = true
        if (player.playbackState == Player.STATE_ENDED) {
            _state.value = PlaybackState.Finished
        }
    }

    override fun stop() {
        player.stop()
        player.clearMediaItems()
        pageComplete = false
        _state.value = PlaybackState.Idle
    }

    /**
     * Not called anywhere in iteration 1: Task 10 provides this class as a Hilt
     * @Singleton, so there is exactly one instance per process and its ExoPlayer
     * is intentionally allowed to outlive any single screen. Kept as an explicit
     * method so it is one call away whenever a later iteration wants a narrower
     * lifecycle.
     */
    fun release() = player.release()

    private fun PreparedUnit.mediaItem(): MediaItem = MediaItem.fromUri(audio.toURI().toString())
}

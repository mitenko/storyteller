package com.storyteller.data.audio

import android.content.Context
import androidx.media3.common.MediaItem
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
 * [PlaybackState] intentionally carries no unit index (see its kdoc) — iteration 1
 * does not highlight the current line, so nothing here reports one.
 */
class PagePlayerImpl(context: Context) : PagePlayer {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val player = ExoPlayer.Builder(context).build().apply {
        addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    _state.value = when (playbackState) {
                        Player.STATE_ENDED -> PlaybackState.Finished
                        Player.STATE_READY -> if (isPlaying) PlaybackState.Playing else _state.value
                        else -> _state.value
                    }
                }
            },
        )
    }

    override fun play(units: List<PreparedUnit>) {
        player.setMediaItems(units.map { it.mediaItem() })
        player.prepare()
        player.play()
        _state.value = PlaybackState.Playing
    }

    /** Appended at the end, so reading order is preserved as units arrive. */
    override fun append(unit: PreparedUnit) {
        player.addMediaItem(unit.mediaItem())
    }

    override fun stop() {
        player.stop()
        player.clearMediaItems()
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

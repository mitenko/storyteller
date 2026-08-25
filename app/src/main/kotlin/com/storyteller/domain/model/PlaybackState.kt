package com.storyteller.domain.model

/**
 * No unit index by design: iteration 1 does not highlight lines. Media3 supplies
 * the playlist index for free, so adding it when iteration 2 wants highlighting
 * is a one-line change.
 */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Playing : PlaybackState
    data object Finished : PlaybackState
}

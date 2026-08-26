package com.storyteller.domain.model

sealed interface PlaybackState {
    data object Idle : PlaybackState

    /**
     * [playlistIndex] is Media3's position in the CURRENT playlist - deliberately
     * not named a unit index, because the two only coincide in Auto mode.
     *
     * Auto builds one playlist per page in reading order from unit 0, so position
     * N is unit N. Tap plays a ONE-ITEM playlist, so this is always 0 no matter
     * which line was tapped. A consumer that wires this straight through to a
     * unit index will highlight line 0 for every tap.
     */
    data class Playing(val playlistIndex: Int) : PlaybackState
    data object Finished : PlaybackState
}

package com.storyteller.ui.reader

import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.ReadingMode

sealed interface ReaderUiState {
    data object ReadingPage : ReaderUiState

    /**
     * [playback] is the player's own state, mirrored here so the reader can show
     * the page has finished. Without it nothing outside PagePlayerImpl observed
     * PlaybackState at all, which made endOfPage(), the pageComplete gate and the
     * onPlayerError -> Finished fallback correct signals that no one listened to,
     * and left a successful read with no ending on screen.
     */
    data class Playing(
        val lines: List<Line>,
        /** Which unit is on screen - the reader shows one bubble at a time. */
        val current: Int,
        /** The page [lines] were read from; what [Bubble] crops a speech-bubble region out of. */
        val image: PageImage?,
        val playback: PlaybackState,
        val mode: ReadingMode,
        /**
         * The unit currently sounding, or null. Tap mode knows it because it
         * handled the tap; Auto mode takes it from the player's own playlist
         * position. Usually equal to [current] - it diverges only when a child
         * has tapped a bubble and then navigated away with the arrows while its
         * audio is still playing, which is exactly the case [Bubble]'s
         * `sounding` marker exists to show.
         */
        val playingIndex: Int?,
    ) : ReaderUiState
    data class Error(val message: String, val canRetry: Boolean) : ReaderUiState

    data class Line(
        val index: Int,
        val speaker: String,
        val text: String,
        /** The unit's speech-bubble box, or null when the model could not locate one. */
        val bounds: BoundingBox?,
        /**
         * False until this line's audio has been synthesized - drives [Bubble]'s
         * greyed-out rendering in BOTH modes. Before F7 this was conflated with
         * tappability under one `enabled` flag, so Auto mode (where every bubble
         * is always tappable-in-spirit) never greyed at all and lost its only
         * synthesis-progress indication.
         */
        val audioReady: Boolean,
        /**
         * The comic panel this line was spoken in, or null when none was resolved.
         * Preferred over [bounds] for rendering: it shows the picture rather than a
         * crop of lettering the child cannot read.
         *
         * Last in the list, and optional, so the existing positional construction
         * in the screen tests keeps compiling.
         */
        val panel: BoundingBox? = null,
    )

    /**
     * One comic panel and the consecutive lines spoken in it.
     *
     * [panel] is null for a line the model could not place in a panel; those never
     * group, so such a group always holds exactly one line.
     */
    data class PanelGroup(
        val panel: BoundingBox?,
        val lines: List<Line>,
    )
}

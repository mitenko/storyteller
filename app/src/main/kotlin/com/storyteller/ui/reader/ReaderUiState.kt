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
        /** The page's panels in reading order, each with the lines spoken in it. */
        val panels: List<PanelGroup>,
        /** Which unit was on screen at the last player transition; retained for the ViewModel's own bookkeeping. The screen itself no longer indexes by it - it shows every line on the page at once. */
        val current: Int,
        /** The page [lines] were read from; what [PanelCard] crops a panel or speech-bubble region out of. */
        val image: PageImage?,
        val playback: PlaybackState,
        val mode: ReadingMode,
        /**
         * The unit currently sounding, or null. Tap mode knows it because it
         * handled the tap; Auto mode takes it from the player's own playlist
         * position. Written together with [current] everywhere else, so the
         * only real divergence is [PlaybackState.Finished] nulling this out
         * while [current] is left pointing at the last line read.
         */
        val playingIndex: Int?,
    ) : ReaderUiState {
        /**
         * Every line on the page, in reading order.
         *
         * Derived rather than stored. It makes "grouping loses no line" true by
         * construction - there is no second copy that can drift from the first
         * - and it is what [ReaderViewModel.onLineTapped] bounds a tapped index
         * against, its one call site in app/src/main. At most ten elements, so
         * the flatMap costs nothing.
         */
        val lines: List<Line> get() = panels.flatMap { it.lines }

        /**
         * The line a child should be LOOKING at, or null when there is none.
         *
         * A pre-reader cannot read ahead, so the reader rings one line to point at
         * it. What that line is depends on whether anything is sounding:
         *
         * - something sounding -> the sounding line. The child should be watching
         *   the words being read to them.
         * - nothing sounding -> the line to tap next, which is an instruction.
         *
         * This was `nextLine`, and it returned `playingIndex + 1` unconditionally,
         * so the ring was ALWAYS one line ahead of the one being read. On a device
         * that reads as a bug: tap a panel, and the ring lands on the next panel's
         * text. The tapped line got only a "♪" beside its speaker, while the ring -
         * a 2dp coloured border around the whole row - had already moved on, so the
         * strongest thing on screen pointed at the wrong line.
         *
         * The cost is that Auto mode no longer previews the coming line. That was
         * deliberate once, but it is the same visual as the bug, and following the
         * reading is what makes the ring mean one thing in both modes.
         *
         * [PlaybackState.Idle] still separates a page nothing has played yet (ring
         * the first line) from one whose first line has just finished (ring the
         * second): without it a fresh page would point past the line it wants the
         * child to start on, because [current] already reads 0.
         *
         * Derived, not stored, for the same reason as [lines]: a second copy could
         * disagree with the playback state it is supposed to describe.
         */
        val focusLine: Int? get() {
            val candidate = when {
                playingIndex != null -> playingIndex
                playback == PlaybackState.Idle -> 0
                else -> current + 1
            }
            return candidate.takeIf { it in lines.indices }
        }
    }
    data class Error(val message: String, val canRetry: Boolean) : ReaderUiState

    data class Line(
        val index: Int,
        val speaker: String,
        val text: String,
        /** The unit's speech-bubble box, or null when the model could not locate one. */
        val bounds: BoundingBox?,
        /**
         * False until this line's audio has been synthesized - drives [LineRow]'s
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

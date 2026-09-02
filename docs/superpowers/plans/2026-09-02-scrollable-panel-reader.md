# Scrollable Panel Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the one-bubble-at-a-time reader with a vertical scroll of the page's comic panels, each with its own lines tappable beneath it.

**Architecture:** Four layers, built innermost first. A pure grouping function turns a flat line list into consecutive panel runs. `ReaderUiState.Playing` carries those groups and derives its flat line list rather than storing it. `ReaderViewModel` gains a playlist-to-unit index map — the thing that makes an Auto-mode tap safe — and a `onLineTapped(index)` that replaces both arrows and the argument-less tap. `ReaderScreen` becomes a `LazyColumn` of panel cards.

**Tech Stack:** Kotlin, Jetpack Compose (`LazyColumn`, `rememberLazyListState`), Robolectric, JUnit 4, Turbine for flow tests.

**Spec:** [`docs/superpowers/specs/2026-09-02-scrollable-panel-reader-design.md`](../specs/2026-09-02-scrollable-panel-reader-design.md)

## Global Constraints

- `GraphTest` enforces `ui -> domain <- data`, `ui` never imports `data`, and `domain` never imports `android.*`/`androidx.*`.
- `current` and `playingIndex` are **line indices, never group indices**. Playback is per line; only rendering groups.
- A panel image is **fitted, never cropped** (`ContentScale.Fit`), capped at `PANEL_MAX_HEIGHT_FRACTION = 0.5f`. Cropping to fill would discard the art this feature exists to show.
- Reject-don't-invent: a group with no producible picture renders text-only. Never substitute a different panel's picture.
- Robolectric pinned at `sdk=34` — do not bump. `buildToolsVersion` stays unset.
- Commits use the repo's existing author, `mitenko`. **No `Co-Authored-By` trailer of any kind.**
- Gradle: `BindException` → `./gradlew --stop`, retry. `Unable to delete directory .../test-results/...` → `./gradlew --stop`, delete `app/build/test-results` and `app/build/reports`, re-run. Neither is a code failure.

## One refinement on the spec, decided while planning

§2 says `Playing` "stops carrying a flat line list". It should stop *storing* one but keep **deriving** one:

```kotlin
val lines: List<Line> get() = panels.flatMap { it.lines }
```

Two reasons. `state.lines` has ten call sites, and a derived property keeps every one of them working without churn in a change that is already touching four files. More importantly it makes the spec's own rule 4 — `panels.flatMap { it.lines }` equals the input — true *by construction* rather than by a test: there is no second copy that can drift out of step with the first. The cost is a `flatMap` over at most ten elements on read, which is nothing.

## What the existing code already tells us

`ReaderViewModel.kt:105-114` carries this comment, which is the reason Task 3 exists:

> *"Auto is the ONLY mode that may trust the player's position. It builds one playlist per page in reading order from unit 0, so position N is unit N."*

An Auto-mode tap that jumps playback **breaks that documented invariant**, because the playlist then starts at the tapped unit. Task 3 replaces the assumption with a map rather than leaving a comment that is no longer true.

---

### Task 1: Group consecutive lines by panel

Pure Kotlin, no Android, no dependencies on other tasks. Built first because everything else consumes it.

**Files:**
- Create: `app/src/main/kotlin/com/storyteller/ui/reader/PanelGrouping.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/reader/PanelGroupingTest.kt`

**Interfaces:**
- Consumes: `ReaderUiState.Line`, `ReaderUiState.PanelGroup` (both defined in Task 2 — see the note below).
- Produces: `fun List<ReaderUiState.Line>.groupByPanel(): List<ReaderUiState.PanelGroup>`; `fun List<ReaderUiState.PanelGroup>.groupIndexOfLine(lineIndex: Int): Int?`.

> **Ordering note:** `PanelGroup` is declared in `ReaderUiState.kt`, which Task 2 edits. Add just that nested data class here as part of Task 1 so this task compiles and tests on its own; Task 2 then changes `Playing` around it.

- [ ] **Step 1: Add the `PanelGroup` type**

In `ReaderUiState.kt`, inside the `ReaderUiState` interface, beside `Line`:

```kotlin
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
```

- [ ] **Step 2: Write the failing tests**

```kotlin
package com.storyteller.ui.reader

import com.storyteller.domain.model.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelGroupingTest {

    private val a = BoundingBox(0f, 0f, 1f, 0.3f)
    private val b = BoundingBox(0f, 0.3f, 1f, 0.6f)

    private fun line(index: Int, panel: BoundingBox?) = ReaderUiState.Line(
        index = index, speaker = "Robot", text = "line $index",
        bounds = null, audioReady = true, panel = panel,
    )

    @Test fun `consecutive lines sharing a panel form one group`() {
        val groups = listOf(line(0, a), line(1, a), line(2, b)).groupByPanel()

        assertEquals(2, groups.size)
        assertEquals(listOf(0, 1), groups[0].lines.map { it.index })
        assertEquals(a, groups[0].panel)
        assertEquals(listOf(2), groups[1].lines.map { it.index })
    }

    /**
     * The rule that keeps reading order intact. Lines 0 and 2 share a panel but are
     * not adjacent, so collapsing them would move line 2 ahead of line 1 and tell
     * the story out of order.
     */
    @Test fun `a non-adjacent repeat of a panel starts a new group`() {
        val groups = listOf(line(0, a), line(1, b), line(2, a)).groupByPanel()

        assertEquals(3, groups.size)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), groups.map { g -> g.lines.map { it.index } })
    }

    /**
     * A null panel is an absence, not a value two lines can share. Merging them
     * would put unrelated lines under one picture.
     */
    @Test fun `null panels never group, even when adjacent`() {
        val groups = listOf(line(0, null), line(1, null)).groupByPanel()

        assertEquals(2, groups.size)
        assertNull(groups[0].panel)
        assertNull(groups[1].panel)
    }

    @Test fun `a null panel between two shared panels splits them`() {
        val groups = listOf(line(0, a), line(1, null), line(2, a)).groupByPanel()

        assertEquals(3, groups.size)
    }

    @Test fun `every line survives grouping, in order`() {
        val input = listOf(line(0, a), line(1, a), line(2, null), line(3, b), line(4, b))

        assertEquals(input, input.groupByPanel().flatMap { it.lines })
    }

    @Test fun `an empty page groups to nothing`() {
        assertEquals(emptyList<ReaderUiState.PanelGroup>(), emptyList<ReaderUiState.Line>().groupByPanel())
    }

    @Test fun `one line is one group`() {
        assertEquals(1, listOf(line(0, a)).groupByPanel().size)
    }

    @Test fun `groupIndexOfLine finds the group holding a line`() {
        val groups = listOf(line(0, a), line(1, a), line(2, b)).groupByPanel()

        assertEquals(0, groups.groupIndexOfLine(0))
        assertEquals(0, groups.groupIndexOfLine(1))
        assertEquals(1, groups.groupIndexOfLine(2))
    }

    @Test fun `groupIndexOfLine returns null for a line that is not there`() {
        val groups = listOf(line(0, a)).groupByPanel()

        assertNull(groups.groupIndexOfLine(7))
        assertNull(groups.groupIndexOfLine(-1))
    }
}
```

- [ ] **Step 3: Run them to make sure they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.ui.reader.PanelGroupingTest"`
Expected: FAIL — `Unresolved reference: groupByPanel`.

- [ ] **Step 4: Write the implementation**

```kotlin
package com.storyteller.ui.reader

/**
 * Groups a page's lines into the panels they were spoken in.
 *
 * Deliberately pure and Android-free so it runs on a plain JVM test: this is the
 * one piece of the scrollable reader whose correctness is worth checking without
 * a Robolectric harness in the way.
 *
 * Grouping is CONSECUTIVE-ONLY. Two lines merge when they are adjacent in reading
 * order and carry an equal panel box; a repeat of the same panel later on the page
 * starts a new group. Reading order is the product, and collapsing across it would
 * reorder the story.
 *
 * A null panel never groups. It means the model could not place that line, which
 * is an absence rather than a value two lines can have in common — merging them
 * would file unrelated lines under one picture.
 *
 * Equality is BoundingBox's own: it is a data class, and units sharing a panel are
 * measured to receive byte-identical boxes (see the accuracy issue doc, section
 * 19), so no tolerance is wanted. If that ever stops holding, the symptom is one
 * picture per line — visible, not silent.
 */
fun List<ReaderUiState.Line>.groupByPanel(): List<ReaderUiState.PanelGroup> {
    val groups = mutableListOf<ReaderUiState.PanelGroup>()
    for (line in this) {
        val last = groups.lastOrNull()
        val extends = last != null && line.panel != null && last.panel == line.panel
        if (extends) {
            groups[groups.lastIndex] = last!!.copy(lines = last.lines + line)
        } else {
            groups += ReaderUiState.PanelGroup(panel = line.panel, lines = listOf(line))
        }
    }
    return groups
}

/**
 * Which group holds [lineIndex], or null if no group does.
 *
 * Pulled out as a pure function because it is the whole decision behind
 * auto-scrolling: the Compose side is then a one-line `animateScrollToItem`, and
 * the part that can be wrong is unit-tested instead of driven through a UI test.
 */
fun List<ReaderUiState.PanelGroup>.groupIndexOfLine(lineIndex: Int): Int? {
    val index = indexOfFirst { group -> group.lines.any { it.index == lineIndex } }
    return index.takeIf { it >= 0 }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.ui.reader.PanelGroupingTest"`
Expected: PASS, 9 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui/reader/PanelGrouping.kt \
        app/src/main/kotlin/com/storyteller/ui/reader/ReaderUiState.kt \
        app/src/test/kotlin/com/storyteller/ui/reader/PanelGroupingTest.kt
git commit -m "feat: group a page's lines into the panels they were spoken in"
```

---

### Task 2: Carry groups in the reader state

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderUiState.kt`
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderViewModel.kt` — `playingState`, around line 213
- Test: `app/src/test/kotlin/com/storyteller/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `groupByPanel()` (Task 1).
- Produces: `Playing(panels, current, image, playback, mode, playingIndex)` with a derived `val lines: List<Line>`.

- [ ] **Step 1: Write the failing test**

Append to `ReaderViewModelTest.kt`:

```kotlin
    @Test fun `the reader state groups lines by their panel`() = runTest {
        // Two units in one panel, a third in another: two groups, and the flat
        // line list still holds all three in order.
        val panelA = BoundingBox(0f, 0f, 1f, 0.5f)
        val panelB = BoundingBox(0f, 0.5f, 1f, 1f)
        val units = listOf(
            speechUnit(0).copy(panel = panelA),
            speechUnit(1).copy(panel = panelA),
            speechUnit(2).copy(panel = panelB),
        )

        val state = playingStateFor(units)

        assertEquals(2, state.panels.size)
        assertEquals(listOf(0, 1), state.panels[0].lines.map { it.index })
        assertEquals(listOf(0, 1, 2), state.lines.map { it.index })
    }
```

Add a `playingStateFor(units)` helper to the test class following the file's
existing fixture style — drive the pipeline to `Ready` with those units and read
`uiState` as `Playing`, exactly as the neighbouring tests do. Do not call the
private `playingState` reflectively.

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.ui.reader.ReaderViewModelTest"`
Expected: FAIL — `Unresolved reference: panels`.

- [ ] **Step 3: Change `Playing`**

In `ReaderUiState.kt`, replace `Playing`'s `lines` parameter with `panels`, and add
the derived accessor:

```kotlin
    data class Playing(
        /** The page's panels in reading order, each with the lines spoken in it. */
        val panels: List<PanelGroup>,
        /** Which LINE is current. A line index, never a group index. */
        val current: Int,
        val image: PageImage?,
        val playback: PlaybackState,
        val mode: ReadingMode,
        val playingIndex: Int?,
    ) : ReaderUiState {
        /**
         * Every line on the page, in reading order.
         *
         * Derived rather than stored. It keeps the ten existing `state.lines` call
         * sites working, and it makes "grouping loses no line" true by
         * construction: there is no second copy that can drift from the first.
         * At most ten elements, so the flatMap costs nothing.
         */
        val lines: List<Line> get() = panels.flatMap { it.lines }
    }
```

- [ ] **Step 4: Build the groups in the ViewModel**

In `playingState`, where `Playing(...)` is constructed, replace the `lines = ...`
argument with `panels = <the same list>.groupByPanel()`. Keep the existing
`current` clamp exactly as it is — it clamps against the unit count, which the
derived `lines` still reports correctly.

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. `ReaderScreen` still compiles because it reads `state.lines`,
which is now derived. If any test constructs `Playing(...)` positionally, name the
`panels` argument rather than reordering the class.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui/reader/ReaderUiState.kt \
        app/src/main/kotlin/com/storyteller/ui/reader/ReaderViewModel.kt \
        app/src/test/kotlin/com/storyteller/ui/reader/ReaderViewModelTest.kt
git commit -m "feat: carry panel groups in the reader state, deriving the flat line list"
```

---

### Task 3: Make an Auto-mode tap safe, and replace the arrows

The behavioural core. `ReaderViewModel.kt:105-114` states that Auto may trust the
player's position "because it builds one playlist per page in reading order from
unit 0, so position N is unit N". A tap that jumps playback makes that false.

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderViewModel.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `Playing.panels`, `Playing.lines` (Task 2).
- Produces: `fun onLineTapped(index: Int)`. Removes `onNext()`, `onPrevious()`, `moveTo(index)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test fun `a tap in Tap mode plays just that line`() = runTest {
        val vm = readerInTapMode(unitCount = 3)

        vm.onLineTapped(2)

        assertEquals(listOf(2), player.played.map { it.unit.index })
        assertEquals(2, (vm.uiState.value as ReaderUiState.Playing).playingIndex)
    }

    @Test fun `a tap in Auto mode plays from that line onward`() = runTest {
        val vm = readerInAutoMode(unitCount = 4)

        vm.onLineTapped(2)

        assertEquals(listOf(2, 3), player.played.map { it.unit.index })
    }

    @Test fun `a tap on a line whose audio is not ready does nothing`() = runTest {
        val vm = readerInTapMode(unitCount = 3, readyCount = 1)
        player.played.clear()

        vm.onLineTapped(2)

        assertEquals(emptyList<Int>(), player.played.map { it.unit.index })
    }

    /**
     * The first invariant from spec section 6.1. A jump replaces the playlist, so
     * `queued` — which counts units already handed to the player — describes a
     * playlist that no longer exists. Left unreset, the next unit to finish
     * synthesising is appended against a stale count and an earlier unit is never
     * heard at all.
     */
    @Test fun `a unit synthesised after an Auto jump is still heard, in order`() = runTest {
        val vm = readerInAutoMode(unitCount = 6, readyCount = 5)

        vm.onLineTapped(3)
        player.played.clear()
        becomeReady(vm, readyCount = 6)

        // Unit 5 was ready but behind the jump; unit 6 is new. Neither may be lost.
        assertEquals(listOf(5), player.appended.map { it.unit.index })
    }

    /**
     * The second invariant from spec section 6.1. After a jump the playlist starts
     * at the tapped unit, so the player's position 0 IS that unit. Copying the
     * position would mark line 0 as sounding and scroll the list to the wrong card.
     */
    @Test fun `playingIndex reports the unit index, not the playlist position`() = runTest {
        val vm = readerInAutoMode(unitCount = 4)

        vm.onLineTapped(2)
        player.emit(PlaybackState.Playing(playlistIndex = 0))

        assertEquals(2, (vm.uiState.value as ReaderUiState.Playing).playingIndex)
    }
```

Build `readerInTapMode` / `readerInAutoMode` / `becomeReady` on the file's existing
fakes. If the fake player does not already record `played` and `appended`
separately, add those two lists to it — the fourth test cannot distinguish a
replaced playlist from an appended one otherwise, which is the whole point of it.

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.ui.reader.ReaderViewModelTest"`
Expected: FAIL — `Unresolved reference: onLineTapped`.

- [ ] **Step 3: Track the unit indices handed to the player**

Add the field beside `queued`:

```kotlin
    /**
     * The unit indices currently in the player's playlist, in playlist order.
     *
     * Replaces the assumption that playlist position N is unit N. That held while
     * every playlist was the whole page from unit 0; an Auto-mode tap jumps to a
     * line, so position 0 becomes the tapped unit and copying the position would
     * mark the wrong line as sounding.
     *
     * A list rather than a start-offset integer: no harder to keep, correct for a
     * playlist that is not a contiguous run, and getOrNull turns a stale position
     * into a null marker rather than an exception.
     */
    private var playlistUnits: List<Int> = emptyList()
```

Set it in `queue()`, where the playlist is built and extended:

```kotlin
    private fun queue(ready: List<PreparedUnit>) {
        if (ready.size <= queued) return
        val fresh = ready.drop(queued)
        if (queued == 0) {
            player.play(listOf(fresh.first()))
            fresh.drop(1).forEach(player::append)
            playlistUnits = fresh.map { it.unit.index }
        } else {
            fresh.forEach(player::append)
            playlistUnits = playlistUnits + fresh.map { it.unit.index }
        }
        queued = ready.size
    }
```

Clear it everywhere `queued` is reset to 0 — the `Idle`/`Reading` reset and the
`Failed` branch both already clear `lastReady` and `playingIndex`; add
`playlistUnits = emptyList()` alongside.

- [ ] **Step 4: Map the position instead of copying it**

In the player-state collector, replace the two assignments:

```kotlin
                    if (mode == ReadingMode.Auto && state is PlaybackState.Playing) {
                        // Mapped, not copied: after an Auto-mode tap the playlist
                        // starts at the tapped unit, so position 0 is not unit 0.
                        playlistUnits.getOrNull(state.playlistIndex)?.let {
                            playingIndex = it
                            current = it
                        }
                    }
```

Update that block's comment: the claim "position N is unit N" is no longer true and
must not be left standing.

- [ ] **Step 5: Add `onLineTapped` and delete the arrows**

```kotlin
    /**
     * Plays the tapped line. The only navigation the reader has, now that the
     * arrows are gone: the list itself is how a child moves around the page.
     *
     * Tap mode plays that one line, as before. Auto mode JUMPS — it plays from the
     * tapped line to the end of what is ready — so a child pointing at a picture
     * continues the story from there rather than restarting a single line.
     *
     * A line whose audio is not synthesised yet is a no-op. Those rows are already
     * greyed by `audioReady`, so the affordance and the behaviour agree.
     */
    fun onLineTapped(index: Int) {
        val state = _uiState.value as? ReaderUiState.Playing ?: return
        val bounded = index.coerceIn(0, (state.lines.size - 1).coerceAtLeast(0))
        if (lastReady.none { it.unit.index == bounded }) return

        current = bounded
        playingIndex = bounded

        val playlist = when (mode) {
            ReadingMode.Tap -> lastReady.filter { it.unit.index == bounded }
            ReadingMode.Auto -> lastReady.filter { it.unit.index >= bounded }
        }
        player.play(playlist)
        player.endOfPage()
        playlistUnits = playlist.map { it.unit.index }
        // The playlist was REPLACED, so every ready unit is now either in it or
        // deliberately behind it. Without this, queue() diffs the next cumulative
        // `ready` against a stale count and silently drops a unit.
        queued = lastReady.size

        _uiState.value = state.copy(current = bounded, playingIndex = bounded)
    }
```

Then delete `onNext()`, `onPrevious()` and `moveTo(index)`.

`endOfPage()` is called in both modes, as Tap does today: it tells the player no
more units are coming for this playlist. In Auto that means `Finished` now fires at
the end of the jumped-to run rather than the page — acceptable, and the same
behaviour Tap has always had.

- [ ] **Step 6: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. `ReaderScreen` will fail to compile against the removed
`onNext`/`onPrevious` — Task 4 rewrites it, so for this task only, temporarily
point the screen's two `IconButton`s at `{}` to keep the build green, and delete
them properly in Task 4. Note that in the commit message.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui/reader/ \
        app/src/test/kotlin/com/storyteller/ui/reader/ReaderViewModelTest.kt
git commit -m "feat: play from a tapped line, and map playlist positions to units"
```

---

### Task 4: The scrolling screen

**Files:**
- Modify: `app/src/main/kotlin/com/storyteller/ui/reader/ReaderScreen.kt`
- Test: `app/src/test/kotlin/com/storyteller/ui/reader/ReaderScreenTest.kt`

**Interfaces:**
- Consumes: `Playing.panels`, `groupIndexOfLine()` (Tasks 1-2), `onLineTapped(index)` (Task 3).
- Produces: no new public API. `ReaderContent` loses `onNext`/`onPrevious`, and `onBubbleTapped: () -> Unit` becomes `onLineTapped: (Int) -> Unit`.

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test fun `each panel group renders one card`() {
        val panelA = BoundingBox(0f, 0f, 1f, 0.5f)
        val panelB = BoundingBox(0f, 0.5f, 1f, 1f)
        composeRule.setContent {
            ReaderContent(
                state = playing(
                    listOf(
                        line("Robot", "first", index = 0, panel = panelA),
                        line("Robot", "second", index = 1, panel = panelA),
                        line("Robot", "third", index = 2, panel = panelB),
                    ),
                ),
                onRetry = {}, onBack = {},
            )
        }

        // All three lines are on screen at once, which the paged reader could not do.
        composeRule.onNodeWithText("first").assertExists()
        composeRule.onNodeWithText("second").assertExists()
        composeRule.onNodeWithText("third").assertExists()
    }

    @Test fun `tapping a line reports that line's index`() {
        var tapped: Int? = null
        composeRule.setContent {
            ReaderContent(
                state = playing(
                    listOf(
                        line("Robot", "first", index = 0),
                        line("Robot", "second", index = 1),
                    ),
                ),
                onRetry = {}, onBack = {}, onLineTapped = { tapped = it },
            )
        }

        composeRule.onNodeWithText("second").performClick()

        assertEquals(1, tapped)
    }

    @Test fun `a line whose audio is not ready is not clickable`() {
        var tapped: Int? = null
        composeRule.setContent {
            ReaderContent(
                state = playing(listOf(line("Robot", "waiting", index = 0, audioReady = false))),
                onRetry = {}, onBack = {}, onLineTapped = { tapped = it },
            )
        }

        composeRule.onNodeWithText("waiting").performClick()

        assertNull(tapped)
    }

    @Test fun `the sounding line is marked and the others are not`() {
        composeRule.setContent {
            ReaderContent(
                state = playing(
                    listOf(line("Robot", "first", index = 0), line("Robot", "second", index = 1)),
                    playingIndex = 1,
                ),
                onRetry = {}, onBack = {},
            )
        }

        composeRule.onAllNodesWithContentDescription("Sounding now").assertCountEquals(1)
    }
```

Extend the file's existing `line(...)` and `playing(...)` helpers with `index`,
`panel` and `playingIndex` parameters rather than writing new fixtures. `playing`
must build `panels = lines.groupByPanel()`.

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.storyteller.ui.reader.ReaderScreenTest"`
Expected: FAIL — `onLineTapped` is not a parameter of `ReaderContent`.

- [ ] **Step 3: Replace the Playing branch with a list**

Change `ReaderContent`'s signature: drop `onNext` and `onPrevious`, and replace
`onBubbleTapped: () -> Unit = {}` with `onLineTapped: (Int) -> Unit = {}`.

Replace the whole `is ReaderUiState.Playing ->` branch with a `LazyColumn`, keeping
the `Finished` text and the "Take another photo" button below it exactly as they
are:

```kotlin
                is ReaderUiState.Playing -> {
                    val listState = rememberLazyListState()
                    val dragged by listState.interactionSource.collectIsDraggedAsState()
                    // Auto-scroll follows the story until the child takes over. A
                    // drag says "I am looking at something else"; a tap says where
                    // they want to be, so it hands control back.
                    var scrollSuspended by remember(state.image) { mutableStateOf(false) }
                    LaunchedEffect(dragged) { if (dragged) scrollSuspended = true }
                    LaunchedEffect(state.playingIndex, scrollSuspended) {
                        val target = state.playingIndex
                            ?.let { state.panels.groupIndexOfLine(it) }
                        if (target != null && !scrollSuspended) {
                            listState.animateScrollToItem(target)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        itemsIndexed(
                            state.panels,
                            key = { _, group -> group.lines.first().index },
                        ) { _, group ->
                            PanelCard(
                                group = group,
                                image = state.image,
                                playingIndex = state.playingIndex,
                                onLineTapped = {
                                    scrollSuspended = false
                                    onLineTapped(it)
                                },
                            )
                        }
                    }

                    if (state.playback == PlaybackState.Finished && state.mode == ReadingMode.Auto) {
                        Text("The End.", style = MaterialTheme.typography.titleMedium)
                    }
                    Button(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_photo_camera),
                            contentDescription = "Take another photo",
                        )
                    }
                }
```

- [ ] **Step 4: Write the card**

Replace `Bubble` with these three composables:

```kotlin
/** How much of the list a single panel picture may occupy. */
internal const val PANEL_MAX_HEIGHT_FRACTION = 0.5f

/**
 * One panel and the lines spoken in it.
 *
 * The picture is decoded once per CARD, not once per line, so two lines sharing a
 * panel no longer decode the same region twice.
 *
 * Keyed on the group's panel and first line so a re-parse that changes which panel
 * a line belongs to produces a fresh decode; keying on the line index alone would
 * keep a stale picture.
 */
@Composable
internal fun PanelCard(
    group: ReaderUiState.PanelGroup,
    image: PageImage?,
    playingIndex: Int?,
    onLineTapped: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = group.lines.first()
    val bitmap by produceState<ImageBitmap?>(null, group.panel, first.index, image) {
        value = image?.let { page ->
            withContext(Dispatchers.Default) { cropBubble(page, first.bounds, group.panel) }
        }?.asImageBitmap()
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Fitted and height-capped, never cropped to fill: a crop would discard
        // the art this whole feature exists to show, and the cap is what keeps the
        // picture and its lines on screen together.
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "Comic panel",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * PANEL_MAX_HEIGHT_FRACTION),
                contentScale = ContentScale.Fit,
            )
        }
        group.lines.forEach { line ->
            LineRow(
                line = line,
                sounding = playingIndex == line.index,
                onTap = { onLineTapped(line.index) },
            )
        }
    }
}

/** One spoken line: who says it, what they say, and whether it is sounding now. */
@Composable
internal fun LineRow(
    line: ReaderUiState.Line,
    sounding: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = "Read this line",
                enabled = line.audioReady,
                onClick = onTap,
            )
            .alpha(contentAlphaFor(line.audioReady))
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line.speaker, style = MaterialTheme.typography.labelLarge)
            if (sounding) {
                Text(
                    "♪",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .semantics { contentDescription = "Sounding now" },
                )
            }
        }
        LineText(line.text)
    }
}

/**
 * A line's words.
 *
 * Its own composable for one reason: a planned feature bolds the word currently
 * being spoken, and that change belongs in one small function rather than inside
 * the card's layout. No parameter for it is added until it is built.
 */
@Composable
internal fun LineText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
}
```

Delete `Bubble`, both arrow `IconButton`s, and the now-unused `ic_arrow_back` /
`ic_arrow_forward` imports. Leave the drawables in place — they are attributed
Material icons and another screen may want them.

Update `ReaderScreen`'s wiring at line 60: drop the two arrow arguments and pass
`onLineTapped = viewModel::onLineTapped`.

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. Existing `ReaderScreenTest` cases that assert the arrows or the
one-bubble-at-a-time behaviour are deleted with the feature they cover; cases about
`Finished`, the error branch and "Take another photo" must keep passing untouched.

- [ ] **Step 6: Install and check on device**

```bash
./gradlew installDebug
adb -s 59251JEBF12416 shell monkey -p com.storyteller -c android.intent.category.LAUNCHER 1
```

Scan a comic page and confirm by eye: one card per panel, the picture above its
lines with both visible together, the list scrolling to the sounding line in Auto,
a drag stopping that, and a tap continuing the story from the tapped line.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/storyteller/ui/reader/ReaderScreen.kt \
        app/src/test/kotlin/com/storyteller/ui/reader/ReaderScreenTest.kt
git commit -m "feat: scroll the page's panels instead of paging one bubble at a time"
```

---

## Self-Review

**Spec coverage.** §2 state → Task 2. §3 grouping rules 1-4 → Task 1, one test each.
§3.1 decode count → Task 4's `PanelCard` doc. §4 screen, cap, fit, fallback → Task 4.
§4.1 seam → Task 4's `LineText`. §5 auto-scroll, drag-suspends, tap-resumes,
page-resets → Task 4 Step 3 (`remember(state.image)` is the page reset). §6 tap
semantics → Task 3. §6.1 both invariants → Task 3, one test each. §7 deletions →
Tasks 3 and 4. §8 edge cases → covered by Task 1's null tests and Task 4's
`bitmap?.let`. §9 testing → each task's tests. §10 out of scope → nothing built.

**Placeholder scan.** No TBD/TODO. Every code step carries the code. Test-fixture
steps say which existing helper to extend rather than inventing a parallel set.

**Type consistency, checked against the source.** `ReaderUiState.Line(index,
speaker, text, bounds, audioReady, panel)` matches `ReaderUiState.kt:38`.
`cropBubble(image, bounds, panel)` matches `BubbleCrop.kt:44`.
`contentAlphaFor(audioReady)` matches `ReaderScreen.kt:197`. `PlaybackState.Playing`
carries `playlistIndex`, per the collector at `ReaderViewModel.kt:110`. `PanelGroup`
is produced in Task 1 and consumed in Tasks 2-4; `groupIndexOfLine` in Task 1 and
consumed in Task 4; `onLineTapped(index)` in Task 3 and consumed in Task 4.

**Three risks worth naming.**

Task 3 leaves `ReaderScreen` temporarily stubbed so the build stays green between
two commits. That is deliberate — the alternative is one commit touching the
ViewModel and the whole screen at once — but the stub must not survive Task 4, and
the commit message says so.

`endOfPage()` after an Auto jump means `Finished`, and therefore "The End.", fires
at the end of the jumped-to run rather than the end of the page. Tap mode has
always behaved this way. It is the honest reading of "no more units are coming for
this playlist", but it is a visible behaviour change in Auto mode and should be
watched for on device in Task 4 Step 6.

`LocalConfiguration.current.screenHeightDp` measures the window, not the list's own
viewport, so on a large-screen or multi-window layout the cap is looser than half
the visible list. Acceptable for a phone app and simpler than a `BoxWithConstraints`
around every card; revisit if the reader is ever put in a split-screen layout.

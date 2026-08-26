package com.storyteller.ui.settings

import com.storyteller.domain.model.ReadingMode
import com.storyteller.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * F2: the only unguarded repository consumers on the branch. `setMode`'s bare
 * `viewModelScope.launch { settings.setMode(mode) }` let a Room write fault -
 * disk full, corrupt database, the exact cases VoiceRepositoryImpl's kdoc
 * enumerates - escape uncaught, crashing the app from the settings screen.
 *
 * Robolectric, not plain JUnit: the fix logs via android.util.Log, which
 * throws "not mocked" under a plain JVM unit test with no shadow behind it.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class ThrowingWriteSettingsRepository(initial: ReadingMode = ReadingMode.Auto) : SettingsRepository {
        override val mode: Flow<ReadingMode> = MutableStateFlow(initial)
        override suspend fun setMode(mode: ReadingMode): Unit = throw RuntimeException("disk full")
    }

    @Test fun `a write fault from setMode does not crash the caller`() = runTest(dispatcher) {
        val vm = SettingsViewModel(ThrowingWriteSettingsRepository())

        // Must not throw out of this call, and must not leave an uncaught
        // exception for the dispatcher to surface once the launch runs.
        vm.setMode(ReadingMode.Tap)
        advanceUntilIdle()
    }
}

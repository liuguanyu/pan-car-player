package com.panmusic.carplayer.service

import android.view.KeyEvent
import app.cash.turbine.test
import com.panmusic.carplayer.model.MediaCommand
import com.panmusic.carplayer.testutil.MainCoroutineRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MediaKeyDispatcherTest {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private val dispatcher = MediaKeyDispatcher()

    // ──────────────────────────────────────────────
    // dispatch: action filtering
    // ──────────────────────────────────────────────

    @Test
    fun `dispatch returns false for ACTION_UP events`() {
        val event = mockk<KeyEvent> {
            every { action } returns KeyEvent.ACTION_UP
            every { keyCode } returns KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        assertFalse(dispatcher.dispatch(event))
    }

    // ──────────────────────────────────────────────
    // dispatch: non-media key filtering
    // ──────────────────────────────────────────────

    @Test
    fun `dispatch returns false for non-media keys`() {
        val event = mockk<KeyEvent> {
            every { action } returns KeyEvent.ACTION_DOWN
            every { keyCode } returns KeyEvent.KEYCODE_A
        }
        assertFalse(dispatcher.dispatch(event))
    }

    // ──────────────────────────────────────────────
    // dispatch: media key → MediaCommand mapping
    // ──────────────────────────────────────────────

    @Test
    fun `dispatch converts KEYCODE_MEDIA_PLAY_PAUSE to TogglePlayPause`() = runTest {
        val event = mockk<KeyEvent> {
            every { action } returns KeyEvent.ACTION_DOWN
            every { keyCode } returns KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        dispatcher.commands.test {
            assertTrue(dispatcher.dispatch(event))
            assertEquals(MediaCommand.TogglePlayPause, awaitItem())
        }
    }

    @Test
    fun `dispatch converts KEYCODE_MEDIA_PLAY to Play`() = runTest {
        val event = mockk<KeyEvent> {
            every { action } returns KeyEvent.ACTION_DOWN
            every { keyCode } returns KeyEvent.KEYCODE_MEDIA_PLAY
        }
        dispatcher.commands.test {
            assertTrue(dispatcher.dispatch(event))
            assertEquals(MediaCommand.Play, awaitItem())
        }
    }

    @Test
    fun `dispatch converts KEYCODE_MEDIA_PAUSE to Pause`() = runTest {
        val event = mockk<KeyEvent> {
            every { action } returns KeyEvent.ACTION_DOWN
            every { keyCode } returns KeyEvent.KEYCODE_MEDIA_PAUSE
        }
        dispatcher.commands.test {
            assertTrue(dispatcher.dispatch(event))
            assertEquals(MediaCommand.Pause, awaitItem())
        }
    }

    @Test
    fun `dispatch converts KEYCODE_MEDIA_NEXT to Next`() = runTest {
        val event = mockk<KeyEvent> {
            every { action } returns KeyEvent.ACTION_DOWN
            every { keyCode } returns KeyEvent.KEYCODE_MEDIA_NEXT
        }
        dispatcher.commands.test {
            assertTrue(dispatcher.dispatch(event))
            assertEquals(MediaCommand.Next, awaitItem())
        }
    }

    @Test
    fun `dispatch converts KEYCODE_MEDIA_PREVIOUS to Previous`() = runTest {
        val event = mockk<KeyEvent> {
            every { action } returns KeyEvent.ACTION_DOWN
            every { keyCode } returns KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        dispatcher.commands.test {
            assertTrue(dispatcher.dispatch(event))
            assertEquals(MediaCommand.Previous, awaitItem())
        }
    }

    // ──────────────────────────────────────────────
    // emit: direct command emission
    // ──────────────────────────────────────────────

    @Test
    fun `emit directly emits Play to commands flow`() = runTest {
        dispatcher.commands.test {
            dispatcher.emit(MediaCommand.Play)
            assertEquals(MediaCommand.Play, awaitItem())
        }
    }

    @Test
    fun `emit directly emits Pause to commands flow`() = runTest {
        dispatcher.commands.test {
            dispatcher.emit(MediaCommand.Pause)
            assertEquals(MediaCommand.Pause, awaitItem())
        }
    }

    @Test
    fun `emit directly emits TogglePlayPause to commands flow`() = runTest {
        dispatcher.commands.test {
            dispatcher.emit(MediaCommand.TogglePlayPause)
            assertEquals(MediaCommand.TogglePlayPause, awaitItem())
        }
    }

    @Test
    fun `emit directly emits Next to commands flow`() = runTest {
        dispatcher.commands.test {
            dispatcher.emit(MediaCommand.Next)
            assertEquals(MediaCommand.Next, awaitItem())
        }
    }

    @Test
    fun `emit directly emits Previous to commands flow`() = runTest {
        dispatcher.commands.test {
            dispatcher.emit(MediaCommand.Previous)
            assertEquals(MediaCommand.Previous, awaitItem())
        }
    }

    // ──────────────────────────────────────────────
    // dispatch: integration — multiple commands in sequence
    // ──────────────────────────────────────────────

    @Test
    fun `dispatch handles multiple key events in sequence`() = runTest {
        dispatcher.commands.test {
            val playPause = mockk<KeyEvent> {
                every { action } returns KeyEvent.ACTION_DOWN
                every { keyCode } returns KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            val next = mockk<KeyEvent> {
                every { action } returns KeyEvent.ACTION_DOWN
                every { keyCode } returns KeyEvent.KEYCODE_MEDIA_NEXT
            }
            val previous = mockk<KeyEvent> {
                every { action } returns KeyEvent.ACTION_DOWN
                every { keyCode } returns KeyEvent.KEYCODE_MEDIA_PREVIOUS
            }

            assertTrue(dispatcher.dispatch(playPause))
            assertEquals(MediaCommand.TogglePlayPause, awaitItem())

            assertTrue(dispatcher.dispatch(next))
            assertEquals(MediaCommand.Next, awaitItem())

            assertTrue(dispatcher.dispatch(previous))
            assertEquals(MediaCommand.Previous, awaitItem())
        }
    }

    @Test
    fun `emit handles multiple direct commands in sequence`() = runTest {
        dispatcher.commands.test {
            dispatcher.emit(MediaCommand.Next)
            assertEquals(MediaCommand.Next, awaitItem())

            dispatcher.emit(MediaCommand.Pause)
            assertEquals(MediaCommand.Pause, awaitItem())

            dispatcher.emit(MediaCommand.Play)
            assertEquals(MediaCommand.Play, awaitItem())
        }
    }

    // ──────────────────────────────────────────────
    // dispatch: edge cases
    // ──────────────────────────────────────────────

    @Test
    fun `dispatch returns false for ACTION_DOWN with unknown keyCode`() {
        val event = mockk<KeyEvent> {
            every { action } returns KeyEvent.ACTION_DOWN
            every { keyCode } returns KeyEvent.KEYCODE_UNKNOWN
        }
        assertFalse(dispatcher.dispatch(event))
    }

    @Test
    fun `dispatch returns false for ACTION_MULTIPLE events`() {
        val event = mockk<KeyEvent> {
            every { action } returns KeyEvent.ACTION_MULTIPLE
            every { keyCode } returns KeyEvent.KEYCODE_MEDIA_NEXT
        }
        assertFalse(dispatcher.dispatch(event))
    }
}

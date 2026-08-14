@file:OptIn(ExperimentalCoroutinesApi::class)

package com.panmusic.carplayer.service

import android.content.Context
import android.media.AudioManager
import com.panmusic.carplayer.model.FocusChange
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AudioFocusManagerTest {
    private lateinit var context: Context
    private lateinit var audioManager: AudioManager

    @Before fun setUp() {
        context = mockk(relaxed = true)
        audioManager = mockk(relaxed = true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
    }

    @Test fun `initial state is Gained`() = runTest {
        assertEquals(FocusChange.Gained, com.panmusic.carplayer.service.AudioFocusManager(context).focusChanges.first())
    }

    @Test fun `GAIN maps to Gained`() {
        val m = com.panmusic.carplayer.service.AudioFocusManager(context)
        getListener(m).onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(FocusChange.Gained, m.focusChanges.value)
    }

    @Test fun `LOSS maps to LostPermanent`() {
        val m = com.panmusic.carplayer.service.AudioFocusManager(context)
        getListener(m).onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(FocusChange.LostPermanent, m.focusChanges.value)
    }

    @Test fun `LOSS_TRANSIENT maps to LostTransient`() {
        val m = com.panmusic.carplayer.service.AudioFocusManager(context)
        getListener(m).onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(FocusChange.LostTransient, m.focusChanges.value)
    }

    @Test fun `CAN_DUCK maps to LostTransientCanDuck`() {
        val m = com.panmusic.carplayer.service.AudioFocusManager(context)
        getListener(m).onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(FocusChange.LostTransientCanDuck, m.focusChanges.value)
    }

    @Test fun `transitions LostTransient to Gained`() {
        val m = com.panmusic.carplayer.service.AudioFocusManager(context)
        val l = getListener(m)
        l.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(FocusChange.LostTransient, m.focusChanges.value)
        l.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(FocusChange.Gained, m.focusChanges.value)
    }

    @Test fun `abandon resets to Gained`() {
        val m = com.panmusic.carplayer.service.AudioFocusManager(context)
        getListener(m).onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(FocusChange.LostPermanent, m.focusChanges.value)
        m.abandon()
        assertEquals(FocusChange.Gained, m.focusChanges.value)
    }

    private fun getListener(manager: com.panmusic.carplayer.service.AudioFocusManager): AudioManager.OnAudioFocusChangeListener {
        val field = manager.javaClass.getDeclaredField("listener")
        field.isAccessible = true
        return field.get(manager) as AudioManager.OnAudioFocusChangeListener
    }
}

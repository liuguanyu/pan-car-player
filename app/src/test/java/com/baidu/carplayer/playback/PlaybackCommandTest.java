package com.baidu.carplayer.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackCommandTest {

    @Test
    public void selectCarriesSeekAndAutoplayForSameRequest() {
        PlaybackCommand command = PlaybackCommand.select(7L, 42L, 1500L, true);

        assertEquals(7L, command.getRequestId());
        assertEquals(42L, command.getSongId());
        assertTrue(command.hasSeekPosition());
        assertEquals(1500L, command.getSeekPositionMs());
        assertTrue(command.isPlayWhenReady());
    }

    @Test
    public void pauseDuringLoadKeepsSeekButDisablesAutoplay() {
        PlaybackCommand paused = PlaybackCommand.select(3L, 1L, 900L, true).withPlayWhenReady(false);

        assertEquals(3L, paused.getRequestId());
        assertTrue(paused.hasSeekPosition());
        assertFalse(paused.isPlayWhenReady());
    }

    @Test
    public void consumedSeekIsNotReplayed() {
        PlaybackCommand consumed = PlaybackCommand.select(3L, 1L, 900L, false).withoutSeek();

        assertFalse(consumed.hasSeekPosition());
        assertEquals(0L, consumed.getSeekPositionMs());
        assertFalse(consumed.isPlayWhenReady());
    }

    @Test
    public void selectWithoutSeekDoesNotRequestSeek() {
        assertFalse(PlaybackCommand.select(1L, 1L, null, true).hasSeekPosition());
    }
}

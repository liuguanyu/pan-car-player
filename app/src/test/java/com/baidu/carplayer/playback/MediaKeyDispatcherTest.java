package com.baidu.carplayer.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.view.KeyEvent;

import org.junit.Test;

public class MediaKeyDispatcherTest {

    @Test
    public void mapsHardwareMediaKeysToCommands() {
        assertEquals(PlaybackCommand.Type.TOGGLE, MediaKeyDispatcher.toCommandType(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertEquals(PlaybackCommand.Type.TOGGLE, MediaKeyDispatcher.toCommandType(KeyEvent.KEYCODE_HEADSETHOOK));
        assertEquals(PlaybackCommand.Type.PLAY, MediaKeyDispatcher.toCommandType(KeyEvent.KEYCODE_MEDIA_PLAY));
        assertEquals(PlaybackCommand.Type.PAUSE, MediaKeyDispatcher.toCommandType(KeyEvent.KEYCODE_MEDIA_PAUSE));
        assertEquals(PlaybackCommand.Type.NEXT, MediaKeyDispatcher.toCommandType(KeyEvent.KEYCODE_MEDIA_NEXT));
        assertEquals(PlaybackCommand.Type.PREVIOUS, MediaKeyDispatcher.toCommandType(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
    }

    @Test
    public void ignoresNonMediaKeys() {
        assertNull(MediaKeyDispatcher.toCommandType(KeyEvent.KEYCODE_BACK));
        assertNull(MediaKeyDispatcher.toCommandType(KeyEvent.KEYCODE_VOLUME_UP));
    }
}

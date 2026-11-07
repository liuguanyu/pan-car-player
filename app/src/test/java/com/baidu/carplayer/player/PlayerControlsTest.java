package com.baidu.carplayer.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.playback.AudioFormat;
import com.baidu.carplayer.playback.PlaybackCommand;
import com.baidu.carplayer.playback.PlaybackState;
import com.baidu.carplayer.service.AudioPlayerService.PlayMode;

import org.junit.Test;

import java.util.Collections;

public class PlayerControlsTest {
    @Test
    public void muteRestoresPreviousVolume() {
        VolumeState v = new VolumeState();
        assertEquals(0f, v.toggle(0.8f), 0f);
        assertTrue(v.isMuted());
        assertEquals(0.8f, v.toggle(0f), 0f);
        assertFalse(v.isMuted());
    }

    @Test
    public void draggingVolumeUnmutes() {
        VolumeState v = new VolumeState();
        v.toggle(0.6f);
        v.onUserVolume(0.3f);
        assertFalse(v.isMuted());
        assertEquals(0f, v.toggle(0.3f), 0f);
        assertEquals(0.3f, v.toggle(0f), 0f);
    }

    @Test
    public void mutingAtZeroRestoresDefault() {
        VolumeState v = new VolumeState();
        v.toggle(0f);
        assertEquals(VolumeState.DEFAULT_RESTORE_VOLUME, v.toggle(0f), 0f);
    }

    @Test
    public void playModeCycles() {
        assertEquals(PlayMode.RANDOM, PlayModeCycle.next(PlayMode.ORDER.ordinal()));
        assertEquals(PlayMode.SINGLE, PlayModeCycle.next(PlayMode.RANDOM.ordinal()));
        assertEquals(PlayMode.ORDER, PlayModeCycle.next(PlayMode.SINGLE.ordinal()));
        assertEquals(PlayMode.ORDER, PlayModeCycle.fromOrdinal(99));
        assertEquals(PlayMode.RANDOM, PlayModeCycle.next(-1));
    }

    @Test
    public void commandsCarryClampedValues() {
        PlaybackCommand volume = PlaybackCommand.setVolume(1.7f);
        assertEquals(PlaybackCommand.Type.SET_VOLUME, volume.getType());
        assertEquals(1f, volume.getVolume(), 0f);
        assertEquals(0f, PlaybackCommand.setVolume(-1f).getVolume(), 0f);
        PlaybackCommand mode = PlaybackCommand.setPlayMode(PlayMode.SINGLE.ordinal());
        assertEquals(PlaybackCommand.Type.SET_PLAY_MODE, mode.getType());
        assertEquals(PlayMode.SINGLE.ordinal(), mode.getPlayMode());
    }

    @Test
    public void audioFormatDisplayNames() {
        assertEquals("FLAC", new AudioFormat("audio/flac", 44100, 0, 2).displayName());
        assertEquals("MP3", new AudioFormat("audio/mpeg", 44100, 320000, 2).displayName());
        assertEquals("AUDIO/X-FOO", new AudioFormat("audio/x-foo", 0, 0, 0).displayName());
        assertNull(new AudioFormat(null, 0, 0, 0).displayName());
    }

    @Test
    public void viewStateCarriesVolumeAndFormatAndDetectsFormatChange() {
        Song song = new Song(5L, "p", "t", "/t.flac", 1L, 0L);
        PlaybackState base = new PlaybackState(Collections.singletonList(song), 0, 5L,
                PlaybackState.Status.READY, true, 0L, 1000L, 0, 1, 0L);
        PlayerViewState noFormat = PlayerViewState.from(base.withPlayerDetails(0.4f, null));
        AudioFormat flac = new AudioFormat("audio/flac", 48000, 0, 2);
        PlayerViewState withFormat = PlayerViewState.from(base.withPlayerDetails(0.4f, flac));
        PlayerViewState same = PlayerViewState.from(base.withPlayerDetails(0.4f,
                new AudioFormat("audio/flac", 48000, 0, 2)));
        assertEquals(0.4f, withFormat.volume, 0f);
        assertTrue(withFormat.audioInfoChangedFrom(noFormat));
        assertFalse(same.audioInfoChangedFrom(withFormat));
        // 错误快照保留音量和格式
        assertEquals(flac, base.withPlayerDetails(0.4f, flac).withError("x").getAudioFormat());
    }
}

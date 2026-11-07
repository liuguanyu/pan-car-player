package com.baidu.carplayer.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.playback.PlaybackState;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PlayerViewStateTest {
    private static Song song(long id) {
        return new Song(id, "p", "s" + id, "/s" + id, 1L, 0L);
    }

    private static PlaybackState state(List<Song> queue, int index, PlaybackState.Status status,
                                       boolean playWhenReady, long pos, long dur) {
        return new PlaybackState(queue, index, queue.get(index).getId(), status, playWhenReady,
                pos, dur, 0, 3, 9L);
    }

    @Test
    public void playingRequiresReadyAndPlayWhenReady() {
        List<Song> q = Arrays.asList(song(1), song(2));
        assertTrue(PlayerViewState.from(state(q, 0, PlaybackState.Status.READY, true, 0, 10)).playing);
        assertFalse(PlayerViewState.from(state(q, 0, PlaybackState.Status.BUFFERING, true, 0, 10)).playing);
        assertFalse(PlayerViewState.from(state(q, 0, PlaybackState.Status.READY, false, 0, 10)).playing);
    }

    @Test
    public void positionIsClampedToDuration() {
        List<Song> q = Arrays.asList(song(1));
        assertEquals(1000L, PlayerViewState.from(state(q, 0, PlaybackState.Status.READY, true, 5000, 1000)).positionMs);
        assertEquals(0L, PlayerViewState.from(state(q, 0, PlaybackState.Status.READY, true, -5, 1000)).positionMs);
    }

    @Test
    public void detectsSongChanges() {
        List<Song> q = Arrays.asList(song(1), song(2));
        PlayerViewState a = PlayerViewState.from(state(q, 0, PlaybackState.Status.READY, true, 0, 10));
        PlayerViewState b = PlayerViewState.from(state(q, 0, PlaybackState.Status.READY, false, 5, 10));
        PlayerViewState c = PlayerViewState.from(state(q, 1, PlaybackState.Status.READY, false, 0, 10));
        assertTrue(a.songChangedFrom(null));
        assertFalse(b.songChangedFrom(a));
        assertTrue(b.playingChangedFrom(a));
        assertTrue(c.songChangedFrom(b));
    }

    @Test
    public void errorStateCarriesMessage() {
        List<Song> q = Arrays.asList(song(1));
        PlayerViewState v = PlayerViewState.from(
                state(q, 0, PlaybackState.Status.READY, true, 0, 10).withError("boom"));
        assertTrue(v.error);
        assertFalse(v.playing);
        assertEquals("boom", v.errorMessage);
    }

    @Test
    public void queueSnapshotIsReusedAndImmutable() {
        List<Song> snapshot = PlaybackState.snapshotQueue(Arrays.asList(song(1), song(2)));
        assertSame(snapshot, PlaybackState.snapshotQueue(snapshot));
        PlaybackState s = new PlaybackState(snapshot, 0, 1L, PlaybackState.Status.IDLE, false, 0, 0, 0, 0, 0);
        assertSame(snapshot, s.getQueue());
        try {
            s.getQueue().add(song(3));
            throw new AssertionError("queue must be immutable");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    public void formatsTime() {
        assertEquals("01:05", PlayerViewState.formatTime(65_000));
        assertEquals("00:00", PlayerViewState.formatTime(-1));
    }
}

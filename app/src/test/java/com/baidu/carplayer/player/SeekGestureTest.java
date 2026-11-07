package com.baidu.carplayer.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.playback.PlaybackCommand;
import com.baidu.carplayer.playback.PlaybackState;

import org.junit.Test;

import java.util.Collections;

public class SeekGestureTest {
    private static PlayerViewState view(long songId, long positionMs, boolean playWhenReady) {
        Song song = new Song(songId, "p", "t", "/t", 1L, 0L);
        PlaybackState state = new PlaybackState(Collections.singletonList(song), 0, songId,
                PlaybackState.Status.READY, playWhenReady, positionMs, 100_000L, 0, 1, 0L);
        return PlayerViewState.from(state);
    }

    @Test
    public void seekWhilePausedOnlySendsSeekCommand() {
        SeekGesture gesture = new SeekGesture();
        gesture.start();
        PlaybackCommand command = gesture.finish(7L, 42_000L);
        assertNotNull(command);
        assertEquals(PlaybackCommand.Type.SEEK, command.getType());
        assertEquals(42_000L, command.getSeekPositionMs());
        // SEEK 不携带"开始播放"意图，暂停中拖动不会恢复播放
        assertFalse(command.isPlayWhenReady());
    }

    @Test
    public void draggingIgnoresPublishedPositions() {
        SeekGesture gesture = new SeekGesture();
        gesture.start();
        assertEquals(-1L, gesture.displayPosition(view(7L, 5_000L, true)));
    }

    @Test
    public void pendingTargetIsShownUntilServiceConfirms() {
        SeekGesture gesture = new SeekGesture();
        gesture.start();
        gesture.finish(7L, 60_000L);
        // 服务尚未处理 seek，仍发布旧位置：保持显示目标位置，避免回跳
        assertEquals(60_000L, gesture.displayPosition(view(7L, 3_000L, false)));
        // 服务确认后切回服务位置
        assertEquals(60_200L, gesture.displayPosition(view(7L, 60_200L, false)));
        assertEquals(61_000L, gesture.displayPosition(view(7L, 61_000L, false)));
    }

    @Test
    public void unconfirmedSeekIsAbandonedAfterLimit() {
        SeekGesture gesture = new SeekGesture();
        gesture.start();
        gesture.finish(7L, 60_000L);
        for (int i = 0; i < SeekGesture.MAX_UNCONFIRMED_UPDATES; i++) {
            assertEquals(60_000L, gesture.displayPosition(view(7L, 3_000L, false)));
        }
        assertEquals(3_000L, gesture.displayPosition(view(7L, 3_000L, false)));
    }

    @Test
    public void songChangeDropsPendingTarget() {
        SeekGesture gesture = new SeekGesture();
        gesture.start();
        gesture.finish(7L, 60_000L);
        assertEquals(0L, gesture.displayPosition(view(8L, 0L, true)));
    }

    @Test
    public void finishWithoutStartOrSongDoesNothing() {
        SeekGesture gesture = new SeekGesture();
        assertNull(gesture.finish(7L, 1L));
        gesture.start();
        assertNull(gesture.finish(-1L, 1L));
        gesture.start();
        gesture.cancel();
        assertNull(gesture.finish(7L, 1L));
    }
}

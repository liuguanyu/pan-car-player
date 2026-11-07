package com.baidu.carplayer.service;

import static org.junit.Assert.assertEquals;

import com.baidu.carplayer.player.PlaybackQueue;

import org.junit.Test;

/** 持久化的播放模式名与队列模式的对应关系。 */
public class PlayModeNameTest {
    @Test
    public void knownNamesMapOneToOne() {
        for (AudioPlayerService.PlayMode mode : AudioPlayerService.PlayMode.values()) {
            PlaybackQueue.Mode mapped = AudioPlayerService.PlayMode.fromName(mode.name());
            assertEquals(mode.name(), mapped.name());
            assertEquals("ordinal 必须一致，页面按 ordinal 发送模式", mode.ordinal(), mapped.ordinal());
        }
    }

    @Test
    public void unknownOrMissingFallsBackToOrder() {
        assertEquals(PlaybackQueue.Mode.ORDER, AudioPlayerService.PlayMode.fromName(null));
        assertEquals(PlaybackQueue.Mode.ORDER, AudioPlayerService.PlayMode.fromName("SHUFFLE_V0"));
        assertEquals(PlaybackQueue.Mode.ORDER, AudioPlayerService.PlayMode.fromName(""));
    }
}

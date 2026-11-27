package com.baidu.carplayer.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 方向盘长按快进判定：纯逻辑，按时间戳驱动，不依赖 Android。 */
public class SeekHoldTest {

    @Test public void shortPressIsNotAHold() {
        SeekHold hold = new SeekHold();
        hold.begin(SeekHold.directionFor(PlaybackCommand.Type.NEXT), 1000L);
        assertEquals(0L, hold.tick(1000L + SeekHold.HOLD_MS - 1L));
        assertFalse(hold.isActive());
        // 未进入长按就松开：调用方仍需执行换曲。
        assertFalse(hold.end());
    }

    @Test public void longPressActivatesOnceThresholdPassed() {
        SeekHold hold = new SeekHold();
        hold.begin(SeekHold.directionFor(PlaybackCommand.Type.NEXT), 0L);
        assertEquals(0L, hold.tick(SeekHold.HOLD_MS - 1L));
        assertEquals(SeekHold.STEP_MS, hold.tick(SeekHold.HOLD_MS));
        assertTrue(hold.isActive());
        assertTrue("released after hold must be consumed", hold.end());
    }

    @Test public void forwardAndBackwardUseOppositeSigns() {
        SeekHold forward = new SeekHold();
        forward.begin(SeekHold.directionFor(PlaybackCommand.Type.NEXT), 0L);
        assertTrue(forward.tick(SeekHold.HOLD_MS) > 0L);

        SeekHold backward = new SeekHold();
        backward.begin(SeekHold.directionFor(PlaybackCommand.Type.PREVIOUS), 0L);
        assertTrue(backward.tick(SeekHold.HOLD_MS) < 0L);
    }

    @Test public void eachTickAdvancesWhileHeld() {
        SeekHold hold = new SeekHold();
        hold.begin(SeekHold.directionFor(PlaybackCommand.Type.NEXT), 0L);
        long now = SeekHold.HOLD_MS;
        long total = 0L;
        for (int i = 0; i < 5; i++) {
            total += hold.tick(now);
            now += SeekHold.TICK_MS;
        }
        assertEquals(5 * SeekHold.STEP_MS, total);
    }

    @Test public void otherKeysHaveNoDirection() {
        assertEquals(0, SeekHold.directionFor(PlaybackCommand.Type.TOGGLE));
        assertEquals(0, SeekHold.directionFor(PlaybackCommand.Type.PLAY));
        SeekHold hold = new SeekHold();
        hold.begin(0, 0L);
        assertEquals(0L, hold.tick(SeekHold.HOLD_MS * 4));
        assertFalse(hold.isActive());
    }

    @Test public void cancelStopsFurtherSteps() {
        SeekHold hold = new SeekHold();
        hold.begin(1, 0L);
        assertEquals(SeekHold.STEP_MS, hold.tick(SeekHold.HOLD_MS));
        hold.cancel();
        assertEquals(0L, hold.tick(SeekHold.HOLD_MS * 10));
        assertFalse(hold.isActive());
        assertFalse(hold.end());
    }
}

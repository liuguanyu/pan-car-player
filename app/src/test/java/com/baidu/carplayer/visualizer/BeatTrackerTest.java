package com.baidu.carplayer.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class BeatTrackerTest {
    private static final int FRAME_MS = 50;   // 20 帧/秒，与 Visualizer 最大采集率接近

    /** 生成合成鼓点：每拍开头一帧低频能量突增，其余帧是带噪声的底噪。 */
    private static float[] frame(long t, float periodMs, double offsetMs, Random rnd) {
        float[] m = new float[24];
        double inBeat = ((t - offsetMs) % periodMs + periodMs) % periodMs;
        boolean hit = inBeat < FRAME_MS;
        for (int i = 0; i < m.length; i++) {
            float base = .05f + rnd.nextFloat() * .03f;
            m[i] = hit && i < 10 ? .7f + rnd.nextFloat() * .2f : base;
        }
        return m;
    }

    private static BeatTracker feed(float periodMs, double offsetMs, long durationMs) {
        BeatTracker tracker = new BeatTracker();
        Random rnd = new Random(7);
        for (long t = 0; t < durationMs; t += FRAME_MS) tracker.onMagnitudes(frame(t, periodMs, offsetMs, rnd), t);
        return tracker;
    }

    private static double phaseError(BeatTracker tracker, long t, float periodMs, double offsetMs) {
        // 真实拍点位置
        double truth = (t - offsetMs) / periodMs;
        double pos = tracker.beatPosition(t);
        double err = (pos - truth) - Math.rint(pos - truth);
        return Math.abs(err);
    }

    @Test public void locksTempoAndPhaseFor120Bpm() {
        BeatTracker tracker = feed(500, 120, 10_000);
        assertEquals(500, tracker.periodMs(), 25);
        assertTrue("phase", phaseError(tracker, 10_000, 500, 120) < .15);
        assertTrue("confidence", tracker.confidence(10_000) > .5f);
    }

    @Test public void locksOtherTempos() {
        for (float period : new float[]{400f, 600f, 700f}) {   // 150 / 100 / 86 BPM
            BeatTracker tracker = feed(period, 80, 12_000);
            assertEquals("period " + period, period, tracker.periodMs(), period * .06f);
            assertTrue("phase " + period, phaseError(tracker, 12_000, period, 80) < .15);
        }
    }

    @Test public void followsTempoChange() {
        BeatTracker tracker = new BeatTracker();
        Random rnd = new Random(3);
        long t = 0;
        for (; t < 8_000; t += FRAME_MS) tracker.onMagnitudes(frame(t, 500, 0, rnd), t);
        for (; t < 20_000; t += FRAME_MS) tracker.onMagnitudes(frame(t, 400, 0, rnd), t);
        assertEquals(400, tracker.periodMs(), 25);
    }

    @Test public void positionIsContinuous() {
        BeatTracker tracker = new BeatTracker();
        Random rnd = new Random(11);
        double last = tracker.beatPosition(0);
        for (long t = 0; t < 10_000; t += FRAME_MS) {
            tracker.onMagnitudes(frame(t, 520, 30, rnd), t);
            double pos = tracker.beatPosition(t);
            // 每帧最多前进约 1/6 拍；不允许后退或跳拍
            assertTrue("t=" + t + " " + last + "→" + pos, pos - last > -.2 && pos - last < .35);
            last = pos;
        }
    }

    @Test public void silenceHasNoConfidence() {
        BeatTracker tracker = new BeatTracker();
        float[] quiet = new float[24];
        for (long t = 0; t < 6_000; t += FRAME_MS) tracker.onMagnitudes(quiet, t);
        assertEquals(0, tracker.confidence(6_000), 1e-6);
    }
}

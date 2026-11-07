package com.baidu.carplayer.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 小羊昼夜循环：白天/夜晚足够长，切换平滑无跳变，暂停时不推进。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SheepDayNightTest {

    @Test public void dayAndNightLastLongEnough() {
        assertTrue(DancingSheepVisualizer.DAY_SECONDS >= 120);
        assertTrue(DancingSheepVisualizer.NIGHT_SECONDS >= 120);
        assertTrue(DancingSheepVisualizer.TRANSITION_SECONDS >= 10);
        assertEquals(0f, DancingSheepVisualizer.nightFactor(0), 0f);
        assertEquals(0f, DancingSheepVisualizer.nightFactor(DancingSheepVisualizer.DAY_SECONDS - 1), 0f);
        float midNight = DancingSheepVisualizer.DAY_SECONDS + DancingSheepVisualizer.TRANSITION_SECONDS
                + DancingSheepVisualizer.NIGHT_SECONDS / 2;
        assertEquals(1f, DancingSheepVisualizer.nightFactor(midNight), 0f);
        assertEquals(0f, DancingSheepVisualizer.nightFactor(DancingSheepVisualizer.CYCLE_SECONDS + 1), 0f);
    }

    @Test public void transitionIsSmoothPerFrame() {
        float dt = 1f / 60, maxStep = 0;
        float prev = DancingSheepVisualizer.nightFactor(0);
        for (float t = dt; t < DancingSheepVisualizer.CYCLE_SECONDS * 2; t += dt) {
            float v = DancingSheepVisualizer.nightFactor(t);
            maxStep = Math.max(maxStep, Math.abs(v - prev));
            prev = v;
        }
        // 15 秒 smoothstep 过渡，每帧最大变化约 0.0017，远低于肉眼可感知的跳变。
        assertTrue("每帧亮度变化过大: " + maxStep, maxStep < .005f);
    }

    @Test public void clockAdvancesOnlyWhilePlaying() {
        DancingSheepVisualizer v = new DancingSheepVisualizer();
        byte[] fft = new byte[256];
        for (int i = 0; i < fft.length; i += 2) fft[i] = 60;
        for (int i = 0; i < 600; i++) v.update(fft, 1000, 300);
        float played = v.clockForTest();
        assertTrue("播放 10 秒后时钟应接近 10 秒: " + played, played > 8 && played <= 10.01f);
        for (int i = 0; i < 600; i++) v.update(new byte[0], 1000, 300);
        float settled = v.clockForTest();
        for (int i = 0; i < 600; i++) v.update(new byte[0], 1000, 300);
        assertEquals("完全静止后时钟不再推进", settled, v.clockForTest(), 0f);
        assertTrue(v.isAtRest());
    }

    @Test public void switchingModesKeepsTimeOfDay() {
        DancingSheepVisualizer v = new DancingSheepVisualizer();
        v.setClockForTest(200);
        v.reset();
        assertEquals(200f, v.clockForTest(), 0f);
    }

    @Test public void dancingMovesAreBig() {
        byte[] fft = new byte[256];
        for (int i = 0; i < fft.length; i += 2) fft[i] = 100;
        for (DancingSheepVisualizer.Move m : DancingSheepVisualizer.Move.values()) {
            DancingSheepVisualizer v = new DancingSheepVisualizer();
            v.setMoveForTest(m);
            float max = 0;
            for (int i = 0; i < 240; i++) { v.update(fft, 640, 300); max = Math.max(max, v.poseMagnitudeForTest()); }
            assertTrue(m + " 动作幅度太小: " + max, max >= 15);
        }
    }

    @Test public void blimpFliesBothWays() {
        java.util.Set<Float> dirs = new java.util.HashSet<>();
        DancingSheepVisualizer v = new DancingSheepVisualizer();
        byte[] fft = new byte[256];
        for (int i = 0; i < 60 * 60 * 20 && dirs.size() < 2; i++) {
            v.setClockForTest(30);   // 保持白天
            v.update(fft, 640, 300);
            dirs.add(v.blimpDirForTest());
        }
        assertEquals("飞艇应会从两个方向飞过", 2, dirs.size());
    }

    /** 舞步动作的拍点（脉冲峰值）必须落在节拍来源给出的整数拍上。 */
    @Test public void poseFollowsExternalBeat() {
        DancingSheepVisualizer v = new DancingSheepVisualizer();
        final long[] now = {0};
        final double period = 430;   // ~140 BPM，刻意不同于默认 120
        v.setTimeSourceForTest(() -> now[0]);
        v.setBeatSource(new DancingSheepVisualizer.BeatSource() {
            @Override public double beatPosition(long t) { return t / period; }
            @Override public float confidence(long t) { return 1f; }
        });
        byte[] fft = new byte[256];
        for (int i = 0; i < fft.length; i++) fft[i] = (byte) 60;
        for (int f = 0; f < 600; f++) {
            now[0] = f * 1000L / 60;
            v.update(fft, 640, 300);
            double pos = v.beatPosForTest();
            assertEquals((now[0] + 70) / period, pos, 1e-6);   // 含显示延迟补偿
        }
        assertEquals(1f, DancingSheepVisualizer.pulse(3.0), 1e-6);
        assertEquals(0f, DancingSheepVisualizer.pulse(3.5), 1e-6);
    }

    /** 舞步只在小节（4 拍）边界切换。 */
    @Test public void movesChangeOnBarBoundaries() {
        DancingSheepVisualizer v = new DancingSheepVisualizer();
        final long[] now = {0};
        v.setTimeSourceForTest(() -> now[0]);
        v.setBeatSource(new DancingSheepVisualizer.BeatSource() {
            @Override public double beatPosition(long t) { return t / 500.0; }
            @Override public float confidence(long t) { return 1f; }
        });
        byte[] fft = new byte[256];
        for (int i = 0; i < fft.length; i++) fft[i] = (byte) 60;
        DancingSheepVisualizer.Move last = null;
        int changes = 0;
        for (int f = 0; f < 60 * 60; f++) {
            now[0] = f * 1000L / 60;
            v.update(fft, 640, 300);
            DancingSheepVisualizer.Move m = v.moveForTest();
            if (last != null && m != last) {
                changes++;
                long beat = (long) Math.floor(v.beatPosForTest());
                assertEquals("beat " + beat, 0, Math.floorMod(beat, 4));
            }
            last = m;
        }
        assertTrue("changes " + changes, changes >= 3);
    }

    /** 均衡器：有声音时点亮，暂停后回落到 0。 */
    @Test public void blimpEqualizerReactsAndSettles() {
        DancingSheepVisualizer v = new DancingSheepVisualizer();
        byte[] fft = new byte[256];
        for (int i = 0; i < fft.length; i++) fft[i] = (byte) 50;
        for (int f = 0; f < 120; f++) v.update(fft, 640, 300);
        float[] eq = v.equalizerForTest();
        assertEquals(DancingSheepVisualizer.EQ_BANDS, eq.length);
        int lit = 0;
        for (float e : eq) if (e > .2f) lit++;
        assertTrue("lit bands " + lit, lit >= DancingSheepVisualizer.EQ_BANDS - 2);
        for (int f = 0; f < 600; f++) v.update(new byte[0], 640, 300);
        for (float e : v.equalizerForTest()) assertEquals(0, e, .01f);
    }

    private static DancingSheepVisualizer beatSheep(long[] now, double periodMs) {
        DancingSheepVisualizer v = new DancingSheepVisualizer();
        v.setTimeSourceForTest(() -> now[0]);
        v.setBeatSource(new DancingSheepVisualizer.BeatSource() {
            @Override public double beatPosition(long t) { return t / periodMs; }
            @Override public float confidence(long t) { return 1f; }
        });
        return v;
    }

    private static byte[] loud() {
        byte[] fft = new byte[256];
        for (int i = 0; i < fft.length; i++) fft[i] = (byte) 60;
        return fft;
    }

    /** 小羊时不时整只腾空离地，并且在拍点上起跳、落地。 */
    @Test public void sheepSometimesJumpsOffTheGroundOnTheBeat() {
        for (double period : new double[]{460, 700}) {   // 130 BPM / 86 BPM
            final long[] now = {0};
            DancingSheepVisualizer v = beatSheep(now, period);
            byte[] fft = loud();
            int jumps = 0;
            float last = 0, maxLift = 0;
            double takeoff = 0;
            for (int f = 0; f < 60 * 90; f++) {
                now[0] = f * 1000L / 60;
                v.update(fft, 640, 300);
                float lift = v.jumpLiftForTest();
                maxLift = Math.max(maxLift, lift);
                double pos = v.beatPosForTest();
                if (last == 0 && lift > 0) {
                    jumps++;
                    takeoff = pos;
                    assertTrue("takeoff off-beat " + pos, pos - Math.floor(pos) < .1);
                }
                if (last > 0 && lift == 0) {
                    assertTrue("landing off-beat " + pos, pos - Math.floor(pos) < .1);
                    assertTrue("airtime", pos - takeoff > .8);
                }
                last = lift;
            }
            assertTrue("jumps " + jumps, jumps >= 4);
            assertTrue("height " + maxLift, maxLift > 25 && maxLift <= DancingSheepVisualizer.MAX_JUMP + 1);
        }
    }

    /** 腾空段是竖直上抛：加速度恒为 -g，且最高点 = g·T²/8。 */
    @Test public void jumpFollowsProjectileMotion() {
        final long[] now = {0};
        final double period = 500;
        DancingSheepVisualizer v = beatSheep(now, period);
        byte[] fft = loud();
        java.util.List<Float> flight = new java.util.ArrayList<>();
        java.util.List<java.util.List<Float>> flights = new java.util.ArrayList<>();
        float dtSec = 1 / 60f;
        for (int f = 0; f < 60 * 60; f++) {
            now[0] = Math.round(f * 1000.0 / 60);
            v.update(fft, 640, 300);
            float lift = v.jumpLiftForTest();
            if (lift > 0) flight.add(lift);
            else if (!flight.isEmpty()) { flights.add(flight); flight = new java.util.ArrayList<>(); }
        }
        assertTrue("flights " + flights.size(), flights.size() >= 3);
        for (java.util.List<Float> fl : flights) {
            // 二阶差分 = a·dt² → 加速度
            for (int i = 1; i + 1 < fl.size(); i++) {
                float acc = (fl.get(i + 1) - 2 * fl.get(i) + fl.get(i - 1)) / (dtSec * dtSec);
                assertEquals("acceleration", -DancingSheepVisualizer.GRAVITY, acc,
                        DancingSheepVisualizer.GRAVITY * .15f);
            }
            float apex = 0;
            for (float h : fl) apex = Math.max(apex, h);
            float airtime = (fl.size() + 1) * dtSec;
            float expected = DancingSheepVisualizer.GRAVITY * airtime * airtime / 8;
            assertEquals("apex", expected, apex, expected * .12f);
        }
    }

    /** 起跳前要下蹲蓄力，落地后有缓冲下压。 */
    @Test public void jumpHasAnticipationAndLandingSquash() {
        final long[] now = {0};
        DancingSheepVisualizer v = beatSheep(now, 500);
        byte[] fft = loud();
        float prevLift = 0, squashBefore = 0, squashAfter = 0;
        java.util.ArrayDeque<Float> recent = new java.util.ArrayDeque<>();
        int sinceLanding = -1;
        for (int f = 0; f < 60 * 40; f++) {
            now[0] = Math.round(f * 1000.0 / 60);
            v.update(fft, 640, 300);
            float lift = v.jumpLiftForTest(), sq = v.jumpSquashForTest();
            if (prevLift == 0 && lift > 0) for (float r : recent) squashBefore = Math.max(squashBefore, r);
            if (prevLift > 0 && lift == 0) sinceLanding = 0;
            if (sinceLanding >= 0 && sinceLanding < 12) { squashAfter = Math.max(squashAfter, sq); sinceLanding++; }
            recent.addLast(sq);
            if (recent.size() > 12) recent.removeFirst();
            prevLift = lift;
        }
        assertTrue("anticipation " + squashBefore, squashBefore > .4f);
        assertTrue("landing " + squashAfter, squashAfter > .4f);
    }
}

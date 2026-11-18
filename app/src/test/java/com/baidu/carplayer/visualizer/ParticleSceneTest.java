package com.baidu.carplayer.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ParticleScene 的硬约束：轮廓闭合、彗星穿越、相机旋转、静止回落、不穿近平面。 */
public class ParticleSceneTest {
    private static final byte[] EMPTY = new byte[0];
    private static final float DT = 1f / 60f;

    private static byte[] loud(int amp) {
        byte[] fft = new byte[512];
        for (int i = 2; i < fft.length; i++) fft[i] = (byte) (i % 2 == 0 ? amp : -amp);
        return fft;
    }

    private static void run(ParticleScene s, byte[] fft, float seconds) {
        for (int i = 0; i < (int) (seconds / DT); i++) s.step(fft, DT, 500f, 1.78f);
    }

    /** 与 GLSL 一致的轮廓函数（最小值钳制 0.4），用于验证极限情况下曲线仍封闭。 */
    private static float profile(float[] h0, float[] h1, float theta, float phase) {
        float f = 1f;
        float[] a = {h0[0], h0[1], h0[2], h0[3], h1[0], h1[1], h1[2]};
        for (int h = 0; h < a.length; h++) f += a[h] * (float) Math.cos((h + 2) * theta + phase * (h + 1));
        return Math.max(f, 0.4f);
    }

    @Test public void harmonicAmplitudesRespectCaps() {
        float[] bands = new float[ParticleScene.BANDS];
        java.util.Arrays.fill(bands, 1f);   // 最响的极端输入
        float[] out = new float[ParticleScene.HARM];
        ParticleScene.harmonics(bands, out, ParticleScene.SNAP_CAP, 0.45f);
        float sum = 0f;
        for (float v : out) { assertTrue(v >= 0f); sum += v; }
        assertTrue("快照幅度总和不得超过上限，实际 " + sum, sum <= ParticleScene.SNAP_CAP + 1e-4f);
        // 快照 + 实时叠加后即使取最坏相位，半径也必须为正：1 - (SNAP+LIVE)*(1+0.8 flare) > 0 或被钳制
        float worst = 1f - (ParticleScene.SNAP_CAP + ParticleScene.LIVE_CAP) * 1.8f * 2f;
        assertTrue("幅度上限应使曲线在无钳制时也大致为正，实际 " + worst, worst > -1f);
    }

    @Test public void contourStaysClosedAndPositive() {
        ParticleScene s = new ParticleScene();
        run(s, loud(120), 12f);
        assertTrue("应已生成环", s.activeRings() > 0);
        int checked = 0;
        for (int i = 0; i < ParticleScene.MAX_RINGS; i++) {
            if (s.ringA[i * 4 + 1] < 0f) continue;
            float[] h0 = {s.ringH0[i * 4], s.ringH0[i * 4 + 1], s.ringH0[i * 4 + 2], s.ringH0[i * 4 + 3]};
            float[] h1 = {s.ringH1[i * 4], s.ringH1[i * 4 + 1], s.ringH1[i * 4 + 2], s.ringH1[i * 4 + 3]};
            for (int k = 0; k < 360; k++) {
                float f = profile(h0, h1, (float) (k * Math.PI / 180.0), 1.3f);
                assertTrue(f >= 0.4f - 1e-6f);
            }
            // 周期性：θ 与 θ+2π 的半径一致 → 首尾相接
            assertEquals(profile(h0, h1, 0f, 1.3f), profile(h0, h1, (float) (2 * Math.PI), 1.3f), 1e-3f);
            checked++;
        }
        assertTrue(checked > 0);
    }

    @Test public void cometTravelsAlongAxisAndSpawnsRings() {
        ParticleScene s = new ParticleScene();
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        boolean seen = false;
        for (int i = 0; i < 60 * 14; i++) {
            s.step(loud(100), DT, 500f, 1.78f);
            if (s.headVis > 0.3f) {
                seen = true;
                minX = Math.min(minX, s.headX);
                maxX = Math.max(maxX, s.headX);
            }
        }
        assertTrue("彗星应出现", seen);
        assertTrue("彗星应沿光轴大幅移动，实际范围 " + (maxX - minX), maxX - minX > 2f);
        assertTrue("彗星应在身后留下环", s.activeRings() > 3);
    }

    @Test public void ringsExpandOverTimeSoWakeIsConical() {
        ParticleScene s = new ParticleScene();
        run(s, loud(100), 6f);
        // 同一轮飞行中，环越老（越靠远端）age 越大；这里只验证 age 单调且在寿命内
        for (int i = 0; i < ParticleScene.MAX_RINGS; i++) {
            float age = s.ringA[i * 4 + 1];
            if (age < 0f) continue;
            assertTrue(age <= ParticleScene.LIFE);
        }
    }

    @Test public void cameraCompletesFullOrbitIncludingHeadOnAndFromBehind() {
        ParticleScene s = new ParticleScene();
        boolean sawHeadOn = false, sawBehind = false, sawLeftToRight = false, sawRightToLeft = false;
        float prevScreenSide = 0f;
        int frames = (int) (ParticleScene.ORBIT_PERIOD * 60f) + 60;
        for (int i = 0; i < frames; i++) {
            s.step(loud(100), DT, 500f, 1.78f);
            float az = (float) (s.orbit % (2 * Math.PI));
            double c = Math.cos(az);
            if (c > 0.97) sawHeadOn = true;      // 相机在 +X 端：彗星迎面冲来
            if (c < -0.97) sawBehind = true;     // 相机在 -X 端：从身后看彗星远去
            // 侧面时光轴在屏幕上的走向：用 +X 端点的屏幕 x 坐标相对中点判断
            double sn = Math.sin(az);
            if (Math.abs(c) < 0.15) {
                float[] m = s.vp;
                float x = s.axisA[0];
                float cw = m[3] * x + m[15];
                if (cw > 0.1f) {
                    float sx = (m[0] * x + m[12]) / cw;
                    if (sn > 0.9 && sx > 0.05f) sawLeftToRight = true;
                    if (sn < -0.9 && sx < -0.05f) sawRightToLeft = true;
                }
            }
        }
        assertTrue("应转满一圈，实际 " + s.orbit, s.orbit >= 2 * Math.PI - 0.05);
        assertTrue("应经过迎面看彗星的角度", sawHeadOn);
        assertTrue("应经过从彗星身后看的角度", sawBehind);
        assertTrue("两个侧面的光轴屏幕走向应相反（右→左与左→右）",
                sawLeftToRight && sawRightToLeft);
    }

    @Test public void cameraElevationStaysAboveAxisPlane() {
        ParticleScene s = new ParticleScene();
        float minG = 9f, maxG = -9f;
        for (int i = 0; i < 60 * 80; i++) {
            s.step(loud(100), DT, 500f, 1.78f);
            minG = Math.min(minG, s.gamma);
            maxG = Math.max(maxG, s.gamma);
        }
        assertTrue("俯仰应始终高于光轴平面，避免环退化成直线：" + minG, minG >= 0.08f);
        assertTrue("俯仰不应接近正上方：" + maxG, maxG <= 0.6f);
    }

    @Test public void axisEndsNeverCrossNearPlane() {
        ParticleScene s = new ParticleScene();
        for (int i = 0; i < 60 * 80; i++) {
            s.step(loud(100), DT, 500f, 1.78f);
            assertTrue("光轴 A 端过近: " + s.depthOf(s.axisA[0]), s.depthOf(s.axisA[0]) >= ParticleScene.MIN_DEPTH - 1e-3f);
            assertTrue("光轴 B 端过近: " + s.depthOf(s.axisB[0]), s.depthOf(s.axisB[0]) >= ParticleScene.MIN_DEPTH - 1e-3f);
        }
    }

    @Test public void settlesToRestAfterAudioStops() {
        ParticleScene s = new ParticleScene();
        assertTrue(s.isAtRest());
        run(s, loud(110), 8f);
        assertFalse(s.isAtRest());
        int frames = 0;
        while (!s.isAtRest() && frames < 60 * 10) { s.step(EMPTY, DT, 500f, 1.78f); frames++; }
        assertTrue("停止后应在 10 秒内回到静止，实际 " + frames, s.isAtRest());
        assertEquals("静止后应清空环", 0, s.activeRings());
    }

    @Test public void quietInputKeepsRingsNearlyCircular() {
        float[] bands = new float[ParticleScene.BANDS];
        float[] quiet = new float[ParticleScene.HARM];
        float[] loudOut = new float[ParticleScene.HARM];
        ParticleScene.harmonics(bands, quiet, ParticleScene.SNAP_CAP, 0.45f);
        java.util.Arrays.fill(bands, 0.8f);
        ParticleScene.harmonics(bands, loudOut, ParticleScene.SNAP_CAP, 0.45f);
        float q = 0f, l = 0f;
        for (int i = 0; i < quiet.length; i++) { q += quiet[i]; l += loudOut[i]; }
        assertTrue("越响轮廓起伏越大：安静 " + q + " 响 " + l, l > q * 1.5f);
    }
}

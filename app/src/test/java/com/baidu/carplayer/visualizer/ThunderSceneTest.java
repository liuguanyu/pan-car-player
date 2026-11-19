package com.baidu.carplayer.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

/** 雷击几何：点在画幅内、分叉随中频增加、通道够长、最小间隔生效。 */
public class ThunderSceneTest {
    private static final float ASPECT = 1280f / 348f;

    @Test public void segmentsStayInsideFrame() {
        ThunderScene s = new ThunderScene();
        Random r = new Random(1);
        for (int k = 0; k < 40; k++) {
            int slot = s.strike(k * 1f, ASPECT, r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat(), r);
            float[] d = s.data[slot];
            for (int i = 0; i < s.count[slot]; i++) {
                int o = i * ThunderScene.FLOATS;
                for (int j = 0; j < 4; j += 2) {
                    float x = d[o + j], y = d[o + j + 1];
                    // 通道被夹进画幅：任何线段都不应画到可视区外
                    assertTrue("x 越界: " + x, x >= -0.001f && x <= ASPECT + 0.001f);
                    assertTrue("y 越界: " + y, y >= -0.001f && y <= 1.001f);
                }
                float sArrive = d[o + 6];
                assertTrue("到达时刻应在 [0,1]: " + sArrive, sArrive >= 0f && sArrive <= 1f);
            }
            assertTrue(s.count[slot] <= ThunderScene.CAP);
        }
    }

    @Test public void moreMidMeansMoreBranches() {
        int low = 0, high = 0;
        for (int seed = 0; seed < 30; seed++) {
            ThunderScene a = new ThunderScene(), b = new ThunderScene();
            int sa = a.strike(0f, ASPECT, .3f, .05f, .3f, .5f, new Random(seed));
            int sb = b.strike(0f, ASPECT, .3f, .95f, .3f, .5f, new Random(seed));
            low += branchSegments(a, sa);
            high += branchSegments(b, sb);
        }
        assertTrue("中频高时分叉应更多: low=" + low + " high=" + high, high > low);
    }

    @Test public void horizontalStrikeSpansMostOfTheSky() {
        ThunderScene s = new ThunderScene();
        Random r = new Random(7);
        float minSpan = Float.MAX_VALUE;
        for (int k = 0; k < 30; k++) {
            int slot = s.strike(k * 1f, ASPECT, .2f, .5f, .3f, .6f, r);
            if (s.kind[slot] != 0) continue;
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            float[] d = s.data[slot];
            for (int i = 0; i < s.count[slot]; i++) {
                int o = i * ThunderScene.FLOATS;
                if (d[o + 7] != 0f) continue;   // 只看主干
                lo = Math.min(lo, Math.min(d[o], d[o + 2]));
                hi = Math.max(hi, Math.max(d[o], d[o + 2]));
            }
            minSpan = Math.min(minSpan, hi - lo);
        }
        assertTrue("主干应横跨较大范围: " + minSpan, minSpan > ASPECT * 0.25f);
    }

    @Test public void enforcesMinimumGap() {
        ThunderScene s = new ThunderScene();
        Random r = new Random(3);
        s.strike(10f, ASPECT, .5f, .5f, .5f, .5f, r);
        assertFalse(s.canStrike(10f + ThunderScene.MIN_GAP * 0.5f));
        assertTrue(s.canStrike(10f + ThunderScene.MIN_GAP));
    }

    @Test public void louderLastsLonger() {
        ThunderScene s = new ThunderScene();
        int quiet = s.strike(0f, ASPECT, .1f, .3f, .3f, .1f, new Random(5));
        int loud = s.strike(5f, ASPECT, .1f, .3f, .3f, .9f, new Random(5));
        assertTrue(s.lifetime(loud) > s.lifetime(quiet));
        assertEquals(0, s.lastSlot() - loud);
    }

    private static int branchSegments(ThunderScene s, int slot) {
        int n = 0;
        for (int i = 0; i < s.count[slot]; i++) if (s.data[slot][i * ThunderScene.FLOATS + 7] != 0f) n++;
        return n;
    }
}

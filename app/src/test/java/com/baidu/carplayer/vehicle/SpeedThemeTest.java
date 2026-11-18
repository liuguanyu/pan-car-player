package com.baidu.carplayer.vehicle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 车速配色：节点插值、越快越暖、白色文字可读。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SpeedThemeTest {
    private static float luminance(int color) {
        return .2126f * Color.red(color) + .7152f * Color.green(color) + .0722f * Color.blue(color);
    }

    @Test public void gradientEndsAtKnownStops() {
        assertEquals(0xff1a237e, SpeedTheme.headerTop(0f));
        assertEquals(0xff311b92, SpeedTheme.headerBottom(0f));
        assertEquals(0xff6e1a1a, SpeedTheme.headerTop(140f));
        // 超出范围不做外插
        assertEquals(SpeedTheme.headerTop(140f), SpeedTheme.headerTop(300f));
        assertEquals(SpeedTheme.headerTop(0f), SpeedTheme.headerTop(-20f));
    }

    @Test public void monotonicWarmthAsSpeedRises() {
        // 速度上升时红色整体上升、蓝色整体下降（越快越暖）
        int prevRed = -1, prevBlue = 999;
        for (float v = 0f; v <= 140f; v += 10f) {
            int c = SpeedTheme.headerTop(v);
            assertTrue("红色应不降：v=" + v, Color.red(c) >= prevRed - 3);
            assertTrue("蓝色应不升：v=" + v, Color.blue(c) <= prevBlue + 3);
            prevRed = Color.red(c);
            prevBlue = Color.blue(c);
        }
    }

    @Test public void colorsStayDarkEnoughForWhiteText() {
        for (float v = 0f; v <= 140f; v += 5f) {
            assertTrue("标题栏顶部过亮：v=" + v, luminance(SpeedTheme.headerTop(v)) < 110);
            assertTrue("标题栏底部过亮：v=" + v, luminance(SpeedTheme.headerBottom(v)) < 110);
            assertTrue("控制栏过亮：v=" + v, luminance(SpeedTheme.controlTint(v)) < 80);
        }
    }

    @Test public void interpolatesBetweenStops() {
        int mid = SpeedTheme.headerTop(15f);   // 0 与 30 之间
        int lo = SpeedTheme.headerTop(0f), hi = SpeedTheme.headerTop(30f);
        assertTrue(Color.red(mid) >= Math.min(Color.red(lo), Color.red(hi)));
        assertTrue(Color.blue(mid) <= Math.max(Color.blue(lo), Color.blue(hi)));
    }

    @Test public void accentAndFractionAreBounded() {
        assertEquals(0f, SpeedTheme.speedFraction(0f), 1e-4f);
        assertEquals(1f, SpeedTheme.speedFraction(200f), 1e-4f);
        for (float v = 0f; v <= 200f; v += 20f) {
            assertEquals(0xff, Color.alpha(SpeedTheme.accent(v)));
        }
    }
}

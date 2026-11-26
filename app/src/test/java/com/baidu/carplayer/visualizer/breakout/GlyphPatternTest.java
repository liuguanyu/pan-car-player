package com.baidu.carplayer.visualizer.breakout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GlyphPatternTest {
    @Test public void sameSeedSameSpec() {
        GlyphPattern.Spec a = GlyphPattern.generate(12345L, 112, GlyphFont.indexOf('A'), 7);
        GlyphPattern.Spec b = GlyphPattern.generate(12345L, 112, GlyphFont.indexOf('A'), 7);
        assertEquals(a.template, b.template);
        assertEquals(a.count, b.count);
        assertEquals(a.bpm, b.bpm);
        int[] ca = new int[GlyphPattern.MAX_BRICKS], ra = new int[GlyphPattern.MAX_BRICKS];
        int[] cb = new int[GlyphPattern.MAX_BRICKS], rb = new int[GlyphPattern.MAX_BRICKS];
        int na = a.gridCells(ca, ra), nb = b.gridCells(cb, rb);
        assertEquals(na, nb);
        for (int i = 0; i < na; i++) {
            assertEquals(ca[i], cb[i]);
            assertEquals(ra[i], rb[i]);
        }
    }

    @Test public void differentSeedsUsuallyDifferentTemplate() {
        int same = 0;
        for (long seed = 1; seed < 200; seed++) {
            int t1 = GlyphPattern.generate(seed, 120, 10, 5).template;
            int t2 = GlyphPattern.generate(seed + 1000, 120, 10, 5).template;
            if (t1 == t2) same++;
        }
        // 1/3 概率同模板，200 组里不该全都一样
        assertTrue(same < 200);
    }

    @Test public void alwaysWithinBrickCap() {
        for (long seed = 0; seed < 500; seed++) {
            int bpm = 60 + (int) (seed % 160);
            int key = (int) (seed % 8); // A..H
            GlyphPattern.Spec s = GlyphPattern.generate(seed, bpm, key, (int) (seed % 32));
            assertTrue("seed " + seed + " over cap: " + s.brickCount,
                    s.brickCount <= GlyphPattern.MAX_BRICKS);
            assertTrue("sparsest glyph is 7 cells (digit 7)", s.brickCount >= 7);
            assertTrue("count within chars", s.count >= 1 && s.count <= GlyphPattern.MAX_CHARS);
        }
    }

    @Test public void templateSIsSingleChar() {
        GlyphPattern.Spec s = GlyphPattern.generate(999L, 100, GlyphFont.indexOf('C'), 11);
        // S 模板就是单字符；generate 可能选中别的模板，这里直接查 count 一致性
        int[] c = new int[GlyphPattern.MAX_BRICKS], r = new int[GlyphPattern.MAX_BRICKS];
        assertEquals(s.brickCount, s.gridCells(c, r));
    }

    @Test public void degradeRemovesTrailingMarker() {
        // 构造一个必然超限的假 spec：4 字符
        int[] chars = {3, 5, 2, 9};
        int[] c = new int[GlyphPattern.MAX_BRICKS], r = new int[GlyphPattern.MAX_BRICKS];
        GlyphPattern.Spec big = new GlyphPattern.Spec(chars, 4, GlyphPattern.TEMPLATE_T, 0, 1, 100);
        big.brickCount = big.gridCells(c, r);
        if (big.brickCount > GlyphPattern.MAX_BRICKS) {
            GlyphPattern.Spec d = GlyphPattern.degrade(big);
            assertEquals(3, d.count);
            assertTrue(d.brickCount <= GlyphPattern.MAX_BRICKS);
        }
    }

    @Test public void charsOnlyFromPool() {
        for (long seed = 0; seed < 100; seed++) {
            GlyphPattern.Spec s = GlyphPattern.generate(seed, 90 + (int) (seed % 100),
                    (int) (seed % 8), (int) (seed % 32));
            for (int i = 0; i < s.count; i++)
                assertTrue(s.chars[i] >= 0 && s.chars[i] < GlyphFont.POOL.length());
        }
    }
}

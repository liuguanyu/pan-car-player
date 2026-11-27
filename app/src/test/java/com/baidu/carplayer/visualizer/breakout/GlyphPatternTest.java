package com.baidu.carplayer.visualizer.breakout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GlyphPatternTest {
    @Test public void sameTrackFeaturesProduceIdenticalCells() {
        for (long seed = -48; seed <= 48; seed++) {
            GlyphPattern.Spec first = GlyphPattern.generate(seed, 92, GlyphFont.indexOf('A'), 7);
            GlyphPattern.Spec again = GlyphPattern.generate(seed, 92, GlyphFont.indexOf('A'), 7);
            assertEquals(first.template, again.template);
            assertEquals(first.count, again.count);
            assertTrue(first.count >= 1 && first.count <= 3);
            int[] cols = new int[60], rows = new int[60];
            assertEquals(first.brickCount, first.gridCells(cols, rows));
            int[] otherCols = new int[60], otherRows = new int[60];
            assertEquals(first.brickCount, again.gridCells(otherCols, otherRows));
            for (int i = 0; i < first.brickCount; i++) {
                assertEquals(cols[i], otherCols[i]);
                assertEquals(rows[i], otherRows[i]);
                assertTrue(cols[i] >= 0 && cols[i] < first.gridCols);
                assertTrue(rows[i] >= 0 && rows[i] < first.gridRows);
            }
        }
    }

    @Test public void knownTempoAndKeyFillTheThreeCharacterTemplate() {
        GlyphPattern.Spec pattern = GlyphPattern.generate(2L, 92, GlyphFont.indexOf('A'), 7);
        assertEquals(GlyphPattern.TEMPLATE_T, pattern.template);
        assertEquals(GlyphFont.indexOf('A'), pattern.charKey(0));
        assertEquals(9, pattern.charKey(1));
        assertEquals(2, pattern.charKey(2));
    }

    @Test public void unknownTempoUsesStableFallbackInsteadOfInventingDigits() {
        GlyphPattern.Spec pattern = GlyphPattern.generate(2L, 0, -1, GlyphFont.indexOf('H'));
        assertEquals(GlyphPattern.TEMPLATE_D, pattern.template);
        assertEquals(GlyphFont.indexOf('H'), pattern.charKey(0));
        assertTrue(pattern.charKey(1) >= 0 && pattern.charKey(1) < GlyphFont.SIZE);
    }
}

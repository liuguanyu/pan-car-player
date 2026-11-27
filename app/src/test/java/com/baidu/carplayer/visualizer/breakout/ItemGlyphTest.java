package com.baidu.carplayer.visualizer.breakout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 技能砖标志编号：五种类型必须各自唯一，且都落在着色器的标志区间内。 */
public class ItemGlyphTest {

    @Test public void everyItemTypeHasDistinctGlyph() {
        int[] types = {
                BreakoutScene.ITEM_MULTI, BreakoutScene.ITEM_FAST, BreakoutScene.ITEM_SLOW,
                BreakoutScene.ITEM_WIDE, BreakoutScene.ITEM_NARROW,
        };
        float[] kinds = new float[types.length];
        for (int i = 0; i < types.length; i++) {
            kinds[i] = ItemGlyph.kindFor(types[i]);
            assertTrue("kind out of glyph range: " + kinds[i], kinds[i] >= 10f && kinds[i] <= 14f);
            for (int j = 0; j < i; j++) {
                assertTrue("types " + types[i] + " and " + types[j] + " share a glyph",
                        kinds[i] != kinds[j]);
            }
        }
    }

    @Test public void plainBrickHasNoGlyph() {
        assertEquals(0f, ItemGlyph.kindFor(BreakoutScene.ITEM_NONE), 0f);
        assertEquals(0f, ItemGlyph.kindFor(99), 0f);
    }

    @Test public void ghostGlyphIsDimmerButVisible() {
        assertTrue(ItemGlyph.GHOST_GAIN > 0.2f);
        assertTrue(ItemGlyph.GHOST_GAIN < 1f);
    }
}

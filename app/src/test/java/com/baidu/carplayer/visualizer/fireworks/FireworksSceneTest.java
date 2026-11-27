package com.baidu.carplayer.visualizer.fireworks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 天际线生成：确定性、三层纵深、地面线与水面的相对关系。 */
public class FireworksSceneTest {

    @Test public void sameSeedProducesSameSkyline() {
        FireworksScene a = new FireworksScene();
        FireworksScene b = new FireworksScene();
        a.reset(424242L);
        b.reset(424242L);
        assertEquals(a.count(), b.count());
        for (int i = 0; i < a.count(); i++) {
            assertEquals(a.buildings()[i].x, b.buildings()[i].x, 1e-6f);
            assertEquals(a.buildings()[i].height, b.buildings()[i].height, 1e-6f);
            assertEquals(a.buildings()[i].layer, b.buildings()[i].layer);
            assertEquals(a.buildings()[i].style, b.buildings()[i].style);
        }
    }

    @Test public void differentSeedsProduceDifferentSkylines() {
        FireworksScene a = new FireworksScene();
        FireworksScene b = new FireworksScene();
        a.reset(1L);
        b.reset(999999L);
        boolean differs = a.count() != b.count();
        for (int i = 0; !differs && i < a.count(); i++) {
            differs = Math.abs(a.buildings()[i].height - b.buildings()[i].height) > 1e-4f;
        }
        assertTrue("skyline should depend on seed", differs);
    }

    @Test public void allThreeLayersArePopulated() {
        FireworksScene scene = new FireworksScene();
        scene.reset(7L);
        int[] perLayer = new int[3];
        for (int i = 0; i < scene.count(); i++) perLayer[scene.buildings()[i].layer]++;
        for (int layer = 0; layer < 3; layer++) {
            assertTrue("layer " + layer + " empty", perLayer[layer] > 0);
        }
        assertTrue(scene.count() <= FireworksScene.MAX_BUILDINGS);
    }

    @Test public void buildingsStayInsideViewAndAboveHorizon() {
        FireworksScene scene = new FireworksScene();
        scene.setAspect(348f / 1280f);
        scene.reset(20260101L);
        float horizon = scene.horizonY();
        assertTrue(horizon > 0f && horizon < scene.aspect());
        for (int i = 0; i < scene.count(); i++) {
            FireworksScene.Building b = scene.buildings()[i];
            assertTrue("x out of view: " + b.x, b.x > 0f && b.x < 1f);
            assertTrue("width must be positive", b.halfW > 0f);
            assertTrue("building must rise above horizon", b.height > 0f);
            // 最高楼也不能顶出画面
            assertTrue("too tall: " + b.height, b.height < horizon);
        }
    }

    @Test public void landmarksUseDistinctSilhouettes() {
        FireworksScene scene = new FireworksScene();
        scene.reset(20260101L);
        boolean[] seen = new boolean[4];
        for (int i = 0; i < scene.count(); i++) {
            seen[scene.buildings()[i].style] = true;
        }
        for (int style = 0; style < 4; style++) {
            assertTrue("silhouette style " + style + " missing", seen[style]);
        }
    }

    @Test public void aspectChangeMovesHorizonOnly() {
        FireworksScene scene = new FireworksScene();
        scene.reset(5L);
        float beforeX = scene.buildings()[0].x;
        scene.setAspect(.5f);
        assertEquals(beforeX, scene.buildings()[0].x, 0f);
        assertEquals(.5f * FireworksScene.HORIZON_RATIO, scene.horizonY(), 1e-5f);
    }
}

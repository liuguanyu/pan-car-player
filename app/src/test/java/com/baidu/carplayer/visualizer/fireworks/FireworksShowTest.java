package com.baidu.carplayer.visualizer.fireworks;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class FireworksShowTest {
    @Test public void wideScreenBurstsHaveRoomForLargerCrowns() {
        FireworksShow show = new FireworksShow();
        float aspect = 348f / 1280f;
        show.setAspect(aspect);
        FireworksShow.Audio audio = new FireworksShow.Audio();
        audio.bass = .5f;
        for (int i = 0; i < FireworksShow.MAX_BURSTS; i++) show.launch(audio, i % 2);
        float[] bursts = show.data();
        for (int i = 0; i < bursts.length; i += FireworksShow.FLOATS_PER_BURST) {
            float x = bursts[i + FireworksShow.F_X];
            float y = bursts[i + FireworksShow.F_Y];
            float radius = bursts[i + FireworksShow.F_RADIUS];
            assertTrue(radius > .24f * aspect);
            assertTrue(x - radius > 0f && x + radius < 1f);
            assertTrue(y - .9f * radius > 0f && y + radius < show.horizonY());
        }
    }

    @Test public void MusicProfilesCanSelectAllEightShapes() {
        FireworksShow show = new FireworksShow();
        FireworksShow.Audio audio = new FireworksShow.Audio();
        boolean[] seen = new boolean[8];
        for (int profile = 0; profile < 4; profile++) {
            audio.bass = profile == 2 || profile == 3 ? .5f : .03f;
            audio.treble = profile == 1 ? .3f : .02f;
            audio.flux = profile == 3 ? .5f : 0f;
            audio.beatConfidence = .8f;
            for (int i = 0; i < 48; i++) show.launch(audio, .5f);
            for (int i = 0; i < show.used(); i++) {
                seen[(int) show.data()[i * FireworksShow.FLOATS_PER_BURST + FireworksShow.F_TYPE]] = true;
            }
        }
        for (int type = 0; type < seen.length; type++) assertTrue("missing shape " + type, seen[type]);
    }
}

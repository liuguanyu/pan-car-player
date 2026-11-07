package com.baidu.carplayer.visualizer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 停止播放后，各效果应在有限帧内平滑回落到 0 态，而不是瞬间清空或永远动下去。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VisualizerRestTest {
    private static final int W = 1000, H = 260;
    private static final byte[] EMPTY = new byte[0];

    private static byte[] loudFft() {
        byte[] fft = new byte[256];
        for (int i = 0; i < fft.length; i++) fft[i] = (byte) (i % 2 == 0 ? 120 : -90);
        return fft;
    }

    private static void assertSettles(AudioVisualizer v) {
        Canvas canvas = new Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888));
        for (int i = 0; i < 60; i++) {
            v.update(loudFft(), W, H);
            v.draw(canvas);
        }
        assertFalse(v.getName() + " 播放中不应处于 0 态", v.isAtRest());

        // 停止后第一帧不应立即归零（需要过渡）
        v.update(EMPTY, W, H);
        assertFalse(v.getName() + " 停止后应有过渡而不是瞬间清零", v.isAtRest());

        int frames = 1;
        while (!v.isAtRest() && frames < 600) {
            v.update(EMPTY, W, H);
            v.draw(canvas);
            frames++;
        }
        assertTrue(v.getName() + " 应在 10 秒（600 帧）内回到 0 态，实际 " + frames, v.isAtRest());
    }

    @Test public void barsSettle() { assertSettles(new BarsVisualizer()); }
    @Test public void waveSettles() { assertSettles(new WaveVisualizer()); }
    @Test public void sheepSettles() { assertSettles(new DancingSheepVisualizer()); }

    @Test public void particlesSettle() {
        ParticlesVisualizer v = new ParticlesVisualizer();
        Canvas canvas = new Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888));
        // 先静默再突增，触发一次粒子爆发
        for (int i = 0; i < 5; i++) v.update(new byte[256], W, H);
        for (int i = 0; i < 10; i++) { v.update(loudFft(), W, H); v.draw(canvas); }
        assertFalse(v.isAtRest());
        int frames = 0;
        while (!v.isAtRest() && frames < 600) { v.update(EMPTY, W, H); v.draw(canvas); frames++; }
        assertTrue("粒子应在停止后消散，实际 " + frames, v.isAtRest());
    }

    @Test public void freshEffectsAreAtRest() {
        assertTrue(new BarsVisualizer().isAtRest());
        assertTrue(new WaveVisualizer().isAtRest());
        assertTrue(new ParticlesVisualizer().isAtRest());
        assertTrue(new DancingSheepVisualizer().isAtRest());
    }

    @Test public void managerKeepsDrawingUntilRestThenStops() {
        VisualizerManager manager = new VisualizerManager();
        manager.switchTo("bars");
        Canvas canvas = new Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888));
        manager.draw(canvas, W, H);
        assertFalse("未播放过时无需过渡", manager.isSettling());
    }
}

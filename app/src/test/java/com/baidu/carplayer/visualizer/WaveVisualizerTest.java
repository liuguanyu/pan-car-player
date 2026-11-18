package com.baidu.carplayer.visualizer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 波形隧道：分层纵深、鼓点推进、静音回落。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WaveVisualizerTest {
    private static final int W = 1216, H = 330;

    private static byte[] loud(int amp) {
        byte[] fft = new byte[512];
        for (int i = 2; i < fft.length; i++) fft[i] = (byte) (i % 3 == 0 ? amp : -amp / 2);
        return fft;
    }

    private static Canvas canvas() {
        return new Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888));
    }

    @Test public void newVisualizerIsAtRestAndDrawsNothing() {
        WaveVisualizer v = new WaveVisualizer();
        assertTrue(v.isAtRest());
        v.draw(canvas());   // 没有数据时不应崩溃
        assertTrue(v.isAtRest());
    }

    @Test public void layeredTunnelBecomesActiveAndSettles() {
        WaveVisualizer v = new WaveVisualizer();
        Canvas c = canvas();
        for (int i = 0; i < 30; i++) { v.update(loud(120), W, H); v.draw(c); }
        assertFalse("有信号时不应是静止态", v.isAtRest());
        int frames = 0;
        while (!v.isAtRest() && frames < 600) { v.update(new byte[0], W, H); v.draw(c); frames++; }
        assertTrue("停止后应在 10 秒内回落，实际 " + frames, v.isAtRest());
        // 回落之后仍需可绘制（只剩一条平线）
        v.draw(c);
    }

    @Test public void beatPushesTunnelForward() {
        WaveVisualizer v = new WaveVisualizer();
        Canvas c = canvas();
        for (int i = 0; i < 10; i++) { v.update(loud(60), W, H); v.draw(c); }
        v.onBeat(1f);
        v.draw(c);
        assertFalse("鼓点后 tunnelKick 应让效果继续运动", v.isAtRest());
    }

    @Test public void resetClearsHistory() {
        WaveVisualizer v = new WaveVisualizer();
        Canvas c = canvas();
        for (int i = 0; i < 10; i++) { v.update(loud(100), W, H); v.draw(c); }
        v.reset();
        assertTrue(v.isAtRest());
        v.update(loud(100), W, H);   // 重置后可重新开始
        v.draw(c);
        assertFalse(v.isAtRest());
    }
}

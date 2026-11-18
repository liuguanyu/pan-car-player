package com.baidu.carplayer.visualizer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** 手动预览：WAVE_PREVIEW=1 时把山峰场景渲染到 /tmp/wave/*.png；平时直接跳过。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WaveRenderPreview {
    /** 与车机播放页可视化区域一致的宽高比。 */
    private static final int W = 1216, H = 416;

    /** 合成频谱：低频强、中频起伏、高频细碎；phase 让频谱随时间变化。 */
    private static byte[] fft(float loud, float phase) {
        byte[] fft = new byte[1024];
        for (int i = 2; i < fft.length; i += 2) {
            float bin = i / 2f;
            float env = (float) (Math.exp(-bin / 90f) * 1.0 + 0.25 * Math.exp(-bin / 260f));
            float wob = 0.65f + 0.35f * (float) Math.sin(bin * 0.07f + phase);
            int v = (int) (127 * loud * env * wob);
            fft[i] = (byte) Math.max(-127, Math.min(127, v));
            fft[i + 1] = (byte) Math.max(-127, Math.min(127, v / 2));
        }
        return fft;
    }

    private static void save(WaveVisualizer v, String name) throws Exception {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        try (FileOutputStream o = new FileOutputStream("/tmp/wave/" + name + ".png")) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
    }

    @Test public void render() throws Exception {
        if (System.getenv("WAVE_PREVIEW") == null) return;
        new java.io.File("/tmp/wave").mkdirs();

        // 安静 / 中等 / 高潮 三种响度各走一段，每段存几帧
        float[] levels = {0.15f, 0.55f, 1.0f};
        String[] names = {"quiet", "mid", "loud"};
        for (int k = 0; k < levels.length; k++) {
            WaveVisualizer v = new WaveVisualizer();
            v.setFrameStepForTest(16f);
            float phase = 0f;
            for (int i = 0; i < 240; i++) {        // 预热 4 秒，填满频谱历史
                phase += 0.05f;
                v.update(fft(levels[k], phase), W, H);
                v.draw(new Canvas(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)));
            }
            for (int f = 0; f < 4; f++) {
                for (int i = 0; i < 45; i++) {     // 每张间隔 0.75 秒
                    phase += 0.05f;
                    v.update(fft(levels[k], phase), W, H);
                    v.draw(new Canvas(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)));
                }
                save(v, names[k] + "_" + f);
            }
        }

        // 鼓点瞬间
        WaveVisualizer b = new WaveVisualizer();
        b.setFrameStepForTest(16f);
        for (int i = 0; i < 200; i++) {
            b.update(fft(0.6f, i * 0.05f), W, H);
            b.draw(new Canvas(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)));
        }
        b.onBeat(1.2f);
        b.update(fft(0.95f, 10f), W, H);
        save(b, "beat");
    }
}

package com.baidu.carplayer.visualizer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** 手动预览：SHEEP_PREVIEW=1 时把各场景渲染到 /tmp/sheep/*.png；平时直接跳过。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SheepRenderPreview {
    private static final int W = 640, H = 300;

    private static byte[] fft() {
        byte[] fft = new byte[256];
        for (int i = 0; i < fft.length; i += 2) fft[i] = (byte) (110 - i / 3);
        return fft;
    }

    private static void save(DancingSheepVisualizer v, String name) throws Exception {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        try (FileOutputStream o = new FileOutputStream("/tmp/sheep/" + name + ".png")) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
    }

    @Test public void render() throws Exception {
        if (System.getenv("SHEEP_PREVIEW") == null) return;
        new java.io.File("/tmp/sheep").mkdirs();
        // 每个舞步渲染 4 帧（相位不同）
        for (DancingSheepVisualizer.Move m : DancingSheepVisualizer.Move.values()) {
            DancingSheepVisualizer v = new DancingSheepVisualizer();
            v.setClockForTest(30);
            v.setOutfitForTest(DancingSheepVisualizer.Outfit.NONE);
            v.setFaceForTest(DancingSheepVisualizer.Face.NORMAL);
            v.setMoveForTest(m);
            for (int i = 0; i < 90; i++) v.update(fft(), W, H);
            for (int f = 0; f < 6; f++) {
                for (int i = 0; i < 5; i++) v.update(fft(), W, H);
                save(v, "move_" + m + "_" + f);
            }
        }
        for (DancingSheepVisualizer.Face face : DancingSheepVisualizer.Face.values()) {
            DancingSheepVisualizer v = new DancingSheepVisualizer();
            v.setClockForTest(30);
            v.setOutfitForTest(DancingSheepVisualizer.Outfit.NONE);
            v.setMoveForTest(DancingSheepVisualizer.Move.BOB);
            v.setFaceForTest(face);
            for (int i = 0; i < 30; i++) v.update(new byte[0], W, H);
            save(v, "face_" + face);
        }
        DancingSheepVisualizer v = new DancingSheepVisualizer();
        v.setClockForTest(30);
        v.setOutfitForTest(DancingSheepVisualizer.Outfit.COWBOY);
        v.triggerForTest("blimpRight");
        for (int i = 0; i < 20; i++) v.update(fft(), W, H);
        save(v, "blimp_right");

        // 一次完整大跳：从蓄力到落地缓冲，每 4 帧（约 67ms）存一张
        final long[] now = {0};
        DancingSheepVisualizer j = new DancingSheepVisualizer();
        j.setClockForTest(30);
        j.setOutfitForTest(DancingSheepVisualizer.Outfit.NONE);
        j.setFaceForTest(DancingSheepVisualizer.Face.NORMAL);
        j.setMoveForTest(DancingSheepVisualizer.Move.BOB);
        j.setTimeSourceForTest(() -> now[0]);
        j.setBeatSource(new DancingSheepVisualizer.BeatSource() {
            @Override public double beatPosition(long t) { return t / 500.0; }
            @Override public float confidence(long t) { return 1f; }
        });
        int f = 0, saved = 0, landedFrames = -1;
        float prev = 0;
        boolean started = false;
        for (; f < 60 * 30 && saved < 24; f++) {
            now[0] = Math.round(f * 1000.0 / 60);
            j.update(fft(), W, H);
            float sq = j.jumpSquashForTest(), lift = j.jumpLiftForTest();
            if (!started && sq > .05f && lift == 0) started = true;
            if (started && f % 4 == 0) save(j, String.format("jump_%02d", saved++));
            if (prev > 0 && lift == 0) landedFrames = 0;
            if (landedFrames >= 0 && ++landedFrames > 16) break;
            prev = lift;
        }
    }
}

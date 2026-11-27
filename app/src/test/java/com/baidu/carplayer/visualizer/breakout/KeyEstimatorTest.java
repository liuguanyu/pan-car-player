package com.baidu.carplayer.visualizer.breakout;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class KeyEstimatorTest {
    @Test public void estimatesTonicAcrossOctaves() {
        assertEquals(GlyphFont.indexOf('C'), estimate(523.25f, 659.25f, 783.99f, 1046.5f));
        assertEquals(GlyphFont.indexOf('A'), estimate(440f, 523.25f, 659.25f, 880f));
        assertEquals(GlyphFont.indexOf('D'), estimate(587.33f, 739.99f, 880f, 1174.66f));
    }

    @Test public void silenceAndSingleToneGiveNoKey() {
        assertEquals(-1, estimate());
        assertEquals(-1, estimate(440f));
    }

    private int estimate(float... notes) {
        KeyEstimator estimator = new KeyEstimator();
        float binHz = 44100f / 1024;
        float[] magnitudes = new float[128];
        for (float note : notes) {
            int peak = Math.round(note / binHz);
            for (int i = peak - 1; i <= peak + 1; i++) {
                float distance = i - note / binHz;
                magnitudes[i] = Math.max(magnitudes[i], 1f - .4f * distance * distance);
            }
        }
        for (int i = 0; i < 16; i++) estimator.addFrame(magnitudes, magnitudes.length, binHz);
        return estimator.letterKey();
    }
}

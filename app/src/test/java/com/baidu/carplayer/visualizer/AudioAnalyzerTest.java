package com.baidu.carplayer.visualizer;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioAnalyzerTest {
    @Test public void emptyAndSilenceAreZero() {
        AudioAnalyzer analyzer = new AudioAnalyzer();
        assertEquals(0, analyzer.analyze(new byte[0]).spectrum.length);
        AudioAnalyzer.Analysis a = analyzer.analyze(new byte[256]);
        assertEquals(0f, a.volume, 0f);
        assertFalse(a.peak);
        assertEquals(0, AudioAnalyzer.fftToMagnitudes(new byte[256], 0).length);
    }
    @Test public void signedBytesAreNotTreatedAsUnsigned() {
        float[] values = AudioAnalyzer.fftToMagnitudes(new byte[]{0, 0, -20, 0}, 128);
        assertEquals(20f / 181, values[1], .0001f);
        assertEquals(1f, AudioAnalyzer.fftToMagnitudes(new byte[]{-128, 0, -128, -128}, 128)[1], 0f);
    }
    @Test public void bandsAndTruncation() {
        byte[] fft = new byte[256];
        for (int i = 1; i < 6; i++) fft[i * 2] = 100;
        AudioAnalyzer.Analysis a = new AudioAnalyzer().analyze(fft);
        assertTrue(a.bass > 0); assertEquals(0f, a.mid, 0f); assertEquals(0f, a.treble, 0f);
        assertEquals(4, AudioAnalyzer.fftToMagnitudes(fft, 4).length);
        fft = new byte[256];
        for (int i = 32; i < 128; i++) fft[i * 2] = 100;
        a = new AudioAnalyzer().analyze(fft);
        assertEquals(0f, a.bass, 0f); assertEquals(0f, a.mid, 0f); assertTrue(a.treble > 0);
    }
    @Test public void peakHasCooldownAndCanReset() {
        byte[] loud = new byte[256];
        java.util.Arrays.fill(loud, (byte) 127);
        AudioAnalyzer analyzer = new AudioAnalyzer();
        assertTrue(analyzer.analyze(loud).peak);
        for (int i = 0; i < 4; i++) {
            analyzer.analyze(new byte[256]);
            assertFalse(analyzer.analyze(loud).peak);
        }
        analyzer.analyze(new byte[256]);
        assertTrue(analyzer.analyze(loud).peak);
        analyzer.reset();
        assertTrue(analyzer.analyze(loud).peak);
    }
}

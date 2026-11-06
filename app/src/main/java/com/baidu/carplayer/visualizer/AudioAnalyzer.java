package com.baidu.carplayer.visualizer;

/** Signed-byte Android FFT decoding and frequency-band/beat analysis, ported from Kotlin. */
public final class AudioAnalyzer {
    public static final class Analysis {
        public final float bass, mid, treble, volume;
        public final boolean peak;
        public final float[] spectrum;
        Analysis(float bass, float mid, float treble, float volume, boolean peak, float[] spectrum) {
            this.bass = bass; this.mid = mid; this.treble = treble;
            this.volume = volume; this.peak = peak; this.spectrum = spectrum;
        }
    }

    private float previousVolume;
    private int cooldown;

    public static float[] fftToMagnitudes(byte[] fft, int maxBins) {
        if (fft == null || fft.length < 2 || maxBins <= 0) return new float[0];
        float[] values = new float[Math.min(fft.length / 2, maxBins)];
        values[0] = Math.abs((int) fft[0]) / 128f;
        for (int i = 1; i < values.length; i++) {
            int real = fft[2 * i], imaginary = fft[2 * i + 1];
            values[i] = Math.min(1f, (float) Math.sqrt(real * real + imaginary * imaginary) / 181f);
        }
        return values;
    }

    private static float average(float[] values, int start, int end) {
        start = Math.min(start, values.length); end = Math.min(end, values.length);
        if (end <= start) return 0;
        float sum = 0;
        for (int i = start; i < end; i++) sum += values[i];
        return sum / (end - start);
    }

    public Analysis analyze(byte[] fft) {
        float[] values = fftToMagnitudes(fft, 128);
        float volume = average(values, 0, values.length);
        boolean peak = false;
        if (cooldown > 0) cooldown--;
        else if (volume > .65f && volume - previousVolume > .15f) {
            peak = true; cooldown = 8;
        }
        previousVolume = volume;
        return new Analysis(average(values, 0, 6), average(values, 6, 32),
                average(values, 32, values.length), volume, peak, values);
    }

    public void reset() { previousVolume = 0; cooldown = 0; }
}

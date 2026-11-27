package com.baidu.carplayer.visualizer.breakout;

/** Collects pitch-class energy from FFT peaks, then scores major and minor profiles on demand. */
public final class KeyEstimator {
    private static final String TONIC_LETTERS = "CCDDEFFGGAAB";
    private static final float[][] PROFILES = {
        {6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f},
        {6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f}
    };
    private static final double SEMITONES_PER_LOG = 12 / Math.log(2);
    private final float[] chroma = new float[12];
    private int frames;

    void addFrame(float[] magnitudes, int count, float binHz) {
        if (!(binHz > 0f)) return;
        boolean found = false;
        for (int i = 2; i + 1 < count; i++) {
            float frequency = i * binHz;
            if (frequency < 160f || frequency < 6f * binHz) continue;
            if (frequency > 2200f) break;
            float left = magnitudes[i - 1], peak = magnitudes[i], right = magnitudes[i + 1];
            if (peak < .025f || peak <= left || peak <= right) continue;
            float offset = .5f * (left - right) / (left + right - 2f * peak);
            double note = 69 + SEMITONES_PER_LOG * Math.log((i + offset) * binHz / 440f);
            int pitch = Math.floorMod((int) Math.round(note), 12);
            chroma[pitch] += peak * peak;
            found = true;
        }
        if (found) frames++;
    }

    int letterKey() {
        if (frames < 8) return -1;
        int populated = 0;
        float sum = 0f, max = 0f;
        for (float energy : chroma) {
            sum += energy;
            max = Math.max(max, energy);
        }
        for (float energy : chroma) if (energy > max * .18f) populated++;
        if (populated < 3) return -1;
        float best = -2f, runnerUp = -2f;
        int tonic = 0;
        for (float[] profile : PROFILES) {
            for (int key = 0; key < 12; key++) {
                float score = correlate(profile, key, sum / 12f);
                if (score > best) {
                    runnerUp = best;
                    best = score;
                    tonic = key;
                } else if (score > runnerUp) runnerUp = score;
            }
        }
        return best >= .55f && best - runnerUp >= .035f
                ? GlyphFont.indexOf(TONIC_LETTERS.charAt(tonic)) : -1;
    }

    private float correlate(float[] profile, int tonic, float chromaMean) {
        float profileMean = 0;
        for (float value : profile) profileMean += value / 12f;
        float dot = 0, energy = 0, norm = 0;
        for (int i = 0; i < 12; i++) {
            float a = chroma[(tonic + i) % 12] - chromaMean;
            float b = profile[i] - profileMean;
            dot += a * b;
            energy += a * a;
            norm += b * b;
        }
        return energy > 0 ? dot / (float) Math.sqrt(energy * norm) : 0f;
    }

    void reset() {
        java.util.Arrays.fill(chroma, 0f);
        frames = 0;
    }
}

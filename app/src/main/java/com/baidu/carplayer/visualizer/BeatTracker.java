package com.baidu.carplayer.visualizer;

/**
 * 实时节拍跟踪：从 FFT 帧检测鼓点（低频谱通量起音），由起音间隔估计速度（BPM），
 * 再用锁相方式把连续的“拍位置”对齐到实际鼓点。
 *
 * <p>线程：{@link #onFrame} 在 Visualizer 采集线程调用，{@link #beatPosition}/{@link #confidence}
 * 在 UI 线程调用，因此所有公开方法都同步。时间统一由调用方传入（毫秒），便于单元测试。
 *
 * <p>拍位置 {@code beatPosition(t)} 随时间连续增长，整数点就是节拍。速度变化和相位修正都以
 * “重新锚定”的方式进行，保证位置连续，动画不会跳帧。
 */
public final class BeatTracker {
    static final float MIN_PERIOD_MS = 300;    // 200 BPM
    static final float MAX_PERIOD_MS = 1000;   // 60 BPM
    static final float DEFAULT_PERIOD_MS = 500; // 120 BPM
    private static final int FLUX_BINS = 24;    // 低频为主（鼓、贝斯）
    private static final int FLUX_HISTORY = 24; // 约 1.2 秒的通量历史（20 帧/秒）
    private static final int MAX_ONSETS = 64;
    private static final long ONSET_WINDOW_MS = 12000;
    private static final long MIN_ONSET_GAP_MS = 180;
    private static final long LOST_AFTER_MS = 4000;

    // 起音检测
    private float[] previous = new float[0];
    private final float[] fluxHistory = new float[FLUX_HISTORY];
    private int fluxCount, fluxIndex;
    private float lastFlux;
    private long lastOnsetMs = Long.MIN_VALUE / 2;

    // 起音时间环形缓冲
    private final long[] onsets = new long[MAX_ONSETS];
    private int onsetCount, onsetStart;

    // 速度 / 相位
    private float periodMs = DEFAULT_PERIOD_MS;
    private float candidatePeriod;
    private int candidateVotes;
    private long anchorMs;
    private double anchorBeat;
    private float confidence;
    private boolean started;

    /** 输入一帧 Android FFT 字节数据；返回该帧是否被判定为起音。 */
    public synchronized boolean onFrame(byte[] fft, long nowMs) {
        float[] mags = AudioAnalyzer.fftToMagnitudes(fft, FLUX_BINS);
        if (mags.length == 0) return false;
        return onMagnitudes(mags, nowMs);
    }

    synchronized boolean onMagnitudes(float[] mags, long nowMs) {
        if (!started) { anchorMs = nowMs; anchorBeat = 0; started = true; }
        float flux = 0;
        if (previous.length == mags.length) {
            for (int i = 1; i < mags.length; i++) {
                // 对数压缩让安静段落的鼓点也能被检测到；低频权重更高
                float cur = (float) Math.log1p(mags[i] * 60), prev = (float) Math.log1p(previous[i] * 60);
                float w = i < 8 ? 1.5f : 1f;
                flux += Math.max(0, cur - prev) * w;
            }
        }
        previous = mags.clone();

        float mean = 0, var = 0;
        int n = Math.min(fluxCount, FLUX_HISTORY);
        for (int i = 0; i < n; i++) mean += fluxHistory[i];
        mean = n > 0 ? mean / n : 0;
        for (int i = 0; i < n; i++) { float d = fluxHistory[i] - mean; var += d * d; }
        float std = n > 1 ? (float) Math.sqrt(var / (n - 1)) : 0;
        fluxHistory[fluxIndex] = flux;
        fluxIndex = (fluxIndex + 1) % FLUX_HISTORY;
        fluxCount++;

        boolean onset = n >= 6 && flux > mean + std * 1.2f && flux > .6f && flux >= lastFlux
                && nowMs - lastOnsetMs >= MIN_ONSET_GAP_MS;
        lastFlux = flux;
        if (onset) registerOnset(nowMs);
        decay(nowMs);
        return onset;
    }

    /** 直接登记一次起音（测试或外部检测使用）。 */
    synchronized void registerOnset(long nowMs) {
        if (!started) { anchorMs = nowMs; anchorBeat = 0; started = true; }
        lastOnsetMs = nowMs;
        if (onsetCount == MAX_ONSETS) { onsetStart = (onsetStart + 1) % MAX_ONSETS; onsetCount--; }
        onsets[(onsetStart + onsetCount) % MAX_ONSETS] = nowMs;
        onsetCount++;
        while (onsetCount > 0 && nowMs - onsets[onsetStart] > ONSET_WINDOW_MS) {
            onsetStart = (onsetStart + 1) % MAX_ONSETS; onsetCount--;
        }
        updateTempo(nowMs);
        correctPhase(nowMs);
    }

    private void updateTempo(long nowMs) {
        if (onsetCount < 4) return;
        float best = estimatePeriod();
        if (best <= 0) return;
        float newPeriod;
        if (Math.abs(best - periodMs) / periodMs < .08f) {
            newPeriod = periodMs * .85f + best * .15f;
            candidateVotes = 0;
        } else {
            // 只有新速度明显优于当前速度（≥1.4 倍得分）且连续多次一致时才切换，
            // 防止在 103 ↔ 131 这类相关速度之间来回跳。
            boolean locked = confidence > .25f;
            if (locked && bestScore < scoreOf(periodMs) * 1.6f) { candidateVotes = 0; return; }
            if (candidatePeriod > 0 && Math.abs(best - candidatePeriod) / candidatePeriod < .08f) candidateVotes++;
            else { candidatePeriod = best; candidateVotes = 1; }
            if (candidateVotes < 6 && locked) return;
            newPeriod = best;
            candidateVotes = 0;
        }
        reanchor(nowMs);
        periodMs = newPeriod;
    }

    /** 在 60–200 BPM 范围内为候选周期打分：起音两两间隔越接近周期的整数倍得分越高。 */
    private float bestScore;

    private float estimatePeriod() {
        bestScore = 0;
        float bestPeriod = -1;
        for (float p = MIN_PERIOD_MS; p <= MAX_PERIOD_MS; p += 5) {
            float score = scoreOf(p);
            if (score > bestScore) { bestScore = score; bestPeriod = p; }
        }
        return bestPeriod;
    }

    private float scoreOf(float p) {
        {
            float score = 0;
            for (int i = 0; i < onsetCount; i++) {
                long a = onsets[(onsetStart + i) % MAX_ONSETS];
                for (int j = i + 1; j < onsetCount; j++) {
                    float d = onsets[(onsetStart + j) % MAX_ONSETS] - a;
                    float k = Math.round(d / p);
                    if (k < 1 || k > 4) continue;
                    float err = (d - k * p) / (p * .08f);
                    score += (float) Math.exp(-err * err) / k;
                }
            }
            // 轻度偏好 90–150 BPM，减少把半拍/倍拍当成主拍
            double octave = Math.log(60000.0 / p / 120.0) / Math.log(2);
            score *= (float) Math.exp(-octave * octave / (2 * .5 * .5));
            return score;
        }
    }

    /** 起音落在预测拍附近时把相位拉向它，并累积置信度。 */
    private void correctPhase(long nowMs) {
        double pos = positionAt(nowMs);
        double err = pos - Math.rint(pos);
        if (Math.abs(err) < .3) {
            // 置信度低时修正更激进，以便尽快锁定
            double gain = confidence < .3f ? .6 : .25;
            anchorBeat = pos - err * gain;
            anchorMs = nowMs;
            confidence = Math.min(1, confidence + .12f * (1 - (float) Math.abs(err) * 2));
        } else {
            confidence = Math.max(0, confidence - .06f);
            if (confidence < .15f) {
                // 尚未锁定：直接把这次起音作为拍点
                anchorBeat = Math.rint(pos);
                anchorMs = nowMs;
            }
        }
    }

    private void decay(long nowMs) {
        if (nowMs - lastOnsetMs > LOST_AFTER_MS) confidence = Math.max(0, confidence - .01f);
    }

    private void reanchor(long nowMs) {
        anchorBeat = positionAt(nowMs);
        anchorMs = nowMs;
    }

    private double positionAt(long nowMs) {
        return anchorBeat + (nowMs - anchorMs) / (double) periodMs;
    }

    /** 当前拍位置（整数为拍点），随时间连续增长。 */
    public synchronized double beatPosition(long nowMs) {
        return positionAt(nowMs);
    }

    /** 0..1：拍子是否可信。安静、无明显鼓点或长时间没有起音时趋近 0。 */
    public synchronized float confidence(long nowMs) {
        if (nowMs - lastOnsetMs > LOST_AFTER_MS) {
            return Math.max(0, confidence - (nowMs - lastOnsetMs - LOST_AFTER_MS) / 2000f);
        }
        return confidence;
    }

    public synchronized float periodMs() { return periodMs; }

    public synchronized void reset() {
        previous = new float[0];
        java.util.Arrays.fill(fluxHistory, 0);
        fluxCount = fluxIndex = 0;
        lastFlux = 0;
        lastOnsetMs = Long.MIN_VALUE / 2;
        onsetCount = onsetStart = 0;
        periodMs = DEFAULT_PERIOD_MS;
        candidatePeriod = 0; candidateVotes = 0;
        confidence = 0;
        started = false;
    }
}

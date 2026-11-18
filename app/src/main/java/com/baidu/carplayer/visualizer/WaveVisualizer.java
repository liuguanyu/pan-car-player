package com.baidu.carplayer.visualizer;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * 波形隧道：把同一条对数频谱波形沿 Z 轴叠成一条向画面深处延伸的回廊。
 *
 * <p>三个特征叠在一起：
 * <ol>
 *   <li><b>体积</b>：{@link #LAYERS} 层波形薄片纵向堆叠，越远越小、越淡、越低，中心的消失点让画面有纵深；</li>
 *   <li><b>透视回廊</b>：每层读的是"延迟过"的频谱快照，于是能量峰从最近的层依次向深处退去，
 *       像声音在隧道里向后传播；鼓点会让整条隧道向前推进一次（{@link #tunnelKick}）；</li>
 *   <li><b>粒子流</b>：波形不是画线，而是由粒子串成——波峰处粒子更密、更大、更亮，
 *       静音时粒子回落到中线，只剩一条细平线。</li>
 * </ol>
 *
 * <p>频率映射沿用原来的对数分布（20Hz–20kHz 铺满宽度），所以高频段也跳得起来。
 * 性能：每层用一次 drawPoints 批量绘制，鼓点高亮再叠一次，约 20 次绘制调用。
 */
public final class WaveVisualizer implements AudioVisualizer {
    static final int POINTS = 128;
    /** 波形层数（隧道深度方向的切片数）。 */
    static final int LAYERS = 9;
    /** 每层读延迟多少帧之前的数据：让能量峰向隧道深处传。 */
    private static final int DELAY_FRAMES = 7;
    private static final float REST = .004f;
    /** 对数频率映射的低/高截止（Hz），20Hz–20kHz 铺满整幅宽度。 */
    private static final float FREQ_MIN = 20f;
    private static final float FREQ_MAX = 20000f;
    /** 虚拟焦距：决定远层收缩到什么程度。 */
    private static final float FOCAL = 1.9f;
    /** 远景压缩系数：越大纵深越强（远层缩得越小）。 */
    private static final float DEPTH_SCALE = 5.0f;
    /** 各层沿竖直方向的错位（占高度的比例），让薄片真正叠出体积。 */
    private static final float STACK_STEP = 0.030f;
    /** 隧道循环推进速度（层/秒）。 */
    private static final float TUNNEL_SPEED = 0.30f;

    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] magnitudes = new float[0];
    /** 延迟环形缓冲：每帧的频谱快照，供深处各层回看。 */
    private float[][] history = new float[0][];
    private int historyHead;
    private int width, height;
    /** 每个采样点代表的频率（Hz），由 update 初始化。 */
    private final float[] pointFreq = new float[POINTS];
    private LinearGradient gradient;
    private int gradientWidth = -1;
    /** 隧道推进相位（0..1 循环）与鼓点冲击包络。 */
    private float tunnelPhase;
    private float tunnelKick;
    private long lastFrameMs = -1;
    /** 最近一层的连线路径：让波形本身仍然可读，不至于散成点云。 */
    private final Path nearPath = new Path();
    /** 上一帧的总能量，用于自检测鼓点（决定隧道推进与提亮）。 */
    private float prevEnergy;
    /** drawPoints 复用的坐标缓冲。 */
    /** 每层上下镜像，每个采样点写 2 个点（4 个 float）。 */
    private final float[] buf = new float[POINTS * 4];
    private final float[] bufPeak = new float[POINTS * 4];

    public WaveVisualizer() {
        dotPaint.setStyle(Paint.Style.FILL);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override public String getName() { return "wave"; }

    @Override public void update(byte[] fft, int width, int height) {
        this.width = width;
        this.height = height;
        // 取全部 512 个 bin（0–24kHz），再按对数位置重采样出 128 个点，
        // 让 20Hz–20kHz 铺满整幅宽度：低频点少 bin（响应快），高频点多 bin（稳）。
        float[] raw = AudioAnalyzer.fftToMagnitudes(fft, 512);
        if (magnitudes.length != POINTS) {
            magnitudes = new float[POINTS];
            history = new float[DELAY_FRAMES][POINTS];
            float logMin = (float) Math.log(FREQ_MIN);
            float logMax = (float) Math.log(FREQ_MAX);
            for (int i = 0; i < POINTS; i++) {
                float t = i / (float) (POINTS - 1);
                pointFreq[i] = (float) Math.exp(logMin + t * (logMax - logMin));
            }
        }
        float binWidth = 48000f / 1024f; // bin 0 = 0Hz，bin i ≈ i*binWidth Hz
        for (int i = 0; i < POINTS; i++) {
            // 每个对数点取 ±半个倍频程内最邻近的 bin（至少 1 个，至多约 5 个）
            float f = pointFreq[i];
            int lo = Math.max(0, (int) ((f - binWidth * 2) / binWidth));
            int hi = Math.min(raw.length - 1, (int) ((f + binWidth * 2) / binWidth) + 1);
            float sum = 0f;
            int count = 0;
            for (int b = lo; b <= hi; b++) {
                sum += raw[b];
                count++;
            }
            float target = count > 0 ? (float) Math.sqrt(sum / count) : 0f;
            magnitudes[i] += (target - magnitudes[i]) * (target > magnitudes[i] ? .5f : .18f);
        }
        // 记录本帧快照（环形缓冲），深处各层按延迟回看
        historyHead = (historyHead + 1) % DELAY_FRAMES;
        System.arraycopy(magnitudes, 0, history[historyHead], 0, POINTS);

        // 自检测鼓点：总能量明显跃升时推动整条隧道（不依赖节拍跟踪器，模式可单独测试）
        float energy = 0f;
        for (float m : magnitudes) energy += m;
        if (energy > prevEnergy + 0.9f && energy > 1.4f) onBeat(energy - prevEnergy);
        prevEnergy = energy;
    }

    /** 鼓点：整条隧道向前推进一次并整体提亮。 */
    public void onBeat(float strength) {
        tunnelKick = Math.min(1.2f, tunnelKick + 0.45f + Math.max(0f, strength) * 0.4f);
    }

    @Override public void draw(Canvas canvas) {
        if (magnitudes.length == 0 || width <= 0 || height <= 0) return;
        long now = System.nanoTime() / 1_000_000L;
        float dt = lastFrameMs < 0 ? 16f : Math.min(60f, now - lastFrameMs);
        lastFrameMs = now;
        float dts = dt / 1000f;
        tunnelPhase = (tunnelPhase + dts * (TUNNEL_SPEED + tunnelKick * 1.6f)) % 1f;
        tunnelKick *= (float) Math.pow(0.92f, Math.max(0.5f, dt / 16f));
        ensureGradient();

        float center = height / 2f;
        float amplitude = height * 0.42f;
        float cx = width * 0.5f;

        // 由远到近绘制：远层先画，近层后画盖在上面
        for (int l = 0; l < LAYERS; l++) {
            // z: 0 = 最远，1 = 最近；叠加隧道相位让层持续向前滚动
            float z = (l + tunnelPhase) / LAYERS;
            float depth = 1f - z;                            // 0 近，1 远
            // 归一化：最近的一层正好 s=1（铺满宽度与高度），最远层缩到最小
            float depthN = Math.min(1f, depth / 0.94f);
            float s = FOCAL / (FOCAL + depthN * DEPTH_SCALE);  // 透视收缩
            float alphaScale = (float) Math.pow(1f - depthN, 1.15f);
            if (alphaScale < 0.03f) continue;
            // 纵向错位 × 透视：远层的错位被压向消失点，近层向上下散开 → 卡片式堆叠的纵深感
            float stackY = (l - (LAYERS - 1) / 2f) * height * STACK_STEP * (0.35f + 0.65f * s);

            float[] src = history[(historyHead - (int) (depth * DELAY_FRAMES) + DELAY_FRAMES * 2)
                    % DELAY_FRAMES];
            // 横向也向消失点收拢；纵向层间距让薄片叠出体积
            float spread = 0.16f + 0.84f * s;
            float layerCenter = center + stackY;
            float layerAmp = amplitude * (float) Math.pow(s, 1.35f);
            // 鼓点让近层额外外扩一下
            layerAmp *= 1f + 0.22f * tunnelKick * (1f - depthN);

            int n = 0;
            int np = 0;
            for (int i = 0; i < POINTS; i++) {
                float t = i / (float) (POINTS - 1);
                float x = cx + (t * width - cx) * spread;
                float m = src[i];
                float dy = m * layerAmp;
                // 上下镜像成一条带：静音时收拢成中线上的平直细带
                buf[n++] = x; buf[n++] = layerCenter - dy;
                buf[n++] = x; buf[n++] = layerCenter + dy;
                // 波峰单独一批：更大更亮的粒子
                if (m > 0.30f) {
                    bufPeak[np++] = x; bufPeak[np++] = layerCenter - dy;
                    bufPeak[np++] = x; bufPeak[np++] = layerCenter + dy;
                }
            }

            // 基础粒子串：波峰处叠第二层高亮粒子
            // 颜色随深度过渡：远处冷蓝，越近越接近青→紫→粉
            dotPaint.setShader(null);
            dotPaint.setColor(tint(1f - depthN));
            dotPaint.setAlpha((int) (52 * alphaScale));
            dotPaint.setStrokeWidth(Math.max(1f, 1.1f * s * (height / 240f)));
            canvas.drawPoints(buf, 0, n, dotPaint);

            // 最近一层的轮廓用折线补一笔，让波形本身仍然可读
            if (l == LAYERS - 1) {
                nearPath.reset();
                for (int i = 0; i < POINTS; i++) {
                    float m = src[i];
                    float t = i / (float) (POINTS - 1);
                    float x = cx + (t * width - cx) * spread;
                    float yTop = layerCenter - m * layerAmp;
                    float yBottom = layerCenter + m * layerAmp;
                    if (i == 0) nearPath.moveTo(x, yTop); else nearPath.lineTo(x, yTop);
                    if (i == 0) nearPath.moveTo(x, yBottom); else nearPath.lineTo(x, yBottom);
                }
                linePaint.setShader(gradient);
                linePaint.setAlpha((int) (150 * alphaScale));
                linePaint.setStrokeWidth(Math.max(1.2f, 1.8f * (height / 240f)));
                canvas.drawPath(nearPath, linePaint);
            }

            if (np > 0) {
                dotPaint.setShader(l == LAYERS - 1 ? gradient : null);
                dotPaint.setColor(tint(1f - depthN));
                dotPaint.setAlpha((int) (210 * alphaScale));
                dotPaint.setStrokeWidth(Math.max(1.4f, 2.6f * s * (height / 240f)));
                canvas.drawPoints(bufPeak, 0, np, dotPaint);
            }

            // 最近的一层叠白色粒子，形成最亮的"波前"
            if (l == LAYERS - 1) {
                dotPaint.setShader(null);
                dotPaint.setColor(0xffffffff);
                dotPaint.setAlpha((int) (170 * alphaScale));
                dotPaint.setStrokeWidth(Math.max(1.6f, 3.0f * (height / 240f)));
                canvas.drawPoints(buf, 0, n, dotPaint);
            }
            dotPaint.setShader(null);
            linePaint.setShader(null);
        }
    }

    /** 深度 0=远（冷蓝）→ 1=近（青/紫/粉混合的亮色）。 */
    private static int tint(float near) {
        float t = Math.max(0f, Math.min(1f, near));
        int r = (int) (0x2a + (0xc8 - 0x2a) * t);
        int g = (int) (0x5e + (0xa6 - 0x5e) * t);
        int b = (int) (0xa8 + (0xff - 0xa8) * t);
        return (0xff << 24) | (r << 16) | (g << 8) | b;
    }

    private void ensureGradient() {
        if (gradientWidth == width) return;
        gradient = new LinearGradient(0, 0, width, 0,
                new int[]{0xff00e5ff, 0xffb388ff, 0xffff80c8},
                new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP);
        gradientWidth = width;
    }

    @Override public void reset() {
        magnitudes = new float[0];
        history = new float[0][];
        historyHead = 0;
        tunnelPhase = 0f;
        tunnelKick = 0f;
        prevEnergy = 0f;
        lastFrameMs = -1;
    }

    @Override public boolean isAtRest() {
        for (float m : magnitudes) if (m > REST) return false;
        return tunnelKick < 0.01f;
    }
}

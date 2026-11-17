package com.baidu.carplayer.visualizer;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * 镜面频谱波形：对数频率铺满宽度，高频也能跳动。
 * 三层绘制——外圈辉光、主线条（青→紫→粉横向渐变）、上下半透明填充。
 */
public final class WaveVisualizer implements AudioVisualizer {
    static final int POINTS = 128;
    private static final float REST = .004f;
    /** 对数频率映射的低/高截止（Hz），20Hz–20kHz 铺满整幅宽度。 */
    private static final float FREQ_MIN = 20f;
    private static final float FREQ_MAX = 20000f;

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path fillPath = new Path();
    private float[] magnitudes = new float[0];
    private int width, height;
    /** 每个采样点对应的横坐标（对数分布）。 */
    private float[] xSteps = new float[0];
    /** 每个采样点代表的频率（Hz），由 update 初始化。 */
    private final float[] pointFreq = new float[POINTS];
    private LinearGradient lineGradient;
    private int gradientWidth = -1;

    public WaveVisualizer() {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    @Override public String getName() { return "wave"; }

    @Override public void update(byte[] fft, int width, int height) {
        this.width = width; this.height = height;
        // 取全部 512 个 bin（0–24kHz），再按对数位置重采样出 128 个点，
        // 让 20Hz–20kHz 铺满整幅宽度：低频点少 bin（响应快），高频点多 bin（稳）。
        float[] raw = AudioAnalyzer.fftToMagnitudes(fft, 512);
        if (raw.length == 0) raw = new float[0];
        if (magnitudes.length != POINTS) {
            magnitudes = new float[POINTS];
            xSteps = new float[POINTS];
            float logMin = (float) Math.log(FREQ_MIN);
            float logMax = (float) Math.log(FREQ_MAX);
            for (int i = 0; i < POINTS; i++) {
                float t = i / (float) (POINTS - 1);
                xSteps[i] = t * width;
                pointFreq[i] = (float) Math.exp(logMin + t * (logMax - logMin));
            }
        }
        // 无数据（暂停/停止）时以 0 为目标衰减，保持平滑过渡
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
    }

    @Override public void draw(Canvas canvas) {
        if (magnitudes.length == 0 || width <= 0 || height <= 0) return;
        ensureGradient();
        float center = height / 2f;
        float amplitude = height * .45f;

        // 轮廓路径：上边界 + 镜像下边界
        path.reset();
        path.moveTo(0, center);
        for (int i = 0; i < POINTS; i++) {
            path.lineTo(xSteps[i], center - magnitudes[i] * amplitude);
        }
        for (int i = POINTS - 1; i >= 0; i--) {
            path.lineTo(xSteps[i], center + magnitudes[i] * amplitude);
        }
        path.close();

        // 填充：上边界 + 沿中线回勾
        fillPath.reset();
        fillPath.moveTo(0, center);
        for (int i = 0; i < POINTS; i++) {
            fillPath.lineTo(xSteps[i], center - magnitudes[i] * amplitude);
        }
        fillPath.lineTo(width, center);
        fillPath.close();

        // 1) 外层辉光：更宽的半透明描边，模拟 bloom
        glowPaint.setAlpha(40);
        glowPaint.setStrokeWidth(14);
        canvas.drawPath(path, glowPaint);
        // 2) 填充
        fillPaint.setAlpha(36);
        fillPaint.setShader(lineGradient);
        canvas.drawPath(fillPath, fillPaint);
        // 3) 主线条
        strokePaint.setAlpha(255);
        strokePaint.setStrokeWidth(3);
        strokePaint.setShader(lineGradient);
        canvas.drawPath(path, strokePaint);

        strokePaint.setShader(null);
        fillPaint.setShader(null);
    }

    private void ensureGradient() {
        if (gradientWidth == width) return;
        lineGradient = new LinearGradient(0, 0, width, 0,
                new int[]{0xff00e5ff, 0xffb388ff, 0xffff80c8},
                new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP);
        gradientWidth = width;
    }

    @Override public void reset() { magnitudes = new float[0]; }

    @Override public boolean isAtRest() {
        for (float m : magnitudes) if (m > REST) return false;
        return true;
    }
}

package com.baidu.carplayer.visualizer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * "光轴环群"粒子效果（黑白电影感，参考 YouTube CQtOFtSBA3I）。
 *
 * 场景是一个真正的 3D 结构：一条贯穿画面的粗白光轴，几十个粒子圆环像铜钱一样
 * 串在轴上（环面垂直于轴）。相机从侧面看，随时间缓慢俯仰、轴的屏幕角度缓慢摆动，
 * 于是同一批圆环在椭圆 ↔ 近圆之间持续变形，近大远小。
 *
 * 音频联动：
 *  - 环沿轴向前流动；每个环在入口处"出生"时冻结当时的响度与 24 段频谱快照——
 *    环半径随出生时的响度、环周形状随频谱，整串环就是一条沿轴传播的"频谱隧道"；
 *  - 鼓点让所有环瞬间向外一胀、流速一冲、光轴端点闪光；
 *  - 流速随运动能量（响度 × BPM）。
 *
 * 性能：每帧 ~3000 个粒子做 3D 变换 + 透视投影，按景深分 4 档用 drawPoints 批量绘制，
 * 不逐粒子创建对象。
 */
public final class ParticlesVisualizer implements AudioVisualizer {
    private static final int RING_COUNT = 16;
    private static final int DOTS = 110;
    private static final int BANDS = 24;
    private static final int DEPTH_BUCKETS = 4;
    private static final PorterDuffXfermode ADD_MODE = new PorterDuffXfermode(PorterDuff.Mode.ADD);

    /** 节拍来源：由 VisualizerManager 注入共享节拍跟踪器，为空按 120 BPM。 */
    interface TempoSource { float periodMs(); }
    private TempoSource tempoSource;

    private final Random random = new Random();
    private final AudioAnalyzer analyzer = new AudioAnalyzer();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path axisPath = new Path();
    private final RectF rect = new RectF();
    private Bitmap glowSprite;

    // 预计算的圆周三角函数与每粒子固定抖动
    private final float[] cosK = new float[DOTS];
    private final float[] sinK = new float[DOTS];
    private final int[] bandOfK = new int[DOTS];
    private final float[][] jitter = new float[RING_COUNT][DOTS];
    private final float[] spin = new float[RING_COUNT];
    private final float[] ringChar = new float[RING_COUNT];

    // 每个环出生时冻结的状态
    private final float[] ringSig = new float[RING_COUNT];
    private final float[][] ringSnap = new float[RING_COUNT][BANDS];
    private final float[] prevPhase = new float[RING_COUNT];

    // 每帧复用的点缓冲（按景深分档）
    private final float[][] pts = new float[DEPTH_BUCKETS][DOTS * 2];
    private final int[] ptCount = new int[DEPTH_BUCKETS];

    // 音频状态
    private final float[] spectrum = new float[BANDS];
    private int width, height;
    private float bass, mid, volume, level;
    private float motionEnergy;
    private float energy;      // 整体可见度：停止后衰减到 0
    private float pulse;       // 鼓点膨胀包络
    private float kick;        // 鼓点流速冲击
    private float flow;        // 环沿轴流动相位
    private float time;        // 秒
    private long lastFrameMs = -1;
    private boolean seeded;

    // 投影结果（避免分配）
    private float projX, projY, projS;

    @Override public String getName() { return "particles"; }

    /** 注入共享节拍跟踪器（估算 BPM 用）。 */
    public void setTempoSource(TempoSource source) { this.tempoSource = source; }

    public ParticlesVisualizer() {
        for (int k = 0; k < DOTS; k++) {
            double a = k * Math.PI * 2 / DOTS;
            cosK[k] = (float) Math.cos(a);
            sinK[k] = (float) Math.sin(a);
            // 环周两半镜像：角度映射频段（0°/180° 对应低频，90°/270° 对应高频）
            float t = Math.abs(sinK[k]);
            bandOfK[k] = Math.min(BANDS - 1, (int) (t * BANDS));
        }
        pointPaint.setStrokeCap(Paint.Cap.ROUND);
        pointPaint.setStyle(Paint.Style.STROKE);
    }

    @Override public void update(byte[] fft, int width, int height) {
        this.width = width;
        this.height = height;
        AudioAnalyzer.Analysis analysis = analyzer.analyze(fft);
        boolean hasData = fft.length > 0;
        bass += ((hasData ? analysis.bass : 0f) - bass) * (analysis.bass > bass ? .45f : .18f);
        mid += ((hasData ? analysis.mid : 0f) - mid) * (analysis.mid > mid ? .45f : .18f);
        volume += ((hasData ? analysis.volume : 0f) - volume) * (analysis.volume > volume ? .4f : .12f);
        float lvTarget = Math.max(bass * 0.95f, Math.max(mid * 0.85f, volume));
        level += (lvTarget - level) * (lvTarget > level ? .5f : .1f);

        float[] raw = AudioAnalyzer.fftToMagnitudes(fft, BANDS * 2);
        for (int i = 0; i < BANDS; i++) {
            float a = i * 2 < raw.length ? raw[i * 2] : 0f;
            float b = i * 2 + 1 < raw.length ? raw[i * 2 + 1] : 0f;
            float target = (float) Math.sqrt((a + b) * 0.5f);
            spectrum[i] += (target - spectrum[i]) * (target > spectrum[i] ? .5f : .12f);
        }

        float period = tempoSource != null ? tempoSource.periodMs() : 500f;
        float bpm = 60000f / Math.max(300f, Math.min(1000f, period));
        float tempoNorm = Math.max(0f, Math.min(1f, (bpm - 60f) / 140f));
        motionEnergy = (0.35f + volume * 1.5f) * (0.55f + tempoNorm * 0.9f);

        boolean beat = hasData
                && (analysis.peak || (bass > .45f && bass - analysis.bass < 0 && mid > .12f));
        if (beat) {
            pulse = Math.min(1.4f, pulse + .55f + bass * .5f);
            kick = Math.min(0.25f, kick + 0.1f);
        }
    }

    @Override public void draw(Canvas canvas) {
        if (width <= 0 || height <= 0) return;
        long now = System.nanoTime() / 1_000_000L;
        float dt = lastFrameMs < 0 ? 16f : Math.min(50f, now - lastFrameMs);
        lastFrameMs = now;
        float dts = dt / 1000f;
        float dtf = Math.max(0.5f, dt / 16f);
        time += dts;

        if (glowSprite == null) glowSprite = softCircle(192);
        if (!seeded) seed();

        // 近纯黑背景
        paint.setXfermode(null);
        paint.setShader(null);
        canvas.drawColor(Color.argb(0xE6, 2, 3, 6), PorterDuff.Mode.SRC_OVER);

        // 可见度：有信号时常亮，停止后衰减到 0（静止态契约）；脉冲/冲击按帧衰减
        boolean hasSignal = bass > 0.001f || mid > 0.001f;
        energy += ((hasSignal ? 1f : 0f) - energy) * (hasSignal ? .3f : .05f);
        pulse *= (float) Math.pow(0.93f, dtf);
        kick *= (float) Math.pow(0.95f, dtf);
        if (energy < 0.004f) return;

        // 沿轴流动：基础速度 + 运动能量 + 鼓点冲击
        flow += dts * (0.03f + motionEnergy * 0.045f + kick);

        float scale = Math.max(1f, height / 190f);
        float pxUnit = 0.55f * height;
        float zMax = 0.73f * width / pxUnit;
        float camDist = 1.6f * zMax + 3f;
        float focal = pxUnit * camDist;

        // 相机：俯仰（椭圆 ↔ 近圆）与光轴屏幕角度缓慢摆动
        float tilt = 0.62f + 0.3f * (float) Math.sin(time * 0.11f);
        float cosT = (float) Math.cos(tilt), sinT = (float) Math.sin(tilt);
        float axisAng = 0.17f + 0.14f * (float) Math.sin(time * 0.07f + 0.6f);
        float roll = (float) Math.PI / 2f - axisAng;
        float cr = (float) Math.cos(roll), sr = (float) Math.sin(roll);
        float cx = width * 0.5f, cy = height * 0.5f;

        drawRings(canvas, zMax, camDist, focal, cosT, sinT, cr, sr, cx, cy, scale);
        drawAxis(canvas, zMax * 1.28f, camDist, focal, cosT, sinT, cr, sr, cx, cy, scale);
    }

    // ---------- 环群 ----------

    private void drawRings(Canvas canvas, float zMax, float camDist, float focal,
                           float cosT, float sinT, float cr, float sr,
                           float cx, float cy, float scale) {
        float[] widths = {1.6f * scale, 2.4f * scale, 3.4f * scale, 4.6f * scale};
        int[] alphas = {135, 195, 240, 255};

        for (int i = 0; i < RING_COUNT; i++) {
            float phase = flow + i / (float) RING_COUNT;
            phase -= (float) Math.floor(phase);
            // 环从入口（phase 回绕）出生：冻结当时的响度与频谱
            if (phase < prevPhase[i]) born(i);
            prevPhase[i] = phase;

            float z = (phase * 2f - 1f) * zMax;
            float edge = 1f - Math.abs(phase * 2f - 1f);        // 两端为 0
            float env = (float) Math.pow(Math.max(0f, edge), 0.55f);
            float ringAlpha = env * energy * (0.6f + 0.4f * Math.min(1f, ringSig[i]));
            if (ringAlpha < 0.02f) continue;

            // 半径：基础波浪 × 出生时响度 × 鼓点膨胀
            float personality = 0.42f + 1.1f * ringChar[i];
            float wave = 0.7f + 0.55f * (float) Math.sin(phase * 4.4f + time * 0.45f + i * 0.9f);
            float radius = personality * wave * (0.55f + 0.7f * ringSig[i]) * (1f + 0.28f * pulse);

            float spinNow = time * (i % 2 == 0 ? 0.28f : -0.2f) + spin[i];
            float cs = (float) Math.cos(spinNow), sn = (float) Math.sin(spinNow);
            float[] snap = ringSnap[i];
            float[] jit = jitter[i];
            for (int b = 0; b < DEPTH_BUCKETS; b++) ptCount[b] = 0;

            for (int k = 0; k < DOTS; k++) {
                float wob = 1f + 0.2f * snap[bandOfK[k]] + jit[k];
                float rr = radius * wob;
                float x = rr * (cosK[k] * cs - sinK[k] * sn);
                float y = rr * (sinK[k] * cs + cosK[k] * sn);
                // 绕 x 轴俯仰
                float y1 = y * cosT - z * sinT;
                float z1 = y * sinT + z * cosT;
                float d = camDist + z1;
                if (d < 0.6f) continue;
                float s = focal / d;
                float sx = x * s, sy = y1 * s;
                float px = cx + sx * cr - sy * sr;
                float py = cy + sx * sr + sy * cr;
                if (px < -20f || px > width + 20f || py < -20f || py > height + 20f) continue;
                float rel = camDist / d;
                int bucket = rel < 0.85f ? 0 : rel < 1.1f ? 1 : rel < 1.45f ? 2 : 3;
                int n = ptCount[bucket];
                pts[bucket][n++] = px;
                pts[bucket][n++] = py;
                ptCount[bucket] = n;
            }
            for (int b = 0; b < DEPTH_BUCKETS; b++) {
                if (ptCount[b] == 0) continue;
                pointPaint.setStrokeWidth(widths[b]);
                pointPaint.setColor(Color.argb(Math.min(255, (int) (alphas[b] * ringAlpha)), 255, 255, 255));
                canvas.drawPoints(pts[b], 0, ptCount[b], pointPaint);
            }
        }
    }

    /** 环出生：冻结当前响度与 24 段频谱快照。 */
    private void born(int i) {
        ringSig[i] = Math.max(0.15f, Math.min(1.6f, 0.2f + 1.35f * level));
        System.arraycopy(spectrum, 0, ringSnap[i], 0, BANDS);
        spin[i] = random.nextFloat() * 6.2832f;
        ringChar[i] = random.nextFloat();
        for (int k = 0; k < DOTS; k++) jitter[i][k] = (random.nextFloat() - 0.5f) * 0.07f;
    }

    private void seed() {
        seeded = true;
        for (int i = 0; i < RING_COUNT; i++) {
            ringSig[i] = 0.35f + random.nextFloat() * 0.45f;
            ringChar[i] = random.nextFloat();
            spin[i] = random.nextFloat() * 6.2832f;
            prevPhase[i] = 0f;
            for (int b = 0; b < BANDS; b++) ringSnap[i][b] = random.nextFloat() * 0.35f;
            for (int k = 0; k < DOTS; k++) jitter[i][k] = (random.nextFloat() - 0.5f) * 0.07f;
        }
    }

    // ---------- 光轴 ----------

    /** 光轴：一条贯穿画面的粗白线，远细近粗；远端有一团闪光。 */
    private void drawAxis(Canvas canvas, float zAxis, float camDist, float focal,
                          float cosT, float sinT, float cr, float sr,
                          float cx, float cy, float scale) {
        project(0f, 0f, zAxis, camDist, focal, cosT, sinT, cr, sr, cx, cy);
        float fx = projX, fy = projY, fs = projS;
        project(0f, 0f, -zAxis, camDist, focal, cosT, sinT, cr, sr, cx, cy);
        float nx = projX, ny = projY, ns = projS;

        float dx = fx - nx, dy = fy - ny;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return;
        float ux = -dy / len, uy = dx / len; // 法向
        float base = 1.5f * scale * (0.8f + level * 0.6f);
        float hwFar = base * fs / (focal / camDist);
        float hwNear = base * ns / (focal / camDist);

        // 外层柔光
        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeCap(Paint.Cap.ROUND);
        axisPaint.setColor(Color.argb((int) (46 * energy), 255, 255, 255));
        axisPaint.setStrokeWidth(Math.max(hwFar, hwNear) * 6f);
        canvas.drawLine(nx, ny, fx, fy, axisPaint);

        // 渐粗的主体
        axisPath.reset();
        axisPath.moveTo(fx + ux * hwFar, fy + uy * hwFar);
        axisPath.lineTo(nx + ux * hwNear, ny + uy * hwNear);
        axisPath.lineTo(nx - ux * hwNear, ny - uy * hwNear);
        axisPath.lineTo(fx - ux * hwFar, fy - uy * hwFar);
        axisPath.close();
        axisPaint.setStyle(Paint.Style.FILL);
        axisPaint.setColor(Color.argb((int) (255 * energy), 255, 255, 255));
        canvas.drawPath(axisPath, axisPaint);

        // 远端闪光：随响度与鼓点
        float glow = Math.min(1f, pulse) * 0.7f + level * 0.3f;
        float r = (12f + 30f * glow) * scale;
        paint.setXfermode(ADD_MODE);
        rect.set(fx - r, fy - r, fx + r, fy + r);
        paint.setAlpha((int) (Math.min(255, 110 + glow * 145) * energy));
        canvas.drawBitmap(glowSprite, null, rect, paint);
        paint.setXfermode(null);
    }

    private void project(float x, float y, float z, float camDist, float focal,
                         float cosT, float sinT, float cr, float sr, float cx, float cy) {
        float y1 = y * cosT - z * sinT;
        float z1 = y * sinT + z * cosT;
        float d = Math.max(0.6f, camDist + z1);
        float s = focal / d;
        float sx = x * s, sy = y1 * s;
        projX = cx + sx * cr - sy * sr;
        projY = cy + sx * sr + sy * cr;
        projS = s;
    }

    private static Bitmap softCircle(int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(size / 2f, size / 2f, size / 2f,
                new int[]{0xFFFFFFFF, 0x8CFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.32f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        return bmp;
    }

    @Override public void reset() {
        bass = 0;
        mid = 0;
        volume = 0;
        level = 0;
        motionEnergy = 0;
        energy = 0;
        pulse = 0;
        kick = 0;
        flow = 0;
        time = 0;
        lastFrameMs = -1;
        seeded = false;
        analyzer.reset();
        java.util.Arrays.fill(spectrum, 0f);
    }

    @Override public boolean isAtRest() {
        return energy < 0.004f && pulse < 0.01f && bass < .004f && mid < .004f;
    }
}

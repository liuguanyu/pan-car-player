package com.baidu.carplayer.visualizer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

/**
 * 粒子可视化：保持"唱片背景粒子"的质感——青/天蓝/紫/粉四色实心圆、直线漂移、留白，
 * 在此基础上只加三件事让它活在音乐上：
 *  1) 每颗粒子绑定一个频段，该频段有能量时粒子变大变亮（呼吸）；
 *  2) 漂移速度随运动能量（响度 × BPM）连续变化；
 *  3) 鼓点时画面中心白炽爆发，向外喷射同色粒子。
 *
 * 刻意不用加法混合、噪声场、力场等复杂机制——克制才有质感。
 */
public final class ParticlesVisualizer implements AudioVisualizer {
    /** 漂移粒子：每颗绑定一个频段。 */
    private static final class Dot {
        float x, y, vx, vy, radius, alpha, alphaSpeed;
        int color, band;
    }

    /** 鼓点喷射粒子，短命。 */
    private static final class Jet {
        float x, y, vx, vy, life, maxLife, radius;
        int color;
    }

    private static final int[] COLORS = {
            0xFF00FFFF, // 青
            0xFF00BFFF, // 深天蓝
            0xFF9370DB, // 紫
            0xFFFF69B4  // 粉
    };
    private static final int DOT_COUNT = 40;
    private static final int BANDS = 24;
    private static final int MAX_JETS = 90;
    private static final PorterDuffXfermode ADD_MODE = new PorterDuffXfermode(PorterDuff.Mode.ADD);

    /** 节拍来源：由 VisualizerManager 注入共享节拍跟踪器，为空按 120 BPM。 */
    interface TempoSource { float periodMs(); }

    private final Dot[] dots = new Dot[DOT_COUNT];
    private final ArrayList<Jet> jets = new ArrayList<>();
    private final Random random = new Random();
    private final AudioAnalyzer analyzer = new AudioAnalyzer();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] spectrum = new float[BANDS];
    private Bitmap coreSprite;
    private TempoSource tempoSource;

    private int width, height;
    private boolean placed;
    private float bass, mid, volume;
    private float motionEnergy;
    private float energy;     // 整体可见度：播放时升起，停止后衰减到 0
    private float flare;      // 中心爆发包络
    private float speedMul = 1f;
    private long lastFrameMs = -1;

    public ParticlesVisualizer() {
        for (int i = 0; i < DOT_COUNT; i++) dots[i] = new Dot();
    }

    @Override public String getName() { return "particles"; }

    /** 注入共享节拍跟踪器（估算 BPM 用）。 */
    public void setTempoSource(TempoSource source) { this.tempoSource = source; }

    @Override public void update(byte[] fft, int width, int height) {
        if (this.width != width || this.height != height) placed = false;
        this.width = width;
        this.height = height;

        AudioAnalyzer.Analysis analysis = analyzer.analyze(fft);
        boolean hasData = fft.length > 0;
        bass += ((hasData ? analysis.bass : 0f) - bass) * (analysis.bass > bass ? .45f : .18f);
        mid += ((hasData ? analysis.mid : 0f) - mid) * (analysis.mid > mid ? .45f : .18f);
        volume += ((hasData ? analysis.volume : 0f) - volume) * (analysis.volume > volume ? .4f : .12f);

        // 24 段频谱（攻击快、衰减慢），粒子按所绑频段呼吸
        float[] raw = AudioAnalyzer.fftToMagnitudes(fft, BANDS * 2);
        for (int i = 0; i < BANDS; i++) {
            float a = i * 2 < raw.length ? raw[i * 2] : 0f;
            float b = i * 2 + 1 < raw.length ? raw[i * 2 + 1] : 0f;
            float target = (float) Math.sqrt((a + b) * 0.5f);
            spectrum[i] += (target - spectrum[i]) * (target > spectrum[i] ? .5f : .12f);
        }

        // 运动能量 = 响度 × 速度：快歌响时漂得快，慢歌轻时漂得慢
        float period = tempoSource != null ? tempoSource.periodMs() : 500f;
        float bpm = 60000f / Math.max(300f, Math.min(1000f, period));
        float tempoNorm = Math.max(0f, Math.min(1f, (bpm - 60f) / 140f));
        motionEnergy = (0.35f + volume * 1.5f) * (0.55f + tempoNorm * 0.9f);

        boolean beat = hasData
                && (analysis.peak || (bass > .45f && bass - analysis.bass < 0 && mid > .12f));
        if (beat) {
            flare = Math.min(1.5f, flare + .6f + bass * .6f);
            spawnJets();
        }
    }

    @Override public void draw(Canvas canvas) {
        if (width <= 0 || height <= 0) return;
        long now = System.nanoTime() / 1_000_000L;
        float dtf = lastFrameMs < 0 ? 1f : Math.max(0.5f, Math.min(2.5f, (now - lastFrameMs) / 16f));
        lastFrameMs = now;
        float scale = Math.max(1f, Math.min(width, height) / 360f);
        if (!placed) place();

        // 可见度包络：有信号时保底可见，停止后平滑衰减归零（满足静止态契约）
        boolean hasSignal = bass > 0.001f || mid > 0.001f || !jets.isEmpty();
        float energyTarget = hasSignal ? 1f : 0f;
        energy += (energyTarget - energy) * (energyTarget > energy ? .25f : .05f);
        flare *= (float) Math.pow(0.945, dtf);
        speedMul += ((0.7f + motionEnergy * 1.1f) - speedMul) * 0.05f;

        paint.setXfermode(null);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        for (Dot d : dots) {
            drift(d, dtf);
            float spec = Math.min(1f, spectrum[d.band] * 1.6f);
            // 闪烁（保持原有质感）+ 频段呼吸：能量高时更大更亮
            d.alpha += d.alphaSpeed * dtf;
            if (d.alpha > 150 || d.alpha < 40) d.alphaSpeed = -d.alphaSpeed;
            float r = d.radius * scale * (1f + spec * 0.9f);
            int a = (int) (Math.min(255f, d.alpha + spec * 90f) * energy);
            if (a <= 2) continue;
            paint.setColor(d.color);
            if (spec > 0.45f) {
                // 高能量粒子外加一圈淡晕（仍是实心圆风格）
                paint.setAlpha(a / 4);
                canvas.drawCircle(d.x, d.y, r * 1.9f, paint);
            }
            paint.setAlpha(a);
            canvas.drawCircle(d.x, d.y, r, paint);
        }

        drawJets(canvas, dtf, scale);
        drawCore(canvas, scale);
    }

    // ---------- 内部 ----------

    private void place() {
        placed = true;
        for (Dot d : dots) {
            d.x = random.nextFloat() * width;
            d.y = random.nextFloat() * height;
            float angle = random.nextFloat() * (float) Math.PI * 2;
            float speed = 0.5f + random.nextFloat() * 0.5f;
            d.vx = (float) Math.cos(angle) * speed;
            d.vy = (float) Math.sin(angle) * speed;
            d.radius = 2f + random.nextFloat() * 4f;
            d.alpha = 40 + random.nextInt(100);
            d.alphaSpeed = 0.5f + random.nextFloat() * 1.0f;
            d.color = COLORS[random.nextInt(COLORS.length)];
            d.band = random.nextInt(BANDS);
        }
    }

    private void drift(Dot d, float dtf) {
        d.x += d.vx * speedMul * dtf;
        d.y += d.vy * speedMul * dtf;
        float m = d.radius * 3f;
        if (d.x < -m) d.x = width + m;
        if (d.x > width + m) d.x = -m;
        if (d.y < -m) d.y = height + m;
        if (d.y > height + m) d.y = -m;
    }

    private void spawnJets() {
        float scale = Math.max(1f, Math.min(width, height) / 360f);
        int count = (int) (14 + bass * 18);
        for (int i = 0; i < count && jets.size() < MAX_JETS; i++) {
            Jet j = new Jet();
            double angle = random.nextDouble() * Math.PI * 2;
            float speed = (2f + bass * 5f) * (0.4f + random.nextFloat()) * scale;
            j.x = width / 2f;
            j.y = height / 2f;
            j.vx = (float) Math.cos(angle) * speed;
            j.vy = (float) Math.sin(angle) * speed;
            j.life = j.maxLife = 20f + random.nextFloat() * 22f;
            j.radius = (1.6f + random.nextFloat() * 2.6f) * scale;
            j.color = COLORS[random.nextInt(COLORS.length)];
            jets.add(j);
        }
    }

    private void drawJets(Canvas canvas, float dtf, float scale) {
        Iterator<Jet> it = jets.iterator();
        while (it.hasNext()) {
            Jet j = it.next();
            j.x += j.vx * dtf;
            j.y += j.vy * dtf;
            j.vx *= (float) Math.pow(0.98, dtf);
            j.vy *= (float) Math.pow(0.98, dtf);
            j.life -= dtf;
            if (j.life <= 0) { it.remove(); continue; }
            float p = j.life / j.maxLife;
            paint.setColor(j.color);
            paint.setAlpha((int) (Math.min(1f, p * 1.3f) * 230));
            canvas.drawCircle(j.x, j.y, j.radius * (0.5f + 0.5f * p), paint);
        }
    }

    /** 鼓点时中心的白炽光团：唯一用加法混合的元素（只有它需要"发光"）。 */
    private void drawCore(Canvas canvas, float scale) {
        float glow = Math.min(1f, flare) * (0.5f + bass * 0.5f);
        if (glow < 0.03f) return;
        if (coreSprite == null) coreSprite = softCircle(192);
        float r = (16f + 54f * glow) * scale;
        float cx = width / 2f, cy = height / 2f;
        rect.set(cx - r, cy - r, cx + r, cy + r);
        paint.setXfermode(ADD_MODE);
        paint.setAlpha((int) Math.min(255, 90 + glow * 165));
        canvas.drawBitmap(coreSprite, null, rect, paint);
        paint.setXfermode(null);
    }

    private static Bitmap softCircle(int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(size / 2f, size / 2f, size / 2f,
                new int[]{0xFFFFFFFF, 0x99FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        return bmp;
    }

    @Override public void reset() {
        jets.clear();
        bass = 0;
        mid = 0;
        volume = 0;
        motionEnergy = 0;
        energy = 0;
        flare = 0;
        speedMul = 1f;
        lastFrameMs = -1;
        analyzer.reset();
        java.util.Arrays.fill(spectrum, 0f);
        placed = false; // 每次进入粒子模式重新随机布局
    }

    @Override public boolean isAtRest() {
        return jets.isEmpty() && flare < 0.01f && energy < 0.004f && bass < .004f && mid < .004f;
    }
}

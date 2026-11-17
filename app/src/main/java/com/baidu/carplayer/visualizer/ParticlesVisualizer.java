package com.baidu.carplayer.visualizer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
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
 * "彗星涟漪"粒子效果（黑白电影感，参考 YouTube CQtOFtSBA3I）：
 *  - 高亮彗星头在画面上缓慢游走，拖着一条渐隐的光尾；
 *  - 鼓点在彗星处生成一圈粒子涟漪环：环是几十个微小亮点组成的圆，
 *    椭圆压扁 + 缓慢旋转 + 半径膨胀，越旧越大越淡（透视纵深）；
 *  - 纯黑背景、高对比单色，只有中心光团用加法混合。
 *
 * 音频联动：环的生成由鼓点触发；环的膨胀速度与亮度随响度；彗星游走速度随 BPM。
 */
public final class ParticlesVisualizer implements AudioVisualizer {
    private static final class Ring {
        float cx, cy;        // 生成点（比例坐标）
        float squash;        // 椭圆压扁 0.3–0.65
        float rot;           // 椭圆旋转角
        float rotSpeed;      // 粒子沿环旋转速度
        float growSpeed;     // 半径膨胀速度 px/s
        float baseR;         // 初始半径 px
        float age, life;     // 秒
        float seed;
    }

    private static final int RING_DOTS = 56;
    private static final int MAX_RINGS = 9;
    private static final PorterDuffXfermode ADD_MODE = new PorterDuffXfermode(PorterDuff.Mode.ADD);

    /** 节拍来源：由 VisualizerManager 注入共享节拍跟踪器，为空按 120 BPM。 */
    interface TempoSource { float periodMs(); }
    private TempoSource tempoSource;

    private final ArrayList<Ring> rings = new ArrayList<>();
    private final Random random = new Random();
    private final AudioAnalyzer analyzer = new AudioAnalyzer();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Bitmap headSprite;   // 彗星头软光斑
    private Bitmap dotSprite;    // 环上小亮点

    private int width, height;
    private float bass, mid, volume;
    private float motionEnergy;
    private float energy;        // 整体可见度：停止后衰减到 0
    private float flare;         // 鼓点头部闪光包络
    private float time;          // 秒
    private long lastFrameMs = -1;
    private float sinceLastRing = 99f;

    // 彗星头状态
    private float headX = 0.5f, headY = 0.5f;   // 比例坐标
    private float dirX = -1f, dirY = -0.6f;     // 尾巴方向（单位向量），平滑跟随
    private float prevHeadX = 0.5f, prevHeadY = 0.5f;
    private float headPhase;

    @Override public String getName() { return "particles"; }

    /** 注入共享节拍跟踪器（估算 BPM 用）。 */
    public void setTempoSource(TempoSource source) { this.tempoSource = source; }

    @Override public void update(byte[] fft, int width, int height) {
        this.width = width;
        this.height = height;
        AudioAnalyzer.Analysis analysis = analyzer.analyze(fft);
        boolean hasData = fft.length > 0;
        bass += ((hasData ? analysis.bass : 0f) - bass) * (analysis.bass > bass ? .45f : .18f);
        mid += ((hasData ? analysis.mid : 0f) - mid) * (analysis.mid > mid ? .45f : .18f);
        volume += ((hasData ? analysis.volume : 0f) - volume) * (analysis.volume > volume ? .4f : .12f);

        float period = tempoSource != null ? tempoSource.periodMs() : 500f;
        float bpm = 60000f / Math.max(300f, Math.min(1000f, period));
        float tempoNorm = Math.max(0f, Math.min(1f, (bpm - 60f) / 140f));
        motionEnergy = (0.35f + volume * 1.5f) * (0.55f + tempoNorm * 0.9f);

        boolean beat = hasData
                && (analysis.peak || (bass > .45f && bass - analysis.bass < 0 && mid > .12f));
        if (beat) flare = Math.min(1.4f, flare + .5f + bass * .5f);
    }

    @Override public void draw(Canvas canvas) {
        if (width <= 0 || height <= 0) return;
        long now = System.nanoTime() / 1_000_000L;
        float dt = lastFrameMs < 0 ? 16f : Math.min(50f, now - lastFrameMs);
        lastFrameMs = now;
        float dts = dt / 1000f;
        float dtf = Math.max(0.5f, dt / 16f);
        time += dts;

        if (headSprite == null) {
            headSprite = softCircle(192);
            dotSprite = softCircle(32);
        }

        // 电影感黑幕（近纯黑，保留一丝底色）
        paint.setXfermode(null);
        paint.setShader(null);
        canvas.drawColor(Color.argb(0xD9, 3, 4, 8), PorterDuff.Mode.SRC_OVER);

        // 可见度：有信号时常亮，停止后衰减归零（静止态契约）
        boolean hasSignal = bass > 0.001f || mid > 0.001f;
        float energyTarget = hasSignal ? 1f : 0f;
        energy += (energyTarget - energy) * (energyTarget > energy ? .3f : .05f);
        // 鼓点闪光按帧衰减（60fps 下每帧 ×0.945，约 1 秒基本消失）
        flare *= (float) Math.pow(0.945f, dtf);

        updateHead(dts);
        spawnRingsIfNeeded(dts);

        float scale = Math.max(1f, Math.min(width, height) / 360f);
        drawTrail(canvas, scale);
        drawRings(canvas, dtf, scale);
        drawHead(canvas, scale);
    }

    // ---------- 彗星头 ----------

    /** 头部沿缓慢的 Lissajous 轨迹游走，速度随 BPM。 */
    private void updateHead(float dts) {
        prevHeadX = headX;
        prevHeadY = headY;
        // 相位增量与当前速度成比：变速时不跳变
        float speed = 0.028f + motionEnergy * 0.03f; // rad/s
        headPhase += dts * (0.6f + motionEnergy * 0.5f) * speed;
        headX = 0.5f + 0.31f * (float) Math.sin(headPhase * 2.2f + 1.1f);
        headY = 0.5f + 0.26f * (float) Math.sin(headPhase * 1.7f + 0.4f);

        // 尾巴方向 = 运动反方向，平滑跟随
        float vx = headX - prevHeadX, vy = headY - prevHeadY;
        float len = (float) Math.sqrt(vx * vx + vy * vy);
        if (len > 0.0004f) {
            vx /= len; vy /= len;
            float k = Math.min(1f, 2.5f * dts);
            dirX += (vx - dirX) * k;
            dirY += (vy - dirY) * k;
            float dl = (float) Math.sqrt(dirX * dirX + dirY * dirY);
            dirX /= dl; dirY /= dl;
        }
    }

    /** 鼓点（或最短间隔）在彗星处生成涟漪环。 */
    private void spawnRingsIfNeeded(float dts) {
        sinceLastRing += dts;
        float minGap = Math.max(0.55f, 60000f / Math.max(300f, Math.min(1000f,
                tempoSource != null ? tempoSource.periodMs() : 500f)) / 1000f * 1.6f);
        boolean beatGate = flare > 0.55f && sinceLastRing > minGap * 0.6f;
        if ((beatGate || sinceLastRing > 2.2f) && rings.size() < MAX_RINGS && energy > 0.15f
                && (bass > 0.01f || mid > 0.01f)) {
            Ring r = new Ring();
            r.cx = headX;
            r.cy = headY;
            // 椭圆近水平（±22°），环最大半径按视图短边比例：铺满可视化区而不被裁掉
            r.squash = 0.34f + random.nextFloat() * 0.28f;
            r.rot = (random.nextFloat() - 0.5f) * 0.77f;
            r.rotSpeed = (random.nextBoolean() ? 1f : -1f) * (0.15f + random.nextFloat() * 0.25f) / 60f;
            float shortSide = Math.min(width, height);
            float maxR = (0.34f + random.nextFloat() * 0.24f) * shortSide;
            r.baseR = (0.03f + random.nextFloat() * 0.03f) * shortSide;
            r.growSpeed = (maxR - r.baseR) / r.life;
            r.life = (4.2f + random.nextFloat() * 2.2f) * 60f; // 帧
            r.rotSpeed = r.rotSpeed / 60f;
            r.seed = random.nextFloat() * 40f;
            rings.add(r);
            sinceLastRing = 0f;
        }
    }

    // ---------- 绘制 ----------

    /** 光尾：从头部沿尾巴方向拉出长渐变光线（参考视频里最醒目的元素）。 */
    private void drawTrail(Canvas canvas, float scale) {
        float hx = headX * width, hy = headY * height;
        float len = (0.42f + volume * 0.25f) * (float) Math.hypot(width, height);
        float tx = hx - dirX * len, ty = hy - dirY * len;
        // 渐变：头部白亮 → 尾部透明
        paint.setShader(new LinearGradient(hx, hy, tx, ty,
                new int[]{Color.WHITE, 0xB3FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.18f, 1f}, Shader.TileMode.CLAMP));
        linePaint.setShader(paint.getShader());
        linePaint.setStrokeWidth(2.6f * scale);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(hx, hy, tx, ty, linePaint);
        // 内芯更亮更细
        linePaint.setStrokeWidth(1.1f * scale);
        paint.setShader(new LinearGradient(hx, hy, tx, ty,
                new int[]{0xFFFFFFFF, 0x8CFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP));
        linePaint.setShader(paint.getShader());
        canvas.drawLine(hx, hy, tx, ty, linePaint);
        linePaint.setShader(null);
        paint.setShader(null);
    }

    /** 涟漪环：椭圆排布的粒子圈，膨胀 + 旋转 + 渐隐。 */
    private void drawRings(Canvas canvas, float dtf, float scale) {
        // 按帧老化（测试与低帧率下稳定）；音乐停止（能量低）时快速消散
        float ageScale = energy < 0.05f ? 30f : 1f;
        Iterator<Ring> it = rings.iterator();
        while (it.hasNext()) {
            Ring r = it.next();
            r.age += dtf * ageScale;
            if (r.age > r.life) { it.remove(); continue; }
            float radius = r.baseR + r.growSpeed * r.age;
            float rotNow = r.rot + r.age * r.rotSpeed;
            float cosR = (float) Math.cos(rotNow), sinR = (float) Math.sin(rotNow);
            // 透明度包络（帧单位）：快起慢出
            float env;
            if (r.age < 20f) env = r.age / 20f;
            else env = 1f - (r.age - 20f) / (r.life - 20f);
            int baseAlpha = (int) (Math.max(0f, env) * 205 * Math.max(0.4f, energy));

            float cxp = r.cx * width, cyp = r.cy * height;
            for (int k = 0; k < RING_DOTS; k++) {
                float ang = (float) (k * Math.PI * 2 / RING_DOTS);
                float jr = 1f + 0.07f * (float) Math.sin(ang * 6f + r.seed + time * 1.7f);
                float px = radius * jr * (float) Math.cos(ang);
                float py = radius * jr * (float) Math.sin(ang) * r.squash;
                float sx = cxp + px * cosR - py * sinR;
                float sy = cyp + px * sinR + py * cosR;
                // 沿环的亮度微差（受光面更亮），偶尔出现亮粒
                float shade = 0.55f + 0.45f * (float) Math.sin(ang + r.seed);
                float sparkle = (k % 9 == 0) ? 1.7f : 1f;
                float size = (0.9f + 1.1f * shade) * scale * sparkle;
                int alpha = (int) (baseAlpha * (0.35f + 0.65f * shade));
                if (alpha <= 2) continue;
                paint.setAlpha(Math.min(255, alpha));
                rect.set(sx - size * 1.6f, sy - size * 1.6f, sx + size * 1.6f, sy + size * 1.6f);
                canvas.drawBitmap(dotSprite, null, rect, paint);
            }
        }
    }

    /** 彗星头：亮核 + 柔光，鼓点时闪。 */
    private void drawHead(Canvas canvas, float scale) {
        float hx = headX * width, hy = headY * height;
        float glow = Math.min(1f, flare) * 0.7f + volume * 0.3f;
        paint.setXfermode(ADD_MODE);
        // 柔光
        float r1 = (22f + 30f * glow) * scale;
        rect.set(hx - r1, hy - r1, hx + r1, hy + r1);
        paint.setAlpha((int) Math.min(255, 120 + glow * 135));
        canvas.drawBitmap(headSprite, null, rect, paint);
        // 亮核
        float r2 = (5f + 6f * glow) * scale;
        rect.set(hx - r2, hy - r2, hx + r2, hy + r2);
        paint.setAlpha((int) Math.min(255, 200 + glow * 55));
        canvas.drawBitmap(headSprite, null, rect, paint);
        paint.setXfermode(null);
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
        rings.clear();
        bass = 0;
        mid = 0;
        volume = 0;
        motionEnergy = 0;
        energy = 0;
        flare = 0;
        time = 0;
        sinceLastRing = 99f;
        headX = headY = 0.5f;
        prevHeadX = prevHeadY = 0.5f;
        dirX = -1f; dirY = -0.6f;
        headPhase = 0f;
        lastFrameMs = -1;
        analyzer.reset();
    }

    @Override public boolean isAtRest() {
        return rings.isEmpty() && flare < 0.01f && energy < 0.004f
                && bass < .004f && mid < .004f;
    }
}

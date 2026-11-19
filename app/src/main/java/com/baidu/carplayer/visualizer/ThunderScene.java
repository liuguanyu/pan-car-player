package com.baidu.carplayer.visualizer;

import java.util.Random;

/**
 * 雷雨场景（纯 Java，不碰 GL，便于单测）。
 *
 * <p>每次雷击在 CPU 上生成一条全新的放电通道：
 * <ul>
 *   <li>主干用中点位移（midpoint displacement）递归细分 {@link #MAIN_DEPTH} 层，得到 128 段。
 *       每层的横向偏移与该层线段长度成正比，所以大尺度弯折和小尺度锯齿同时存在（自相似分形），
 *       不会像少量平滑折线那样死板；</li>
 *   <li>分叉从主干中段长出，偏离主干方向 20°～55°，分叉上还可能再长出更短的次级分叉；</li>
 *   <li>每段记录它在通道上的"到达时刻" s∈[0,1]，着色器据此表现先导从起点向前生长的过程。</li>
 * </ul>
 *
 * <p>坐标系：x∈[0, aspect]，y∈[0, 1]（y 向上），与屏幕等比例，偏移在横竖方向上一致。
 *
 * <p>音频映射：
 * <ul>
 *   <li>亮度峰值 ← 响度；</li>
 *   <li>分叉个数 ← 中频能量；</li>
 *   <li>通道长度、是否向下延伸 ← 低频；</li>
 *   <li>回击次数 ← 响度；</li>
 *   <li>余辉时长 ← 整体能量；</li>
 *   <li>走向倾斜 ← 高频相对低频的比例（越"亮"的音色越斜）。</li>
 * </ul>
 */
final class ThunderScene {
    /** 同时存在的雷击数（上一道还在余辉时，下一道可以出现）。 */
    static final int SLOTS = 4;
    static final int MAIN_DEPTH = 7;      // 2^7 = 128 段
    static final int BRANCH_DEPTH = 5;    // 32 段
    static final int SUB_DEPTH = 4;       // 16 段
    static final int MAX_BRANCHES = 5;
    /** 每个槽位最多的线段数。 */
    static final int CAP = (1 << MAIN_DEPTH) + MAX_BRANCHES * ((1 << BRANCH_DEPTH) + (1 << SUB_DEPTH));
    /** 每段 8 个 float：ax, ay, bx, by | 宽度, 亮度, 到达时刻 s, 类型(0 主干/1 分叉/2 次级分叉)。 */
    static final int FLOATS = 8;
    /** 两次雷击的最小间隔（秒），约束闪光频率远低于 3 Hz。 */
    static final float MIN_GAP = 0.55f;

    final float[][] data = new float[SLOTS][CAP * FLOATS];
    final int[] count = new int[SLOTS];
    final float[] birth = new float[SLOTS];
    final float[] peak = new float[SLOTS];
    final float[] tau = new float[SLOTS];
    final int[] strokes = new int[SLOTS];
    final float[] off1 = new float[SLOTS];
    final float[] off2 = new float[SLOTS];
    /** 雷击照亮云层的中心（与线段同一坐标系）。 */
    final float[] centerX = new float[SLOTS];
    final float[] centerY = new float[SLOTS];
    /** 0 = 云内横向放电，1 = 向下延伸。 */
    final int[] kind = new int[SLOTS];

    private int next;
    private float lastStrike = -1e9f;
    private final float[] px = new float[(1 << MAIN_DEPTH) + 1];
    private final float[] py = new float[(1 << MAIN_DEPTH) + 1];
    private final float[] bx = new float[(1 << BRANCH_DEPTH) + 1];
    private final float[] by = new float[(1 << BRANCH_DEPTH) + 1];
    private final float[] sx = new float[(1 << SUB_DEPTH) + 1];
    private final float[] sy = new float[(1 << SUB_DEPTH) + 1];

    ThunderScene() {
        for (int i = 0; i < SLOTS; i++) birth[i] = -1e9f;
    }

    /** 最近一次雷击使用的槽位。 */
    int lastSlot() {
        return (next + SLOTS - 1) % SLOTS;
    }

    boolean canStrike(float now) {
        return now - lastStrike >= MIN_GAP;
    }

    /** 槽位从出生到完全熄灭的时长（秒）。 */
    float lifetime(int slot) {
        return tau[slot] * 4f + 0.6f;
    }

    boolean alive(int slot, float now) {
        return count[slot] > 0 && now - birth[slot] < lifetime(slot);
    }

    /**
     * 生成一次雷击，返回所用槽位。
     *
     * @param aspect 画面宽高比
     * @param bass   低频 0..1
     * @param mid    中频 0..1
     * @param treble 高频 0..1
     * @param loud   响度 0..1
     */
    int strike(float now, float aspect, float bass, float mid, float treble, float loud, Random r) {
        int slot = next;
        next = (next + 1) % SLOTS;
        lastStrike = now;
        float w = Math.max(1f, aspect);

        // ---- 走向：大多数是云内横向放电；低频很重时偶尔向下延伸，但仍停在天空中 ----
        boolean down = bass > 0.55f && r.nextFloat() < 0.22f;
        float ax, ay, ex, ey;
        if (down) {
            ax = w * (0.12f + 0.76f * r.nextFloat());
            ay = 0.86f + 0.08f * r.nextFloat();
            ex = clamp(ax + (r.nextFloat() - 0.5f) * 1.2f, 0.05f, w - 0.05f);
            ey = 0.10f + 0.14f * r.nextFloat() - 0.06f * bass;
        } else {
            // 从画面一侧起步，横贯大半个天空
            boolean fromLeft = r.nextBoolean();
            ax = w * (fromLeft ? 0.02f + 0.22f * r.nextFloat() : 0.76f + 0.22f * r.nextFloat());
            ay = 0.58f + 0.30f * r.nextFloat();
            float len = (1.30f + 1.20f * loud + 0.60f * bass) * (0.85f + 0.30f * r.nextFloat());
            len = Math.min(len, w * 0.92f);
            // 高频越强走向越斜（"刺"），低频为主时更平（"滚"）
            float tilt = (0.10f + 0.45f * clamp(treble / Math.max(0.05f, bass + treble), 0f, 1f))
                    * (r.nextBoolean() ? 1f : -1f);
            float dir = fromLeft ? 0f : (float) Math.PI;
            float ang = dir + tilt;
            ex = clamp(ax + (float) Math.cos(ang) * len, 0.03f, w - 0.03f);
            ey = clamp(ay + (float) Math.sin(ang) * len, 0.42f, 0.96f);
        }
        kind[slot] = down ? 1 : 0;

        // ---- 主干 ----
        float[] out = data[slot];
        int n = 0;
        float rough = down ? 0.30f : 0.34f;
        int pts = midpoint(ax, ay, ex, ey, MAIN_DEPTH, rough, r, px, py, 0.01f, w - 0.01f, 0.02f, 0.99f);
        float mainW = 0.0034f + 0.0024f * loud;
        float total = 0f;
        for (int i = 1; i < pts; i++) total += dist(px[i - 1], py[i - 1], px[i], py[i]);
        float acc = 0f;
        float[] sAt = new float[pts];
        for (int i = 1; i < pts; i++) {
            acc += dist(px[i - 1], py[i - 1], px[i], py[i]);
            sAt[i] = acc / Math.max(1e-5f, total);
        }
        for (int i = 1; i < pts; i++) {
            n = put(out, n, px[i - 1], py[i - 1], px[i], py[i],
                    mainW, 0.86f + 0.14f * r.nextFloat(), sAt[i - 1], 0);
        }
        float minX = Math.min(ax, ex), maxX = Math.max(ax, ex);
        float minY = Math.min(ay, ey), maxY = Math.max(ay, ey);

        // ---- 分叉：个数随中频能量 ----
        int branches = Math.min(MAX_BRANCHES, 1 + Math.round(4f * mid * (0.7f + 0.6f * r.nextFloat())));
        for (int b = 0; b < branches; b++) {
            int k = (int) (pts * (0.12f + 0.68f * r.nextFloat()));
            k = Math.max(1, Math.min(pts - 5, k));
            float dx = px[k + 4] - px[k], dy = py[k + 4] - py[k];
            float base = (float) Math.atan2(dy, dx);
            float side = r.nextBoolean() ? 1f : -1f;
            if (down) side = dx >= 0 ? -1f : 1f;              // 向下的通道，分叉也往下
            float ang = base + side * (0.35f + 0.60f * r.nextFloat());
            float len = total * (0.16f + 0.26f * r.nextFloat()) * (0.7f + 0.6f * mid);
            float tx = clamp(px[k] + (float) Math.cos(ang) * len, 0.02f, w - 0.02f);
            float ty = clamp(py[k] + (float) Math.sin(ang) * len, 0.08f, 0.98f);
            int bp = midpoint(px[k], py[k], tx, ty, BRANCH_DEPTH, 0.38f, r, bx, by, 0.01f, w - 0.01f, 0.02f, 0.99f);
            float bLen = len / Math.max(1e-5f, total);
            float bI = 0.50f + 0.25f * r.nextFloat();
            for (int i = 1; i < bp; i++) {
                float local = (i - 1) / (float) (bp - 1);
                n = put(out, n, bx[i - 1], by[i - 1], bx[i], by[i],
                        mainW * 0.62f, bI * (1f - 0.65f * local), sAt[k] + local * bLen, 1);
            }
            minX = Math.min(minX, tx); maxX = Math.max(maxX, tx);
            minY = Math.min(minY, ty); maxY = Math.max(maxY, ty);

            // 次级分叉
            if (r.nextFloat() < 0.35f + 0.4f * mid) {
                int kk = 2 + r.nextInt(Math.max(1, bp - 6));
                float sdx = bx[kk + 2] - bx[kk], sdy = by[kk + 2] - by[kk];
                float sa = (float) Math.atan2(sdy, sdx) + (r.nextBoolean() ? 1f : -1f) * (0.4f + 0.5f * r.nextFloat());
                float sl = len * (0.30f + 0.25f * r.nextFloat());
                float ux = clamp(bx[kk] + (float) Math.cos(sa) * sl, 0.02f, w - 0.02f);
                float uy = clamp(by[kk] + (float) Math.sin(sa) * sl, 0.08f, 0.98f);
                int sp = midpoint(bx[kk], by[kk], ux, uy, SUB_DEPTH, 0.40f, r, sx, sy, 0.01f, w - 0.01f, 0.02f, 0.99f);
                float s0 = sAt[k] + (kk / (float) (bp - 1)) * bLen;
                for (int i = 1; i < sp; i++) {
                    float local = (i - 1) / (float) (sp - 1);
                    n = put(out, n, sx[i - 1], sy[i - 1], sx[i], sy[i],
                            mainW * 0.42f, bI * 0.6f * (1f - 0.7f * local), s0 + local * sl / Math.max(1e-5f, total), 2);
                }
            }
        }
        count[slot] = n;

        // ---- 时间与亮度 ----
        birth[slot] = now;
        peak[slot] = 0.45f + 0.55f * loud;
        // 余辉：约 0.45～1.25 s，越响越久
        tau[slot] = 0.45f + 0.60f * loud + 0.20f * bass;
        strokes[slot] = 1 + (loud > 0.45f ? 1 : 0) + (r.nextFloat() < 0.4f * loud ? 1 : 0);
        // 回击间隔 ≥ 70 ms：30 fps 下也不会被吞帧
        off1[slot] = 0.075f + 0.045f * r.nextFloat();
        off2[slot] = off1[slot] + 0.075f + 0.05f * r.nextFloat();
        centerX[slot] = (minX + maxX) * 0.5f;
        centerY[slot] = (minY + maxY) * 0.5f;
        return slot;
    }

    /** 中点位移：从 a 到 b 细分 depth 层，返回点数 2^depth+1。生成结果被夹在 [xlo,xhi]×[ylo,yhi]。 */
    static int midpoint(float ax, float ay, float bx, float by, int depth, float rough, Random r,
                        float[] x, float[] y) {
        return midpoint(ax, ay, bx, by, depth, rough, r, x, y, -1e9f, 1e9f, -1e9f, 1e9f);
    }

    /** 带边界的版本：分形电流通道本应局限在云层内，超出部分夹回来以免画到画幅外。 */
    static int midpoint(float ax, float ay, float bx, float by, int depth, float rough, Random r,
                        float[] x, float[] y, float xlo, float xhi, float ylo, float yhi) {
        int n = 1 << depth;
        x[0] = ax; y[0] = ay; x[n] = bx; y[n] = by;
        for (int step = n; step > 1; step >>= 1) {
            int half = step >> 1;
            for (int i = 0; i < n; i += step) {
                float x0 = x[i], y0 = y[i], x1 = x[i + step], y1 = y[i + step];
                float dx = x1 - x0, dy = y1 - y0;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                // 中点沿线略微前后错动，避免节点等距
                float t = 0.5f + (r.nextFloat() - 0.5f) * 0.3f;
                float g = (float) r.nextGaussian() * 0.5f;
                g = Math.max(-1.2f, Math.min(1.2f, g));
                float off = g * rough * len;
                float nx = len > 1e-6f ? -dy / len : 0f, ny = len > 1e-6f ? dx / len : 0f;
                x[i + half] = clamp(x0 + dx * t + nx * off, xlo, xhi);
                y[i + half] = clamp(y0 + dy * t + ny * off, ylo, yhi);
            }
        }
        return n + 1;
    }

    private static int put(float[] out, int n, float ax, float ay, float bx, float by,
                           float width, float intensity, float s, int type) {
        if (n >= CAP) return n;
        int o = n * FLOATS;
        out[o] = ax; out[o + 1] = ay; out[o + 2] = bx; out[o + 3] = by;
        out[o + 4] = width; out[o + 5] = intensity; out[o + 6] = Math.min(1f, s); out[o + 7] = type;
        return n + 1;
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

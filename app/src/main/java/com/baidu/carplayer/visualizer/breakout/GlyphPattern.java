package com.baidu.carplayer.visualizer.breakout;

import java.util.Random;

/**
 * 字符阵型生成器（方案 B）：由 seed/BPM/调性/标题 稳定确定一个 {@link Spec}，
 * 栅格化成砖块网格坐标。同 seed 永远同输出——确定性是验收标准 1 的根基。
 *
 * <p>模板（seed 选择，同一首歌固定）：
 * <ul>
 *   <li>S：1 个字符（调性优先，缺调性取 BPM 十位）</li>
 *   <li>D：BPM 两位 + 调性（缺调性用标题 hash 字符），3 个字符</li>
 *   <li>T：调性 + BPM 两位 + 标记字符（C/V/B/标题 hash），4 个字符</li>
 * </ul>
 * 超 {@link #MAX_BRICKS} 时按 T→D→S 降级，优先保笔画主干。
 *
 * <p>段落识别已裁剪：全曲固定一个模板，切换只发生在切歌/seek/旋转。
 * 标记字符里的 "i" 因字符池剔除了 I，用标题 hash 字符替代。
 */
public final class GlyphPattern {
    public static final int MAX_BRICKS = 72;
    public static final int MAX_CHARS = 4;
    public static final int TEMPLATE_S = 0, TEMPLATE_D = 1, TEMPLATE_T = 2;

    /** 字符间距（格）。 */
    public static final int CHAR_GAP = 2;

    public static final class Spec {
        public final int[] chars = new int[MAX_CHARS];
        public final int count;
        public final int template;
        public int brickCount;
        public final long seed;
        public final int bpm;

        Spec(int[] chars, int count, int template, int brickCount, long seed, int bpm) {
            this.count = count;
            this.template = template;
            this.brickCount = brickCount;
            this.seed = seed;
            this.bpm = bpm;
            System.arraycopy(chars, 0, this.chars, 0, count);
        }

        /**
         * 栅格化：把 spec 展开成砖块网格坐标（3×5 字库，字符间留 {@link #CHAR_GAP} 列）。
         * 输出写入预分配缓冲，返回砖块数。col 0 = 最左。
         */
        public int gridCells(int[] colOut, int[] rowOut) {
            int n = 0;
            int colBase = 0;
            for (int i = 0; i < count; i++) {
                int key = chars[i];
                for (int r = 0; r < GlyphFont.SMALL_ROWS; r++) {
                    for (int c = 0; c < GlyphFont.SMALL_COLS; c++) {
                        if (!GlyphFont.cell35(key, r, c)) continue;
                        colOut[n] = colBase + c;
                        rowOut[n] = r;
                        n++;
                    }
                }
                colBase += GlyphFont.SMALL_COLS + CHAR_GAP;
            }
            return n;
        }

        /** 阵型总列数（3×5 字号 + 间距）。 */
        public int totalCols() {
            return count * GlyphFont.SMALL_COLS + (count - 1) * CHAR_GAP;
        }

        public int totalRows() {
            return GlyphFont.SMALL_ROWS;
        }
    }

    private GlyphPattern() { }

    /** 稳定字符串 hash（不用 String.hashCode 的随机性顾虑：它本来就是确定的）。 */
    public static long stableHash(String s) {
        long h = 1469598103934665603L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 1099511628211L;
        }
        return h;
    }

    /** trackId → seed。 */
    public static long trackSeed(long trackId) {
        return stableHash(Long.toString(trackId)) & 0x7FFFFFFFFFFFFFFFL;
    }

    /**
     * @param keyCharKey   调性字符的池下标（A–G），-1 = 无调性
     * @param titleCharKey 标题 hash 落在字符池内的下标（调用方保证 0..SIZE-1）
     * @param bpm          整数 BPM，0 = 未知（用 seed 兜底一个 88..132 的值）
     */
    public static Spec generate(long seed, int bpm, int keyCharKey, int titleCharKey) {
        if (bpm < 60) bpm = 88 + (int) ((seed >>> 8) % 45);
        if (bpm > 220) bpm = 220;
        long s = (seed ^ stableHash("glyph-v1")) & 0x7FFFFFFFFFFFFFFFL;
        int template = (int) (s % 3);
        int[] chars;
        // 十位/个位字符
        int tens = (int) (bpm / 10) % 10, ones = bpm % 10;
        int key = keyCharKey >= 0 ? keyCharKey : titleCharKey;
        // 标记字符：C/V/B/标题 hash（无段落识别，按 seed 稳定取）
        int marker = (keyCharKey >= 0 ? titleCharKey : keyCharKey);
        int[] markerPool = {GlyphFont.indexOf('C'), GlyphFont.indexOf('V'),
                GlyphFont.indexOf('B'), marker >= 0 ? marker : 0};
        if (marker < 0 || marker >= GlyphFont.POOL.length()) marker = 0;
        marker = markerPool[(int) ((s >>> 3) % markerPool.length)];

        switch (template) {
            case TEMPLATE_T:
                chars = new int[]{key, tens, ones, marker};
                break;
            case TEMPLATE_D:
                chars = new int[]{tens, ones, key};
                break;
            default:
                chars = new int[]{key};
                break;
        }
        return degrade(new Spec(chars, chars.length, template, countBricks(chars, chars.length),
                seed, bpm));
    }

    /** 超砖块上限时降级：T→D→S，优先保留笔画主干（调性 > BPM > 标记）。 */
    static Spec degrade(Spec in) {
        Spec cur = in;
        while (cur.brickCount > MAX_BRICKS && cur.count > 1) {
            int[] smaller = new int[MAX_CHARS];
            int n = cur.count - (cur.template == TEMPLATE_T ? 1 : 0);
            if (n < 1) n = 1;
            // 保留前 n 个（T 去掉最后一个标记字符；D 去掉调性；S 无得降）
            System.arraycopy(cur.chars, 0, smaller, 0, n);
            int tpl = n <= 1 ? TEMPLATE_S : (n == 3 ? TEMPLATE_D : TEMPLATE_T);
            cur = new Spec(smaller, n, tpl, countBricks(smaller, n), cur.seed, cur.bpm);
        }
        return cur;
    }

    private static int countBricks(int[] chars, int count) {
        int[] c = new int[MAX_BRICKS], r = new int[MAX_BRICKS];
        return new Spec(chars, count, 0, 0, 0, 0).gridCells(c, r);
    }
}

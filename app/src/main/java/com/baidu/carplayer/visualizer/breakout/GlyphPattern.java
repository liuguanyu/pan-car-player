package com.baidu.carplayer.visualizer.breakout;

/** 固定字库生成的曲目阵型。栅格化写入调用方的预分配缓冲。 */
public final class GlyphPattern {
    public static final int MAX_BRICKS = 60;
    public static final int MAX_CHARS = 3;
    public static final int TEMPLATE_S = 0, TEMPLATE_D = 1, TEMPLATE_T = 2;
    private static final int GAP = 2;

    public static final class Spec {
        private final int[] keys;
        public final int count, template, brickCount, gridCols, gridRows;
        public final long seed;

        private Spec(int[] keys, int template, long seed, int brickCount) {
            this.keys = keys;
            this.count = keys.length;
            this.template = template;
            this.seed = seed;
            this.brickCount = brickCount;
            gridCols = count * GlyphFont.BIG_COLS + (count - 1) * GAP;
            gridRows = GlyphFont.BIG_ROWS;
        }

        public int charKey(int index) { return keys[index]; }

        public int gridCells(int[] cols, int[] rows) {
            int n = 0;
            for (int i = 0; i < count; i++) {
                int base = i * (GlyphFont.BIG_COLS + GAP);
                for (int row = 0; row < gridRows; row++) {
                    for (int col = 0; col < GlyphFont.BIG_COLS; col++) {
                        if (!GlyphFont.cell57(keys[i], row, col)) continue;
                        cols[n] = base + col;
                        rows[n++] = row;
                    }
                }
            }
            return n;
        }
    }

    private GlyphPattern() { }

    public static long stableHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    public static Spec generate(long seed, int bpm, int key, int titleKey) {
        int template = (int) Math.floorMod(seed, 3L);
        int fallback = (int) Math.floorMod(seed >>> 8, GlyphFont.SIZE);
        int tonic = key >= 10 && key <= GlyphFont.indexOf('G') ? key : fallback;
        int marker = titleKey >= 0 && titleKey < GlyphFont.SIZE ? titleKey : fallback;
        boolean knownBpm = bpm >= 60 && bpm <= 220;
        int tens = knownBpm ? bpm / 10 % 10 : marker;
        int ones = knownBpm ? bpm % 10 : marker;
        int single = key >= 10 && key <= GlyphFont.indexOf('G') ? tonic : knownBpm ? tens : fallback;
        if (template == TEMPLATE_T) {
            Spec triple = fit(seed, TEMPLATE_T, tonic, tens, ones);
            if (knownBpm && triple != null) return triple;
        }
        if (template != TEMPLATE_S) {
            Spec pair = fit(seed, TEMPLATE_D, knownBpm ? ones : marker, tonic);
            if (pair != null) return pair;
        }
        return fit(seed, TEMPLATE_S, single);
    }

    /** Change the last glyph while keeping the other track-derived characters. */
    static Spec nextRound(Spec current) {
        int last = current.count - 1;
        int oldKey = current.keys[last];
        int otherBricks = current.brickCount - GlyphFont.litCount57(oldKey);
        for (int offset = 1; offset < GlyphFont.SIZE; offset++) {
            int nextKey = (oldKey + offset) % GlyphFont.SIZE;
            int brickCount = otherBricks + GlyphFont.litCount57(nextKey);
            if (brickCount > MAX_BRICKS) continue;
            int[] keys = current.keys.clone();
            keys[last] = nextKey;
            return new Spec(keys, current.template, current.seed, brickCount);
        }
        return current;
    }

    private static Spec fit(long seed, int template, int... keys) {
        int bricks = 0;
        for (int key : keys) bricks += GlyphFont.litCount57(key);
        return bricks <= MAX_BRICKS ? new Spec(keys, template, seed, bricks) : null;
    }
}

package com.baidu.carplayer.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * 液晶点阵数字（5×7 点阵，自绘）。
 *
 * <p>用 5×7 点阵而不是字体文件：不依赖 TTF（避免字体授权问题，也不受车机缺字体影响），
 * 任何分辨率下都能画出清晰的"仪表液晶"观感。未点亮的点位可选画成极淡的"鬼影"，
 * 这是真实段码/点阵屏的质感来源。
 *
 * <p>只包含仪表需要的字符：数字 0-9、K、M、H、/、.、空格；其它字符按空格处理。
 */
public final class LcdTextView extends View {
    private static final int COLS = 5;
    private static final int ROWS = 7;

    /** 5×7 点阵字模：每行 5 bit，最高位在左。 */
    private static final int[][] GLYPHS = new int[128][];
    private static final String CHARS = "0123456789KMH/. ";

    private static final int[] G0 = {0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E};
    private static final int[] G1 = {0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E};
    private static final int[] G2 = {0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F};
    private static final int[] G3 = {0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E};
    private static final int[] G4 = {0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02};
    private static final int[] G5 = {0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E};
    private static final int[] G6 = {0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E};
    private static final int[] G7 = {0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08};
    private static final int[] G8 = {0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E};
    private static final int[] G9 = {0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C};
    private static final int[] GK = {0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11};
    private static final int[] GM = {0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11};
    private static final int[] GH = {0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11};
    private static final int[] GSLASH = {0x01, 0x02, 0x02, 0x04, 0x08, 0x08, 0x10};
    private static final int[] GDOT = {0x00, 0x00, 0x00, 0x00, 0x00, 0x0C, 0x0C};
    private static final int[] GSPACE = new int[ROWS];

    static {
        GLYPHS['0'] = G0; GLYPHS['1'] = G1; GLYPHS['2'] = G2; GLYPHS['3'] = G3;
        GLYPHS['4'] = G4; GLYPHS['5'] = G5; GLYPHS['6'] = G6; GLYPHS['7'] = G7;
        GLYPHS['8'] = G8; GLYPHS['9'] = G9;
        GLYPHS['K'] = GK; GLYPHS['M'] = GM; GLYPHS['H'] = GH;
        GLYPHS['/'] = GSLASH; GLYPHS['.'] = GDOT; GLYPHS[' '] = GSPACE;
    }

    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dot = new RectF();
    private String text = "";
    private float dotSize;
    private float gapRatio = .34f;
    private float radiusRatio = .28f;
    private int digitColor = 0xffffffff;
    private int ghostColor = 0x14ffffff;
    private boolean ghost = true;

    public LcdTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dotPaint.setStyle(Paint.Style.FILL);
        // 点阵尺寸默认跟 sp 文字大小挂钩，和普通 TextView 手感一致
        dotSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 4f,
                getResources().getDisplayMetrics());
    }

    public void setText(String value) {
        String next = value == null ? "" : value.toUpperCase(java.util.Locale.ROOT);
        if (next.equals(text)) return;
        text = next;
        requestLayout();
        invalidate();
    }

    public String getText() { return text; }

    /** 点的边长（像素）。 */
    public void setDotSize(float sizePx) {
        if (sizePx == dotSize) return;
        dotSize = Math.max(1f, sizePx);
        requestLayout();
        invalidate();
    }

    /** 点亮颜色。 */
    public void setDigitColor(int color) {
        digitColor = color;
        invalidate();
    }

    /** 是否绘制未点亮点的"鬼影"（默认开，更像真实液晶）。 */
    public void setGhost(boolean enabled) {
        ghost = enabled;
        invalidate();
    }

    public void setGhostColor(int color) {
        ghostColor = color;
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int glyphs = Math.max(1, text.length());
        int cell = Math.round(dotSize);
        int gap = Math.round(dotSize * gapRatio);
        int w = glyphs * (COLS * cell + (COLS - 1) * gap) + (glyphs - 1) * cell
                + getPaddingLeft() + getPaddingRight();
        int h = ROWS * cell + (ROWS - 1) * gap + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(w, widthSpec), resolveSize(h, heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (text.isEmpty()) return;
        int cell = Math.round(dotSize);
        int gap = Math.round(dotSize * gapRatio);
        float stepX = cell + gap;
        float charStep = COLS * stepX + cell;   // 字间距 = 一个点宽
        float r = cell * radiusRatio;
        float x0 = getPaddingLeft();
        float y0 = getPaddingTop();
        for (int i = 0; i < text.length(); i++) {
            int[] glyph = GLYPHS[text.charAt(i)];
            if (glyph == null) glyph = GSPACE;
            float cx = x0 + i * charStep;
            for (int row = 0; row < ROWS; row++) {
                int bits = glyph[row];
                for (int col = 0; col < COLS; col++) {
                    boolean on = (bits & (1 << (COLS - 1 - col))) != 0;
                    if (!on && !ghost) continue;
                    dotPaint.setColor(on ? digitColor : ghostColor);
                    float left = cx + col * stepX;
                    float top = y0 + row * stepX;
                    dot.set(left, top, left + cell, top + cell);
                    canvas.drawRoundRect(dot, r, r, dotPaint);
                }
            }
        }
    }
}

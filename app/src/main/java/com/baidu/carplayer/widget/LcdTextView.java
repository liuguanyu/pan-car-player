package com.baidu.carplayer.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.util.Locale;

/**
 * 七段数码管数字（自绘，矢量），车用仪表的经典画法。
 *
 * <p>每段是带斜角的四边形；未点亮的段画成很淡的"鬼影"（真实 LED/液晶的质感来源），
 * 点亮段外圈有一圈同色低透明度的辉光。支持数字滚动动画（里程表式 count-up/down）。
 * 只含仪表需要的字符：0-9、K、M、H、/、.、-、空格。
 *
 * <p>段定义（经典编号）：
 * <pre>
 *      ┌─ a ─┐
 *      f     b
 *      ├─ g ─┤
 *      e     c
 *      └─ d ─┘
 * </pre>
 */
public final class LcdTextView extends View {
    /** 每个字符的段位掩码：a,b,c,d,e,f,g。 */
    private static final int SEG_A = 1, SEG_B = 2, SEG_C = 4, SEG_D = 8, SEG_E = 16, SEG_F = 32, SEG_G = 64;

    private static final int[] DIG0 = {SEG_A, SEG_B, SEG_C, SEG_D, SEG_E, SEG_F};
    private static final int[] DIG1 = {SEG_B, SEG_C};
    private static final int[] DIG2 = {SEG_A, SEG_B, SEG_G, SEG_E, SEG_D};
    private static final int[] DIG3 = {SEG_A, SEG_B, SEG_G, SEG_C, SEG_D};
    private static final int[] DIG4 = {SEG_F, SEG_G, SEG_B, SEG_C};
    private static final int[] DIG5 = {SEG_A, SEG_F, SEG_G, SEG_C, SEG_D};
    private static final int[] DIG6 = {SEG_A, SEG_F, SEG_G, SEG_E, SEG_C, SEG_D};
    private static final int[] DIG7 = {SEG_A, SEG_B, SEG_C};
    private static final int[] DIG8 = {SEG_A, SEG_B, SEG_C, SEG_D, SEG_E, SEG_F, SEG_G};
    private static final int[] DIG9 = {SEG_A, SEG_B, SEG_C, SEG_D, SEG_F, SEG_G};
    private static final int[] LETK = {SEG_F, SEG_E, SEG_G, SEG_B, SEG_C};
    private static final int[] LETH = {SEG_F, SEG_E, SEG_G, SEG_B, SEG_C};
    private static final int[] MINUS = {SEG_G};

    private static final int[] SEG = new int[128];

    static {
        SEG['0'] = mask(DIG0); SEG['1'] = mask(DIG1); SEG['2'] = mask(DIG2);
        SEG['3'] = mask(DIG3); SEG['4'] = mask(DIG4); SEG['5'] = mask(DIG5);
        SEG['6'] = mask(DIG6); SEG['7'] = mask(DIG7); SEG['8'] = mask(DIG8);
        SEG['9'] = mask(DIG9);
        SEG['K'] = mask(LETK); SEG['H'] = mask(LETH); SEG['-'] = mask(MINUS);
        SEG['/'] = 0; SEG['.'] = 0; SEG[' '] = 0; SEG['M'] = 0;
    }

    private static int mask(int[] segs) {
        int m = 0;
        for (int s : segs) m |= s;
        return m;
    }

    private final Paint segPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private String text = "";
    /** 段粗细（像素）。 */
    private float stroke;
    /** 字符宽度（不含段粗细）。 */
    private float glyphWidth;
    private float glyphHeight;
    private float charGap;
    private int digitColor = 0xffffffff;
    private int ghostColor = 0x1affffff;
    private boolean ghost = true;

    // ---- 数字滚动动画 ----
    private float currentNumber = Float.NaN;
    private ValueAnimator animator;

    public LcdTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        segPaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);
        ghostPaint.setStyle(Paint.Style.FILL);
        // 默认尺寸跟 sp 走，和普通 TextView 手感一致
        float sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f,
                getResources().getDisplayMetrics());
        setSize(sp * 7f, sp * 14f, sp * 2.2f, sp * 2.4f);
    }

    /** 设定字形几何：段厚、字宽、字高、字间距（均为像素）。 */
    public void setSize(float strokePx, float glyphWidthPx, float glyphHeightPx, float charGapPx) {
        stroke = Math.max(1f, strokePx);
        glyphWidth = Math.max(stroke * 2f, glyphWidthPx);
        glyphHeight = Math.max(stroke * 2f, glyphHeightPx);
        charGap = Math.max(stroke, charGapPx);
        requestLayout();
        invalidate();
    }

    public void setText(String value) {
        String next = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (next.equals(text)) return;
        text = next;
        requestLayout();
        invalidate();
    }

    public String getText() { return text; }

    /**
     * 数字滚动：从当前显示值向目标值插值（里程表式 count-up/count-down）。
     * 首帧（无当前值）或 animate=false 时直接落到目标值。
     */
    public void setTextNumber(float target, boolean animate) {
        float from = currentNumber;
        if (!animate || Float.isNaN(from)) {
            currentNumber = target;
            applyNumber(target);
            return;
        }
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(from, target);
        // 滚动时长与差值成正比，限制在 120~650ms：跟手但不拖沓
        animator.setDuration(Math.max(120L, Math.min(650L, (long) (Math.abs(target - from) * 9f))));
        animator.addUpdateListener(a -> {
            currentNumber = (float) a.getAnimatedValue();
            applyNumber(Math.round(currentNumber));
        });
        animator.start();
    }

    private void applyNumber(float rounded) {
        String next = String.format(Locale.US, "%.0f", rounded);
        if (next.equals(text)) return;
        text = next;
        requestLayout();
        invalidate();
    }

    /** 点亮颜色（辉光自动跟随）。 */
    public void setDigitColor(int color) {
        digitColor = color;
        invalidate();
    }

    /** 是否绘制未点亮段的鬼影（默认开；小字号建议关）。 */
    public void setGhost(boolean enabled) {
        ghost = enabled;
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int n = Math.max(1, text.length());
        int w = Math.round(n * glyphWidth + (n - 1) * charGap) + getPaddingLeft() + getPaddingRight();
        int h = Math.round(glyphHeight) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(w, widthSpec), resolveSize(h, heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (text.isEmpty()) return;
        float y0 = getPaddingTop();
        float x = getPaddingLeft();
        for (int i = 0; i < text.length(); i++) {
            drawGlyph(canvas, x, y0, SEG[text.charAt(i)]);
            x += glyphWidth + charGap;
        }
    }

    private void drawGlyph(Canvas canvas, float left, float top, int mask) {
        float w = glyphWidth, h = glyphHeight, s = stroke;

        float cx = top + h / 2f;
        float[][] segs = {
                // a: 顶横
                {left + s * .4f, top, left + w - s * .4f, top, left + w - s * 1.2f, top + s, left + s * 1.2f, top + s},
                // b: 右上竖
                {left + w, top + s * .4f, left + w, cx - s * .4f, left + w - s, cx - s * .2f, left + w - s, top + s * 1.2f},
                // c: 右下竖
                {left + w, cx + s * .4f, left + w, top + h - s * .4f, left + w - s, top + h - s * 1.2f, left + w - s, cx + s * .2f},
                // d: 底横
                {left + s * 1.2f, top + h - s, left + w - s * 1.2f, top + h - s, left + w - s * .4f, top + h, left + s * .4f, top + h},
                // e: 左下竖
                {left, cx + s * .4f, left, top + h - s * .4f, left + s, top + h - s * 1.2f, left + s, cx + s * .2f},
                // f: 左上竖
                {left, top + s * .4f, left, cx - s * .4f, left + s, cx - s * .2f, left + s, top + s * 1.2f},
                // g: 中横
                {left + s * .9f, cx, left + w - s * .9f, cx, left + w - s * 1.5f, cx + s, left + s * 1.5f, cx + s},
        };

        if (ghost) {
            ghostPaint.setColor(ghostColor);
            for (int i = 0; i < segs.length; i++) {
                if ((mask & (1 << i)) != 0) continue;
                quad(segs[i]);
                canvas.drawPath(path, ghostPaint);
            }
        }
        for (int i = 0; i < segs.length; i++) {
            if ((mask & (1 << i)) == 0) continue;
            quad(segs[i]);
            glowPaint.setColor(digitColor);
            glowPaint.setAlpha(52);
            canvas.drawPath(path, glowPaint);
            segPaint.setColor(digitColor);
            segPaint.setAlpha(255);
            canvas.drawPath(path, segPaint);
        }
    }

    private void quad(float[] p) {
        path.reset();
        path.moveTo(p[0], p[1]);
        path.lineTo(p[2], p[3]);
        path.lineTo(p[4], p[5]);
        path.lineTo(p[6], p[7]);
        path.close();
    }
}

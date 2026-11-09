package com.baidu.carplayer.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;

import com.baidu.carplayer.R;

/**
 * 给带列表的 AlertDialog 加上与页面一致的斑马纹。
 * 按 ListView 当前可见行的绝对位置着色，滚动/复用后仍正确；保留按压反馈（foreground ripple）。
 */
public final class DialogStripes {
    private DialogStripes() {}

    public static AlertDialog show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.show();
        apply(dialog.getListView());
        return dialog;
    }

    public static void apply(ListView list) {
        if (list == null) return;
        Context ctx = list.getContext();
        // 与“我的播放列表”页的斑马纹同色
        int even = 0xFF1E1E1E;
        int odd = 0xFF2A2A2A;
        list.setDivider(null);
        list.setDividerHeight(0);
        list.getViewTreeObserver().addOnPreDrawListener(() -> {
            int first = list.getFirstVisiblePosition();
            for (int i = 0; i < list.getChildCount(); i++) {
                View row = list.getChildAt(i);
                int color = (first + i) % 2 == 0 ? even : odd;
                Object tag = row.getTag(R.id.dialog_stripe_color);
                if (!Integer.valueOf(color).equals(tag)) {
                    row.setBackgroundColor(color);
                    row.setTag(R.id.dialog_stripe_color, color);
                    if (row.getForeground() == null) row.setForeground(ripple(ctx));
                }
            }
            return true;
        });
    }

    private static android.graphics.drawable.Drawable ripple(Context ctx) {
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId != 0 ? ctx.getDrawable(tv.resourceId) : null;
    }
}

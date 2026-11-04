package com.dongting.player;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 三个界面共用的视觉与控件工具，避免配色、圆角、dp 换算各写一份。 */
final class UiKit {
    static final int BG = 0xFF160E09;
    static final int PANEL = 0xFF26170E;
    static final int TEXT = 0xFFFFF4E2;
    static final int SUBTLE = 0xFFD7B98E;
    static final int ACCENT = 0xFFFFB451;

    private UiKit() {
    }

    static Button button(Context context, String text, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(TEXT);
        button.setTextSize(14);
        button.setBackgroundResource(R.drawable.dt_button);
        button.setPadding(dp(context, 4), 0, dp(context, 4), 0);
        button.setOnClickListener(listener);
        return button;
    }

    /** 主操作按钮（播放、朗读）用强调色，文字改为深色以保证对比度。 */
    static void makePrimary(Button button) {
        if (button == null) return;
        button.setTextColor(BG);
        button.setBackgroundResource(R.drawable.dt_button_primary);
    }

    static TextView label(Context context, String text, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(context, 4), 0, dp(context, 4));
        return view;
    }

    static LinearLayout row(Context context, View... views) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        int height = dp(context, 44);
        for (View view : views) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height, 1f);
            params.setMargins(dp(context, 2), dp(context, 1), dp(context, 2), dp(context, 1));
            row.addView(view, params);
        }
        return row;
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

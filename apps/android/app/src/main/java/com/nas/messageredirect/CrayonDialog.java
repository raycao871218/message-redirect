package com.nas.messageredirect;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One paper-and-wax treatment for every app-owned menu, explanation and confirmation. */
final class CrayonDialog extends Dialog {
    private static final int INK = 0xff3b3a49, BLUE = 0xff4d669d, PAPER = 0xfffffcf4, WAX_BLUE = 0xffbacceb;
    private final Activity activity;
    private final float density;

    private CrayonDialog(Activity activity) {
        super(activity, R.style.CrayonDialogTheme); this.activity = activity;
        density = activity.getResources().getDisplayMetrics().density;
        setOwnerActivity(activity); setCanceledOnTouchOutside(true);
    }

    private int dp(int value) { return Math.round(value * density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.VERTICAL); return view; }

    private void content(Builder builder) {
        setTitle(builder.title);
        ScrollView scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                // The entire sheet can scroll on small screens or with large accessibility fonts.
                int maximum = Math.round(activity.getResources().getDisplayMetrics().heightPixels * .84f);
                int available = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? maximum : Math.min(maximum, MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
            }
        };
        scroll.setBackground(new SketchDrawable(PAPER, BLUE, 2.1f, density));
        scroll.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.setClipToPadding(true); scroll.setVerticalScrollBarEnabled(true);
        if (Build.VERSION.SDK_INT >= 29) scroll.setEdgeEffectColor(WAX_BLUE);
        LinearLayout sheet = column(); scroll.addView(sheet);
        LinearLayout header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL);
        MascotView icon = new MascotView(activity); icon.setMode(builder.mode, false); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = new TextView(activity); title.setText(builder.title); title.setTextSize(21); title.setTextColor(INK); title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(9), 0, 0, 0); title.setLineSpacing(dp(3), 1);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); sheet.addView(header);
        LinearLayout.LayoutParams mark = new LinearLayout.LayoutParams(dp(84), dp(12)); mark.topMargin = dp(6); mark.bottomMargin = dp(12);
        sheet.addView(new CrayonAccent(activity), mark);
        if (builder.message != null) {
            TextView message = new TextView(activity); message.setText(builder.message); message.setTextColor(INK); message.setTextSize(15);
            message.setLineSpacing(dp(4), 1); message.setPadding(dp(2), 0, dp(2), dp(8)); sheet.addView(message);
        }
        if (builder.items != null) {
            for (int i = 0; i < builder.items.length; i++) {
                final int which = i;
                Button item = button(sheet, builder.items[i], false, () -> {
                    dismiss(); if (builder.itemClick != null) builder.itemClick.onClick(this, which);
                });
                item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            }
            View gap = new View(activity); sheet.addView(gap, new LinearLayout.LayoutParams(1, dp(8)));
        }
        action(sheet, builder.positive, true, DialogInterface.BUTTON_POSITIVE, builder.positiveClick);
        action(sheet, builder.neutral, false, DialogInterface.BUTTON_NEUTRAL, builder.neutralClick);
        action(sheet, builder.negative, false, DialogInterface.BUTTON_NEGATIVE, builder.negativeClick);
        setContentView(scroll, new ViewGroup.LayoutParams(-1, -2));
    }

    private void action(LinearLayout parent, String text, boolean primary, int which, DialogInterface.OnClickListener listener) {
        if (text == null) return;
        button(parent, text, primary, () -> { dismiss(); if (listener != null) listener.onClick(this, which); });
    }

    private Button button(LinearLayout parent, String text, boolean primary, Runnable action) {
        Button button = new Button(activity); button.setText(text); button.setAllCaps(false); button.setTextSize(15); button.setTextColor(INK);
        button.setTypeface(null, primary ? Typeface.BOLD : Typeface.NORMAL); button.setMinHeight(dp(50)); button.setMinimumWidth(0);
        button.setPadding(dp(15), dp(12), dp(15), dp(12)); button.setElevation(0); button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x20677ea9), new SketchDrawable(primary ? WAX_BLUE : PAPER, primary ? BLUE : 0xff69677a, primary ? 2.4f : 1.5f, density), null));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(8); parent.addView(button, layout); return button;
    }

    interface OnShown { void shown(CrayonDialog dialog); }
    static final class Builder {
        private final Activity activity;
        private String title = "", message, positive, neutral, negative;
        private String mode = SyncConfig.EMAIL;
        private String[] items;
        private DialogInterface.OnClickListener itemClick, positiveClick, neutralClick, negativeClick;
        private OnShown onShown;
        Builder(Activity activity) { this.activity = activity; }
        Builder setMode(String mode) { this.mode = mode; return this; }
        Builder onShown(OnShown listener) { onShown = listener; return this; }
        Builder setTitle(String title) { this.title = title; return this; }
        Builder setMessage(String message) { this.message = message; return this; }
        Builder setItems(String[] items, DialogInterface.OnClickListener listener) { this.items = items.clone(); itemClick = listener; return this; }
        Builder setPositiveButton(String text, DialogInterface.OnClickListener listener) { positive = text; positiveClick = listener; return this; }
        Builder setNeutralButton(String text, DialogInterface.OnClickListener listener) { neutral = text; neutralClick = listener; return this; }
        Builder setNegativeButton(String text, DialogInterface.OnClickListener listener) { negative = text; negativeClick = listener; return this; }
        CrayonDialog show() {
            CrayonDialog dialog = new CrayonDialog(activity); dialog.content(this); dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                int available = activity.getResources().getDisplayMetrics().widthPixels - dialog.dp(32);
                window.setLayout(Math.min(dialog.dp(420), available), ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setGravity(Gravity.CENTER); window.setDimAmount(.28f);
            }
            if (onShown != null) onShown.shown(dialog);
            return dialog;
        }
    }
}

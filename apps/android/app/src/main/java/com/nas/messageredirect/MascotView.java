package com.nas.messageredirect;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;

/** A costume derived from the displayed delivery mode; never changes saved routing settings. */
final class MascotView extends ImageView {
    private String mode;
    MascotView(Context context) { super(context); setScaleType(ScaleType.FIT_CENTER); }
    String mode() { return mode; }
    void setMode(String selectedMode, boolean animateChange) {
        String next = SyncConfig.DATABASE.equals(selectedMode) ? SyncConfig.DATABASE : SyncConfig.EMAIL;
        if (next.equals(mode)) return;
        mode = next; animate().cancel();
        boolean cyber = SyncConfig.DATABASE.equals(mode);
        setImageResource(cyber ? R.drawable.messenger_mascot_cyber : R.drawable.messenger_mascot);
        setContentDescription(cyber ? getContext().getString(R.string.cyber_dove_self_hosted_archive) : getContext().getString(R.string.spotted_dove_email_forwarding));
        setScaleX(1); setScaleY(1); setRotation(0);
        if (animateChange && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())) {
            setScaleX(.88f); setScaleY(.88f); setRotation(cyber ? -5 : 5);
            animate().scaleX(1).scaleY(1).rotation(0).setDuration(220).setInterpolator(new OvershootInterpolator(.8f)).start();
        }
    }
    @Override protected void onDetachedFromWindow() { animate().cancel(); super.onDetachedFromWindow(); }
}

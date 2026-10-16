package com.nas.messageredirect;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A small wax underline, kept outside the text's layout and accessibility tree. */
final class CrayonAccent extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final float density;
    CrayonAccent(Context context) {
        super(context); density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setShader(CrayonTexture.shader(0xffdf997e, density, true));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(); line.reset(); line.moveTo(4 * density, 6 * density);
        line.cubicTo(width * .3f, 2 * density, width * .64f, 8 * density, width - 4 * density, 4 * density);
        paint.setStrokeWidth(4 * density); paint.setAlpha(210); canvas.drawPath(line, paint);
        canvas.save(); canvas.translate(2 * density, 2 * density);
        paint.setStrokeWidth(density); paint.setAlpha(180); canvas.drawPath(line, paint); canvas.restore();
    }
}

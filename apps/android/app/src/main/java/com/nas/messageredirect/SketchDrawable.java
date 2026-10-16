package com.nas.messageredirect;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.PathMeasure;
import android.graphics.drawable.Drawable;
import java.util.Random;

/** Wax pigment on paper with several imperfect pencil-like passes around the edge. */
final class SketchDrawable extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path[] scratches = {new Path(), new Path(), new Path()};
    private final float density;
    private int alpha = 255;
    SketchDrawable(int background, int outline, float width, float density) {
        this.density = density; fill.setColor(background); fill.setStyle(Paint.Style.FILL); fill.setShader(CrayonTexture.shader(background, density, true));
        ink.setColor(outline); ink.setShader(CrayonTexture.shader(outline, density, true)); ink.setStyle(Paint.Style.STROKE); ink.setStrokeWidth(width * density); ink.setStrokeJoin(Paint.Join.ROUND); ink.setStrokeCap(Paint.Cap.ROUND);
    }
    @Override protected void onBoundsChange(Rect bounds) {
        float inset = ink.getStrokeWidth() / 2 + 2 * density;
        float left = bounds.left + inset, top = bounds.top + inset, right = bounds.right - inset, bottom = bounds.bottom - inset;
        float radius = Math.min(16 * density, Math.min(right-left, bottom-top)/3), wobble = 2 * density;
        path.reset(); path.moveTo(left+radius, top+wobble);
        path.cubicTo(left+(right-left)*.4f, top-wobble, right-radius*2, top+wobble, right-radius, top);
        path.quadTo(right, top, right, top+radius);
        path.cubicTo(right-wobble, top+(bottom-top)*.4f, right+wobble, bottom-radius*2, right, bottom-radius);
        path.quadTo(right, bottom, right-radius, bottom);
        path.cubicTo(right-(right-left)*.4f, bottom+wobble, left+radius*2, bottom-wobble, left+radius, bottom);
        path.quadTo(left, bottom, left, bottom-radius);
        path.cubicTo(left+wobble, bottom-(bottom-top)*.4f, left-wobble, top+radius*2, left, top+radius);
        path.quadTo(left, top, left+radius, top+wobble); path.close();
        PathMeasure measure = new PathMeasure(path, true); float length = measure.getLength();
        float[] point = new float[2], tangent = new float[2]; Random random = new Random(81);
        for (Path scratch : scratches) {
            scratch.reset();
            int samples = Math.max(1, (int) (length / (5 * density)));
            for (int i = 0; i <= samples; i++) {
                measure.getPosTan(length * i / samples, point, tangent);
                float shift = (random.nextFloat() - .5f) * 2.3f * density;
                float x = point[0] - tangent[1] * shift, y = point[1] + tangent[0] * shift;
                if (i == 0) scratch.moveTo(x, y); else scratch.lineTo(x, y);
            }
            scratch.close();
        }
    }
    @Override public void draw(Canvas canvas) {
        canvas.drawPath(path, fill);
        float width = ink.getStrokeWidth(); ink.setAlpha(alpha * 2 / 3); canvas.drawPath(path, ink);
        ink.setStrokeWidth(Math.max(.45f * density, width * .35f)); ink.setAlpha(alpha * 3 / 4);
        for (Path scratch : scratches) canvas.drawPath(scratch, ink);
        ink.setStrokeWidth(width);
    }
    @Override public void setAlpha(int alpha) { this.alpha = alpha; fill.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { fill.setColorFilter(filter); ink.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

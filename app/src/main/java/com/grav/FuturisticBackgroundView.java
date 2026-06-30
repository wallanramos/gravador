package com.grav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

public class FuturisticBackgroundView extends View {

    private Paint blobPaint;
    private Path blobPath;
    private float phase = 0;
    private boolean isAnimating = false;

    private float[] blobX = {0.2f, 0.8f, 0.5f, 0.1f, 0.9f, 0.5f, 0.3f, 0.7f};
    private float[] blobY = {0.1f, 0.3f, 0.5f, 0.6f, 0.7f, 0.9f, 0.8f, 0.15f};
    private float[] blobSize = {0.7f, 0.65f, 0.8f, 0.6f, 0.7f, 0.65f, 0.55f, 0.6f};
    private int[] colors = {0xFF6366F1, 0xFF8B5CF6, 0xFFA855F7, 0xFF3B82F6, 0xFF7C3AED, 0xFF6366F1, 0xFF8B5CF6, 0xFF3B82F6};

    public FuturisticBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        blobPath = new Path();
    }

    public void startAnimation() {
        isAnimating = true;
        invalidate();
    }

    public void stopAnimation() {
        isAnimating = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        canvas.drawColor(0xFF0F0F1A);

        if (!isAnimating) return;

        for (int i = 0; i < 8; i++) {
            float cx = w * (blobX[i] + (float) Math.sin(phase * 2 + i * 1.5) * 0.1f);
            float cy = h * (blobY[i] + (float) Math.cos(phase * 1.5 + i * 1.2) * 0.08f);
            float size = Math.min(w, h) * blobSize[i];

            RadialGradient gradient = new RadialGradient(cx, cy, size,
                    new int[]{colors[i], colors[i] & 0x80FFFFFF, 0x00000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            blobPaint.setShader(gradient);

            blobPath.reset();
            for (int j = 0; j <= 360; j += 10) {
                double angle = Math.toRadians(j);
                float deform = 1f + 0.3f * (float) Math.sin(angle * 3 + phase * 3 + i);
                float r = size * deform;
                float x = cx + r * (float) Math.cos(angle);
                float y = cy + r * (float) Math.sin(angle);
                if (j == 0) blobPath.moveTo(x, y);
                else blobPath.lineTo(x, y);
            }
            blobPath.close();
            canvas.drawPath(blobPath, blobPaint);
        }

        phase += 0.015f;
        if (isAnimating) postInvalidateDelayed(16);
    }
}

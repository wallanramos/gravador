package com.grav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class WaveformView extends View {

    private Paint paint;
    private float[] amplitudes = new float[60];
    private int index = 0;

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint = new Paint();
        paint.setColor(0xFFF44336);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void addAmplitude(float amplitude) {
        amplitudes[index] = amplitude;
        index = (index + 1) % amplitudes.length;
        invalidate();
    }

    public void clear() {
        amplitudes = new float[60];
        index = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float barWidth = width / amplitudes.length;
        float centerY = height / 2;

        paint.setStrokeWidth(barWidth * 0.6f);

        for (int i = 0; i < amplitudes.length; i++) {
            int drawIndex = (index + i) % amplitudes.length;
            float barHeight = amplitudes[drawIndex] * height * 0.8f;
            float x = i * barWidth + barWidth / 2;
            canvas.drawLine(x, centerY - barHeight / 2, x, centerY + barHeight / 2, paint);
        }
    }
}

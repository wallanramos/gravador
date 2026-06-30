package com.grav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

public class RtaView extends View {

    // Frequências de referência para labels (escala log 20-20k)
    private static final float FREQ_MIN = 20f;
    private static final float FREQ_MAX = 20000f;
    private static final float[] LABEL_FREQS  = {20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000};
    private static final String[] LABEL_NAMES = {"20", "50", "100", "200", "500", "1k", "2k", "5k", "10k", "20k"};
    private static final int[] DB_LINES = {0, -10, -20, -30, -40, -50, -60, -70};

    // Número de barras de visualização (resolução visual)
    private static final int BAR_COUNT = 120;

    private float[] barMagnitudes = new float[BAR_COUNT]; // suavizado
    private float[] rawMagnitudes = new float[BAR_COUNT];
    private float[] peaks         = new float[BAR_COUNT];
    private long[]  peakTime      = new long[BAR_COUNT];
    private static final long PEAK_HOLD_MS = 1500;

    private Paint barPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint gridPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint zeroPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint textPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint peakPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    // recebido do Activity
    private float[] fftMag;
    private int     sampleRate;
    private int     fftSize;

    public RtaView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }
    public RtaView(Context ctx)                     { super(ctx);        init(); }

    private void init() {
        gridPaint.setColor(0x22FFFFFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);

        zeroPaint.setColor(0x55FFFFFF);
        zeroPaint.setStyle(Paint.Style.STROKE);
        zeroPaint.setStrokeWidth(1.5f);

        textPaint.setColor(0x66FFFFFF);
        textPaint.setTextSize(20f);
        textPaint.setTextAlign(Paint.Align.RIGHT);

        labelPaint.setColor(0x88FFFFFF);
        labelPaint.setTextSize(20f);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        peakPaint.setColor(0xFFFFFFFF);
        peakPaint.setStyle(Paint.Style.FILL);
        peakPaint.setAntiAlias(true);
    }

    /** Chamado pelo Activity a cada frame FFT */
    public void setMagnitudes(float[] mag, int sampleRate, int fftSize) {
        this.fftMag    = mag;
        this.sampleRate = sampleRate;
        this.fftSize    = fftSize;
        processBars();
        invalidate();
    }

    /** Mapeia os bins FFT para BAR_COUNT barras em escala log */
    private void processBars() {
        if (fftMag == null) return;
        float binWidth = sampleRate / (float) fftSize;
        long now = System.currentTimeMillis();

        for (int b = 0; b < BAR_COUNT; b++) {
            // Frequência central desta barra (log entre FREQ_MIN e FREQ_MAX)
            float t = b / (float)(BAR_COUNT - 1);
            float freqCenter = (float)(FREQ_MIN * Math.pow(FREQ_MAX / FREQ_MIN, t));

            // Borda da barra: raiz da razão entre barras vizinhas
            float freqLo = b == 0 ? FREQ_MIN
                : (float)(FREQ_MIN * Math.pow(FREQ_MAX / FREQ_MIN, (b - 0.5f) / (BAR_COUNT - 1)));
            float freqHi = b == BAR_COUNT-1 ? FREQ_MAX
                : (float)(FREQ_MIN * Math.pow(FREQ_MAX / FREQ_MIN, (b + 0.5f) / (BAR_COUNT - 1)));

            int binLo = Math.max(1, (int)(freqLo / binWidth));
            int binHi = Math.min(fftMag.length - 1, (int)(freqHi / binWidth));
            if (binHi < binLo) binHi = binLo;

            // RMS dos bins nesta banda
            float sum = 0;
            int   cnt = 0;
            for (int i = binLo; i <= binHi; i++) {
                sum += fftMag[i] * fftMag[i];
                cnt++;
            }
            float rms = cnt > 0 ? (float) Math.sqrt(sum / cnt) : 0f;

            // dB → normalizado 0..1  (range -90..0 dB)
            float db  = rms > 0 ? (float)(20.0 * Math.log10(rms + 1e-10)) : -90f;
            float norm = Math.max(0f, Math.min(1f, (db + 90f) / 90f));

            rawMagnitudes[b] = norm;

            // Suavização exponencial (rápido subindo, lento descendo)
            float alpha = norm > barMagnitudes[b] ? 0.6f : 0.15f;
            barMagnitudes[b] = barMagnitudes[b] * (1f - alpha) + norm * alpha;

            // Picos
            if (barMagnitudes[b] >= peaks[b]) {
                peaks[b]   = barMagnitudes[b];
                peakTime[b] = now;
            } else if (now - peakTime[b] > PEAK_HOLD_MS) {
                peaks[b] = Math.max(0f, peaks[b] - 0.004f);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth(), h = getHeight();
        float padL = 52f, padR = 8f, padT = 12f, padB = 36f;
        float cW = w - padL - padR;
        float cH = h - padT - padB;

        // --- Grid horizontal (dB) ---
        for (int db : DB_LINES) {
            float frac = (db + 90f) / 90f;
            float y = padT + cH * (1f - frac);
            canvas.drawLine(padL, y, padL + cW, y, db == 0 ? zeroPaint : gridPaint);
            canvas.drawText(db + "dB", padL - 4f, y + 7f, textPaint);
        }

        // --- Grid vertical (frequências) ---
        for (int fi = 0; fi < LABEL_FREQS.length; fi++) {
            float freq = LABEL_FREQS[fi];
            float xFrac = (float)(Math.log(freq / FREQ_MIN) / Math.log(FREQ_MAX / FREQ_MIN));
            float x = padL + xFrac * cW;
            canvas.drawLine(x, padT, x, padT + cH, gridPaint);
            canvas.drawText(LABEL_NAMES[fi], x, h - padB + 24f, labelPaint);
        }

        // --- Curva de espectro ---
        float[] px = new float[BAR_COUNT];
        float[] py = new float[BAR_COUNT];
        for (int b = 0; b < BAR_COUNT; b++) {
            px[b] = padL + b * (cW / (BAR_COUNT - 1));
            py[b] = padT + cH * (1f - barMagnitudes[b]);
        }

        // Area preenchida abaixo da curva
        Path fillPath = new Path();
        fillPath.moveTo(px[0], padT + cH);
        fillPath.lineTo(px[0], py[0]);
        for (int b = 1; b < BAR_COUNT; b++) {
            float cpX = (px[b - 1] + px[b]) / 2f;
            fillPath.cubicTo(cpX, py[b - 1], cpX, py[b], px[b], py[b]);
        }
        fillPath.lineTo(px[BAR_COUNT - 1], padT + cH);
        fillPath.close();

        LinearGradient fillGrad = new LinearGradient(
            0, padT, 0, padT + cH,
            new int[]{0x88FF1744, 0x55FFD600, 0x1100C853},
            new float[]{0f, 0.35f, 1f},
            Shader.TileMode.CLAMP
        );
        barPaint.setShader(fillGrad);
        barPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(fillPath, barPaint);

        // Linha da curva por cima
        Path linePath = new Path();
        linePath.moveTo(px[0], py[0]);
        for (int b = 1; b < BAR_COUNT; b++) {
            float cpX = (px[b - 1] + px[b]) / 2f;
            linePath.cubicTo(cpX, py[b - 1], cpX, py[b], px[b], py[b]);
        }
        barPaint.setShader(null);
        barPaint.setColor(0xFFFFFFFF);
        barPaint.setStyle(Paint.Style.STROKE);
        barPaint.setStrokeWidth(2.5f);
        barPaint.setStrokeCap(Paint.Cap.ROUND);
        barPaint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(linePath, barPaint);
        barPaint.setStyle(Paint.Style.FILL);

        postInvalidateDelayed(33); // ~30fps
    }
}

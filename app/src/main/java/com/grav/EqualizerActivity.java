package com.grav;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.graphics.Color;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

public class EqualizerActivity extends AppCompatActivity {

    private static final int SAMPLE_RATE = 48000;
    private static final int FFT_SIZE = 8192;

    private RtaView rtaView;
    private AudioRecord audioRecord;
    private Thread captureThread;
    private boolean isRunning = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    private short[] fftAccum = new short[FFT_SIZE];
    private int fftAccumPos = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        setContentView(R.layout.activity_equalizer);
        rtaView = findViewById(R.id.rtaView);
        startCapture();
    }

    private void startCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 2, FFT_SIZE * 2));

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            return;
        }

        audioRecord.startRecording();
        isRunning = true;
        fftAccumPos = 0;

        captureThread = new Thread(() -> {
            short[] buf = new short[FFT_SIZE / 4];
            while (isRunning) {
                int read = audioRecord.read(buf, 0, buf.length);
                if (read > 0) {
                    for (int i = 0; i < read && fftAccumPos < FFT_SIZE; i++) {
                        fftAccum[fftAccumPos++] = buf[i];
                    }
                    if (fftAccumPos >= FFT_SIZE) {
                        float[] mag = computeFFTMagnitudes(fftAccum);
                        handler.post(() -> rtaView.setMagnitudes(mag, SAMPLE_RATE, FFT_SIZE));
                        // overlap 75%
                        int keep = FFT_SIZE * 3 / 4;
                        System.arraycopy(fftAccum, FFT_SIZE / 4, fftAccum, 0, keep);
                        fftAccumPos = keep;
                    }
                }
            }
        });
        captureThread.start();
    }

    private float[] computeFFTMagnitudes(short[] samples) {
        int n = FFT_SIZE;
        float[] real = new float[n];
        float[] imag = new float[n];

        for (int i = 0; i < n; i++) {
            float w = 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * i / (n - 1)));
            real[i] = samples[i] / 32768f * w;
        }

        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) {
            int rev = Integer.reverse(i) >>> (32 - bits);
            if (rev > i) {
                float tmp = real[i]; real[i] = real[rev]; real[rev] = tmp;
            }
        }

        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            float wR = (float) Math.cos(ang), wI = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float curR = 1f, curI = 0f;
                for (int j = 0; j < len / 2; j++) {
                    float uR = real[i+j], uI = imag[i+j];
                    float vR = real[i+j+len/2]*curR - imag[i+j+len/2]*curI;
                    float vI = real[i+j+len/2]*curI + imag[i+j+len/2]*curR;
                    real[i+j] = uR+vR; imag[i+j] = uI+vI;
                    real[i+j+len/2] = uR-vR; imag[i+j+len/2] = uI-vI;
                    float nr = curR*wR - curI*wI;
                    curI = curR*wI + curI*wR;
                    curR = nr;
                }
            }
        }

        float[] mag = new float[n / 2];
        for (int i = 1; i < n / 2; i++) {
            mag[i] = (float) Math.sqrt(real[i]*real[i] + imag[i]*imag[i]);
        }
        return mag;
    }

    private void stopCapture() {
        isRunning = false;
        if (captureThread != null) {
            try { captureThread.join(400); } catch (InterruptedException ignored) {}
            captureThread = null;
        }
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
    }

    @Override protected void onResume()  { super.onResume();  if (!isRunning) startCapture(); }
    @Override protected void onPause()   { super.onPause();   stopCapture(); }
}

package com.grav;

import android.content.Intent;
import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.graphics.Color;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final int BLUETOOTH_PERMISSION_REQUEST_CODE = 2;
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean isRecording = false;
    private boolean isMonitoring = false;
    private Thread recordingThread;
    private Thread monitorThread;

    // Dispositivo de saída escolhido pelo usuário (null = deixa o sistema escolher)
    private AudioDeviceInfo selectedOutputDevice = null;

    private float gainMultiplier = 1.0f;

    private Button btnRecord, btnMonitor, btnEq, btnOutputDevice;
    private SeekBar seekBarGain;
    private TextView tvStatus, tvTimer, tvGainValue;
    private WaveformView waveformView;
    private FuturisticBackgroundView backgroundView;
    private RecyclerView rvRecordings;
    private RecordingsAdapter recordingsAdapter;
    private List<File> recordingsList = new ArrayList<>();
    private Handler waveformHandler = new Handler(Looper.getMainLooper());

    private Handler timerHandler = new Handler(Looper.getMainLooper());
    private int seconds = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        setContentView(R.layout.activity_main);

        btnRecord = findViewById(R.id.btnRecord);
        btnMonitor = findViewById(R.id.btnMonitor);
        tvStatus = findViewById(R.id.tvStatus);
        tvTimer = findViewById(R.id.tvTimer);
        seekBarGain = findViewById(R.id.seekBarGain);
        tvGainValue = findViewById(R.id.tvGainValue);

        seekBarGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Range: 0–400 → 0.0x–4.0x, default 100 = 1.0x
                gainMultiplier = progress / 100f;
                tvGainValue.setText(String.format(Locale.getDefault(), "%.1fx", gainMultiplier));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        waveformView = findViewById(R.id.waveformView);
        backgroundView = findViewById(R.id.backgroundView);
        backgroundView.startAnimation();

        rvRecordings = findViewById(R.id.rvRecordings);
        rvRecordings.setLayoutManager(new LinearLayoutManager(this));
        recordingsAdapter = new RecordingsAdapter(recordingsList, () -> {}, waveformView);
        rvRecordings.setAdapter(recordingsAdapter);
        loadRecordings();

        btnRecord.setOnClickListener(v -> {
            if (!isRecording) {
                if (checkPermissions()) {
                    startRecording();
                } else {
                    requestPermissions();
                }
            } else {
                stopRecording();
            }
        });

        btnMonitor.setOnClickListener(v -> toggleMonitor());

        btnEq = findViewById(R.id.btnEq);
        btnEq.setOnClickListener(v -> startActivity(new Intent(this, EqualizerActivity.class)));

        btnOutputDevice = findViewById(R.id.btnOutputDevice);
        btnOutputDevice.setOnClickListener(v -> openOutputDevicePicker());
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
    }

    // No Android 12+ (API 31+), ler o nome de dispositivos Bluetooth pareados exige BLUETOOTH_CONNECT
    private boolean checkBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestBluetoothPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            } else {
                Toast.makeText(this, "Permissão de áudio necessária", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == BLUETOOTH_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openOutputDevicePicker();
            } else {
                Toast.makeText(this, "Permissão de Bluetooth necessária para listar os dispositivos", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // Abre a lista de dispositivos de saída disponíveis (alto-falante, fone com fio, Bluetooth conectado, etc.)
    private void openOutputDevicePicker() {
        if (!checkBluetoothPermission()) {
            requestBluetoothPermission();
            return;
        }

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);

        List<AudioDeviceInfo> available = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        // Primeira opção: deixa o Android escolher automaticamente
        available.add(null);
        labels.add("Padrão (automático)");

        for (AudioDeviceInfo device : devices) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                    available.add(device);
                    labels.add(getDeviceLabel(device));
                    break;
                default:
                    break;
            }
        }

        if (available.size() == 1) {
            Toast.makeText(this, "Nenhum dispositivo Bluetooth/fone conectado encontrado", Toast.LENGTH_SHORT).show();
        }

        String[] items = labels.toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle("Dispositivo de saída")
                .setItems(items, (dialog, which) -> {
                    selectedOutputDevice = available.get(which);
                    btnOutputDevice.setText(items[which]);

                    // Se o monitor já estiver tocando, aplica a troca imediatamente
                    if (audioTrack != null) {
                        audioTrack.setPreferredDevice(selectedOutputDevice);
                    }
                })
                .show();
    }

    // Monta um nome amigável pro dispositivo (tipo + nome do produto, quando disponível)
    private String getDeviceLabel(AudioDeviceInfo device) {
        String typeName;
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                typeName = "Bluetooth (chamada)";
                break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                typeName = "Bluetooth";
                break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                typeName = "Fone com fio";
                break;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                typeName = "Alto-falante";
                break;
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                typeName = "Fone de ouvido (chamada)";
                break;
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                typeName = "USB";
                break;
            default:
                typeName = "Dispositivo";
        }

        CharSequence productName = device.getProductName();
        if (productName != null && productName.length() > 0 && !"Android".contentEquals(productName)) {
            return typeName + " — " + productName;
        }
        return typeName;
    }

    private void startRecording() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
        );

        audioRecord.startRecording();
        isRecording = true;

        btnRecord.setText("STOP");
        btnRecord.setBackgroundResource(R.drawable.btn_record_active);
        tvStatus.setText("Gravando...");

        seconds = 0;
        startTimer();

        final int finalBufferSize = bufferSize;
        recordingThread = new Thread(() -> writeWavFile(finalBufferSize));
        recordingThread.start();

        // Se o monitoramento estiver ativo, reinicia para usar o novo audioRecord
        if (isMonitoring) {
            stopMonitor();
            startMonitor();
        }
    }

    private void stopRecording() {
        isRecording = false;

        // Para o monitoramento se estiver ativo, pois ele usa o mesmo audioRecord
        if (isMonitoring) {
            stopMonitor();
        }

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }

        btnRecord.setText("REC");
        btnRecord.setBackgroundResource(R.drawable.btn_record);
        tvStatus.setText("Salvo em Music/");
        tvTimer.setText("00:00");

        stopTimer();
        waveformView.clear();
        loadRecordings();
    }

    private void startTimer() {
        timerHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isRecording) {
                    int mins = seconds / 60;
                    int secs = seconds % 60;
                    tvTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", mins, secs));
                    seconds++;
                    timerHandler.postDelayed(this, 1000);
                }
            }
        });
    }

    private void toggleMonitor() {
        if (!checkPermissions()) {
            requestPermissions();
            return;
        }

        if (!isMonitoring) {
            startMonitor();
        } else {
            stopMonitor();
        }
    }

    private void startMonitor() {
        // Buffer mínimo para o AudioRecord — não pode ser menor, senão dropa amostras
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

        // Cria um AudioRecord dedicado só para monitoração se não estiver gravando
        if (audioRecord == null) {
            // AudioRecord com UNPROCESSED — sem nenhum processamento do sistema
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.UNPROCESSED,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minBuffer
            );
            audioRecord.startRecording();
        }

        // Modo MEDIA: usa caminho de hardware dedicado a mídia,
        // ideal para reprodução de áudio com maior qualidade
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.setMode(AudioManager.MODE_NORMAL);

        // AudioTrack com buffer reduzido à metade — drena mais rápido, menos delay de saída
        // PERFORMANCE_MODE_LOW_LATENCY: pipeline direto, sem mixer do sistema
        int trackBufferSize = minBuffer / 2;

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        AudioFormat audioFormat = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AUDIO_FORMAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(trackBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();

        // Roteia para o dispositivo escolhido pelo usuário, se houver um selecionado
        if (selectedOutputDevice != null) {
            audioTrack.setPreferredDevice(selectedOutputDevice);
        }

        audioTrack.play();
        isMonitoring = true;

        btnMonitor.setSelected(true);
        btnMonitor.setBackgroundResource(R.drawable.btn_monitor_active);

        final int finalBuffer = minBuffer;
        monitorThread = new Thread(() -> {
            // Prioridade máxima de thread — reduz jitter e glitches
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            byte[] buffer = new byte[finalBuffer];
            while (isMonitoring) {
                // READ_NON_BLOCKING: não espera o buffer encher — envia assim que tiver dados
                int read = audioRecord.read(buffer, 0, finalBuffer, AudioRecord.READ_NON_BLOCKING);
                if (read > 0) {
                    applyGain(buffer, read);
                    // WRITE_NON_BLOCKING: não bloqueia a thread na escrita, mantém o loop fluindo
                    audioTrack.write(buffer, 0, read, AudioTrack.WRITE_NON_BLOCKING);
                }
            }
        });
        monitorThread.start();
    }

    private void stopMonitor() {
        isMonitoring = false;

        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }

        // Restaura modo de áudio normal ao encerrar monitoração
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.setMode(AudioManager.MODE_NORMAL);

        btnMonitor.setSelected(false);
        btnMonitor.setBackgroundResource(R.drawable.btn_monitor);
    }

    private void stopTimer() {
        timerHandler.removeCallbacksAndMessages(null);
    }

    private void loadRecordings() {
        recordingsList.clear();
        File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        if (musicDir.exists()) {
            File[] files = musicDir.listFiles((dir, name) -> name.startsWith("REC_") && name.endsWith(".wav"));
            if (files != null) {
                Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                recordingsList.addAll(Arrays.asList(files));
            }
        }
        recordingsAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recordingsAdapter != null) recordingsAdapter.release();
    }

    private void writeWavFile(int bufferSize) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        if (!musicDir.exists()) musicDir.mkdirs();

        File wavFile = new File(musicDir, "REC_" + timestamp + ".wav");
        File tempFile = new File(getCacheDir(), "temp_audio.pcm");

        try (FileOutputStream tempOut = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[bufferSize];
            while (isRecording) {
                int read = audioRecord.read(buffer, 0, bufferSize);
                if (read > 0) {
                    // Calcula amplitude antes de qualquer processamento
                    float amplitude = calculateAmplitude(buffer, read);
                    // Grava o áudio puro, sem aplicar ganho
                    tempOut.write(buffer, 0, read);
                    waveformHandler.post(() -> waveformView.addAmplitude(amplitude));
                }
                
                // Se o monitoramento estiver ativo, lê novamente para enviar ao AudioTrack
                if (isMonitoring && audioTrack != null) {
                    byte[] monitorBuffer = new byte[bufferSize];
                    int monitorRead = audioRecord.read(monitorBuffer, 0, bufferSize);
                    if (monitorRead > 0) {
                        applyGain(monitorBuffer, monitorRead);
                        audioTrack.write(monitorBuffer, 0, monitorRead, AudioTrack.WRITE_NON_BLOCKING);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        convertPcmToWav(tempFile, wavFile);
        tempFile.delete();
    }

    private void convertPcmToWav(File pcmFile, File wavFile) {
        try (FileOutputStream wavOut = new FileOutputStream(wavFile);
             FileInputStream pcmIn = new FileInputStream(pcmFile)) {

            long pcmSize = pcmFile.length();
            int channels = 1;
            int bitsPerSample = 16;
            long byteRate = SAMPLE_RATE * channels * bitsPerSample / 8;

            wavOut.write(new byte[]{'R', 'I', 'F', 'F'});
            wavOut.write(intToBytes((int) (36 + pcmSize)));
            wavOut.write(new byte[]{'W', 'A', 'V', 'E'});
            wavOut.write(new byte[]{'f', 'm', 't', ' '});
            wavOut.write(intToBytes(16));
            wavOut.write(shortToBytes((short) 1));
            wavOut.write(shortToBytes((short) channels));
            wavOut.write(intToBytes(SAMPLE_RATE));
            wavOut.write(intToBytes((int) byteRate));
            wavOut.write(shortToBytes((short) (channels * bitsPerSample / 8)));
            wavOut.write(shortToBytes((short) bitsPerSample));
            wavOut.write(new byte[]{'d', 'a', 't', 'a'});
            wavOut.write(intToBytes((int) pcmSize));

            byte[] buffer = new byte[4096];
            int read;
            while ((read = pcmIn.read(buffer)) != -1) wavOut.write(buffer, 0, read);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private byte[] intToBytes(int value) {
        return new byte[]{(byte) (value & 0xff), (byte) ((value >> 8) & 0xff), (byte) ((value >> 16) & 0xff), (byte) ((value >> 24) & 0xff)};
    }

    private byte[] shortToBytes(short value) {
        return new byte[]{(byte) (value & 0xff), (byte) ((value >> 8) & 0xff)};
    }

    private void applyGain(byte[] buffer, int length) {
        if (gainMultiplier == 1.0f) return;
        for (int i = 0; i < length - 1; i += 2) {
            short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
            float amplified = sample * gainMultiplier;
            // clamp to 16-bit range
            if (amplified > 32767f) amplified = 32767f;
            else if (amplified < -32768f) amplified = -32768f;
            short out = (short) amplified;
            buffer[i]     = (byte) (out & 0xFF);
            buffer[i + 1] = (byte) ((out >> 8) & 0xFF);
        }
    }

    private float calculateAmplitude(byte[] buffer, int read) {
        long sum = 0;
        int count = 0;
        for (int i = 0; i < read - 1; i += 2) {
            short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
            sum += (long) sample * sample;
            count++;
        }
        if (count == 0) return 0f;
        return (float) Math.sqrt((double) sum / count) / 32768f;
    }
}
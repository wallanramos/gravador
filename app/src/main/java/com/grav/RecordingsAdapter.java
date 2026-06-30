package com.grav;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.ViewHolder> {

    private List<File> recordings;
    private MediaPlayer mediaPlayer;
    private Visualizer visualizer;
    private int playingPosition = -1;
    private OnDeleteListener onDeleteListener;
    private WaveformView waveformView;
    private Handler handler = new Handler(Looper.getMainLooper());

    public interface OnDeleteListener {
        void onDeleted();
    }

    public RecordingsAdapter(List<File> recordings, OnDeleteListener listener, WaveformView waveformView) {
        this.recordings = recordings;
        this.onDeleteListener = listener;
        this.waveformView = waveformView;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recording, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File file = recordings.get(position);
        holder.tvFileName.setText(file.getName());
        
        String size = formatSize(file.length());
        String date = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date(file.lastModified()));
        holder.tvFileInfo.setText(size + " • " + date);

        holder.btnPlay.setImageResource(playingPosition == position ? 
            android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);

        holder.btnPlay.setOnClickListener(v -> togglePlay(file, position));
        holder.btnDelete.setOnClickListener(v -> confirmDelete(v, file, position));
    }

    private void confirmDelete(View view, File file, int position) {
        Dialog dialog = new Dialog(view.getContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_delete);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        TextView tvMessage = dialog.findViewById(R.id.tvMessage);
        tvMessage.setText("Deseja excluir " + file.getName() + "?");

        dialog.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            if (playingPosition == position) {
                if (mediaPlayer != null) {
                    mediaPlayer.release();
                    mediaPlayer = null;
                }
                playingPosition = -1;
            }
            file.delete();
            recordings.remove(position);
            notifyItemRemoved(position);
            if (onDeleteListener != null) onDeleteListener.onDeleted();
            dialog.dismiss();
        });

        dialog.show();
    }

    private void togglePlay(File file, int position) {
        if (mediaPlayer != null) {
            stopPlayback();
            int oldPosition = playingPosition;
            playingPosition = -1;
            notifyItemChanged(oldPosition);
            if (oldPosition == position) return;
        }

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();
            playingPosition = position;
            notifyItemChanged(position);
            
            setupVisualizer();
            
            mediaPlayer.setOnCompletionListener(mp -> {
                stopPlayback();
                playingPosition = -1;
                notifyItemChanged(position);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupVisualizer() {
        try {
            visualizer = new Visualizer(mediaPlayer.getAudioSessionId());
            visualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[0]);
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer vis, byte[] waveform, int samplingRate) {
                    int sum = 0;
                    for (byte b : waveform) {
                        sum += Math.abs((int) b - 128);
                    }
                    float amplitude = (sum / (float) waveform.length) / 128f;
                    handler.post(() -> waveformView.addAmplitude(amplitude));
                }
                @Override
                public void onFftDataCapture(Visualizer vis, byte[] fft, int samplingRate) {}
            }, Visualizer.getMaxCaptureRate() / 2, true, false);
            visualizer.setEnabled(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopPlayback() {
        if (visualizer != null) {
            visualizer.setEnabled(false);
            visualizer.release();
            visualizer = null;
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        waveformView.clear();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f));
    }

    @Override
    public int getItemCount() {
        return recordings.size();
    }

    public void release() {
        stopPlayback();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFileName, tvFileInfo;
        ImageButton btnPlay, btnDelete;

        ViewHolder(View itemView) {
            super(itemView);
            tvFileName = itemView.findViewById(R.id.tvFileName);
            tvFileInfo = itemView.findViewById(R.id.tvFileInfo);
            btnPlay = itemView.findViewById(R.id.btnPlay);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}

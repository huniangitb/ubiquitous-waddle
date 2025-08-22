package com.example.dualaudioplayer;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private MediaPlayer speakerPlayer, earpiecePlayer;
    private Equalizer earpieceEqualizer, speakerEqualizer;
    private Handler handler = new Handler();

    private TextView statusTextView, currentTimeTextView, totalTimeTextView, delayValueTextView, highPassFilterValueTextView, lowPassFilterValueTextView;
    private SeekBar playbackSeekBar, delaySeekBar, highPassFilterSeekBar, lowPassFilterSeekBar;
    private Button playPauseButton;

    private boolean isPlaying = false;
    private int speakerDelayMs = 0;
    private int playersPreparedCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupUI();

        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri audioUri = intent.getData();
            if (audioUri != null) {
                statusTextView.setText("正在加载: " + audioUri.getLastPathSegment());
                preparePlayers(audioUri);
            } else {
                statusTextView.setText("无法获取音频文件。");
            }
        }
    }

    private void setupUI() {
        statusTextView = findViewById(R.id.statusTextView);
        currentTimeTextView = findViewById(R.id.currentTimeTextView);
        totalTimeTextView = findViewById(R.id.totalTimeTextView);
        delayValueTextView = findViewById(R.id.delayValueTextView);
        highPassFilterValueTextView = findViewById(R.id.highPassFilterValueTextView);
        lowPassFilterValueTextView = findViewById(R.id.lowPassFilterValueTextView);
        playbackSeekBar = findViewById(R.id.playbackSeekBar);
        delaySeekBar = findViewById(R.id.delaySeekBar);
        highPassFilterSeekBar = findViewById(R.id.highPassFilterSeekBar);
        lowPassFilterSeekBar = findViewById(R.id.lowPassFilterSeekBar);
        playPauseButton = findViewById(R.id.playPauseButton);

        playPauseButton.setOnClickListener(v -> {
            if (isPlaying) pauseAudio(); else playAudio();
        });

        playbackSeekBar.setOnSeekBarChangeListener(createSeekListener());
        delaySeekBar.setOnSeekBarChangeListener(createDelayListener());
        highPassFilterSeekBar.setOnSeekBarChangeListener(createHighPassListener());
        lowPassFilterSeekBar.setOnSeekBarChangeListener(createLowPassListener());
    }

    private void preparePlayers(Uri uri) {
        releasePlayers();
        playersPreparedCount = 0;
        playPauseButton.setEnabled(false);

        try {
            speakerPlayer = createPlayer(uri, AudioAttributes.USAGE_MEDIA);
            earpiecePlayer = createPlayer(uri, AudioAttributes.USAGE_VOICE_COMMUNICATION);
        } catch (IOException e) {
            Toast.makeText(this, "加载文件失败", Toast.LENGTH_SHORT).show();
            statusTextView.setText("错误: " + e.getMessage());
        }
    }

    private MediaPlayer createPlayer(Uri uri, int usage) throws IOException {
        MediaPlayer player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(usage).build());
        player.setDataSource(this, uri);
        player.setOnPreparedListener(mp -> onPlayerPrepared(mp));
        player.prepareAsync();
        return player;
    }

    private synchronized void onPlayerPrepared(MediaPlayer mp) {
        playersPreparedCount++;
        if (playersPreparedCount == 2) {
            // 设置听筒只播放左声道
            earpiecePlayer.setVolume(1.0f, 0.0f);
            
            int duration = speakerPlayer.getDuration();
            playbackSeekBar.setMax(duration);
            totalTimeTextView.setText(formatTime(duration));
            playPauseButton.setEnabled(true);
            setupEqualizers();
            Toast.makeText(this, "加载完成，可以播放", Toast.LENGTH_SHORT).show();
        }
    }

    private void playAudio() {
        if (speakerPlayer == null || earpiecePlayer == null) return;
        earpiecePlayer.start();
        handler.postDelayed(() -> {
            if (speakerPlayer != null) speakerPlayer.start();
        }, speakerDelayMs);
        isPlaying = true;
        playPauseButton.setText("暂停");
        handler.post(updateSeekBarRunnable);
    }

    private void pauseAudio() {
        if (speakerPlayer == null || earpiecePlayer == null) return;
        if (speakerPlayer.isPlaying()) speakerPlayer.pause();
        if (earpiecePlayer.isPlaying()) earpiecePlayer.pause();
        handler.removeCallbacksAndMessages(null);
        isPlaying = false;
        playPauseButton.setText("播放");
    }

    private void setupEqualizers() {
        try {
            earpieceEqualizer = new Equalizer(0, earpiecePlayer.getAudioSessionId());
            earpieceEqualizer.setEnabled(true);
            highPassFilterSeekBar.setEnabled(true);
            int maxFreqEarpiece = earpieceEqualizer.getCenterFreq((short)(earpieceEqualizer.getNumberOfBands() - 1)) / 1000;
            highPassFilterSeekBar.setMax(maxFreqEarpiece);

            speakerEqualizer = new Equalizer(0, speakerPlayer.getAudioSessionId());
            speakerEqualizer.setEnabled(true);
            lowPassFilterSeekBar.setEnabled(true);
            int maxFreqSpeaker = speakerEqualizer.getCenterFreq((short)(speakerEqualizer.getNumberOfBands() - 1)) / 1000;
            lowPassFilterSeekBar.setMax(maxFreqSpeaker);

        } catch (Exception e) {
            Toast.makeText(this, "效果器初始化失败", Toast.LENGTH_SHORT).show();
            highPassFilterSeekBar.setEnabled(false);
            lowPassFilterSeekBar.setEnabled(false);
        }
    }

    private void updateHighPassFilter(int progress) {
        if (earpieceEqualizer == null) return;
        int cutoffFrequency = progress * 1000;
        highPassFilterValueTextView.setText(progress == 0 ? "关闭" : String.format(Locale.US, ">= %d Hz", progress));
        short minLevel = earpieceEqualizer.getBandLevelRange()[0];
        for (short i = 0; i < earpieceEqualizer.getNumberOfBands(); i++) {
            earpieceEqualizer.setBandLevel(i, earpieceEqualizer.getCenterFreq(i) < cutoffFrequency ? minLevel : (short) 0);
        }
    }

    private void updateLowPassFilter(int progress) {
        if (speakerEqualizer == null) return;
        int cutoffFrequency = progress * 1000;
        lowPassFilterValueTextView.setText(progress == 0 ? "关闭" : String.format(Locale.US, "<= %d Hz", progress));
        short minLevel = speakerEqualizer.getBandLevelRange()[0];
        for (short i = 0; i < speakerEqualizer.getNumberOfBands(); i++) {
            speakerEqualizer.setBandLevel(i, speakerEqualizer.getCenterFreq(i) > cutoffFrequency ? minLevel : (short) 0);
        }
    }
    
    // --- Listeners and Utils ---

    private SeekBar.OnSeekBarChangeListener createSeekListener() {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    if (speakerPlayer != null) speakerPlayer.seekTo(progress);
                    if (earpiecePlayer != null) earpiecePlayer.seekTo(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private SeekBar.OnSeekBarChangeListener createDelayListener() {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speakerDelayMs = progress;
                delayValueTextView.setText(String.format(Locale.US, "%d ms", progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private SeekBar.OnSeekBarChangeListener createHighPassListener() {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { updateHighPassFilter(progress); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private SeekBar.OnSeekBarChangeListener createLowPassListener() {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { updateLowPassFilter(progress); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private Runnable updateSeekBarRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlaying && speakerPlayer != null) {
                int currentPosition = speakerPlayer.getCurrentPosition();
                playbackSeekBar.setProgress(currentPosition);
                currentTimeTextView.setText(formatTime(currentPosition));
                handler.postDelayed(this, 500);
            }
        }
    };

    private String formatTime(int millis) {
        return String.format(Locale.US, "%02d:%02d", TimeUnit.MILLISECONDS.toMinutes(millis),
                TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis)));
    }

    private void releasePlayers() {
        handler.removeCallbacksAndMessages(null);
        if (speakerPlayer != null) { speakerPlayer.release(); speakerPlayer = null; }
        if (earpiecePlayer != null) { earpiecePlayer.release(); earpiecePlayer = null; }
        if (earpieceEqualizer != null) { earpieceEqualizer.release(); earpieceEqualizer = null; }
        if (speakerEqualizer != null) { speakerEqualizer.release(); speakerEqualizer = null; }
        isPlaying = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayers();
    }
}

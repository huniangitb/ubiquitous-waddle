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
    private Equalizer equalizer;
    private Handler handler = new Handler();

    private TextView statusTextView, currentTimeTextView, totalTimeTextView, delayValueTextView, filterValueTextView;
    private SeekBar playbackSeekBar, delaySeekBar, filterSeekBar;
    private Button playPauseButton;

    private Uri audioUri;
    private boolean isPlaying = false;
    private int speakerDelayMs = 0;
    private int playersPreparedCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化UI组件
        setupUI();

        // 处理传入的音频文件
        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            audioUri = intent.getData();
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
        filterValueTextView = findViewById(R.id.filterValueTextView);
        playbackSeekBar = findViewById(R.id.playbackSeekBar);
        delaySeekBar = findViewById(R.id.delaySeekBar);
        filterSeekBar = findViewById(R.id.filterSeekBar);
        playPauseButton = findViewById(R.id.playPauseButton);

        playPauseButton.setOnClickListener(v -> {
            if (isPlaying) {
                pauseAudio();
            } else {
                playAudio();
            }
        });

        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    if (speakerPlayer != null) speakerPlayer.seekTo(progress);
                    if (earpiecePlayer != null) earpiecePlayer.seekTo(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        delaySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speakerDelayMs = progress;
                delayValueTextView.setText(String.format(Locale.US, "%d ms", progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        filterSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateEqualizer(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void preparePlayers(Uri uri) {
        releasePlayers();
        playersPreparedCount = 0;
        playPauseButton.setEnabled(false);

        try {
            // 1. 准备扬声器播放器
            speakerPlayer = new MediaPlayer();
            speakerPlayer.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
            speakerPlayer.setDataSource(this, uri);
            speakerPlayer.setOnPreparedListener(mp -> onPlayerPrepared());
            speakerPlayer.prepareAsync();

            // 2. 准备听筒播放器
            earpiecePlayer = new MediaPlayer();
            earpiecePlayer.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build());
            earpiecePlayer.setDataSource(this, uri);
            earpiecePlayer.setOnPreparedListener(mp -> onPlayerPrepared());
            earpiecePlayer.prepareAsync();

        } catch (IOException e) {
            Toast.makeText(this, "加载文件失败", Toast.LENGTH_SHORT).show();
            statusTextView.setText("错误: " + e.getMessage());
        }
    }

    private synchronized void onPlayerPrepared() {
        playersPreparedCount++;
        if (playersPreparedCount == 2) {
            // 两个播放器都准备好了
            int duration = speakerPlayer.getDuration();
            playbackSeekBar.setMax(duration);
            totalTimeTextView.setText(formatTime(duration));
            playPauseButton.setEnabled(true);
            setupEqualizer();
            Toast.makeText(this, "加载完成，可以播放", Toast.LENGTH_SHORT).show();
        }
    }

    private void playAudio() {
        if (speakerPlayer == null || earpiecePlayer == null) return;

        earpiecePlayer.start();
        handler.postDelayed(() -> {
            if (speakerPlayer != null) {
                speakerPlayer.start();
            }
        }, speakerDelayMs);

        isPlaying = true;
        playPauseButton.setText("暂停");
        handler.post(updateSeekBarRunnable);
    }

    private void pauseAudio() {
        if (speakerPlayer == null || earpiecePlayer == null) return;

        if (speakerPlayer.isPlaying()) speakerPlayer.pause();
        if (earpiecePlayer.isPlaying()) earpiecePlayer.pause();
        handler.removeCallbacksAndMessages(null); // 停止所有延迟任务，包括延迟播放和UI更新

        isPlaying = false;
        playPauseButton.setText("播放");
    }

    private void setupEqualizer() {
        try {
            equalizer = new Equalizer(0, earpiecePlayer.getAudioSessionId());
            equalizer.setEnabled(true);
            filterSeekBar.setEnabled(true);

            // 设置滑条的最大值，代表最高频段的中心频率
            short highestBand = (short) (equalizer.getNumberOfBands() - 1);
            int maxFreq = equalizer.getCenterFreq(highestBand) / 1000; // 转换为Hz
            filterSeekBar.setMax(maxFreq);

        } catch (Exception e) {
            Toast.makeText(this, "效果器初始化失败", Toast.LENGTH_SHORT).show();
            filterSeekBar.setEnabled(false);
        }
    }

    private void updateEqualizer(int progress) {
        if (equalizer == null) return;

        int cutoffFrequency = progress * 1000; // 滑条的progress是Hz, 转换为mHz
        if (progress == 0) {
            filterValueTextView.setText("关闭");
        } else {
            filterValueTextView.setText(String.format(Locale.US, ">= %d Hz", progress));
        }

        short minLevel = equalizer.getBandLevelRange()[0]; // 获取最小增益

        for (short i = 0; i < equalizer.getNumberOfBands(); i++) {
            int bandCenterFreq = equalizer.getCenterFreq(i);
            if (bandCenterFreq < cutoffFrequency) {
                equalizer.setBandLevel(i, minLevel); // 衰减低于截止频率的频段
            } else {
                equalizer.setBandLevel(i, (short) 0); // 保持高于截止频率的频段
            }
        }
    }

    private Runnable updateSeekBarRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlaying && speakerPlayer != null) {
                int currentPosition = speakerPlayer.getCurrentPosition();
                playbackSeekBar.setProgress(currentPosition);
                currentTimeTextView.setText(formatTime(currentPosition));
                handler.postDelayed(this, 500); // 每500ms更新一次
            }
        }
    };

    private String formatTime(int millis) {
        return String.format(Locale.US, "%02d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(millis),
                TimeUnit.MILLISECONDS.toSeconds(millis) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
        );
    }

    private void releasePlayers() {
        handler.removeCallbacksAndMessages(null);
        if (speakerPlayer != null) {
            speakerPlayer.release();
            speakerPlayer = null;
        }
        if (earpiecePlayer != null) {
            earpiecePlayer.release();
            earpiecePlayer = null;
        }
        if (equalizer != null) {
            equalizer.release();
            equalizer = null;
        }
        isPlaying = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayers();
    }
}

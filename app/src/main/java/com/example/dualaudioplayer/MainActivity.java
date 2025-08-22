package com.example.dualaudioplayer;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private MediaPlayer earpiecePlayer, speakerPlayerLeft, speakerPlayerRight;
    private Equalizer earpieceEqualizer, speakerLpEqualizer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusTextView, currentTimeTextView, totalTimeTextView, delayValueTextView, highPassFilterValueTextView, lowPassFilterValueTextView;
    private Slider playbackSlider, delaySlider, highPassFilterSlider, lowPassFilterSlider;
    private MaterialButton playPauseButton;
    private MaterialSwitch phaseInvertSwitch;

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
                statusTextView.setText("加载中: " + audioUri.getLastPathSegment());
                preparePlayers(audioUri);
            } else {
                statusTextView.setText("无法获取音频文件");
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
        playbackSlider = findViewById(R.id.playbackSlider);
        delaySlider = findViewById(R.id.delaySlider);
        highPassFilterSlider = findViewById(R.id.highPassFilterSlider);
        lowPassFilterSlider = findViewById(R.id.lowPassFilterSlider);
        playPauseButton = findViewById(R.id.playPauseButton);
        phaseInvertSwitch = findViewById(R.id.phaseInvertSwitch);

        playPauseButton.setOnClickListener(v -> togglePlayPause());
        playbackSlider.addOnChangeListener((slider, value, fromUser) -> { if (fromUser) seekAllPlayers((int) value); });
        delaySlider.addOnChangeListener((slider, value, fromUser) -> { speakerDelayMs = (int) value; delayValueTextView.setText(String.format(Locale.US, "%d ms", speakerDelayMs)); });
        highPassFilterSlider.addOnChangeListener((slider, value, fromUser) -> updateHighPassFilter((int) value));
        lowPassFilterSlider.addOnChangeListener((slider, value, fromUser) -> updateLowPassFilter((int) value));
        phaseInvertSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> { Toast.makeText(this, "相位反转无法直接实现。\n请使用扬声器延迟滑条调整相位抵消。", Toast.LENGTH_LONG).show(); buttonView.setChecked(false); });
    }

    private void preparePlayers(Uri uri) {
        releasePlayers();
        playersPreparedCount = 0;
        playPauseButton.setEnabled(false);
        try {
            earpiecePlayer = createPlayer(uri, AudioAttributes.USAGE_VOICE_COMMUNICATION);
            speakerPlayerLeft = createPlayer(uri, AudioAttributes.USAGE_MEDIA);
            speakerPlayerRight = createPlayer(uri, AudioAttributes.USAGE_MEDIA);
        } catch (IOException e) {
            handleError("加载文件失败: " + e.getMessage());
        }
    }
    
    private MediaPlayer createPlayer(Uri uri, int usage) throws IOException {
        MediaPlayer player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(usage).build());
        player.setDataSource(this, uri);
        player.setOnPreparedListener(mp -> onPlayerPrepared());
        player.setOnCompletionListener(mp -> checkCompletion());
        player.prepareAsync();
        return player;
    }

    private synchronized void onPlayerPrepared() {
        playersPreparedCount++;
        if (playersPreparedCount == 3) {
            earpiecePlayer.setVolume(1.0f, 0.0f);
            speakerPlayerLeft.setVolume(1.0f, 0.0f);
            speakerPlayerRight.setVolume(0.0f, 1.0f);
            int duration = earpiecePlayer.getDuration();
            playbackSlider.setValueTo(duration);
            totalTimeTextView.setText(formatTime(duration));
            playPauseButton.setEnabled(true);
            setupEqualizers();
            Toast.makeText(this, "加载完成", Toast.LENGTH_SHORT).show();
        }
    }

    private void togglePlayPause() { if (isPlaying) pauseAudio(); else playAudio(); }

    private void playAudio() {
        if (!areAllPlayersReady()) return;
        earpiecePlayer.start();
        speakerPlayerRight.start();
        handler.postDelayed(() -> { if (speakerPlayerLeft != null) speakerPlayerLeft.start(); }, speakerDelayMs);
        isPlaying = true;
        playPauseButton.setIconResource(android.R.drawable.ic_media_pause);
        handler.post(updateSeekBarRunnable);
    }

    private void pauseAudio() {
        if (!areAllPlayersReady()) return;
        handler.removeCallbacksAndMessages(null);
        getAllPlayers().forEach(p -> { if (p != null && p.isPlaying()) p.pause(); });
        isPlaying = false;
        playPauseButton.setIconResource(android.R.drawable.ic_media_play);
    }
    
    private void seekAllPlayers(int position) { getAllPlayers().forEach(p -> { if (p != null) p.seekTo(position); }); }

    private void setupEqualizers() {
        try {
            earpieceEqualizer = new Equalizer(0, earpiecePlayer.getAudioSessionId());
            earpieceEqualizer.setEnabled(true);
            highPassFilterSlider.setEnabled(true);
            float maxFreqEarpiece = earpieceEqualizer.getCenterFreq((short)(earpieceEqualizer.getNumberOfBands() - 1)) / 1000f;
            highPassFilterSlider.setValueTo(maxFreqEarpiece);

            speakerLpEqualizer = new Equalizer(0, speakerPlayerLeft.getAudioSessionId());
            speakerLpEqualizer.setEnabled(true);
            lowPassFilterSlider.setEnabled(true);
            float maxFreqSpeaker = speakerLpEqualizer.getCenterFreq((short)(speakerLpEqualizer.getNumberOfBands() - 1)) / 1000f;
            lowPassFilterSlider.setValueTo(maxFreqSpeaker);
        } catch (Exception e) {
            handleError("效果器初始化失败");
            highPassFilterSlider.setEnabled(false);
            lowPassFilterSlider.setEnabled(false);
        }
    }
    
    private void updateHighPassFilter(int progress) {
        if (earpieceEqualizer == null) return;
        int cutoffFrequency = progress * 1000;
        highPassFilterValueTextView.setText(progress == 0 ? "关闭" : String.format(Locale.US, ">= %d Hz", progress));
        short minLevel = earpieceEqualizer.getBandLevelRange()[0];
        for (short i = 0; i < earpieceEqualizer.getNumberOfBands(); i++) { earpieceEqualizer.setBandLevel(i, earpieceEqualizer.getCenterFreq(i) < cutoffFrequency ? minLevel : (short) 0); }
    }

    private void updateLowPassFilter(int progress) {
        if (speakerLpEqualizer == null) return;
        int cutoffFrequency = progress * 1000;
        lowPassFilterValueTextView.setText(progress == 0 ? "关闭" : String.format(Locale.US, "<= %d Hz", progress));
        short minLevel = speakerLpEqualizer.getBandLevelRange()[0];
        for (short i = 0; i < speakerLpEqualizer.getNumberOfBands(); i++) { speakerLpEqualizer.setBandLevel(i, speakerLpEqualizer.getCenterFreq(i) > cutoffFrequency ? minLevel : (short) 0); }
    }

    private void checkCompletion() {
        if (!earpiecePlayer.isPlaying() && !speakerPlayerLeft.isPlaying() && !speakerPlayerRight.isPlaying()) {
            isPlaying = false;
            playPauseButton.setIconResource(android.R.drawable.ic_media_play);
            playbackSlider.setValue(0);
            currentTimeTextView.setText(formatTime(0));
            seekAllPlayers(0);
            handler.removeCallbacks(updateSeekBarRunnable);
        }
    }
    
    private final Runnable updateSeekBarRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlaying && areAllPlayersReady()) {
                int currentPosition = earpiecePlayer.getCurrentPosition();
                playbackSlider.setValue(currentPosition);
                currentTimeTextView.setText(formatTime(currentPosition));
                handler.postDelayed(this, 500);
            }
        }
    };
    
    private void releasePlayers() {
        handler.removeCallbacksAndMessages(null);
        getAllPlayers().forEach(p -> { if (p != null) p.release(); });
        earpiecePlayer = speakerPlayerLeft = speakerPlayerRight = null;
        if (earpieceEqualizer != null) earpieceEqualizer.release();
        if (speakerLpEqualizer != null) speakerLpEqualizer.release();
        earpieceEqualizer = speakerLpEqualizer = null;
        isPlaying = false;
    }

    @Override
    protected void onDestroy() { super.onDestroy(); releasePlayers(); }
    private List<MediaPlayer> getAllPlayers() { return Arrays.asList(earpiecePlayer, speakerPlayerLeft, speakerPlayerRight); }
    private boolean areAllPlayersReady() { return earpiecePlayer != null && speakerPlayerLeft != null && speakerPlayerRight != null; }
    private String formatTime(int millis) { return String.format(Locale.US, "%02d:%02d", TimeUnit.MILLISECONDS.toMinutes(millis), TimeUnit.MILLISECONDS.toSeconds(millis) % 60); }
    private void handleError(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); statusTextView.setText(message); }
}

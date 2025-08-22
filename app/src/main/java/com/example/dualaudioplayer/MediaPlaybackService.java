package com.example.dualaudioplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MediaPlaybackService extends Service {
    public static final String ACTION_UPDATE_UI = "com.example.dualaudioplayer.UPDATE_UI";
    public static final String ACTION_PLAY_PAUSE = "ACTION_PLAY_PAUSE", ACTION_NEXT = "ACTION_NEXT", ACTION_PREV = "ACTION_PREV";
    private static final String CHANNEL_ID = "DualAudioChannel";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder binder = new LocalBinder();
    private MediaPlayer earpiecePlayer, speakerPlayerLeft, speakerPlayerRight;
    private Equalizer earpieceEqualizer, speakerLpEqualizer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    private List<AudioItem> audioList = new ArrayList<>();
    private int currentIndex = -1;
    private boolean isPlaying = false;
    private int playersPreparedCount = 0;
    private int syncDelayMs = 0;
    private float earpieceDb = 0.0f, speakerDb = 0.0f;

    public class LocalBinder extends Binder { MediaPlaybackService getService() { return MediaPlaybackService.this; } }

    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onCreate() { super.onCreate(); createNotificationChannel(); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY_PAUSE: togglePlayPause(); break;
                case ACTION_NEXT: playNext(); break;
                case ACTION_PREV: playPrev(); break;
            }
        }
        return START_NOT_STICKY;
    }
    
    public void setAudioList(List<AudioItem> list) { this.audioList = list; }

    public void playSongAtIndex(int index) {
        if (index < 0 || index >= audioList.size()) return;
        currentIndex = index;
        releasePlayers();
        playersPreparedCount = 0;
        try {
            Uri uri = audioList.get(index).getUri();
            earpiecePlayer = createPlayer(uri, AudioAttributes.USAGE_VOICE_COMMUNICATION);
            speakerPlayerLeft = createPlayer(uri, AudioAttributes.USAGE_MEDIA);
            speakerPlayerRight = createPlayer(uri, AudioAttributes.USAGE_MEDIA);
        } catch (IOException e) { Toast.makeText(this, "无法播放文件", Toast.LENGTH_SHORT).show(); }
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
        if (playersPreparedCount == 3) { applyGains(); setupEqualizers(); playAudio(); }
    }

    public void togglePlayPause() { if (isPlaying) pauseAudio(); else resumeAudio(); }
    
    private void playAudio() {
        if (!areAllPlayersReady()) return;
        int earpieceDelay = Math.max(0, syncDelayMs);
        int speakerDelay = Math.max(0, -syncDelayMs);

        handler.postDelayed(() -> { if(earpiecePlayer != null) earpiecePlayer.start(); }, earpieceDelay);
        handler.postDelayed(() -> { if(speakerPlayerLeft != null) speakerPlayerLeft.start(); if(speakerPlayerRight != null) speakerPlayerRight.start(); }, speakerDelay);
        
        isPlaying = true;
        updateNotification();
        handler.post(updateSeekBarRunnable);
    }
    
    private void resumeAudio() {
        if (!areAllPlayersReady() || isPlaying) return;
        playAudio(); // Re-trigger delay logic on resume
    }

    private void pauseAudio() {
        if (!areAllPlayersReady() || !isPlaying) return;
        handler.removeCallbacksAndMessages(null);
        getAllPlayers().forEach(p -> { if (p.isPlaying()) p.pause(); });
        isPlaying = false; 
        updateNotification();
        broadcastUpdate();
    }

    public void seekTo(int position) { getAllPlayers().forEach(p -> p.seekTo(position)); }
    private void playNext() { if (audioList.isEmpty()) return; playSongAtIndex((currentIndex + 1) % audioList.size()); }
    private void playPrev() { if (audioList.isEmpty()) return; playSongAtIndex((currentIndex - 1 + audioList.size()) % audioList.size()); }
    private void checkCompletion() { if (isPlaying && !earpiecePlayer.isPlaying()) playNext(); }

    public void setSyncDelay(int ms) { this.syncDelayMs = ms; }
    public void setEarpieceGain(float db) { this.earpieceDb = db; applyGains(); }
    public void setSpeakerGain(float db) { this.speakerDb = db; applyGains(); }
    
    private void applyGains() {
        if (!areAllPlayersReady()) return;
        float earpieceAmp = (float) Math.pow(10, earpieceDb / 20.0);
        float speakerAmp = (float) Math.pow(10, speakerDb / 20.0);
        earpiecePlayer.setVolume(earpieceAmp, 0f);
        speakerPlayerLeft.setVolume(speakerAmp, 0f);
        speakerPlayerRight.setVolume(0f, speakerAmp);
    }
    
    private void setupEqualizers() {
        try {
            earpieceEqualizer = new Equalizer(0, earpiecePlayer.getAudioSessionId());
            earpieceEqualizer.setEnabled(true);
            speakerLpEqualizer = new Equalizer(0, speakerPlayerLeft.getAudioSessionId());
            speakerLpEqualizer.setEnabled(true);
        } catch (Exception ignored) {}
    }

    public void updateHighPassFilter(int freqHz) {
        if (earpieceEqualizer == null) return;
        int cutoffFrequency = freqHz * 1000;
        short minLevel = earpieceEqualizer.getBandLevelRange()[0];
        for (short i = 0; i < earpieceEqualizer.getNumberOfBands(); i++) { earpieceEqualizer.setBandLevel(i, earpieceEqualizer.getCenterFreq(i) < cutoffFrequency ? minLevel : (short) 0); }
    }

    public void updateLowPassFilter(int freqHz) {
        if (speakerLpEqualizer == null) return;
        int cutoffFrequency = freqHz * 1000;
        short minLevel = speakerLpEqualizer.getBandLevelRange()[0];
        for (short i = 0; i < speakerLpEqualizer.getNumberOfBands(); i++) { speakerLpEqualizer.setBandLevel(i, speakerLpEqualizer.getCenterFreq(i) > cutoffFrequency ? minLevel : (short) 0); }
    }

    private final Runnable updateSeekBarRunnable = new Runnable() {
        @Override public void run() { if (isPlaying && areAllPlayersReady()) { broadcastUpdate(); handler.postDelayed(this, 1000); } }
    };

    private void broadcastUpdate() {
        if (currentIndex == -1 || currentIndex >= audioList.size()) return;
        Intent intent = new Intent(ACTION_UPDATE_UI);
        intent.putExtra("isPlaying", isPlaying);
        intent.putExtra("currentIndex", currentIndex);
        intent.putExtra("currentPosition", earpiecePlayer.getCurrentPosition());
        intent.putExtra("duration", earpiecePlayer.getDuration());
        sendBroadcast(intent);
    }
    
    private void updateNotification() {
        if (currentIndex == -1 || currentIndex >= audioList.size()) return;
        AudioItem currentItem = audioList.get(currentIndex);

        PendingIntent playPauseIntent = PendingIntent.getService(this, 0, new Intent(this, MediaPlaybackService.class).setAction(ACTION_PLAY_PAUSE), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent nextIntent = PendingIntent.getService(this, 0, new Intent(this, MediaPlaybackService.class).setAction(ACTION_NEXT), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent prevIntent = PendingIntent.getService(this, 0, new Intent(this, MediaPlaybackService.class).setAction(ACTION_PREV), PendingIntent.FLAG_IMMUTABLE);
        
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(currentItem.getTitle())
                .setContentText(currentItem.getArtist())
                .setSmallIcon(android.R.drawable.ic_media_play)
                .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
                .addAction(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, "Play/Pause", playPauseIntent)
                .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2))
                .setOngoing(isPlaying)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, "Dual Audio Playback", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(serviceChannel);
        }
    }
    
    private void releasePlayers() {
        handler.removeCallbacksAndMessages(null);
        getAllPlayers().forEach(p -> { if (p != null) p.release(); });
        earpiecePlayer = speakerPlayerLeft = speakerPlayerRight = null;
        if (earpieceEqualizer != null) earpieceEqualizer.release();
        if (speakerLpEqualizer != null) speakerLpEqualizer.release();
        earpieceEqualizer = speakerLpEqualizer = null;
        isPlaying = false;
    }

    private List<MediaPlayer> getAllPlayers() { return Arrays.asList(earpiecePlayer, speakerPlayerLeft, speakerPlayerRight); }
    private boolean areAllPlayersReady() { return earpiecePlayer != null && speakerPlayerLeft != null && speakerPlayerRight != null; }
    
    @Override
    public void onDestroy() { super.onDestroy(); releasePlayers(); }
}

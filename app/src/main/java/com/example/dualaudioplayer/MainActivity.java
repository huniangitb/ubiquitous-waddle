package com.example.dualaudioplayer;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.slider.Slider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

// --- MainActivity (UI Controller) ---
public class MainActivity extends Activity {
    private static final String PREFS_NAME = "DualAudioPrefs";

    private MediaPlaybackService mediaService;
    private boolean isBound = false;
    private List<AudioItem> audioList = new ArrayList<>();
    private AudioListAdapter adapter;

    private TextView statusTextView, currentTimeTextView, totalTimeTextView;
    private Slider playbackSlider, earpieceGainSlider, speakerGainSlider, delaySlider, highPassFilterSlider, lowPassFilterSlider;
    private FloatingActionButton fabPlayPause;
    private BottomSheetBehavior<LinearLayout> bottomSheetBehavior;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    loadAudioFiles();
                } else {
                    Toast.makeText(this, "需要权限才能加载音频文件", Toast.LENGTH_LONG).show();
                }
            });

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MediaPlaybackService.ACTION_UPDATE_UI.equals(intent.getAction())) {
                updateUI(
                    intent.getBooleanExtra("isPlaying", false),
                    intent.getIntExtra("currentIndex", -1),
                    intent.getIntExtra("currentPosition", 0),
                    intent.getIntExtra("duration", 0)
                );
            }
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupUI();
        checkAndRequestPermission();
        Intent serviceIntent = new Intent(this, MediaPlaybackService.class);
        startService(serviceIntent);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
    }

    private void setupUI() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                return true;
            }
            return false;
        });

        statusTextView = findViewById(R.id.statusTextView);
        currentTimeTextView = findViewById(R.id.currentTimeTextView);
        totalTimeTextView = findViewById(R.id.totalTimeTextView);
        playbackSlider = findViewById(R.id.playbackSlider);
        fabPlayPause = findViewById(R.id.fabPlayPause);

        LinearLayout bottomSheet = findViewById(R.id.bottom_sheet);
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);

        earpieceGainSlider = findViewById(R.id.earpieceGainSlider);
        speakerGainSlider = findViewById(R.id.speakerGainSlider);
        delaySlider = findViewById(R.id.delaySlider);
        highPassFilterSlider = findViewById(R.id.highPassFilterSlider);
        lowPassFilterSlider = findViewById(R.id.lowPassFilterSlider);

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AudioListAdapter(audioList, item -> {
            if (isBound) mediaService.playSongAtIndex(audioList.indexOf(item));
        });
        recyclerView.setAdapter(adapter);

        fabPlayPause.setOnClickListener(v -> {
            if (isBound) mediaService.togglePlayPause();
        });

        setupSliderListeners();
    }

    private void setupSliderListeners() {
        playbackSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser && isBound) mediaService.seekTo((int) value);
        });
        earpieceGainSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (isBound) mediaService.setEarpieceGain(value);
            saveFloat("earpieceGain", value);
        });
        speakerGainSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (isBound) mediaService.setSpeakerGain(value);
            saveFloat("speakerGain", value);
        });
        delaySlider.addOnChangeListener((slider, value, fromUser) -> {
            if (isBound) mediaService.setEarpieceDelay((int) value);
            saveInt("earpieceDelay", (int) value);
        });
        highPassFilterSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (isBound) mediaService.updateHighPassFilter((int) value);
            saveInt("highPass", (int) value);
        });
        lowPassFilterSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (isBound) mediaService.updateLowPassFilter((int) value);
            saveInt("lowPass", (int) value);
        });
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        earpieceGainSlider.setValue(prefs.getFloat("earpieceGain", 1.0f));
        speakerGainSlider.setValue(prefs.getFloat("speakerGain", 1.0f));
        delaySlider.setValue(prefs.getInt("earpieceDelay", 0));
        highPassFilterSlider.setValue(prefs.getInt("highPass", 0));
        lowPassFilterSlider.setValue(prefs.getInt("lowPass", 0));
        
        // Apply settings to the service
        if(isBound) {
            mediaService.setEarpieceGain(earpieceGainSlider.getValue());
            mediaService.setSpeakerGain(speakerGainSlider.getValue());
            mediaService.setEarpieceDelay((int)delaySlider.getValue());
            mediaService.updateHighPassFilter((int)highPassFilterSlider.getValue());
            mediaService.updateLowPassFilter((int)lowPassFilterSlider.getValue());
        }
    }

    private void saveFloat(String key, float value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putFloat(key, value).apply();
    }

    private void saveInt(String key, int value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(key, value).apply();
    }
    
    private void checkAndRequestPermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ?
                Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadAudioFiles();
        } else {
            requestPermissionLauncher.launch(permission);
        }
    }

    private void loadAudioFiles() {
        audioList.clear();
        String[] projection = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION};
        try (Cursor cursor = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, MediaStore.Audio.Media.TITLE + " ASC")) {
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String title = cursor.getString(titleColumn);
                String artist = cursor.getString(artistColumn);
                int duration = cursor.getInt(durationColumn);
                Uri contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                audioList.add(new AudioItem(contentUri, title, artist, duration));
            }
        }
        adapter.notifyDataSetChanged();
        if (isBound) {
            mediaService.setAudioList(audioList);
        }
    }
    
    private void updateUI(boolean isPlaying, int currentIndex, int currentPosition, int duration) {
        fabPlayPause.setImageResource(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        if (currentIndex != -1 && currentIndex < audioList.size()) {
            statusTextView.setText(audioList.get(currentIndex).getTitle());
        }
        playbackSlider.setValueTo(duration);
        playbackSlider.setValue(currentPosition);
        currentTimeTextView.setText(formatTime(currentPosition));
        totalTimeTextView.setText(formatTime(duration));
    }
    
    private String formatTime(int millis) {
        return String.format(Locale.US, "%02d:%02d", TimeUnit.MILLISECONDS.toMinutes(millis), TimeUnit.MILLISECONDS.toSeconds(millis) % 60);
    }
    
    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(updateReceiver, new IntentFilter(MediaPlaybackService.ACTION_UPDATE_UI));
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(updateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }
    
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MediaPlaybackService.LocalBinder binder = (MediaPlaybackService.LocalBinder) service;
            mediaService = binder.getService();
            isBound = true;
            mediaService.setAudioList(audioList);
            loadSettings();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };
}

// --- AudioItem Data Class ---
class AudioItem {
    final Uri uri;
    final String title;
    final String artist;
    final int duration;

    AudioItem(Uri uri, String title, String artist, int duration) {
        this.uri = uri;
        this.title = title;
        this.artist = artist;
        this.duration = duration;
    }
}

// --- AudioListAdapter for RecyclerView ---
class AudioListAdapter extends RecyclerView.Adapter<AudioListAdapter.ViewHolder> {
    private final List<AudioItem> audioList;
    private final OnItemClickListener listener;

    interface OnItemClickListener { void onItemClick(AudioItem item); }

    AudioListAdapter(List<AudioItem> audioList, OnItemClickListener listener) {
        this.audioList = audioList;
        this.listener = listener;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_audio, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        holder.bind(audioList.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return audioList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView titleTextView;
        final TextView artistTextView;

        ViewHolder(View itemView) {
            super(itemView);
            titleTextView = itemView.findViewById(R.id.titleTextView);
            artistTextView = itemView.findViewById(R.id.artistTextView);
        }

        void bind(final AudioItem item, final OnItemClickListener listener) {
            titleTextView.setText(item.title);
            artistTextView.setText(item.artist);
            itemView.setOnClickListener(v -> listener.onItemClick(item));
        }
    }
}

// --- MediaPlaybackService (Handles all background playback and notifications) ---
class MediaPlaybackService extends Service {
    public static final String ACTION_UPDATE_UI = "com.example.dualaudioplayer.UPDATE_UI";
    public static final String ACTION_PLAY_PAUSE = "ACTION_PLAY_PAUSE";
    public static final String ACTION_NEXT = "ACTION_NEXT";
    public static final String ACTION_PREV = "ACTION_PREV";
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
    private int earpieceDelayMs = 0;
    private float earpieceGain = 1.0f, speakerGain = 1.0f;

    public class LocalBinder extends Binder {
        MediaPlaybackService getService() { return MediaPlaybackService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }
    
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY_PAUSE: togglePlayPause(); break;
                case ACTION_NEXT: playNext(); break;
                case ACTION_PREV: playPrev(); break;
            }
        }
        return START_STICKY;
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
        } catch (IOException e) {
            Toast.makeText(this, "无法播放文件", Toast.LENGTH_SHORT).show();
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
            applyGains();
            setupEqualizers();
            playAudio();
        }
    }

    public void togglePlayPause() {
        if (isPlaying) pauseAudio(); else resumeAudio();
    }
    
    private void playAudio() {
        if (!areAllPlayersReady()) return;
        speakerPlayerRight.start();
        handler.postDelayed(() -> {
            if (speakerPlayerLeft != null) speakerPlayerLeft.start();
            if (earpiecePlayer != null) earpiecePlayer.start();
        }, earpieceDelayMs);
        isPlaying = true;
        updateNotification();
        handler.post(updateSeekBarRunnable);
    }
    
    private void resumeAudio() {
        if (!areAllPlayersReady() || isPlaying) return;
        getAllPlayers().forEach(MediaPlayer::start);
        isPlaying = true;
        updateNotification();
        handler.post(updateSeekBarRunnable);
    }

    private void pauseAudio() {
        if (!areAllPlayersReady() || !isPlaying) return;
        handler.removeCallbacks(updateSeekBarRunnable);
        getAllPlayers().forEach(p -> { if (p.isPlaying()) p.pause(); });
        isPlaying = false;
        updateNotification();
    }

    public void seekTo(int position) {
        getAllPlayers().forEach(p -> p.seekTo(position));
    }

    private void playNext() {
        if (audioList.isEmpty()) return;
        int nextIndex = (currentIndex + 1) % audioList.size();
        playSongAtIndex(nextIndex);
    }
    
    private void playPrev() {
        if (audioList.isEmpty()) return;
        int prevIndex = (currentIndex - 1 + audioList.size()) % audioList.size();
        playSongAtIndex(prevIndex);
    }

    private void checkCompletion() {
        if (isPlaying && !earpiecePlayer.isPlaying()) {
            playNext();
        }
    }

    // --- Settings ---
    public void setEarpieceDelay(int ms) { this.earpieceDelayMs = ms; }
    public void setEarpieceGain(float gain) { this.earpieceGain = gain; applyGains(); }
    public void setSpeakerGain(float gain) { this.speakerGain = gain; applyGains(); }
    
    private void applyGains() {
        if (!areAllPlayersReady()) return;
        earpiecePlayer.setVolume(earpieceGain, 0f);
        speakerPlayerLeft.setVolume(speakerGain, 0f);
        speakerPlayerRight.setVolume(0f, speakerGain);
    }
    
    private void setupEqualizers() {
        try {
            earpieceEqualizer = new Equalizer(0, earpiecePlayer.getAudioSessionId());
            earpieceEqualizer.setEnabled(true);
            speakerLpEqualizer = new Equalizer(0, speakerPlayerLeft.getAudioSessionId());
            speakerLpEqualizer.setEnabled(true);
        } catch (Exception ignored) {}
    }

    public void updateHighPassFilter(int progress) {
        if (earpieceEqualizer == null) return;
        int cutoffFrequency = progress * 1000;
        short minLevel = earpieceEqualizer.getBandLevelRange()[0];
        for (short i = 0; i < earpieceEqualizer.getNumberOfBands(); i++) {
            earpieceEqualizer.setBandLevel(i, earpieceEqualizer.getCenterFreq(i) < cutoffFrequency ? minLevel : (short) 0);
        }
    }

    public void updateLowPassFilter(int progress) {
        if (speakerLpEqualizer == null) return;
        int cutoffFrequency = progress * 1000;
        short minLevel = speakerLpEqualizer.getBandLevelRange()[0];
        for (short i = 0; i < speakerLpEqualizer.getNumberOfBands(); i++) {
            speakerLpEqualizer.setBandLevel(i, speakerLpEqualizer.getCenterFreq(i) > cutoffFrequency ? minLevel : (short) 0);
        }
    }

    // --- Notification and UI Update ---
    private final Runnable updateSeekBarRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlaying && areAllPlayersReady()) {
                broadcastUpdate();
                handler.postDelayed(this, 1000);
            }
        }
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

    private List<MediaPlayer> getAllPlayers() {
        return Arrays.asList(earpiecePlayer, speakerPlayerLeft, speakerPlayerRight);
    }
    private boolean areAllPlayersReady() {
        return earpiecePlayer != null && speakerPlayerLeft != null && speakerPlayerRight != null;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        releasePlayers();
    }
}

package com.example.dualaudioplayer;

import android.Manifest;
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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.MediaStore;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
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
        return String.format(Locale.US, "%d:%02d", TimeUnit.MILLISECONDS.toMinutes(millis), TimeUnit.MILLISECONDS.toSeconds(millis) % 60);
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

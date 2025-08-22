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
import com.google.android.material.materialswitch.MaterialSwitch;
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
    private TextView earpieceGainLabel, speakerGainLabel, delayLabel, highPassFilterLabel, lowPassFilterLabel;
    private Slider playbackSlider, earpieceGainSlider, speakerGainSlider, delaySlider, highPassFilterSlider, lowPassFilterSlider;
    private FloatingActionButton fabPlayPause;
    private BottomSheetBehavior<LinearLayout> bottomSheetBehavior;
    private MaterialSwitch filterLockSwitch;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) loadAudioFiles(); else Toast.makeText(this, "需要权限才能加载音频文件", Toast.LENGTH_LONG).show();
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

        earpieceGainLabel = findViewById(R.id.earpieceGainLabel);
        speakerGainLabel = findViewById(R.id.speakerGainLabel);
        delayLabel = findViewById(R.id.delayLabel);
        highPassFilterLabel = findViewById(R.id.highPassFilterLabel);
        lowPassFilterLabel = findViewById(R.id.lowPassFilterLabel);
        
        earpieceGainSlider = findViewById(R.id.earpieceGainSlider);
        speakerGainSlider = findViewById(R.id.speakerGainSlider);
        delaySlider = findViewById(R.id.delaySlider);
        highPassFilterSlider = findViewById(R.id.highPassFilterSlider);
        lowPassFilterSlider = findViewById(R.id.lowPassFilterSlider);
        filterLockSwitch = findViewById(R.id.filterLockSwitch);

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AudioListAdapter(audioList, item -> { if (isBound) mediaService.playSongAtIndex(audioList.indexOf(item)); });
        recyclerView.setAdapter(adapter);

        fabPlayPause.setOnClickListener(v -> { if (isBound) mediaService.togglePlayPause(); });

        setupSliderListeners();
    }

    private void setupSliderListeners() {
        playbackSlider.addOnChangeListener((s, v, u) -> { if (u && isBound) mediaService.seekTo((int) v); });
        
        earpieceGainSlider.addOnChangeListener((s, v, u) -> {
            earpieceGainLabel.setText(String.format(Locale.US, "听筒增益: %.1f dB", v));
            if (u && isBound) { mediaService.setEarpieceGain(v); saveFloat("earpieceGain", v); }
        });
        speakerGainSlider.addOnChangeListener((s, v, u) -> {
            speakerGainLabel.setText(String.format(Locale.US, "扬声器增益: %.1f dB", v));
            if (u && isBound) { mediaService.setSpeakerGain(v); saveFloat("speakerGain", v); }
        });
        delaySlider.addOnChangeListener((s, v, u) -> {
            delayLabel.setText(String.format(Locale.US, "同步延迟: %d ms", (int)v));
            if (u && isBound) { mediaService.setSyncDelay((int) v); saveInt("syncDelay", (int) v); }
        });

        highPassFilterSlider.addOnChangeListener((s, v, u) -> {
            highPassFilterLabel.setText(String.format(Locale.US, "听筒高通 (左): %d Hz", (int)v));
            if (u) {
                if (filterLockSwitch.isChecked()) lowPassFilterSlider.setValue(v);
                if (isBound) { mediaService.updateHighPassFilter((int) v); saveInt("highPass", (int) v); }
            }
        });
        lowPassFilterSlider.addOnChangeListener((s, v, u) -> {
            lowPassFilterLabel.setText(String.format(Locale.US, "扬声器低通 (左): %d Hz", (int)v));
            if (u) {
                if (filterLockSwitch.isChecked()) highPassFilterSlider.setValue(v);
                if (isBound) { mediaService.updateLowPassFilter((int) v); saveInt("lowPass", (int) v); }
            }
        });
    }
    
    private float snapToStep(float value, float valueFrom, float stepSize) {
        if (stepSize <= 0) return value;
        return Math.round((value - valueFrom) / stepSize) * stepSize + valueFrom;
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        float earpieceGain = prefs.getFloat("earpieceGain", 0.0f);
        float speakerGain = prefs.getFloat("speakerGain", 0.0f);
        int syncDelay = prefs.getInt("syncDelay", 0);
        int highPass = prefs.getInt("highPass", 50);
        int lowPass = prefs.getInt("lowPass", 15000);

        float alignedEarpieceGain = snapToStep(earpieceGain, earpieceGainSlider.getValueFrom(), earpieceGainSlider.getStepSize());
        float alignedSpeakerGain = snapToStep(speakerGain, speakerGainSlider.getValueFrom(), speakerGainSlider.getStepSize());

        earpieceGainSlider.setValue(alignedEarpieceGain);
        speakerGainSlider.setValue(alignedSpeakerGain);
        delaySlider.setValue(syncDelay);
        highPassFilterSlider.setValue(highPass);
        lowPassFilterSlider.setValue(lowPass);

        if(isBound) {
            mediaService.setEarpieceGain(alignedEarpieceGain);
            mediaService.setSpeakerGain(alignedSpeakerGain);
            mediaService.setSyncDelay(syncDelay);
            mediaService.updateHighPassFilter(highPass);
            mediaService.updateLowPassFilter(lowPass);
        }
    }

    private void saveFloat(String key, float value) { getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putFloat(key, value).apply(); }
    private void saveInt(String key, int value) { getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(key, value).apply(); }
    
    private void checkAndRequestPermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) loadAudioFiles(); else requestPermissionLauncher.launch(permission);
    }

    private void loadAudioFiles() {
        audioList.clear();
        String[] projection = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION};
        try (Cursor cursor = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, MediaStore.Audio.Media.TITLE + " ASC")) {
            int idCol=cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID), titleCol=cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE), artistCol=cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST), durCol=cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            while (cursor.moveToNext()) {
                audioList.add(new AudioItem(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol)), cursor.getString(titleCol), cursor.getString(artistCol), cursor.getInt(durCol)));
            }
        }
        adapter.notifyDataSetChanged();
        if (isBound) mediaService.setAudioList(audioList);
    }
    
    private void updateUI(boolean isPlaying, int currentIndex, int currentPosition, int duration) {
        fabPlayPause.setImageResource(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        if (currentIndex != -1 && currentIndex < audioList.size()) {
            statusTextView.setText(audioList.get(currentIndex).getTitle());
        }
        playbackSlider.setValueTo(duration);
        playbackSlider.setStepSize(1000f);
        playbackSlider.setValue(currentPosition);
        currentTimeTextView.setText(formatTime(currentPosition));
        totalTimeTextView.setText(formatTime(duration));
    }
    
    private String formatTime(int millis) { return String.format(Locale.US, "%d:%02d", TimeUnit.MILLISECONDS.toMinutes(millis), TimeUnit.MILLISECONDS.toSeconds(millis) % 60); }
    
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
        if (isBound) { unbindService(connection); isBound = false; }
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
        public void onServiceDisconnected(ComponentName name) { isBound = false; }
    };
}

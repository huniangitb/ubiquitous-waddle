package com.example.dualaudioplayer;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

public class MainActivity extends Activity {

    private MediaPlayer speakerPlayer;
    private MediaPlayer earpiecePlayer;
    private TextView statusTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusTextView = new TextView(this);
        statusTextView.setTextSize(20);
        statusTextView.setPadding(50, 50, 50, 50);
        setContentView(statusTextView);

        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri audioUri = intent.getData();
            if (audioUri != null) {
                statusTextView.setText("正在加载音频...\n" + audioUri.getPath());
                playAudioOnBoth(audioUri);
            } else {
                statusTextView.setText("无法获取音频文件。");
            }
        } else {
            statusTextView.setText("请通过文件管理器选择一个音频文件，并使用本应用打开。");
        }
    }

    private void playAudioOnBoth(Uri audioUri) {
        releasePlayers();

        try {
            speakerPlayer = new MediaPlayer();
            speakerPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
            );
            speakerPlayer.setDataSource(this, audioUri);
            speakerPlayer.prepare();
            speakerPlayer.start();

            earpiecePlayer = new MediaPlayer();
            earpiecePlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
            );
            earpiecePlayer.setDataSource(this, audioUri);
            earpiecePlayer.prepare();
            earpiecePlayer.start();

            statusTextView.setText("正在通过扬声器和听筒同时播放！");
            Toast.makeText(this, "播放开始", Toast.LENGTH_SHORT).show();

        } catch (IOException e) {
            e.printStackTrace();
            statusTextView.setText("播放失败: " + e.getMessage());
            Toast.makeText(this, "无法播放此文件", Toast.LENGTH_LONG).show();
        }
    }

    private void releasePlayers() {
        if (speakerPlayer != null) {
            speakerPlayer.release();
            speakerPlayer = null;
        }
        if (earpiecePlayer != null) {
            earpiecePlayer.release();
            earpiecePlayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayers();
    }
}

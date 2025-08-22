package com.example.dualaudioplayer;

import android.net.Uri;

public class AudioItem {
    final Uri uri;
    final String title;
    final String artist;
    final int duration;

    AudioItem(Uri uri, String title, String artist, int duration) {
        this.uri = uri; this.title = title; this.artist = artist; this.duration = duration;
    }
    public Uri getUri() { return uri; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
}

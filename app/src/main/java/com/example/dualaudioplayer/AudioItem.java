package com.example.dualaudioplayer;

import android.net.Uri;

public class AudioItem {
    final Uri uri;
    final String title;
    final String artist;
    final int duration;
    final Uri albumArtUri;

    AudioItem(Uri uri, String title, String artist, int duration, Uri albumArtUri) {
        this.uri = uri; this.title = title; this.artist = artist; this.duration = duration; this.albumArtUri = albumArtUri;
    }
    public Uri getUri() { return uri; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public Uri getAlbumArtUri() { return albumArtUri; }
}

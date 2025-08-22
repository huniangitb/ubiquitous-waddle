package com.example.dualaudioplayer
import android.net.Uri
data class AudioItem(val uri: Uri, val title: String, val artist: String, val duration: Int, val albumArtUri: Uri)

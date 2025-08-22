package com.example.dualaudioplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.*
import java.io.IOException
import kotlin.math.max
import kotlin.math.pow

class MediaPlaybackService : Service() {

    companion object {
        const val ACTION_UPDATE_UI = "com.example.dualaudioplayer.UPDATE_UI"
        const val ACTION_PLAY_PAUSE = "ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "ACTION_NEXT"
        const val ACTION_PREV = "ACTION_PREV"
        private const val CHANNEL_ID = "DualAudioChannel"
        private const val NOTIFICATION_ID = 1
    }

    private val binder = LocalBinder()
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val handler = Handler(Looper.getMainLooper())
    private var earpiecePlayer: MediaPlayer? = null
    private var speakerPlayer: MediaPlayer? = null
    private var earpieceEqualizer: Equalizer? = null
    private var speakerEqualizer: Equalizer? = null
    private var audioList: List<AudioItem> = emptyList()
    private var currentIndex = -1
    var isPlaying = false
        private set
    private var playersPreparedCount = 0
    private var syncDelayMs = 0
    private var earpieceAttenuation = 1.0f
    private var speakerAttenuation = 1.0f
    private var isEarpieceEnabled = true 
    private var isSpeakerEnabled = true  
    private var currentHighPassHz: Int = 50
    private var currentLowPassHz: Int = 15000

    inner class LocalBinder : Binder() { fun getService(): MediaPlaybackService = this@MediaPlaybackService }
    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayers()
        serviceJob.cancel()
    }

    private fun applyCurrentVolumes() {
        val earpieceVolume = if (isEarpieceEnabled) earpieceAttenuation else 0.0f
        earpiecePlayer?.setVolume(earpieceVolume, 0.0f)
        
        val speakerVolume = if (isSpeakerEnabled) speakerAttenuation else 0.0f
        speakerPlayer?.setVolume(0.0f, speakerVolume)
    }

    fun setEarpieceEnabled(isEnabled: Boolean) {
        this.isEarpieceEnabled = isEnabled
        applyCurrentVolumes()
    }

    fun setSpeakerEnabled(isEnabled: Boolean) {
        this.isSpeakerEnabled = isEnabled
        applyCurrentVolumes()
    }

    fun setEarpieceAttenuationDb(db: Float) {
        earpieceAttenuation = 10.0f.pow(db / 20.0f)
        applyCurrentVolumes()
    }
    
    fun setSpeakerAttenuationDb(db: Float) {
        speakerAttenuation = 10.0f.pow(db / 20.0f)
        applyCurrentVolumes()
    }

    fun setSyncDelay(ms: Int) {
        this.syncDelayMs = ms
        if (isPlaying && areAllPlayersReady()) {
            val pos = earpiecePlayer?.currentPosition ?: 0
            pauseAudio()
            seekTo(pos)
            resumeAudio()
        }
    }

    @Synchronized
    private fun onPlayerPrepared() {
        playersPreparedCount++
        if (playersPreparedCount == 2) {
            setupAudioEffects()
            applyCurrentVolumes()
            playAudio()
        }
    }
    
    fun setAudioList(list: List<AudioItem>) { this.audioList = list }
    fun playSongAtIndex(index: Int) {
        if (index !in audioList.indices) return; currentIndex = index; releasePlayers(); playersPreparedCount = 0
        try { val uri = audioList[index].uri; earpiecePlayer = createPlayer(uri, AudioAttributes.USAGE_VOICE_COMMUNICATION); speakerPlayer = createPlayer(uri, AudioAttributes.USAGE_MEDIA)
        } catch (e: IOException) { Toast.makeText(this, "无法播放文件", Toast.LENGTH_SHORT).show() }
    }
    private fun createPlayer(uri: Uri, usage: Int) = MediaPlayer().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build()); setDataSource(this@MediaPlaybackService, uri)
        setOnPreparedListener { onPlayerPrepared() }; setOnCompletionListener { checkCompletion() }; prepareAsync()
    }
    fun togglePlayPause() { if (isPlaying) pauseAudio() else resumeAudio() }
    private fun playAudio() {
        if (!areAllPlayersReady() || isPlaying) return; val earpieceDelay = max(0, syncDelayMs).toLong(); val speakerDelay = max(0, -syncDelayMs).toLong()
        serviceScope.launch { delay(earpieceDelay); earpiecePlayer?.start() }; serviceScope.launch { delay(speakerDelay); speakerPlayer?.start() }
        isPlaying = true; updateNotification(); handler.post(updateSeekBarRunnable)
    }
    private fun resumeAudio() { if (areAllPlayersReady() && !isPlaying) playAudio() }
    private fun pauseAudio() {
        if (!areAllPlayersReady()) return; serviceScope.coroutineContext.cancelChildren(); handler.removeCallbacksAndMessages(null)
        getAllPlayers().forEach { it?.takeIf { p -> p.isPlaying }?.pause() }; isPlaying = false; updateNotification(); broadcastUpdate()
    }
    fun seekTo(position: Int) { getAllPlayers().forEach { it?.seekTo(position) } }
    private fun playNext() { if (audioList.isNotEmpty()) playSongAtIndex((currentIndex + 1) % audioList.size) }
    private fun playPrev() { if (audioList.isNotEmpty()) playSongAtIndex((currentIndex - 1 + audioList.size) % audioList.size) }
    private fun checkCompletion() { if (isPlaying && earpiecePlayer?.isPlaying == false) playNext() }
    private fun setupAudioEffects() {
        try {
            earpiecePlayer?.audioSessionId?.let { earpieceEqualizer = Equalizer(0, it).apply { setEnabled(true) }; updateHighPassFilter(currentHighPassHz) }
            speakerPlayer?.audioSessionId?.let { speakerEqualizer = Equalizer(0, it).apply { setEnabled(true) }; updateLowPassFilter(currentLowPassHz) }
        } catch (e: Exception) { /* Ignore */ }
    }
    fun updateHighPassFilter(freqHz: Int) {
        currentHighPassHz = freqHz
        earpieceEqualizer?.let { eq -> val cutoff = freqHz * 1000; val min = eq.bandLevelRange[0]; for (i in 0 until eq.numberOfBands) { val band = i.toShort(); eq.setBandLevel(band, if (eq.getCenterFreq(band) < cutoff) min else 0.toShort()) } }
    }
    fun updateLowPassFilter(freqHz: Int) {
        currentLowPassHz = freqHz
        speakerEqualizer?.let { eq -> val cutoff = freqHz * 1000; val min = eq.bandLevelRange[0]; for (i in 0 until eq.numberOfBands) { val band = i.toShort(); eq.setBandLevel(band, if (eq.getCenterFreq(band) > cutoff) min else 0.toShort()) } }
    }
    private val updateSeekBarRunnable = object : Runnable { override fun run() { if (isPlaying && areAllPlayersReady()) { broadcastUpdate(); handler.postDelayed(this, 1000) } } }
    private fun broadcastUpdate() {
        if (currentIndex !in audioList.indices) return
        sendBroadcast(Intent(ACTION_UPDATE_UI).apply {
            putExtra("isPlaying", isPlaying); putExtra("currentIndex", currentIndex); putExtra("currentPosition", earpiecePlayer?.currentPosition ?: 0); putExtra("duration", earpiecePlayer?.duration ?: 0)
        })
    }
    private fun updateNotification() {
        if (currentIndex !in audioList.indices) return; val item = audioList[currentIndex]
        val playPause = PendingIntent.getService(this, 0, Intent(this, MediaPlaybackService::class.java).setAction(ACTION_PLAY_PAUSE), PendingIntent.FLAG_IMMUTABLE)
        val next = PendingIntent.getService(this, 1, Intent(this, MediaPlaybackService::class.java).setAction(ACTION_NEXT), PendingIntent.FLAG_IMMUTABLE)
        val prev = PendingIntent.getService(this, 2, Intent(this, MediaPlaybackService::class.java).setAction(ACTION_PREV), PendingIntent.FLAG_IMMUTABLE)
        val action = Notification.Action.Builder(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "Play/Pause", playPause).build()
        val builder = Notification.Builder(this, CHANNEL_ID).setContentTitle(item.title).setContentText(item.artist).setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", prev).build()).addAction(action)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", next).build()).setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)).setOngoing(isPlaying)
        try { builder.setLargeIcon(MediaStore.Images.Media.getBitmap(this.contentResolver, item.albumArtUri)) } catch (e: IOException) { /* ignore */ }
        startForeground(NOTIFICATION_ID, builder.build())
    }
    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Dual Audio Playback", NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
    private fun releasePlayers() {
        handler.removeCallbacksAndMessages(null); serviceScope.coroutineContext.cancelChildren(); getAllPlayers().forEach { it?.release() }; earpiecePlayer = null; speakerPlayer = null
        earpieceEqualizer?.release(); speakerEqualizer?.release(); earpieceEqualizer = null; speakerEqualizer = null; isPlaying = false
    }
    private fun getAllPlayers() = listOf(earpiecePlayer, speakerPlayer)
    private fun areAllPlayersReady() = earpiecePlayer != null && speakerPlayer != null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action -> when (action) { ACTION_PLAY_PAUSE -> togglePlayPause(); ACTION_NEXT -> playNext(); ACTION_PREV -> playPrev() } }; return START_NOT_STICKY
    }
}

package com.example.dualaudioplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
    private lateinit var audioManager: AudioManager
    private var earpiecePlayer: MediaPlayer? = null
    private var speakerPlayer: MediaPlayer? = null
    private var earpieceEqualizer: Equalizer? = null
    private var speakerEqualizer: Equalizer? = null
    private var earpieceDevice: AudioDeviceInfo? = null
    private var audioList: List<AudioItem> = emptyList()
    private var currentIndex = -1
    var isPlaying = false
        private set
    private var earpieceAttenuation = 1.0f
    private var speakerAttenuation = 1.0f
    private var isEarpieceEnabled = true 
    private var isSpeakerEnabled = true  
    private var currentHighPassHz: Int = 50
    private var currentLowPassHz: Int = 15000
    private var syncDelayMs = 0

    inner class LocalBinder : Binder() { fun getService(): MediaPlaybackService = this@MediaPlaybackService }
    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        earpieceDevice = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        if (earpieceDevice == null) {
            Toast.makeText(this, "未找到听筒设备", Toast.LENGTH_LONG).show()
        }
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayers()
        serviceJob.cancel()
    }
    
    // --- 音量和通道启用/禁用 ---
    fun setEarpieceEnabled(isEnabled: Boolean) {
        isEarpieceEnabled = isEnabled
        if (isEnabled) {
            recreateEarpiecePlayer()
        } else {
            earpiecePlayer?.release()
            earpiecePlayer = null
            earpieceEqualizer?.release()
            earpieceEqualizer = null
        }
    }

    fun setSpeakerEnabled(isEnabled: Boolean) {
        isSpeakerEnabled = isEnabled
        if (isEnabled) {
            recreateSpeakerPlayer()
        } else {
            speakerPlayer?.release()
            speakerPlayer = null
            speakerEqualizer?.release()
            speakerEqualizer = null
        }
    }
    
    fun setEarpieceAttenuationDb(db: Float) {
        earpieceAttenuation = 10.0f.pow(db / 20.0f)
        earpiecePlayer?.setVolume(earpieceAttenuation, 0.0f)
    }
    
    fun setSpeakerAttenuationDb(db: Float) {
        speakerAttenuation = 10.0f.pow(db / 20.0f)
        speakerPlayer?.setVolume(0.0f, speakerAttenuation)
    }
    
    // --- 核心播放逻辑 ---
    fun playSongAtIndex(index: Int) {
        if (index !in audioList.indices) return
        currentIndex = index
        releasePlayers()
        
        if (isSpeakerEnabled) recreateSpeakerPlayer()
        if (isEarpieceEnabled) recreateEarpiecePlayer()
    }

    private fun recreateEarpiecePlayer() {
        if (earpieceDevice == null || currentIndex == -1) return
        earpiecePlayer?.release()
        earpiecePlayer = createPlayer(audioList[currentIndex].uri, earpieceDevice) {
            // Prepared
            val otherPlayer = speakerPlayer
            val currentPos = if (otherPlayer != null && otherPlayer.isPlaying) otherPlayer.currentPosition else 0
            it.seekTo(currentPos)
            if (isPlaying) it.start()
        }
    }

    private fun recreateSpeakerPlayer() {
        if (currentIndex == -1) return
        speakerPlayer?.release()
        speakerPlayer = createPlayer(audioList[currentIndex].uri) {
            // Prepared
            val otherPlayer = earpiecePlayer
            val currentPos = if (otherPlayer != null && otherPlayer.isPlaying) otherPlayer.currentPosition else 0
            it.seekTo(currentPos)
            if (isPlaying) it.start()
        }
    }

    private fun createPlayer(uri: Uri, audioDevice: AudioDeviceInfo? = null, onPreparedAction: (MediaPlayer) -> Unit): MediaPlayer {
        return MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            audioDevice?.let { setPreferredDevice(it) }
            setDataSource(this@MediaPlaybackService, uri)
            setOnPreparedListener { mp ->
                setupAudioEffectsForPlayer(mp, audioDevice != null)
                applyVolumeForPlayer(mp, audioDevice != null)
                onPreparedAction(mp)
            }
            setOnCompletionListener { checkCompletion() }
            prepareAsync()
        }
    }

    private fun applyVolumeForPlayer(player: MediaPlayer, isEarpiece: Boolean) {
        if (isEarpiece) {
            player.setVolume(earpieceAttenuation, 0.0f)
        } else {
            player.setVolume(0.0f, speakerAttenuation)
        }
    }

    fun togglePlayPause() {
        if (isPlaying) {
            pauseAudio()
        } else if (areAnyPlayersReady()) {
            resumeAudio()
        }
    }

    private fun playAudio() {
        if (!areAnyPlayersReady() || isPlaying) return
        val earpieceDelay = max(0, syncDelayMs).toLong()
        val speakerDelay = max(0, -syncDelayMs).toLong()
        serviceScope.launch { delay(earpieceDelay); earpiecePlayer?.start() }
        serviceScope.launch { delay(speakerDelay); speakerPlayer?.start() }
        isPlaying = true
        updateNotification()
        handler.post(updateSeekBarRunnable)
    }
    
    private fun resumeAudio() {
        if (!areAnyPlayersReady() || isPlaying) return
        playAudio()
    }

    private fun pauseAudio() {
        if (!areAnyPlayersReady()) return
        serviceScope.coroutineContext.cancelChildren()
        handler.removeCallbacksAndMessages(null)
        getAllPlayers().forEach { it?.takeIf { p -> p.isPlaying }?.pause() }
        isPlaying = false
        updateNotification()
        broadcastUpdate()
    }

    // --- 其他方法 ---
    fun setAudioList(list: List<AudioItem>) { this.audioList = list }
    fun seekTo(position: Int) { getAllPlayers().forEach { it?.seekTo(position) } }
    private fun playNext() { if (audioList.isNotEmpty()) playSongAtIndex((currentIndex + 1) % audioList.size) }
    private fun playPrev() { if (audioList.isNotEmpty()) playSongAtIndex((currentIndex - 1 + audioList.size) % audioList.size) }
    private fun checkCompletion() {
        val onePlayerFinished = earpiecePlayer?.isPlaying == false || speakerPlayer?.isPlaying == false
        if (isPlaying && onePlayerFinished) {
            // Wait a moment to see if the other player also finishes
            handler.postDelayed({
                if (earpiecePlayer?.isPlaying != true && speakerPlayer?.isPlaying != true) {
                    playNext()
                }
            }, 200)
        }
    }
    private fun setupAudioEffectsForPlayer(player: MediaPlayer, isEarpiece: Boolean) {
        try {
            if (isEarpiece) {
                earpieceEqualizer = Equalizer(0, player.audioSessionId).apply { setEnabled(true) }
                updateHighPassFilter(currentHighPassHz)
            } else {
                speakerEqualizer = Equalizer(0, player.audioSessionId).apply { setEnabled(true) }
                updateLowPassFilter(currentLowPassHz)
            }
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
    fun setSyncDelay(ms: Int) {
        this.syncDelayMs = ms
        if (isPlaying && areAnyPlayersReady()) {
            val pos = (earpiecePlayer?.currentPosition ?: speakerPlayer?.currentPosition) ?: 0
            pauseAudio()
            seekTo(pos)
            resumeAudio()
        }
    }
    private val updateSeekBarRunnable = object : Runnable { override fun run() { if (isPlaying && areAnyPlayersReady()) { broadcastUpdate(); handler.postDelayed(this, 1000) } } }
    private fun broadcastUpdate() {
        if (currentIndex !in audioList.indices) return
        val player = earpiecePlayer ?: speakerPlayer ?: return
        sendBroadcast(Intent(ACTION_UPDATE_UI).apply {
            putExtra("isPlaying", isPlaying); putExtra("currentIndex", currentIndex)
            putExtra("currentPosition", player.currentPosition); putExtra("duration", player.duration)
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
    private fun areAnyPlayersReady() = earpiecePlayer != null || speakerPlayer != null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action -> when (action) { ACTION_PLAY_PAUSE -> togglePlayPause(); ACTION_NEXT -> playNext(); ACTION_PREV -> playPrev() } }; return START_NOT_STICKY
    }
}

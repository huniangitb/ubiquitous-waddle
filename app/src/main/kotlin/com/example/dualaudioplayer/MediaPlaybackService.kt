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
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.*
import java.io.IOException
import kotlin.math.max

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
    private var earpieceEnhancer: LoudnessEnhancer? = null
    private var speakerEnhancer: LoudnessEnhancer? = null

    private var audioList: List<AudioItem> = emptyList()
    private var currentIndex = -1
    var isPlaying = false
        private set
    private var playersPreparedCount = 0
    private var syncDelayMs = 0

    inner class LocalBinder : Binder() {
        fun getService(): MediaPlaybackService = this@MediaPlaybackService
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let {
            when (it) {
                ACTION_PLAY_PAUSE -> togglePlayPause()
                ACTION_NEXT -> playNext()
                ACTION_PREV -> playPrev()
            }
        }
        return START_NOT_STICKY
    }

    fun setAudioList(list: List<AudioItem>) { this.audioList = list }

    fun playSongAtIndex(index: Int) {
        if (index < 0 || index >= audioList.size) return
        currentIndex = index
        releasePlayers()
        playersPreparedCount = 0
        try {
            val uri = audioList[index].uri
            earpiecePlayer = createPlayer(uri, AudioAttributes.USAGE_VOICE_COMMUNICATION)
            speakerPlayer = createPlayer(uri, AudioAttributes.USAGE_MEDIA)
        } catch (e: IOException) {
            Toast.makeText(this, "无法播放文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createPlayer(uri: Uri, usage: Int): MediaPlayer {
        return MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build())
            setDataSource(this@MediaPlaybackService, uri)
            setOnPreparedListener { onPlayerPrepared() }
            setOnCompletionListener { checkCompletion() }
            prepareAsync()
        }
    }

    @Synchronized
    private fun onPlayerPrepared() {
        playersPreparedCount++
        if (playersPreparedCount == 2) {
            earpiecePlayer?.setVolume(1.0f, 0.0f)
            speakerPlayer?.setVolume(0.0f, 1.0f)
            setupAudioEffects()
            playAudio()
        }
    }

    fun togglePlayPause() { if (isPlaying) pauseAudio() else resumeAudio() }

    private fun playAudio() {
        if (!areAllPlayersReady() || isPlaying) return
        val earpieceDelay = max(0, syncDelayMs).toLong()
        val speakerDelay = max(0, -syncDelayMs).toLong()
        serviceScope.launch { delay(earpieceDelay); earpiecePlayer?.start() }
        serviceScope.launch { delay(speakerDelay); speakerPlayer?.start() }
        isPlaying = true
        updateNotification()
        handler.post(updateSeekBarRunnable)
    }

    private fun resumeAudio() { if (areAllPlayersReady() && !isPlaying) playAudio() }

    private fun pauseAudio() {
        if (!areAllPlayersReady() || !isPlaying) return
        serviceScope.coroutineContext.cancelChildren()
        handler.removeCallbacksAndMessages(null)
        getAllPlayers().forEach { it?.takeIf { it.isPlaying }?.pause() }
        isPlaying = false
        updateNotification()
        broadcastUpdate()
    }

    fun seekTo(position: Int) { getAllPlayers().forEach { it?.seekTo(position) } }
    private fun playNext() { if (audioList.isNotEmpty()) playSongAtIndex((currentIndex + 1) % audioList.size) }
    private fun playPrev() { if (audioList.isNotEmpty()) playSongAtIndex((currentIndex - 1 + audioList.size) % audioList.size) }
    private fun checkCompletion() { if (isPlaying && earpiecePlayer?.isPlaying == false) playNext() }

    fun setSyncDelay(ms: Int) { this.syncDelayMs = ms }
    fun setEarpieceGainDb(db: Float) { earpieceEnhancer?.setTargetGain((db * 100).toInt()) }
    fun setSpeakerGainDb(db: Float) { speakerEnhancer?.setTargetGain((db * 100).toInt()) }

    private fun setupAudioEffects() {
        try {
            earpiecePlayer?.audioSessionId?.let {
                earpieceEqualizer = Equalizer(0, it).apply { setEnabled(true) }
                earpieceEnhancer = LoudnessEnhancer(it).apply { setEnabled(true) }
            }
            speakerPlayer?.audioSessionId?.let {
                speakerEqualizer = Equalizer(0, it).apply { setEnabled(true) }
                speakerEnhancer = LoudnessEnhancer(it).apply { setEnabled(true) }
            }
        } catch (e: Exception) { /* Ignore */ }
    }

    fun updateHighPassFilter(freqHz: Int) {
        earpieceEqualizer?.let { eq ->
            val cutoffFreqMilliHz = freqHz * 1000
            val minLevel = eq.bandLevelRange[0]
            for (i in 0 until eq.numberOfBands) {
                val bandIndex = i.toShort()
                eq.setBandLevel(bandIndex, if (eq.getCenterFreq(bandIndex) < cutoffFreqMilliHz) minLevel else 0.toShort())
            }
        }
    }

    fun updateLowPassFilter(freqHz: Int) {
        speakerEqualizer?.let { eq ->
            val cutoffFreqMilliHz = freqHz * 1000
            val minLevel = eq.bandLevelRange[0]
            for (i in 0 until eq.numberOfBands) {
                val bandIndex = i.toShort()
                eq.setBandLevel(bandIndex, if (eq.getCenterFreq(bandIndex) > cutoffFreqMilliHz) minLevel else 0.toShort())
            }
        }
    }

    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            if (isPlaying && areAllPlayersReady()) {
                broadcastUpdate()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private fun broadcastUpdate() {
        if (currentIndex !in audioList.indices) return
        val intent = Intent(ACTION_UPDATE_UI).apply {
            putExtra("isPlaying", isPlaying)
            putExtra("currentIndex", currentIndex)
            putExtra("currentPosition", earpiecePlayer?.currentPosition ?: 0)
            putExtra("duration", earpiecePlayer?.duration ?: 0)
        }
        sendBroadcast(intent)
    }

    private fun updateNotification() {
        if (currentIndex !in audioList.indices) return
        val currentItem = audioList[currentIndex]
        val pendingIntentFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val playPauseIntent = PendingIntent.getService(this, 0, Intent(this, MediaPlaybackService::class.java).setAction(ACTION_PLAY_PAUSE), pendingIntentFlag)
        val nextIntent = PendingIntent.getService(this, 0, Intent(this, MediaPlaybackService::class.java).setAction(ACTION_NEXT), pendingIntentFlag)
        val prevIntent = PendingIntent.getService(this, 0, Intent(this, MediaPlaybackService::class.java).setAction(ACTION_PREV), pendingIntentFlag)
        val playPauseAction = Notification.Action.Builder(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "Play/Pause", playPauseIntent).build()

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(currentItem.title).setContentText(currentItem.artist).setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", prevIntent).build())
            .addAction(playPauseAction)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", nextIntent).build())
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)).setOngoing(isPlaying)
        try {
            builder.setLargeIcon(MediaStore.Images.Media.getBitmap(this.contentResolver, currentItem.albumArtUri))
        } catch (e: IOException) { /* ignore */ }
        startForeground(NOTIFICATION_ID, builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Dual Audio Playback", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
        }
    }

    private fun releasePlayers() {
        handler.removeCallbacksAndMessages(null)
        serviceScope.coroutineContext.cancelChildren()
        getAllPlayers().forEach { it?.release() }
        earpiecePlayer = null; speakerPlayer = null
        earpieceEqualizer?.release(); speakerEqualizer?.release(); earpieceEnhancer?.release(); speakerEnhancer?.release()
        earpieceEqualizer = null; speakerEqualizer = null; earpieceEnhancer = null; speakerEnhancer = null
        isPlaying = false
    }

    private fun getAllPlayers() = listOf(earpiecePlayer, speakerPlayer)
    private fun areAllPlayersReady() = earpiecePlayer != null && speakerPlayer != null
    override fun onDestroy() { super.onDestroy(); releasePlayers(); serviceJob.cancel() }
}

package com.example.dualaudioplayer

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.dualaudioplayer.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.slider.Slider
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.round

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<LinearLayout>
    private lateinit var audioAdapter: AudioListAdapter

    private var mediaService: MediaPlaybackService? = null
    private var isBound = false
    private var audioList = emptyList<AudioItem>()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) loadAudioFiles()
            else Toast.makeText(this, "需要权限才能加载音频文件", Toast.LENGTH_LONG).show()
        }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MediaPlaybackService.ACTION_UPDATE_UI) {
                updateUI(
                    isPlaying = intent.getBooleanExtra("isPlaying", false),
                    currentIndex = intent.getIntExtra("currentIndex", -1),
                    currentPosition = intent.getIntExtra("currentPosition", 0),
                    duration = intent.getIntExtra("duration", 0)
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
        checkAndRequestPermission()
        Intent(this, MediaPlaybackService::class.java).also {
            startService(it)
            bindService(it, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun setupUI() {
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                true
            } else false
        }
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)
        audioAdapter = AudioListAdapter { audioItem -> mediaService?.playSongAtIndex(audioList.indexOf(audioItem)) }
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = audioAdapter
        }
        binding.fabPlayPause.setOnClickListener { mediaService?.togglePlayPause() }
        setupSliderListeners()
    }

    private fun setupSliderListeners() {
        binding.playbackSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                mediaService?.seekTo((slider.value * 1000).toInt())
            }
        })

        val sliderChangeListener = Slider.OnChangeListener { slider, value, _ ->
            when (slider.id) {
                R.id.earpieceGainSlider -> {
                    binding.earpieceGainLabel.text = "听筒增益: %.1f dB".format(Locale.US, value)
                    mediaService?.setEarpieceGainDb(value)
                }
                R.id.speakerGainSlider -> {
                    binding.speakerGainLabel.text = "扬声器增益: %.1f dB".format(Locale.US, value)
                    mediaService?.setSpeakerGainDb(value)
                }
                R.id.delaySlider -> {
                    val target = when { value == 0f -> "无"; value > 0f -> "听筒"; else -> "扬声器" }
                    binding.delayLabel.text = "同步延迟: %d ms (%s)".format(Locale.US, abs(value).toInt(), target)
                    mediaService?.setSyncDelay(value.toInt())
                }
                R.id.highPassFilterSlider -> {
                    binding.highPassFilterLabel.text = "听筒高通 (左): %d Hz".format(Locale.US, value.toInt())
                    if (binding.filterLockSwitch.isChecked) binding.lowPassFilterSlider.value = value
                    mediaService?.updateHighPassFilter(value.toInt())
                }
                R.id.lowPassFilterSlider -> {
                    binding.lowPassFilterLabel.text = "扬声器低通 (右): %d Hz".format(Locale.US, value.toInt())
                    if (binding.filterLockSwitch.isChecked) binding.highPassFilterSlider.value = value
                    mediaService?.updateLowPassFilter(value.toInt())
                }
            }
        }

        binding.earpieceGainSlider.addOnChangeListener(sliderChangeListener)
        binding.speakerGainSlider.addOnChangeListener(sliderChangeListener)
        binding.delaySlider.addOnChangeListener(sliderChangeListener)
        binding.highPassFilterSlider.addOnChangeListener(sliderChangeListener)
        binding.lowPassFilterSlider.addOnChangeListener(sliderChangeListener)

        val touchStopListener = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                when (slider.id) {
                    R.id.earpieceGainSlider -> saveFloat("earpieceGain", slider.value)
                    R.id.speakerGainSlider -> saveFloat("speakerGain", slider.value)
                    R.id.delaySlider -> saveInt("syncDelay", slider.value.toInt())
                    R.id.highPassFilterSlider -> saveInt("highPass", slider.value.toInt())
                    R.id.lowPassFilterSlider -> saveInt("lowPass", slider.value.toInt())
                }
            }
        }
        binding.earpieceGainSlider.addOnSliderTouchListener(touchStopListener)
        binding.speakerGainSlider.addOnSliderTouchListener(touchStopListener)
        binding.delaySlider.addOnSliderTouchListener(touchStopListener)
        binding.highPassFilterSlider.addOnSliderTouchListener(touchStopListener)
        binding.lowPassFilterSlider.addOnSliderTouchListener(touchStopListener)
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("DualAudioPrefs", MODE_PRIVATE)
        with(binding) {
            earpieceGainSlider.value = snapToStep(prefs.getFloat("earpieceGain", 0.0f), earpieceGainSlider.valueFrom, earpieceGainSlider.stepSize)
            speakerGainSlider.value = snapToStep(prefs.getFloat("speakerGain", 0.0f), speakerGainSlider.valueFrom, speakerGainSlider.stepSize)
            delaySlider.value = prefs.getInt("syncDelay", 0).toFloat()
            highPassFilterSlider.value = prefs.getInt("highPass", 50).toFloat()
            lowPassFilterSlider.value = prefs.getInt("lowPass", 15000).toFloat()
        }
    }

    private fun snapToStep(value: Float, valueFrom: Float, stepSize: Float): Float {
        if (stepSize <= 0) return value
        return round((value - valueFrom) / stepSize) * stepSize + valueFrom
    }

    private fun saveFloat(key: String, value: Float) = getSharedPreferences("DualAudioPrefs", MODE_PRIVATE).edit().putFloat(key, value).apply()
    private fun saveInt(key: String, value: Int) = getSharedPreferences("DualAudioPrefs", MODE_PRIVATE).edit().putInt(key, value).apply()

    private fun checkAndRequestPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> loadAudioFiles()
            else -> requestPermissionLauncher.launch(permission)
        }
    }

    private fun loadAudioFiles() {
        val tempList = mutableListOf<AudioItem>()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE); val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION); val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                val albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), cursor.getLong(albumIdCol))
                val itemUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol))
                tempList.add(AudioItem(itemUri, cursor.getString(titleCol), cursor.getString(artistCol), cursor.getInt(durCol), albumArtUri))
            }
        }
        audioList = tempList
        audioAdapter.submitList(audioList)
        mediaService?.setAudioList(audioList)
    }

    private fun updateUI(isPlaying: Boolean, currentIndex: Int, currentPosition: Int, duration: Int) {
        binding.fabPlayPause.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        if (currentIndex in audioList.indices) { binding.statusTextView.text = audioList[currentIndex].title }
        val durationInSeconds = if (duration > 0) duration / 1000 else 100
        val currentPositionInSeconds = currentPosition / 1000
        binding.playbackSlider.valueTo = durationInSeconds.toFloat()
        binding.playbackSlider.value = currentPositionInSeconds.toFloat()
        binding.currentTimeTextView.text = formatTime(currentPosition)
        binding.totalTimeTextView.text = formatTime(duration)
    }

    private fun formatTime(millis: Int): String = String.format(Locale.US, "%d:%02d", TimeUnit.MILLISECONDS.toMinutes(millis.toLong()), TimeUnit.MILLISECONDS.toSeconds(millis.toLong()) % 60)

    override fun onStart() { super.onStart(); registerReceiver(updateReceiver, IntentFilter(MediaPlaybackService.ACTION_UPDATE_UI)) }
    override fun onStop() { super.onStop(); unregisterReceiver(updateReceiver) }
    override fun onDestroy() { super.onDestroy(); if (isBound) { unbindService(connection); isBound = false } }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as MediaPlaybackService.LocalBinder
            mediaService = binder.getService(); isBound = true
            mediaService?.setAudioList(audioList)
            loadSettings()
            // After loading settings, update the service with initial values
            mediaService?.setEarpieceGainDb(binding.earpieceGainSlider.value)
            mediaService?.setSpeakerGainDb(binding.speakerGainSlider.value)
            mediaService?.setSyncDelay(binding.delaySlider.value.toInt())
            mediaService?.updateHighPassFilter(binding.highPassFilterSlider.value.toInt())
            mediaService?.updateLowPassFilter(binding.lowPassFilterSlider.value.toInt())
        }
        override fun onServiceDisconnected(name: ComponentName) { isBound = false }
    }
}

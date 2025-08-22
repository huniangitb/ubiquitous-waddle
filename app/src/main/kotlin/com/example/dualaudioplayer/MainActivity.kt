package com.example.dualaudioplayer

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
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

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.READ_MEDIA_AUDIO] == true) {
                loadAudioFiles()
            } else {
                Toast.makeText(this, "需要读取音频权限才能使用", Toast.LENGTH_LONG).show()
            }
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
        setSupportActionBar(binding.toolbar)
        setupUI()
        checkAndRequestPermissions()
        Intent(this, MediaPlaybackService::class.java).also {
            startService(it)
            bindService(it, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun setupUI() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)
        audioAdapter = AudioListAdapter { audioItem -> mediaService?.playSongAtIndex(audioList.indexOf(audioItem)) }
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = audioAdapter
        }
        binding.fabPlayPause.setOnClickListener { mediaService?.togglePlayPause() }
        setupAdvancedControlListeners()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupAdvancedControlListeners() {
        // Playback Slider
        binding.playbackSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) { mediaService?.seekTo((slider.value * 1000).toInt()) }
        })

        // Mute Switches
        binding.enableEarpieceSwitch.setOnCheckedChangeListener { _, isChecked ->
            mediaService?.setEarpieceEnabled(isChecked)
            saveBoolean("earpieceEnabled", isChecked)
        }
        binding.enableSpeakerSwitch.setOnCheckedChangeListener { _, isChecked ->
            mediaService?.setSpeakerEnabled(isChecked)
            saveBoolean("speakerEnabled", isChecked)
        }
        
        // Other Sliders
        val listener = Slider.OnChangeListener { slider, value, fromUser ->
            if (!fromUser) return@OnChangeListener
            when (slider.id) {
                R.id.earpieceGainSlider -> {
                    binding.earpieceGainLabel.text = "听筒衰减: %.1f dB".format(Locale.US, value)
                    mediaService?.setEarpieceAttenuationDb(value)
                }
                R.id.speakerGainSlider -> {
                    binding.speakerGainLabel.text = "扬声器衰减: %.1f dB".format(Locale.US, value)
                    mediaService?.setSpeakerAttenuationDb(value)
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
        binding.earpieceGainSlider.addOnChangeListener(listener); binding.speakerGainSlider.addOnChangeListener(listener)
        binding.delaySlider.addOnChangeListener(listener); binding.highPassFilterSlider.addOnChangeListener(listener)
        binding.lowPassFilterSlider.addOnChangeListener(listener)
        
        val stopListener = object : Slider.OnSliderTouchListener {
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
        binding.earpieceGainSlider.addOnSliderTouchListener(stopListener); binding.speakerGainSlider.addOnSliderTouchListener(stopListener)
        binding.delaySlider.addOnSliderTouchListener(stopListener); binding.highPassFilterSlider.addOnSliderTouchListener(stopListener)
        binding.lowPassFilterSlider.addOnSliderTouchListener(stopListener)
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("DualAudioPrefs", MODE_PRIVATE)
        with(binding) {
            earpieceGainSlider.value = snapToStep(prefs.getFloat("earpieceGain", 0.0f), earpieceGainSlider.valueFrom, earpieceGainSlider.stepSize)
            speakerGainSlider.value = snapToStep(prefs.getFloat("speakerGain", 0.0f), speakerGainSlider.valueFrom, speakerGainSlider.stepSize)
            delaySlider.value = prefs.getInt("syncDelay", 0).toFloat()
            highPassFilterSlider.value = prefs.getInt("highPass", 50).toFloat()
            lowPassFilterSlider.value = prefs.getInt("lowPass", 15000).toFloat()
            enableEarpieceSwitch.isChecked = prefs.getBoolean("earpieceEnabled", true)
            enableSpeakerSwitch.isChecked = prefs.getBoolean("speakerEnabled", true)
        }
    }
    private fun snapToStep(value: Float, valueFrom: Float, stepSize: Float): Float {
        if (stepSize <= 0) return value
        return round((value - valueFrom) / stepSize) * stepSize + valueFrom
    }
    private fun saveFloat(key: String, value: Float) = getSharedPreferences("DualAudioPrefs", MODE_PRIVATE).edit().putFloat(key, value).apply()
    private fun saveInt(key: String, value: Int) = getSharedPreferences("DualAudioPrefs", MODE_PRIVATE).edit().putInt(key, value).apply()
    private fun saveBoolean(key: String, value: Boolean) = getSharedPreferences("DualAudioPrefs", MODE_PRIVATE).edit().putBoolean(key, value).apply()
    
    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.READ_MEDIA_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { permissions.add(Manifest.permission.POST_NOTIFICATIONS) }
        val toRequest = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isEmpty()) loadAudioFiles() else requestPermissionsLauncher.launch(toRequest.toTypedArray())
    }
    private fun loadAudioFiles() {
        val list = mutableListOf<AudioItem>()
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID)
        contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE); val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION); val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (c.moveToNext()) {
                val artUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), c.getLong(albumIdCol))
                val itemUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol))
                list.add(AudioItem(itemUri, c.getString(titleCol), c.getString(artistCol), c.getInt(durCol), artUri))
            }
        }
        audioList = list; audioAdapter.submitList(audioList); mediaService?.setAudioList(audioList)
    }
    private fun updateUI(isPlaying: Boolean, currentIndex: Int, currentPosition: Int, duration: Int) {
        binding.fabPlayPause.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        if (currentIndex in audioList.indices) { binding.statusTextView.text = audioList[currentIndex].title }
        val durationInSeconds = duration / 1000f; val currentPositionInSeconds = currentPosition / 1000f
        val sliderValueTo = if (durationInSeconds > 0) durationInSeconds else 1f
        binding.playbackSlider.valueTo = sliderValueTo
        binding.playbackSlider.value = currentPositionInSeconds.coerceIn(0f, sliderValueTo)
        binding.currentTimeTextView.text = formatTime(currentPosition); binding.totalTimeTextView.text = formatTime(duration)
    }
    private fun formatTime(millis: Int): String = String.format(Locale.US, "%d:%02d", TimeUnit.MILLISECONDS.toMinutes(millis.toLong()), TimeUnit.MILLISECONDS.toSeconds(millis.toLong()) % 60)
    override fun onStart() { super.onStart(); registerReceiver(updateReceiver, IntentFilter(MediaPlaybackService.ACTION_UPDATE_UI), RECEIVER_NOT_EXPORTED) }
    override fun onStop() { super.onStop(); unregisterReceiver(updateReceiver) }
    override fun onDestroy() { super.onDestroy(); if (isBound) { unbindService(connection); isBound = false } }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as MediaPlaybackService.LocalBinder
            mediaService = binder.getService(); isBound = true
            mediaService?.setAudioList(audioList)
            loadSettings()
            // 将所有初始状态传递给服务
            mediaService?.setEarpieceAttenuationDb(binding.earpieceGainSlider.value)
            mediaService?.setSpeakerAttenuationDb(binding.speakerGainSlider.value)
            mediaService?.setEarpieceEnabled(binding.enableEarpieceSwitch.isChecked)
            mediaService?.setSpeakerEnabled(binding.enableSpeakerSwitch.isChecked)
            mediaService?.setSyncDelay(binding.delaySlider.value.toInt())
            mediaService?.updateHighPassFilter(binding.highPassFilterSlider.value.toInt())
            mediaService?.updateLowPassFilter(binding.lowPassFilterSlider.value.toInt())
        }
        override fun onServiceDisconnected(name: ComponentName) { isBound = false }
    }
}

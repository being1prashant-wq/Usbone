package com.example

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.media.AudioCacheManager
import com.example.media.PhotoThumbnailLoader
import com.example.media.PtpMediaItem
import com.example.media.PtpMediaRepository
import com.example.media.VideoCacheManager
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import com.example.usb.UsbConnectionState
import com.example.usb.UsbHostManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen {
    START,
    HOME,
    PHOTO_BROWSER,
    PHOTO_VIEWER,
    VIDEO_BROWSER,
    VIDEO_PLAYER,
    AUDIO_BROWSER,
    AUDIO_PLAYER
}

class MainActivity : AppCompatActivity() {

    private lateinit var usbHostManager: UsbHostManager
    private lateinit var repository: PtpMediaRepository
    private lateinit var thumbnailLoader: PhotoThumbnailLoader
    private lateinit var videoCacheManager: VideoCacheManager
    private lateinit var audioCacheManager: AudioCacheManager

    // UI Screen containers
    private lateinit var screenStart: View
    private lateinit var screenHome: View
    private lateinit var screenPhotoBrowser: View
    private lateinit var screenPhotoViewer: View
    private lateinit var screenVideoBrowser: View
    private lateinit var screenVideoPlayer: View
    private lateinit var screenAudioBrowser: View
    private lateinit var screenAudioPlayer: View

    // Start Screen Views
    private lateinit var tvStartStatus: TextView

    // Home Screen Views
    private lateinit var tvHomeDeviceName: TextView
    private lateinit var btnPhotos: Button
    private lateinit var btnVideos: Button
    private lateinit var btnAudio: Button

    // Photo Browser Views
    private lateinit var rvPhotos: RecyclerView
    private lateinit var tvPhotosCount: TextView
    private lateinit var tvEmptyPhotos: TextView
    private lateinit var photoAdapter: PhotoAdapter

    // Photo Viewer Views
    private lateinit var ivFullPhoto: ImageView
    private lateinit var progressPhoto: ProgressBar
    private lateinit var tvPhotoError: TextView
    private var currentPhotoIndex = 0
    private var photoLoadJob: Job? = null

    // Video Browser Views
    private lateinit var rvVideos: RecyclerView
    private lateinit var tvVideosCount: TextView
    private lateinit var tvEmptyVideos: TextView
    private lateinit var videoAdapter: VideoAdapter

    // Video Player Views
    private lateinit var videoView: VideoView
    private lateinit var layoutVideoBuffering: View
    private lateinit var tvVideoError: TextView
    private var videoCachingJob: Job? = null

    // Audio Browser Views
    private lateinit var rvAudio: RecyclerView
    private lateinit var tvAudioCount: TextView
    private lateinit var tvEmptyAudio: TextView
    private lateinit var audioAdapter: AudioAdapter

    // Audio Player Views
    private lateinit var tvAudioPlayerTitle: TextView
    private lateinit var tvAudioPlayerStatus: TextView
    private lateinit var tvAudioPlayerTime: TextView
    private lateinit var progressAudioSeek: ProgressBar
    private lateinit var layoutAudioBuffering: View
    private lateinit var tvAudioError: TextView
    private var audioPlayer: MediaPlayer? = null
    private var audioCachingJob: Job? = null
    private var audioProgressJob: Job? = null

    private var currentScreen: Screen = Screen.START

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initServices()
        showScreen(Screen.START)
    }

    private fun initViews() {
        screenStart = findViewById(R.id.screen_start)
        screenHome = findViewById(R.id.screen_home)
        screenPhotoBrowser = findViewById(R.id.screen_photo_browser)
        screenPhotoViewer = findViewById(R.id.screen_photo_viewer)
        screenVideoBrowser = findViewById(R.id.screen_video_browser)
        screenVideoPlayer = findViewById(R.id.screen_video_player)
        screenAudioBrowser = findViewById(R.id.screen_audio_browser)
        screenAudioPlayer = findViewById(R.id.screen_audio_player)

        tvStartStatus = findViewById(R.id.tv_start_status)
        tvHomeDeviceName = findViewById(R.id.tv_home_device_name)
        btnPhotos = findViewById(R.id.btn_photos)
        btnVideos = findViewById(R.id.btn_videos)
        btnAudio = findViewById(R.id.btn_audio)

        // Home button actions
        btnPhotos.setOnClickListener {
            showScreen(Screen.PHOTO_BROWSER)
            rvPhotos.requestFocus()
        }
        btnVideos.setOnClickListener {
            showScreen(Screen.VIDEO_BROWSER)
            rvVideos.requestFocus()
        }
        btnAudio.setOnClickListener {
            showScreen(Screen.AUDIO_BROWSER)
            rvAudio.requestFocus()
        }

        // Photo Browser
        rvPhotos = findViewById(R.id.rv_photos)
        tvPhotosCount = findViewById(R.id.tv_photos_count)
        tvEmptyPhotos = findViewById(R.id.tv_empty_photos)
        rvPhotos.layoutManager = GridLayoutManager(this, 5)

        // Photo Viewer
        ivFullPhoto = findViewById(R.id.iv_full_photo)
        progressPhoto = findViewById(R.id.progress_photo)
        tvPhotoError = findViewById(R.id.tv_photo_error)

        // Video Browser
        rvVideos = findViewById(R.id.rv_videos)
        tvVideosCount = findViewById(R.id.tv_videos_count)
        tvEmptyVideos = findViewById(R.id.tv_empty_videos)
        rvVideos.layoutManager = GridLayoutManager(this, 4)

        // Video Player
        videoView = findViewById(R.id.video_view)
        layoutVideoBuffering = findViewById(R.id.layout_video_buffering)
        tvVideoError = findViewById(R.id.tv_video_error)

        // Audio Browser
        rvAudio = findViewById(R.id.rv_audio)
        tvAudioCount = findViewById(R.id.tv_audio_count)
        tvEmptyAudio = findViewById(R.id.tv_empty_audio)
        rvAudio.layoutManager = GridLayoutManager(this, 4)

        // Audio Player
        tvAudioPlayerTitle = findViewById(R.id.tv_audio_player_title)
        tvAudioPlayerStatus = findViewById(R.id.tv_audio_player_status)
        tvAudioPlayerTime = findViewById(R.id.tv_audio_player_time)
        progressAudioSeek = findViewById(R.id.progress_audio_seek)
        layoutAudioBuffering = findViewById(R.id.layout_audio_buffering)
        tvAudioError = findViewById(R.id.tv_audio_error)
    }

    private fun initServices() {
        repository = PtpMediaRepository(this)
        thumbnailLoader = PhotoThumbnailLoader(lifecycleScope) { repository.client }
        videoCacheManager = VideoCacheManager(this)
        audioCacheManager = AudioCacheManager(this)

        photoAdapter = PhotoAdapter(
            items = repository.photoItems,
            thumbnailLoader = thumbnailLoader,
            onItemClicked = { index ->
                currentPhotoIndex = index
                showScreen(Screen.PHOTO_VIEWER)
                loadSelectedPhoto(index)
            }
        )
        rvPhotos.adapter = photoAdapter

        videoAdapter = VideoAdapter(
            items = repository.videoItems,
            thumbnailLoader = thumbnailLoader,
            onItemBound = { item ->
                lifecycleScope.launch {
                    val updated = repository.fetchMetadataIfNeeded(item)
                    if (updated.isMetadataLoaded) {
                        val idx = repository.videoItems.indexOf(item)
                        if (idx >= 0) videoAdapter.notifyItemChanged(idx)
                    }
                }
            },
            onItemClicked = { item ->
                startVideoPlayback(item)
            }
        )
        rvVideos.adapter = videoAdapter

        audioAdapter = AudioAdapter(
            items = repository.audioItems,
            thumbnailLoader = thumbnailLoader,
            onItemBound = { item ->
                lifecycleScope.launch {
                    val updated = repository.fetchMetadataIfNeeded(item)
                    if (updated.isMetadataLoaded) {
                        val idx = repository.audioItems.indexOf(item)
                        if (idx >= 0) audioAdapter.notifyItemChanged(idx)
                    }
                }
            },
            onItemClicked = { item ->
                startAudioPlayback(item)
            }
        )
        rvAudio.adapter = audioAdapter

        usbHostManager = UsbHostManager(this) { state ->
            handleUsbState(state)
        }
    }

    override fun onStart() {
        super.onStart()
        usbHostManager.start()
    }

    override fun onStop() {
        super.onStop()
        stopAndClearVideoPlayer()
        stopAndClearAudioPlayer()
        photoLoadJob?.cancel()
        videoCachingJob?.cancel()
        audioCachingJob?.cancel()
        audioProgressJob?.cancel()
        thumbnailLoader.clear()
        usbHostManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        videoCacheManager.clearCache()
        audioCacheManager.clearCache()
        lifecycleScope.launch {
            repository.clear()
        }
    }

    private var initJob: kotlinx.coroutines.Job? = null

    private fun handleUsbState(state: UsbConnectionState) {
        when (state) {
            is UsbConnectionState.Idle -> {
                initJob?.cancel()
                tvStartStatus.text = ""
                showScreen(Screen.START)
            }
            is UsbConnectionState.DeviceAttached -> {
                tvStartStatus.text = getString(R.string.ptp_device_detected)
            }
            is UsbConnectionState.PermissionRequired -> {
                tvStartStatus.text = getString(R.string.permission_required)
            }
            is UsbConnectionState.PermissionDenied -> {
                initJob?.cancel()
                tvStartStatus.text = getString(R.string.permission_denied)
            }
            is UsbConnectionState.Connected -> {
                initJob?.cancel()
                tvStartStatus.text = "PTP Initializing..."
                showScreen(Screen.START)
                initJob = lifecycleScope.launch {
                    try {
                        val success = repository.initialize(state.client, state.deviceName)
                        if (success) {
                            tvHomeDeviceName.text = repository.deviceName
                            updateMediaCounts()
                            showScreen(Screen.HOME)
                            btnPhotos.requestFocus()
                        } else {
                            usbHostManager.disconnect()
                            tvStartStatus.text = "PTP initialization failed.\nPlease check phone USB mode is set to 'PTP / Transfer photos'."
                            showScreen(Screen.START)
                        }
                    } catch (e: Exception) {
                        Log.e(PtpConstants.TAG, "Error during PTP initialization", e)
                        usbHostManager.disconnect()
                        tvStartStatus.text = "Connection error during PTP setup.\nPlease reconnect USB."
                        showScreen(Screen.START)
                    }
                }
            }
            is UsbConnectionState.Error -> {
                initJob?.cancel()
                tvStartStatus.text = state.message
                showScreen(Screen.START)
            }
            is UsbConnectionState.Disconnected -> {
                initJob?.cancel()
                stopAndClearVideoPlayer()
                stopAndClearAudioPlayer()
                photoLoadJob?.cancel()
                videoCachingJob?.cancel()
                audioCachingJob?.cancel()
                audioProgressJob?.cancel()
                thumbnailLoader.clear()
                lifecycleScope.launch {
                    try {
                        repository.clear()
                    } catch (_: Exception) {}
                }
                tvStartStatus.text = getString(R.string.phone_disconnected)
                showScreen(Screen.START)
            }
        }
    }

    private fun updateMediaCounts() {
        photoAdapter.notifyDataSetChanged()
        videoAdapter.notifyDataSetChanged()
        audioAdapter.notifyDataSetChanged()

        val pCount = repository.photoItems.size
        tvPhotosCount.text = " ($pCount items)"
        tvEmptyPhotos.visibility = if (pCount == 0) View.VISIBLE else View.GONE

        val vCount = repository.videoItems.size
        tvVideosCount.text = " ($vCount items)"
        tvEmptyVideos.visibility = if (vCount == 0) View.VISIBLE else View.GONE

        val aCount = repository.audioItems.size
        tvAudioCount.text = " ($aCount items)"
        tvEmptyAudio.visibility = if (aCount == 0) View.VISIBLE else View.GONE
    }

    private fun showScreen(screen: Screen) {
        currentScreen = screen
        screenStart.visibility = if (screen == Screen.START) View.VISIBLE else View.GONE
        screenHome.visibility = if (screen == Screen.HOME) View.VISIBLE else View.GONE
        screenPhotoBrowser.visibility = if (screen == Screen.PHOTO_BROWSER) View.VISIBLE else View.GONE
        screenPhotoViewer.visibility = if (screen == Screen.PHOTO_VIEWER) View.VISIBLE else View.GONE
        screenVideoBrowser.visibility = if (screen == Screen.VIDEO_BROWSER) View.VISIBLE else View.GONE
        screenVideoPlayer.visibility = if (screen == Screen.VIDEO_PLAYER) View.VISIBLE else View.GONE
        screenAudioBrowser.visibility = if (screen == Screen.AUDIO_BROWSER) View.VISIBLE else View.GONE
        screenAudioPlayer.visibility = if (screen == Screen.AUDIO_PLAYER) View.VISIBLE else View.GONE

        if (screen == Screen.PHOTO_VIEWER) {
            screenPhotoViewer.requestFocus()
        } else if (screen == Screen.VIDEO_PLAYER) {
            screenVideoPlayer.requestFocus()
        } else if (screen == Screen.AUDIO_PLAYER) {
            screenAudioPlayer.requestFocus()
        }
    }

    private fun loadSelectedPhoto(index: Int) {
        if (index < 0 || index >= repository.photoItems.size) return
        val item = repository.photoItems[index]

        photoLoadJob?.cancel()
        ivFullPhoto.setImageBitmap(null)
        progressPhoto.visibility = View.VISIBLE
        tvPhotoError.visibility = View.GONE

        photoLoadJob = lifecycleScope.launch {
            val bitmap = repository.loadFullPhoto(item.handle, maxDim = 1920)
            progressPhoto.visibility = View.GONE
            if (bitmap != null) {
                ivFullPhoto.setImageBitmap(bitmap)
                tvPhotoError.visibility = View.GONE
            } else {
                tvPhotoError.visibility = View.VISIBLE
            }
        }
    }

    private fun startVideoPlayback(item: PtpMediaItem) {
        val client = repository.client ?: return
        showScreen(Screen.VIDEO_PLAYER)

        videoView.visibility = View.GONE
        tvVideoError.visibility = View.GONE
        layoutVideoBuffering.visibility = View.VISIBLE

        videoCachingJob?.cancel()
        videoCachingJob = lifecycleScope.launch {
            val file = videoCacheManager.cacheVideo(client, item.handle, item.filename)
            if (file != null && file.exists()) {
                videoView.visibility = View.VISIBLE
                videoView.setVideoPath(file.absolutePath)
                videoView.setOnPreparedListener { mp ->
                    layoutVideoBuffering.visibility = View.GONE
                    mp.isLooping = false
                    videoView.start()
                }
                videoView.setOnErrorListener { _, what, extra ->
                    Log.e(PtpConstants.TAG, "VideoView playback error: what=$what extra=$extra")
                    layoutVideoBuffering.visibility = View.GONE
                    tvVideoError.visibility = View.VISIBLE
                    true
                }
                videoView.setOnCompletionListener {
                    // Finished playing
                }
            } else {
                layoutVideoBuffering.visibility = View.GONE
                tvVideoError.text = getString(R.string.video_load_failed)
                tvVideoError.visibility = View.VISIBLE
            }
        }
    }

    private fun stopAndClearVideoPlayer() {
        videoCachingJob?.cancel()
        if (videoView.isPlaying) {
            videoView.stopPlayback()
        }
        videoCacheManager.clearCache()
    }

    private fun startAudioPlayback(item: PtpMediaItem) {
        val client = repository.client ?: return
        showScreen(Screen.AUDIO_PLAYER)

        tvAudioPlayerTitle.text = item.displayName
        tvAudioPlayerStatus.text = "Caching track..."
        tvAudioPlayerTime.text = "00:00 / 00:00"
        progressAudioSeek.progress = 0
        tvAudioError.visibility = View.GONE
        layoutAudioBuffering.visibility = View.VISIBLE

        stopAudioPlaybackOnly()

        audioCachingJob?.cancel()
        audioCachingJob = lifecycleScope.launch {
            val file = audioCacheManager.cacheAudio(client, item.handle, item.filename)
            if (file != null && file.exists()) {
                layoutAudioBuffering.visibility = View.GONE
                tvAudioPlayerStatus.text = "[ PLAYING ]"
                try {
                    val mp = MediaPlayer()
                    audioPlayer = mp
                    mp.setDataSource(file.absolutePath)
                    mp.setOnPreparedListener { player ->
                        layoutAudioBuffering.visibility = View.GONE
                        player.start()
                        tvAudioPlayerStatus.text = "[ PLAYING ]"
                        startAudioProgressLoop(player)
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        Log.e(PtpConstants.TAG, "Audio playback error: what=$what extra=$extra")
                        layoutAudioBuffering.visibility = View.GONE
                        tvAudioError.text = getString(R.string.audio_codec_unsupported)
                        tvAudioError.visibility = View.VISIBLE
                        tvAudioPlayerStatus.text = "[ ERROR ]"
                        true
                    }
                    mp.setOnCompletionListener {
                        tvAudioPlayerStatus.text = "[ FINISHED ]"
                        progressAudioSeek.progress = 100
                    }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    Log.e(PtpConstants.TAG, "Error initializing MediaPlayer for audio", e)
                    layoutAudioBuffering.visibility = View.GONE
                    tvAudioError.text = getString(R.string.audio_load_failed)
                    tvAudioError.visibility = View.VISIBLE
                    tvAudioPlayerStatus.text = "[ ERROR ]"
                }
            } else {
                layoutAudioBuffering.visibility = View.GONE
                tvAudioError.text = getString(R.string.audio_load_failed)
                tvAudioError.visibility = View.VISIBLE
                tvAudioPlayerStatus.text = "[ FAILED ]"
            }
        }
    }

    private fun startAudioProgressLoop(player: MediaPlayer) {
        audioProgressJob?.cancel()
        audioProgressJob = lifecycleScope.launch {
            while (currentScreen == Screen.AUDIO_PLAYER && audioPlayer == player) {
                try {
                    if (player.isPlaying) {
                        val current = player.currentPosition
                        val total = player.duration
                        if (total > 0) {
                            val percent = (current.toLong() * 100 / total).toInt()
                            progressAudioSeek.progress = percent
                            tvAudioPlayerTime.text = "${formatTime(current)} / ${formatTime(total)}"
                        }
                    }
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private fun formatTime(millis: Int): String {
        val totalSecs = millis / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format("%02d:%02d", mins, secs)
    }

    private fun stopAudioPlaybackOnly() {
        audioProgressJob?.cancel()
        audioPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        audioPlayer = null
    }

    private fun stopAndClearAudioPlayer() {
        audioCachingJob?.cancel()
        stopAudioPlaybackOnly()
        audioCacheManager.clearCache()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (currentScreen) {
            Screen.PHOTO_VIEWER -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (currentPhotoIndex > 0) {
                            currentPhotoIndex--
                            loadSelectedPhoto(currentPhotoIndex)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (currentPhotoIndex < repository.photoItems.size - 1) {
                            currentPhotoIndex++
                            loadSelectedPhoto(currentPhotoIndex)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        photoLoadJob?.cancel()
                        ivFullPhoto.setImageBitmap(null)
                        showScreen(Screen.PHOTO_BROWSER)
                        rvPhotos.requestFocus()
                        return true
                    }
                }
            }
            Screen.VIDEO_PLAYER -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (videoView.isPlaying) {
                            videoView.pause()
                        } else {
                            videoView.start()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val pos = (videoView.currentPosition - 10000).coerceAtLeast(0)
                        videoView.seekTo(pos)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val pos = (videoView.currentPosition + 10000).coerceAtMost(videoView.duration)
                        videoView.seekTo(pos)
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        stopAndClearVideoPlayer()
                        showScreen(Screen.VIDEO_BROWSER)
                        rvVideos.requestFocus()
                        return true
                    }
                }
            }
            Screen.AUDIO_PLAYER -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        audioPlayer?.let { mp ->
                            try {
                                if (mp.isPlaying) {
                                    mp.pause()
                                    tvAudioPlayerStatus.text = "[ PAUSED ]"
                                } else {
                                    mp.start()
                                    tvAudioPlayerStatus.text = "[ PLAYING ]"
                                }
                            } catch (_: Exception) {}
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        audioPlayer?.let { mp ->
                            try {
                                val pos = (mp.currentPosition - 10000).coerceAtLeast(0)
                                mp.seekTo(pos)
                            } catch (_: Exception) {}
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        audioPlayer?.let { mp ->
                            try {
                                val pos = (mp.currentPosition + 10000).coerceAtMost(mp.duration)
                                mp.seekTo(pos)
                            } catch (_: Exception) {}
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        stopAndClearAudioPlayer()
                        showScreen(Screen.AUDIO_BROWSER)
                        rvAudio.requestFocus()
                        return true
                    }
                }
            }
            Screen.PHOTO_BROWSER -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    showScreen(Screen.HOME)
                    btnPhotos.requestFocus()
                    return true
                }
            }
            Screen.VIDEO_BROWSER -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    showScreen(Screen.HOME)
                    btnVideos.requestFocus()
                    return true
                }
            }
            Screen.AUDIO_BROWSER -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    showScreen(Screen.HOME)
                    btnAudio.requestFocus()
                    return true
                }
            }
            Screen.HOME -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    finish()
                    return true
                }
            }
            Screen.START -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    finish()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val ivThumb: ImageView = view.findViewById(R.id.iv_thumb)
}

class PhotoAdapter(
    private val items: List<PtpMediaItem>,
    private val thumbnailLoader: PhotoThumbnailLoader,
    private val onItemClicked: (Int) -> Unit
) : RecyclerView.Adapter<PhotoViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val item = items[position]
        holder.itemView.tag = item.handle
        holder.itemView.setOnClickListener { onItemClicked(position) }
        thumbnailLoader.loadThumbnail(item.handle, holder.ivThumb, R.drawable.ic_photo_placeholder)
    }

    override fun getItemCount(): Int = items.size
}

class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val ivThumb: ImageView = view.findViewById(R.id.iv_video_thumb)
    val tvName: TextView = view.findViewById(R.id.tv_video_name)
    val tvSize: TextView = view.findViewById(R.id.tv_video_size)
}

class VideoAdapter(
    private val items: List<PtpMediaItem>,
    private val thumbnailLoader: PhotoThumbnailLoader,
    private val onItemBound: (PtpMediaItem) -> Unit,
    private val onItemClicked: (PtpMediaItem) -> Unit
) : RecyclerView.Adapter<VideoViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val item = items[position]
        holder.itemView.tag = item.handle
        holder.tvName.text = item.displayName
        holder.tvSize.text = item.formattedSize

        holder.itemView.setOnClickListener { onItemClicked(item) }

        thumbnailLoader.loadThumbnail(item.handle, holder.ivThumb, R.drawable.ic_video_placeholder)

        if (!item.isMetadataLoaded) {
            onItemBound(item)
        }
    }

    override fun getItemCount(): Int = items.size
}

class AudioViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val ivThumb: ImageView = view.findViewById(R.id.iv_audio_thumb)
    val tvName: TextView = view.findViewById(R.id.tv_audio_name)
    val tvSize: TextView = view.findViewById(R.id.tv_audio_size)
}

class AudioAdapter(
    private val items: List<PtpMediaItem>,
    private val thumbnailLoader: PhotoThumbnailLoader,
    private val onItemBound: (PtpMediaItem) -> Unit,
    private val onItemClicked: (PtpMediaItem) -> Unit
) : RecyclerView.Adapter<AudioViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AudioViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_audio, parent, false)
        return AudioViewHolder(view)
    }

    override fun onBindViewHolder(holder: AudioViewHolder, position: Int) {
        val item = items[position]
        holder.itemView.tag = item.handle
        holder.tvName.text = item.displayName
        holder.tvSize.text = item.formattedSize

        holder.itemView.setOnClickListener { onItemClicked(item) }

        thumbnailLoader.loadThumbnail(item.handle, holder.ivThumb, R.drawable.ic_audio_placeholder)

        if (!item.isMetadataLoaded) {
            onItemBound(item)
        }
    }

    override fun getItemCount(): Int = items.size
}

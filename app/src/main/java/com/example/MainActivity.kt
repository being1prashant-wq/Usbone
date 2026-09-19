package com.example

import android.content.Intent
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
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import java.io.File

enum class Screen {
    START,
    HOME,
    PHOTO_BROWSER,
    PHOTO_VIEWER,
    VIDEO_BROWSER,
    VIDEO_PLAYER
}

class MainActivity : AppCompatActivity() {

    private lateinit var usbHostManager: UsbHostManager
    private lateinit var repository: PtpMediaRepository
    private lateinit var thumbnailLoader: PhotoThumbnailLoader
    private lateinit var videoCacheManager: VideoCacheManager

    // UI Screen containers
    private lateinit var screenStart: View
    private lateinit var screenHome: View
    private lateinit var screenPhotoBrowser: View
    private lateinit var screenPhotoViewer: View
    private lateinit var screenVideoBrowser: View
    private lateinit var screenVideoPlayer: View

    // Start Screen Views
    private lateinit var tvStartStatus: TextView

    // Home Screen Views
    private lateinit var tvHomeDeviceName: TextView
    private lateinit var btnPhotos: Button
    private lateinit var btnVideos: Button

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
    private lateinit var layoutVideoScanning: View
    private lateinit var tvVideoScanningStatus: TextView
    private lateinit var videoAdapter: VideoAdapter

    // Video Player Views
    private lateinit var videoView: VideoView
    private lateinit var layoutVideoBuffering: View
    private lateinit var tvVideoError: TextView

    // Video Selection Dialog Views
    private lateinit var layoutVideoOptionsDialog: View
    private lateinit var tvDialogVideoTitle: TextView
    private lateinit var tvDialogVideoMeta: TextView
    private lateinit var btnPlayDirectusb: Button
    private lateinit var btnPlayExternal: Button
    private lateinit var btnCancelPlay: Button

    // Video Preparation Dialog Views
    private lateinit var layoutVideoPrepDialog: View
    private lateinit var tvPrepTitle: TextView
    private lateinit var tvPrepFilename: TextView
    private lateinit var progressVideoPrep: ProgressBar
    private lateinit var tvPrepStatus: TextView
    private lateinit var tvPrepError: TextView
    private lateinit var btnPrepRetry: Button
    private lateinit var btnPrepCancel: Button

    private var selectedVideoItem: PtpMediaItem? = null
    private var isCurrentPrepExternal: Boolean = false
    private var videoCachingJob: Job? = null
    private var videoScanJob: Job? = null
    private var initJob: Job? = null

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

        tvStartStatus = findViewById(R.id.tv_start_status)
        tvHomeDeviceName = findViewById(R.id.tv_home_device_name)
        btnPhotos = findViewById(R.id.btn_photos)
        btnVideos = findViewById(R.id.btn_videos)

        // Home button actions
        btnPhotos.setOnClickListener {
            showScreen(Screen.PHOTO_BROWSER)
            rvPhotos.requestFocus()
        }
        btnVideos.setOnClickListener {
            showScreen(Screen.VIDEO_BROWSER)
            rvVideos.requestFocus()
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
        layoutVideoScanning = findViewById(R.id.layout_video_scanning)
        tvVideoScanningStatus = findViewById(R.id.tv_video_scanning_status)
        rvVideos.layoutManager = GridLayoutManager(this, 4)

        // Video Player
        videoView = findViewById(R.id.video_view)
        layoutVideoBuffering = findViewById(R.id.layout_video_buffering)
        tvVideoError = findViewById(R.id.tv_video_error)

        // Video Selection Dialog
        layoutVideoOptionsDialog = findViewById(R.id.layout_video_options_dialog)
        tvDialogVideoTitle = findViewById(R.id.tv_dialog_video_title)
        tvDialogVideoMeta = findViewById(R.id.tv_dialog_video_meta)
        btnPlayDirectusb = findViewById(R.id.btn_play_directusb)
        btnPlayExternal = findViewById(R.id.btn_play_external)
        btnCancelPlay = findViewById(R.id.btn_cancel_play)

        btnPlayDirectusb.setOnClickListener {
            layoutVideoOptionsDialog.visibility = View.GONE
            selectedVideoItem?.let { item ->
                prepareAndPlayVideo(item, isExternal = false)
            }
        }

        btnPlayExternal.setOnClickListener {
            layoutVideoOptionsDialog.visibility = View.GONE
            selectedVideoItem?.let { item ->
                prepareAndPlayVideo(item, isExternal = true)
            }
        }

        btnCancelPlay.setOnClickListener {
            layoutVideoOptionsDialog.visibility = View.GONE
            rvVideos.requestFocus()
        }

        // Video Preparation Dialog
        layoutVideoPrepDialog = findViewById(R.id.layout_video_prep_dialog)
        tvPrepTitle = findViewById(R.id.tv_prep_title)
        tvPrepFilename = findViewById(R.id.tv_prep_filename)
        progressVideoPrep = findViewById(R.id.progress_video_prep)
        tvPrepStatus = findViewById(R.id.tv_prep_status)
        tvPrepError = findViewById(R.id.tv_prep_error)
        btnPrepRetry = findViewById(R.id.btn_prep_retry)
        btnPrepCancel = findViewById(R.id.btn_prep_cancel)

        btnPrepRetry.setOnClickListener {
            tvPrepError.visibility = View.GONE
            btnPrepRetry.visibility = View.GONE
            selectedVideoItem?.let { item ->
                prepareAndPlayVideo(item, isExternal = isCurrentPrepExternal)
            }
        }

        btnPrepCancel.setOnClickListener {
            cancelVideoPreparation()
        }
    }

    private fun initServices() {
        repository = PtpMediaRepository(this)
        thumbnailLoader = PhotoThumbnailLoader(lifecycleScope) { repository.client }
        videoCacheManager = VideoCacheManager(this)

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
                        val pos = repository.videoItems.indexOf(item)
                        if (pos >= 0) {
                            videoAdapter.notifyItemChanged(pos)
                        }
                    }
                }
            },
            onItemClicked = { item ->
                showVideoOptionsDialog(item)
            }
        )
        rvVideos.adapter = videoAdapter

        usbHostManager = UsbHostManager(this) { state ->
            handleUsbState(state)
        }
        usbHostManager.start()
    }

    override fun onResume() {
        super.onResume()
        try {
            videoCacheManager.pruneSharedCache()
        } catch (_: Exception) {}
    }

    private fun handleUsbState(state: UsbConnectionState) {
        when (state) {
            is UsbConnectionState.Idle -> {
                initJob?.cancel()
                tvStartStatus.text = getString(R.string.connect_phone)
                showScreen(Screen.START)
            }
            is UsbConnectionState.DeviceAttached -> {
                initJob?.cancel()
                tvStartStatus.text = "Device connected: ${state.device.deviceName}\nRequesting permission..."
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
                            startVideoDiscovery()
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
                videoScanJob?.cancel()
                cancelVideoPreparation()
                stopAndClearVideoPlayer()
                photoLoadJob?.cancel()
                thumbnailLoader.clear()
                lifecycleScope.launch {
                    try {
                        repository.clear()
                    } catch (_: Exception) {}
                }
                layoutVideoOptionsDialog.visibility = View.GONE
                layoutVideoPrepDialog.visibility = View.GONE
                layoutVideoScanning.visibility = View.GONE
                tvStartStatus.text = getString(R.string.phone_disconnected)
                showScreen(Screen.START)
            }
        }
    }

    private fun startVideoDiscovery() {
        videoScanJob?.cancel()
        videoScanJob = lifecycleScope.launch {
            layoutVideoScanning.visibility = View.VISIBLE
            tvVideoScanningStatus.text = getString(R.string.scanning_videos)

            repository.scanAllVideos { foundCount, isDone ->
                lifecycleScope.launch(Dispatchers.Main) {
                    updateMediaCounts()
                    if (isDone) {
                        layoutVideoScanning.visibility = View.GONE
                    }
                }
            }

            layoutVideoScanning.visibility = View.GONE
            updateMediaCounts()
        }
    }

    private fun updateMediaCounts() {
        photoAdapter.notifyDataSetChanged()
        videoAdapter.notifyDataSetChanged()

        val pCount = repository.photoItems.size
        tvPhotosCount.text = " ($pCount items)"
        tvEmptyPhotos.visibility = if (pCount == 0) View.VISIBLE else View.GONE

        val vCount = repository.videoItems.size
        tvVideosCount.text = " ($vCount items)"
        tvEmptyVideos.visibility = if (vCount == 0) View.VISIBLE else View.GONE
    }

    private fun showScreen(screen: Screen) {
        currentScreen = screen
        screenStart.visibility = if (screen == Screen.START) View.VISIBLE else View.GONE
        screenHome.visibility = if (screen == Screen.HOME) View.VISIBLE else View.GONE
        screenPhotoBrowser.visibility = if (screen == Screen.PHOTO_BROWSER) View.VISIBLE else View.GONE
        screenPhotoViewer.visibility = if (screen == Screen.PHOTO_VIEWER) View.VISIBLE else View.GONE
        screenVideoBrowser.visibility = if (screen == Screen.VIDEO_BROWSER) View.VISIBLE else View.GONE
        screenVideoPlayer.visibility = if (screen == Screen.VIDEO_PLAYER) View.VISIBLE else View.GONE

        if (screen == Screen.PHOTO_VIEWER) {
            screenPhotoViewer.requestFocus()
        } else if (screen == Screen.VIDEO_PLAYER) {
            screenVideoPlayer.requestFocus()
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

    private fun showVideoOptionsDialog(item: PtpMediaItem) {
        selectedVideoItem = item
        tvDialogVideoTitle.text = item.displayName
        tvDialogVideoMeta.text = if (item.sizeBytes > 0) item.formattedSize else ""
        layoutVideoOptionsDialog.visibility = View.VISIBLE
        btnPlayDirectusb.requestFocus()
    }

    private fun prepareAndPlayVideo(item: PtpMediaItem, isExternal: Boolean) {
        val client = repository.client ?: return
        isCurrentPrepExternal = isExternal

        layoutVideoPrepDialog.visibility = View.VISIBLE
        tvPrepTitle.text = getString(R.string.preparing_video)
        tvPrepFilename.text = item.displayName
        progressVideoPrep.progress = 0
        progressVideoPrep.isIndeterminate = (item.sizeBytes <= 0)
        tvPrepStatus.text = if (item.sizeBytes > 0) "0 MB / ${item.formattedSize}" else "0 MB"
        tvPrepError.visibility = View.GONE
        btnPrepRetry.visibility = View.GONE
        btnPrepCancel.requestFocus()

        videoCachingJob?.cancel()
        videoCachingJob = lifecycleScope.launch {
            val cachedFile = videoCacheManager.cacheVideo(
                client = client,
                item = item,
                isForExternalShare = isExternal
            ) { transferred, total ->
                lifecycleScope.launch(Dispatchers.Main) {
                    if (total > 0) {
                        val pct = ((transferred * 100) / total).toInt().coerceIn(0, 100)
                        progressVideoPrep.isIndeterminate = false
                        progressVideoPrep.progress = pct
                        val mbTransferred = transferred / (1024.0 * 1024.0)
                        val mbTotal = total / (1024.0 * 1024.0)
                        tvPrepStatus.text = String.format("%.1f MB / %.1f MB (%d%%)", mbTransferred, mbTotal, pct)
                    } else {
                        progressVideoPrep.isIndeterminate = true
                        val mbTransferred = transferred / (1024.0 * 1024.0)
                        tvPrepStatus.text = String.format("%.1f MB transferred", mbTransferred)
                    }
                }
            }

            if (cachedFile != null && cachedFile.exists()) {
                layoutVideoPrepDialog.visibility = View.GONE
                if (isExternal) {
                    launchExternalVideoPlayer(cachedFile, item)
                } else {
                    playVideoLocally(cachedFile)
                }
            } else {
                progressVideoPrep.progress = 0
                tvPrepError.visibility = View.VISIBLE
                tvPrepError.text = getString(R.string.transfer_failed)
                btnPrepRetry.visibility = View.VISIBLE
                btnPrepRetry.requestFocus()
            }
        }
    }

    private fun cancelVideoPreparation() {
        videoCachingJob?.cancel()
        layoutVideoPrepDialog.visibility = View.GONE
        videoCacheManager.clearCache()
        if (currentScreen == Screen.VIDEO_BROWSER) {
            rvVideos.requestFocus()
        }
    }

    private fun launchExternalVideoPlayer(file: File, item: PtpMediaItem) {
        try {
            val authority = "${applicationContext.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(this, authority, file)
            val mimeType = PtpConstants.getMimeType(item.filename, item.format)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, getString(R.string.open_with_tv_player)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(chooser)
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Failed to launch external player", e)
            Toast.makeText(this, "Could not open external player: ${e.message}", Toast.LENGTH_LONG).show()
            rvVideos.requestFocus()
        }
    }

    private fun playVideoLocally(file: File) {
        showScreen(Screen.VIDEO_PLAYER)
        videoView.visibility = View.GONE
        tvVideoError.visibility = View.GONE
        layoutVideoBuffering.visibility = View.VISIBLE

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
            // Playback finished
        }
    }

    private fun stopAndClearVideoPlayer() {
        try {
            if (videoView.isPlaying) {
                videoView.stopPlayback()
            }
        } catch (_: Exception) {}
        videoCacheManager.clearCache()
    }

    override fun onDestroy() {
        super.onDestroy()
        initJob?.cancel()
        videoScanJob?.cancel()
        photoLoadJob?.cancel()
        videoCachingJob?.cancel()
        stopAndClearVideoPlayer()
        thumbnailLoader.clear()
        videoCacheManager.clearAll()
        usbHostManager.stop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle dialogs first
        if (layoutVideoPrepDialog.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                cancelVideoPreparation()
                return true
            }
        }

        if (layoutVideoOptionsDialog.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                layoutVideoOptionsDialog.visibility = View.GONE
                rvVideos.requestFocus()
                return true
            }
        }

        when (currentScreen) {
            Screen.PHOTO_VIEWER -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        photoLoadJob?.cancel()
                        showScreen(Screen.PHOTO_BROWSER)
                        rvPhotos.requestFocus()
                        return true
                    }
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
                }
            }
            Screen.VIDEO_PLAYER -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        stopAndClearVideoPlayer()
                        showScreen(Screen.VIDEO_BROWSER)
                        rvVideos.requestFocus()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (videoView.isPlaying) {
                            videoView.pause()
                        } else {
                            videoView.start()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val newPos = (videoView.currentPosition - 10000).coerceAtLeast(0)
                        videoView.seekTo(newPos)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val newPos = (videoView.currentPosition + 10000).coerceAtMost(videoView.duration)
                        videoView.seekTo(newPos)
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

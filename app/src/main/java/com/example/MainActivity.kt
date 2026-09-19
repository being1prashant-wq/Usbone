package com.example

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ftp.FtpAdapter
import com.example.ftp.FtpFileItem
import com.example.ftp.SimpleFtpClient
import com.example.media.PhotoThumbnailLoader
import com.example.media.PtpMediaItem
import com.example.media.PtpMediaRepository
import com.example.media.VideoCacheManager
import com.example.usb.PtpConstants
import com.example.usb.UsbConnectionState
import com.example.usb.UsbHostManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class Screen {
    START,
    HOME,
    PHOTO_BROWSER,
    PHOTO_VIEWER,
    VIDEO_BROWSER,
    VIDEO_PLAYER,
    FOLDERS
}

class MainActivity : AppCompatActivity() {

    private lateinit var usbHostManager: UsbHostManager
    private lateinit var repository: PtpMediaRepository
    private lateinit var thumbnailLoader: PhotoThumbnailLoader
    private lateinit var videoCacheManager: VideoCacheManager
    private val ftpClient = SimpleFtpClient()

    // UI Screen containers
    private lateinit var screenStart: View
    private lateinit var screenHome: View
    private lateinit var screenPhotoBrowser: View
    private lateinit var screenPhotoViewer: View
    private lateinit var screenVideoBrowser: View
    private lateinit var screenVideoPlayer: View
    private lateinit var screenFolders: View

    // Start Screen Views
    private lateinit var tvStartStatus: TextView

    // Home Screen Views
    private lateinit var tvHomeDeviceName: TextView
    private lateinit var btnPhotos: Button
    private lateinit var btnVideos: Button
    private lateinit var btnFolders: Button

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
    private var videoOriginScreen: Screen = Screen.VIDEO_BROWSER

    // Folders (FTP) Views
    private lateinit var tvFoldersPath: TextView
    private lateinit var btnFoldersDisconnect: Button
    private lateinit var layoutFtpConnect: LinearLayout
    private lateinit var etFtpHost: EditText
    private lateinit var etFtpPort: EditText
    private lateinit var btnFtpConnect: Button
    private lateinit var progressFtpConnect: ProgressBar
    private lateinit var tvFtpStatus: TextView

    private lateinit var layoutFtpBrowser: LinearLayout
    private lateinit var btnFolderUp: Button
    private lateinit var rvFolders: RecyclerView
    private lateinit var progressFolders: ProgressBar
    private lateinit var tvEmptyFolders: TextView
    private lateinit var ftpAdapter: FtpAdapter
    private val ftpItems = mutableListOf<FtpFileItem>()
    private var ftpCurrentPath: String = "/"
    private var ftpJob: Job? = null

    // File Actions Dialog (FTP)
    private lateinit var layoutFileActionsDialog: FrameLayout
    private lateinit var tvFileActionName: TextView
    private lateinit var tvFileActionInfo: TextView
    private lateinit var btnActionPlay: Button
    private lateinit var btnActionCopy: Button
    private lateinit var btnActionMove: Button
    private lateinit var btnActionCancel: Button
    private var selectedFtpItem: FtpFileItem? = null

    // Destination Choice Dialog (TV Local Storage)
    private lateinit var layoutDestChoiceDialog: FrameLayout
    private lateinit var btnDestMovies: Button
    private lateinit var btnDestDownloads: Button
    private lateinit var btnDestApp: Button
    private lateinit var btnDestCancel: Button
    private var isMoveOperation: Boolean = false

    // Transfer & Video Preparation Dialog
    private lateinit var layoutVideoPrepDialog: FrameLayout
    private lateinit var tvPrepTitle: TextView
    private lateinit var tvPrepFilename: TextView
    private lateinit var progressVideoPrep: ProgressBar
    private lateinit var tvPrepStatus: TextView
    private lateinit var tvPrepError: TextView
    private lateinit var btnPrepRetry: Button
    private lateinit var btnPrepCancel: Button

    private var selectedVideoItem: PtpMediaItem? = null
    private var videoCachingJob: Job? = null
    private var fileTransferJob: Job? = null
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
        screenFolders = findViewById(R.id.screen_folders)

        tvStartStatus = findViewById(R.id.tv_start_status)
        tvHomeDeviceName = findViewById(R.id.tv_home_device_name)
        btnPhotos = findViewById(R.id.btn_photos)
        btnVideos = findViewById(R.id.btn_videos)
        btnFolders = findViewById(R.id.btn_folders)

        // Home button actions
        btnPhotos.setOnClickListener {
            showScreen(Screen.PHOTO_BROWSER)
            rvPhotos.requestFocus()
        }
        btnVideos.setOnClickListener {
            showScreen(Screen.VIDEO_BROWSER)
            rvVideos.requestFocus()
        }
        btnFolders.setOnClickListener {
            openFoldersScreen()
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

        // Folders (FTP)
        tvFoldersPath = findViewById(R.id.tv_folders_path)
        btnFoldersDisconnect = findViewById(R.id.btn_folders_disconnect)
        layoutFtpConnect = findViewById(R.id.layout_ftp_connect)
        etFtpHost = findViewById(R.id.et_ftp_host)
        etFtpPort = findViewById(R.id.et_ftp_port)
        btnFtpConnect = findViewById(R.id.btn_ftp_connect)
        progressFtpConnect = findViewById(R.id.progress_ftp_connect)
        tvFtpStatus = findViewById(R.id.tv_ftp_status)

        layoutFtpBrowser = findViewById(R.id.layout_ftp_browser)
        btnFolderUp = findViewById(R.id.btn_folder_up)
        rvFolders = findViewById(R.id.rv_folders)
        progressFolders = findViewById(R.id.progress_folders)
        tvEmptyFolders = findViewById(R.id.tv_empty_folders)
        rvFolders.layoutManager = LinearLayoutManager(this)

        // Load saved FTP host/port from preferences
        val prefs = getSharedPreferences("ftp_prefs", Context.MODE_PRIVATE)
        etFtpHost.setText(prefs.getString("last_host", ""))
        etFtpPort.setText(prefs.getString("last_port", "2121"))

        btnFtpConnect.setOnClickListener {
            connectFtp()
        }

        btnFoldersDisconnect.setOnClickListener {
            disconnectFtp()
        }

        btnFolderUp.setOnClickListener {
            navigateFtpUp()
        }

        // File Actions Dialog
        layoutFileActionsDialog = findViewById(R.id.layout_file_actions_dialog)
        tvFileActionName = findViewById(R.id.tv_file_action_name)
        tvFileActionInfo = findViewById(R.id.tv_file_action_info)
        btnActionPlay = findViewById(R.id.btn_action_play)
        btnActionCopy = findViewById(R.id.btn_action_copy)
        btnActionMove = findViewById(R.id.btn_action_move)
        btnActionCancel = findViewById(R.id.btn_action_cancel)

        btnActionPlay.setOnClickListener {
            layoutFileActionsDialog.visibility = View.GONE
            selectedFtpItem?.let { playFtpVideo(it) }
        }

        btnActionCopy.setOnClickListener {
            layoutFileActionsDialog.visibility = View.GONE
            showDestinationChoice(isMove = false)
        }

        btnActionMove.setOnClickListener {
            layoutFileActionsDialog.visibility = View.GONE
            showDestinationChoice(isMove = true)
        }

        btnActionCancel.setOnClickListener {
            layoutFileActionsDialog.visibility = View.GONE
            rvFolders.requestFocus()
        }

        // Destination Choice Dialog
        layoutDestChoiceDialog = findViewById(R.id.layout_dest_choice_dialog)
        btnDestMovies = findViewById(R.id.btn_dest_movies)
        btnDestDownloads = findViewById(R.id.btn_dest_downloads)
        btnDestApp = findViewById(R.id.btn_dest_app)
        btnDestCancel = findViewById(R.id.btn_dest_cancel)

        btnDestMovies.setOnClickListener {
            layoutDestChoiceDialog.visibility = View.GONE
            val dir = getDestinationDirectory(Environment.DIRECTORY_MOVIES)
            selectedFtpItem?.let { executeFtpTransfer(it, dir, isMoveOperation) }
        }

        btnDestDownloads.setOnClickListener {
            layoutDestChoiceDialog.visibility = View.GONE
            val dir = getDestinationDirectory(Environment.DIRECTORY_DOWNLOADS)
            selectedFtpItem?.let { executeFtpTransfer(it, dir, isMoveOperation) }
        }

        btnDestApp.setOnClickListener {
            layoutDestChoiceDialog.visibility = View.GONE
            val dir = getExternalFilesDir(null) ?: filesDir
            selectedFtpItem?.let { executeFtpTransfer(it, dir, isMoveOperation) }
        }

        btnDestCancel.setOnClickListener {
            layoutDestChoiceDialog.visibility = View.GONE
            rvFolders.requestFocus()
        }

        // Video / Transfer Preparation Dialog
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
                prepareAndPlayVideo(item)
            }
        }

        btnPrepCancel.setOnClickListener {
            cancelVideoPreparation()
            cancelFileTransfer()
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
                prepareAndPlayVideo(item)
            }
        )
        rvVideos.adapter = videoAdapter

        ftpAdapter = FtpAdapter(ftpItems) { item ->
            onFtpItemClicked(item)
        }
        rvFolders.adapter = ftpAdapter

        usbHostManager = UsbHostManager(this) { state ->
            handleUsbState(state)
        }
        usbHostManager.start()
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
                cancelVideoPreparation()
                stopAndClearVideoPlayer()
                photoLoadJob?.cancel()
                thumbnailLoader.clear()
                lifecycleScope.launch {
                    try {
                        repository.clear()
                    } catch (_: Exception) {}
                }
                layoutVideoPrepDialog.visibility = View.GONE
                tvStartStatus.text = getString(R.string.phone_disconnected)
                showScreen(Screen.START)
            }
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
        screenFolders.visibility = if (screen == Screen.FOLDERS) View.VISIBLE else View.GONE

        if (screen == Screen.PHOTO_VIEWER) {
            screenPhotoViewer.requestFocus()
        } else if (screen == Screen.VIDEO_PLAYER) {
            screenVideoPlayer.requestFocus()
        }
    }

    // ==========================================
    // PHOTO VIEWER LOGIC
    // ==========================================
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

    // ==========================================
    // VIDEO PLAYBACK LOGIC (PTP)
    // ==========================================
    private fun prepareAndPlayVideo(item: PtpMediaItem) {
        val client = repository.client ?: return
        selectedVideoItem = item
        videoOriginScreen = Screen.VIDEO_BROWSER

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
                isForExternalShare = false
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
                playVideoLocally(cachedFile)
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
        } else if (currentScreen == Screen.FOLDERS) {
            rvFolders.requestFocus()
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
            // Video finished
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

    // ==========================================
    // FOLDERS (FTP) LOGIC
    // ==========================================
    private fun openFoldersScreen() {
        showScreen(Screen.FOLDERS)
        if (ftpClient.isConnected) {
            showFtpBrowserView()
            refreshFtpCurrentDirectory()
        } else {
            showFtpConnectView()
        }
    }

    private fun showFtpConnectView() {
        layoutFtpConnect.visibility = View.VISIBLE
        layoutFtpBrowser.visibility = View.GONE
        btnFoldersDisconnect.visibility = View.GONE
        tvFoldersPath.text = ""
        btnFtpConnect.requestFocus()
    }

    private fun showFtpBrowserView() {
        layoutFtpConnect.visibility = View.GONE
        layoutFtpBrowser.visibility = View.VISIBLE
        btnFoldersDisconnect.visibility = View.VISIBLE
        btnFolderUp.requestFocus()
    }

    private fun connectFtp() {
        val host = etFtpHost.text.toString().trim()
        val portStr = etFtpPort.text.toString().trim()
        val port = portStr.toIntOrNull() ?: 2121

        if (host.isEmpty()) {
            tvFtpStatus.text = "Please enter phone IP address"
            return
        }

        // Save preferences
        getSharedPreferences("ftp_prefs", Context.MODE_PRIVATE).edit()
            .putString("last_host", host)
            .putString("last_port", port.toString())
            .apply()

        progressFtpConnect.visibility = View.VISIBLE
        tvFtpStatus.text = "Connecting to $host:$port..."
        btnFtpConnect.isEnabled = false

        ftpJob?.cancel()
        ftpJob = lifecycleScope.launch {
            val success = ftpClient.connect(host = host, port = port)
            progressFtpConnect.visibility = View.GONE
            btnFtpConnect.isEnabled = true

            if (success) {
                tvFtpStatus.text = ""
                showFtpBrowserView()
                refreshFtpCurrentDirectory()
            } else {
                tvFtpStatus.text = "Connection failed. Please check IP, port, and ensure FTP server is active on phone."
            }
        }
    }

    private fun disconnectFtp() {
        ftpJob?.cancel()
        lifecycleScope.launch(Dispatchers.IO) {
            ftpClient.disconnect()
        }
        ftpItems.clear()
        ftpAdapter.notifyDataSetChanged()
        showFtpConnectView()
    }

    private fun refreshFtpCurrentDirectory() {
        ftpJob?.cancel()
        ftpJob = lifecycleScope.launch {
            progressFolders.visibility = View.VISIBLE
            tvEmptyFolders.visibility = View.GONE

            val pwd = ftpClient.getCurrentDirectory()
            ftpCurrentPath = pwd
            tvFoldersPath.text = pwd

            val files = ftpClient.listFiles()
            ftpItems.clear()
            ftpItems.addAll(files)
            ftpAdapter.notifyDataSetChanged()

            progressFolders.visibility = View.GONE
            tvEmptyFolders.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            if (files.isNotEmpty()) {
                rvFolders.requestFocus()
            } else {
                btnFolderUp.requestFocus()
            }
        }
    }

    private fun navigateFtpUp() {
        ftpJob?.cancel()
        ftpJob = lifecycleScope.launch {
            progressFolders.visibility = View.VISIBLE
            ftpClient.changeToParentDirectory()
            refreshFtpCurrentDirectory()
        }
    }

    private fun onFtpItemClicked(item: FtpFileItem) {
        if (item.isDirectory) {
            ftpJob?.cancel()
            ftpJob = lifecycleScope.launch {
                progressFolders.visibility = View.VISIBLE
                val ok = ftpClient.changeDirectory(item.name)
                if (ok) {
                    refreshFtpCurrentDirectory()
                } else {
                    progressFolders.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Cannot open directory: ${item.name}", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // Show File Actions dialog
            selectedFtpItem = item
            tvFileActionName.text = item.name
            tvFileActionInfo.text = item.formattedSize
            btnActionPlay.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            layoutFileActionsDialog.visibility = View.VISIBLE

            if (item.isVideo) {
                btnActionPlay.requestFocus()
            } else {
                btnActionCopy.requestFocus()
            }
        }
    }

    private fun playFtpVideo(item: FtpFileItem) {
        videoOriginScreen = Screen.FOLDERS
        layoutVideoPrepDialog.visibility = View.VISIBLE
        tvPrepTitle.text = getString(R.string.preparing_video)
        tvPrepFilename.text = item.name
        progressVideoPrep.progress = 0
        progressVideoPrep.isIndeterminate = (item.sizeBytes <= 0)
        tvPrepStatus.text = if (item.sizeBytes > 0) "0 MB / ${item.formattedSize}" else "0 MB"
        tvPrepError.visibility = View.GONE
        btnPrepRetry.visibility = View.GONE
        btnPrepCancel.requestFocus()

        val cacheFile = videoCacheManager.localPlaybackFile
        if (cacheFile.exists()) cacheFile.delete()

        videoCachingJob?.cancel()
        videoCachingJob = lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    FileOutputStream(cacheFile).use { fos ->
                        ftpClient.downloadFile(item.name, fos, item.sizeBytes) { transferred, total ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                if (total > 0) {
                                    val pct = ((transferred * 100) / total).toInt().coerceIn(0, 100)
                                    progressVideoPrep.isIndeterminate = false
                                    progressVideoPrep.progress = pct
                                    val mbT = transferred / (1024.0 * 1024.0)
                                    val mbTot = total / (1024.0 * 1024.0)
                                    tvPrepStatus.text = String.format("%.1f MB / %.1f MB (%d%%)", mbT, mbTot, pct)
                                } else {
                                    progressVideoPrep.isIndeterminate = true
                                    val mbT = transferred / (1024.0 * 1024.0)
                                    tvPrepStatus.text = String.format("%.1f MB transferred", mbT)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error downloading FTP video", e)
                    false
                }
            }

            if (success && cacheFile.exists() && cacheFile.length() > 0) {
                layoutVideoPrepDialog.visibility = View.GONE
                playVideoLocally(cacheFile)
            } else {
                progressVideoPrep.progress = 0
                tvPrepError.visibility = View.VISIBLE
                tvPrepError.text = getString(R.string.transfer_failed)
                btnPrepRetry.visibility = View.GONE
                btnPrepCancel.requestFocus()
            }
        }
    }

    private fun showDestinationChoice(isMove: Boolean) {
        isMoveOperation = isMove
        layoutDestChoiceDialog.visibility = View.VISIBLE
        btnDestMovies.requestFocus()
    }

    private fun getDestinationDirectory(type: String): File {
        return try {
            val publicDir = Environment.getExternalStoragePublicDirectory(type)
            if (publicDir.exists() || publicDir.mkdirs()) {
                publicDir
            } else {
                getExternalFilesDir(type) ?: filesDir
            }
        } catch (_: Exception) {
            getExternalFilesDir(type) ?: filesDir
        }
    }

    private fun executeFtpTransfer(item: FtpFileItem, destDir: File, isMove: Boolean) {
        val destFile = File(destDir, item.name)

        layoutVideoPrepDialog.visibility = View.VISIBLE
        tvPrepTitle.text = if (isMove) getString(R.string.moving_file) else getString(R.string.copying_file)
        tvPrepFilename.text = item.name
        progressVideoPrep.progress = 0
        progressVideoPrep.isIndeterminate = (item.sizeBytes <= 0)
        tvPrepStatus.text = if (item.sizeBytes > 0) "0 MB / ${item.formattedSize}" else "0 MB"
        tvPrepError.visibility = View.GONE
        btnPrepRetry.visibility = View.GONE
        btnPrepCancel.requestFocus()

        fileTransferJob?.cancel()
        fileTransferJob = lifecycleScope.launch {
            val downloadOk = withContext(Dispatchers.IO) {
                try {
                    FileOutputStream(destFile).use { fos ->
                        ftpClient.downloadFile(item.name, fos, item.sizeBytes) { transferred, total ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                if (total > 0) {
                                    val pct = ((transferred * 100) / total).toInt().coerceIn(0, 100)
                                    progressVideoPrep.isIndeterminate = false
                                    progressVideoPrep.progress = pct
                                    val mbT = transferred / (1024.0 * 1024.0)
                                    val mbTot = total / (1024.0 * 1024.0)
                                    tvPrepStatus.text = String.format("%.1f MB / %.1f MB (%d%%)", mbT, mbTot, pct)
                                } else {
                                    progressVideoPrep.isIndeterminate = true
                                    val mbT = transferred / (1024.0 * 1024.0)
                                    tvPrepStatus.text = String.format("%.1f MB transferred", mbT)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "FTP file transfer error", e)
                    false
                }
            }

            if (downloadOk && destFile.exists()) {
                if (isMove) {
                    // Delete from FTP server to complete move
                    withContext(Dispatchers.IO) {
                        ftpClient.deleteFile(item.name)
                    }
                    refreshFtpCurrentDirectory()
                }
                layoutVideoPrepDialog.visibility = View.GONE
                Toast.makeText(this@MainActivity, "${if (isMove) "Moved" else "Copied"} ${item.name} to ${destDir.name}", Toast.LENGTH_LONG).show()
                rvFolders.requestFocus()
            } else {
                progressVideoPrep.progress = 0
                tvPrepError.visibility = View.VISIBLE
                tvPrepError.text = getString(R.string.transfer_failed)
                btnPrepRetry.visibility = View.GONE
                btnPrepCancel.requestFocus()
            }
        }
    }

    private fun cancelFileTransfer() {
        fileTransferJob?.cancel()
        layoutVideoPrepDialog.visibility = View.GONE
        rvFolders.requestFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        initJob?.cancel()
        photoLoadJob?.cancel()
        videoCachingJob?.cancel()
        fileTransferJob?.cancel()
        ftpJob?.cancel()
        lifecycleScope.launch(Dispatchers.IO) {
            ftpClient.disconnect()
        }
        stopAndClearVideoPlayer()
        thumbnailLoader.clear()
        videoCacheManager.clearAll()
        usbHostManager.stop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle active dialogs first
        if (layoutVideoPrepDialog.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                cancelVideoPreparation()
                cancelFileTransfer()
                return true
            }
        }

        if (layoutDestChoiceDialog.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                layoutDestChoiceDialog.visibility = View.GONE
                rvFolders.requestFocus()
                return true
            }
        }

        if (layoutFileActionsDialog.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                layoutFileActionsDialog.visibility = View.GONE
                rvFolders.requestFocus()
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
                        showScreen(videoOriginScreen)
                        if (videoOriginScreen == Screen.VIDEO_BROWSER) {
                            rvVideos.requestFocus()
                        } else {
                            rvFolders.requestFocus()
                        }
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
            Screen.FOLDERS -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (layoutFtpBrowser.visibility == View.VISIBLE && ftpCurrentPath != "/" && ftpCurrentPath.isNotEmpty()) {
                        navigateFtpUp()
                        return true
                    }
                    showScreen(Screen.HOME)
                    btnFolders.requestFocus()
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

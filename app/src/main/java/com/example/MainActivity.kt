package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.media.AudioCacheManager
import com.example.media.BackgroundPlayService
import com.example.media.MediaContextMenuHelper
import com.example.media.MediaThumbnailLoader
import com.example.media.PhotoThumbnailLoader
import com.example.media.PtpMediaItem
import com.example.media.PtpMediaRepository
import com.example.media.PtpLoopbackServer
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import com.example.usb.UsbConnectionState
import com.example.usb.UsbHostManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import android.net.Uri
import java.io.File

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

enum class LoopMode {
    OFF,
    SINGLE,
    ALL
}

enum class ViewMode {
    GRID,
    LIST
}

enum class SortMode {
    DEFAULT,
    NAME_ASC,
    SIZE_DESC,
    DATE_DESC
}

class MainActivity : AppCompatActivity() {

    private lateinit var usbHostManager: UsbHostManager
    private lateinit var repository: PtpMediaRepository
    private lateinit var legacyPhotoThumbLoader: PhotoThumbnailLoader
    private lateinit var mediaThumbnailLoader: MediaThumbnailLoader
    private lateinit var audioCacheManager: AudioCacheManager
    private lateinit var contextMenuHelper: MediaContextMenuHelper

    // Media Session for hardware TV controls
    private var mediaSession: MediaSessionCompat? = null

    // UI Screen containers
    private lateinit var screenStart: View
    private lateinit var screenHome: View
    private lateinit var screenPhotoBrowser: View
    private lateinit var screenPhotoViewer: View
    private lateinit var screenVideoBrowser: View
    private lateinit var screenVideoPlayer: View
    private lateinit var screenAudioBrowser: View
    private lateinit var screenAudioPlayer: View

    // Overlays
    private lateinit var overlayQueue: FrameLayout
    private lateinit var tvQueueTitle: TextView
    private lateinit var btnCloseQueue: Button
    private lateinit var rvQueue: RecyclerView
    private lateinit var queueAdapter: QueueAdapter

    private lateinit var overlayCopy: FrameLayout
    private lateinit var tvCopyStatus: TextView
    private lateinit var tvCopyFileName: TextView
    private lateinit var btnCancelCopy: Button

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
    private lateinit var btnPhotosSort: Button
    private lateinit var btnPhotosViewMode: Button
    private lateinit var photoAdapter: PhotoAdapter
    private var photoViewMode = ViewMode.GRID
    private var photoSortMode = SortMode.DEFAULT
    private var displayedPhotoList: MutableList<PtpMediaItem> = mutableListOf()

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
    private lateinit var btnVideosSort: Button
    private lateinit var btnVideosViewMode: Button
    private lateinit var videoAdapter: VideoAdapter
    private var videoViewMode = ViewMode.GRID
    private var videoSortMode = SortMode.DEFAULT
    private var displayedVideoList: MutableList<PtpMediaItem> = mutableListOf()

    // Video Player Views
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var layoutVideoBuffering: View
    private lateinit var tvBuffering: TextView
    private lateinit var tvVideoError: TextView
    private lateinit var layoutVideoTopBar: View
    private lateinit var tvVideoBadgeFormat: TextView
    private lateinit var tvVideoTitle: TextView
    private lateinit var tvVideoHudSpeed: TextView
    private lateinit var tvVideoHudLoop: TextView
    private lateinit var layoutVideoControls: View
    private lateinit var tvVideoTimeCurrent: TextView
    private lateinit var tvVideoTimeTotal: TextView
    private lateinit var sbVideoSeek: SeekBar

    // Video Player Buttons
    private lateinit var btnVideoPrev: Button
    private lateinit var btnVideoRewind10: Button
    private lateinit var btnVideoPlayPause: Button
    private lateinit var btnVideoForward10: Button
    private lateinit var btnVideoNext: Button
    private lateinit var btnVideoSubs: Button
    private lateinit var btnVideoAudioTrack: Button
    private lateinit var btnVideoSpeed: Button
    private lateinit var btnVideoLoop: Button
    private lateinit var btnVideoBgPlay: Button
    private lateinit var btnVideoQueue: Button

    private var videoProgressJob: Job? = null
    private var currentVideoIndex = -1
    private var isVideoTracking = false
    private var currentPlaybackSpeed: Float = 1.0f
    private var currentVideoSessionId: Long = 0L
    private lateinit var bridge: PtpLoopbackServer
    private var libVlc: LibVLC? = null
    private var vlcPlayer: VlcMediaPlayer? = null
    private var currentVlcMedia: Media? = null
    private var vlcGenerationCounter: Long = 0L
    private var isVlcSeeking: Boolean = false

    // Storage Permission Handling for TV copy
    private var storagePermissionCallback: ((Boolean) -> Unit)? = null
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        storagePermissionCallback?.invoke(isGranted)
        storagePermissionCallback = null
    }

    // Video HUD Auto-hide Handler
    private val hudHandler = Handler(Looper.getMainLooper())
    private val hideVideoHudRunnable = Runnable { hideVideoHud() }
    private val hideAudioHudRunnable = Runnable { hideAudioHud() }

    // Audio Browser Views
    private lateinit var rvAudio: RecyclerView
    private lateinit var tvAudioCount: TextView
    private lateinit var tvEmptyAudio: TextView
    private lateinit var btnAudioSort: Button
    private lateinit var btnAudioViewMode: Button
    private lateinit var audioAdapter: AudioAdapter
    private var audioViewMode = ViewMode.GRID
    private var audioSortMode = SortMode.DEFAULT
    private var displayedAudioList: MutableList<PtpMediaItem> = mutableListOf()

    // Audio Player Views
    private lateinit var ivAudioAmbientBg: ImageView
    private lateinit var ivAudioPlayerArt: ImageView
    private lateinit var tvAudioPlayerTitle: TextView
    private lateinit var tvAudioPlayerStatus: TextView
    private lateinit var tvAudioTimeCurrent: TextView
    private lateinit var tvAudioTimeTotal: TextView
    private lateinit var progressAudioSeek: SeekBar
    private lateinit var layoutAudioBuffering: View
    private lateinit var tvAudioBuffering: TextView
    private lateinit var tvAudioError: TextView
    private lateinit var layoutAudioControls: View

    // Audio Player Buttons
    private lateinit var btnAudioPrev: Button
    private lateinit var btnAudioRewind10: Button
    private lateinit var btnAudioPlayPause: Button
    private lateinit var btnAudioForward10: Button
    private lateinit var btnAudioNext: Button
    private lateinit var btnAudioLoop: Button
    private lateinit var btnAudioBgPlay: Button
    private lateinit var btnAudioQueue: Button

    private var audioPlayer: MediaPlayer? = null
    private var audioCachingJob: Job? = null
    private var audioProgressJob: Job? = null
    private var currentAudioIndex = -1
    private var isAudioTracking = false
    private var currentAudioSessionId: Long = 0L

    // Playback state configurations
    private var videoLoopMode: LoopMode = LoopMode.OFF
    private var audioLoopMode: LoopMode = LoopMode.OFF
    private var isBackgroundPlayEnabled: Boolean = false
    private var currentSubtitlesTrack: Int = -1

    private var currentScreen: Screen = Screen.START

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initServices()
        initMediaSession()
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

        // Overlays
        overlayQueue = findViewById(R.id.overlay_queue)
        tvQueueTitle = findViewById(R.id.tv_queue_title)
        btnCloseQueue = findViewById(R.id.btn_close_queue)
        rvQueue = findViewById(R.id.rv_queue)
        rvQueue.layoutManager = LinearLayoutManager(this)

        overlayCopy = findViewById(R.id.overlay_copy)
        tvCopyStatus = findViewById(R.id.tv_copy_status)
        tvCopyFileName = findViewById(R.id.tv_copy_file_name)
        btnCancelCopy = findViewById(R.id.btn_cancel_copy)

        btnCloseQueue.setOnClickListener { overlayQueue.visibility = View.GONE }
        btnCancelCopy.setOnClickListener {
            contextMenuHelper.cancelCopy()
            overlayCopy.visibility = View.GONE
        }

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
        btnPhotosSort = findViewById(R.id.btn_photos_sort)
        btnPhotosViewMode = findViewById(R.id.btn_photos_view_mode)
        setupPhotosBrowserControls()

        // Photo Viewer
        ivFullPhoto = findViewById(R.id.iv_full_photo)
        progressPhoto = findViewById(R.id.progress_photo)
        tvPhotoError = findViewById(R.id.tv_photo_error)

        // Video Browser
        rvVideos = findViewById(R.id.rv_videos)
        tvVideosCount = findViewById(R.id.tv_videos_count)
        tvEmptyVideos = findViewById(R.id.tv_empty_videos)
        btnVideosSort = findViewById(R.id.btn_videos_sort)
        btnVideosViewMode = findViewById(R.id.btn_videos_view_mode)
        setupVideosBrowserControls()

        // Video Player UI
        videoLayout = findViewById(R.id.video_view)
        layoutVideoBuffering = findViewById(R.id.layout_video_buffering)
        tvBuffering = findViewById(R.id.tv_buffering)
        tvVideoError = findViewById(R.id.tv_video_error)
        layoutVideoTopBar = findViewById(R.id.layout_video_top_bar)
        tvVideoBadgeFormat = findViewById(R.id.tv_video_badge_format)
        tvVideoTitle = findViewById(R.id.tv_video_title)
        tvVideoHudSpeed = findViewById(R.id.tv_video_hud_speed)
        tvVideoHudLoop = findViewById(R.id.tv_video_hud_loop)
        layoutVideoControls = findViewById(R.id.layout_video_controls)
        tvVideoTimeCurrent = findViewById(R.id.tv_video_time_current)
        tvVideoTimeTotal = findViewById(R.id.tv_video_time_total)
        sbVideoSeek = findViewById(R.id.sb_video_seek)

        btnVideoPrev = findViewById(R.id.btn_video_prev)
        btnVideoRewind10 = findViewById(R.id.btn_video_rewind10)
        btnVideoPlayPause = findViewById(R.id.btn_video_play_pause)
        btnVideoForward10 = findViewById(R.id.btn_video_forward10)
        btnVideoNext = findViewById(R.id.btn_video_next)
        btnVideoSubs = findViewById(R.id.btn_video_subs)
        btnVideoAudioTrack = findViewById(R.id.btn_video_audio_track)
        btnVideoSpeed = findViewById(R.id.btn_video_speed)
        btnVideoLoop = findViewById(R.id.btn_video_loop)
        btnVideoBgPlay = findViewById(R.id.btn_video_bg_play)
        btnVideoQueue = findViewById(R.id.btn_video_queue)

        setupVideoControls()

        // Audio Browser
        rvAudio = findViewById(R.id.rv_audio)
        tvAudioCount = findViewById(R.id.tv_audio_count)
        tvEmptyAudio = findViewById(R.id.tv_empty_audio)
        btnAudioSort = findViewById(R.id.btn_audio_sort)
        btnAudioViewMode = findViewById(R.id.btn_audio_view_mode)
        setupAudioBrowserControls()

        // Audio Player
        ivAudioAmbientBg = findViewById(R.id.iv_audio_ambient_bg)
        ivAudioPlayerArt = findViewById(R.id.iv_audio_player_art)
        tvAudioPlayerTitle = findViewById(R.id.tv_audio_player_title)
        tvAudioPlayerStatus = findViewById(R.id.tv_audio_player_status)
        tvAudioTimeCurrent = findViewById(R.id.tv_audio_time_current)
        tvAudioTimeTotal = findViewById(R.id.tv_audio_time_total)
        progressAudioSeek = findViewById(R.id.progress_audio_seek)
        layoutAudioBuffering = findViewById(R.id.layout_audio_buffering)
        tvAudioBuffering = findViewById(R.id.tv_audio_buffering)
        tvAudioError = findViewById(R.id.tv_audio_error)
        layoutAudioControls = findViewById(R.id.layout_audio_controls)

        btnAudioPrev = findViewById(R.id.btn_audio_prev)
        btnAudioRewind10 = findViewById(R.id.btn_audio_rewind10)
        btnAudioPlayPause = findViewById(R.id.btn_audio_play_pause)
        btnAudioForward10 = findViewById(R.id.btn_audio_forward10)
        btnAudioNext = findViewById(R.id.btn_audio_next)
        btnAudioLoop = findViewById(R.id.btn_audio_loop)
        btnAudioBgPlay = findViewById(R.id.btn_audio_bg_play)
        btnAudioQueue = findViewById(R.id.btn_audio_queue)

        setupAudioControls()
    }

    private fun initServices() {
        repository = PtpMediaRepository(this)
        legacyPhotoThumbLoader = PhotoThumbnailLoader(lifecycleScope) { repository.client }
        mediaThumbnailLoader = MediaThumbnailLoader(this, lifecycleScope) { repository.client }
        audioCacheManager = AudioCacheManager(this)
        bridge = PtpLoopbackServer(lifecycleScope, repository)
        bridge.start()
        initVlc()

        contextMenuHelper = MediaContextMenuHelper(
            context = this,
            scope = lifecycleScope,
            ptpClientProvider = { repository.client },
            onRequestStoragePermission = { callback ->
                storagePermissionCallback = callback
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else callback(true)
            },
            onCopyStart = { filename ->
                tvCopyStatus.text = getString(R.string.copying_file)
                tvCopyFileName.text = filename
                overlayCopy.visibility = View.VISIBLE
                btnCancelCopy.requestFocus()
            },
            onCopyProgress = { written,total ->
                val mb=written/(1024.0*1024.0)
                tvCopyStatus.text=if(total>0) String.format("Copying %.1f / %.1f MB (%d%%)...",mb,total/(1024.0*1024.0),((written*100)/total).toInt()) else String.format("Copying %.1f MB...",mb)
            },
            onCopyComplete = { success,msg ->
                overlayCopy.visibility=View.GONE
                Toast.makeText(this, if(success) "${getString(R.string.copy_success)}\n$msg" else "${getString(R.string.copy_failed)}: $msg", if(success) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }
        )

        photoAdapter=PhotoAdapter(displayedPhotoList,legacyPhotoThumbLoader,{photoViewMode==ViewMode.LIST},
            { index->currentPhotoIndex=index;showScreen(Screen.PHOTO_VIEWER);loadSelectedPhoto(index) },
            { item->contextMenuHelper.showContextMenu(item) })
        rvPhotos.adapter=photoAdapter

        videoAdapter=VideoAdapter(displayedVideoList,mediaThumbnailLoader,{videoViewMode==ViewMode.LIST},
            { item->lifecycleScope.launch{val updated=repository.fetchMetadataIfNeeded(item);if(updated.isMetadataLoaded){val idx=displayedVideoList.indexOf(item);if(idx>=0)videoAdapter.notifyItemChanged(idx)}}},
            { item->displayedVideoList.indexOf(item).takeIf{it>=0}?.let{playVideoAtIndex(it)} },
            { item->contextMenuHelper.showContextMenu(item) })
        rvVideos.adapter=videoAdapter

        audioAdapter=AudioAdapter(displayedAudioList,mediaThumbnailLoader,{audioViewMode==ViewMode.LIST},
            { item->lifecycleScope.launch{val updated=repository.fetchMetadataIfNeeded(item);if(updated.isMetadataLoaded){val idx=displayedAudioList.indexOf(item);if(idx>=0)audioAdapter.notifyItemChanged(idx)}}},
            { item->displayedAudioList.indexOf(item).takeIf{it>=0}?.let{playAudioAtIndex(it)} },
            { item->contextMenuHelper.showContextMenu(item) })
        rvAudio.adapter=audioAdapter

        queueAdapter=QueueAdapter(emptyList(),-1){index->
            overlayQueue.visibility=View.GONE
            if(currentScreen==Screen.VIDEO_PLAYER)playVideoAtIndex(index) else if(currentScreen==Screen.AUDIO_PLAYER)playAudioAtIndex(index)
        }
        rvQueue.adapter=queueAdapter
        usbHostManager=UsbHostManager(this){state->handleUsbState(state)}
    }

    private fun setupPhotosBrowserControls() {
        rvPhotos.layoutManager = GridLayoutManager(this, 4)
        btnPhotosViewMode.setOnClickListener {
            val isNowList = (photoViewMode == ViewMode.GRID)
            photoViewMode = if (isNowList) ViewMode.LIST else ViewMode.GRID
            btnPhotosViewMode.text = if (isNowList) "☰ LIST" else "▦ GRID"
            rvPhotos.recycledViewPool.clear()
            rvPhotos.layoutManager = if (isNowList) LinearLayoutManager(this) else GridLayoutManager(this, 4)
            photoAdapter.notifyDataSetChanged()
        }
        btnPhotosSort.setOnClickListener {
            showSortDialog("Photos", photoSortMode) { selected ->
                photoSortMode = selected
                btnPhotosSort.text = "⫽ ${selected.name.replace('_', ' ')}"
                applySorting()
            }
        }
    }

    private fun setupVideosBrowserControls() {
        rvVideos.layoutManager = GridLayoutManager(this, 4)
        btnVideosViewMode.setOnClickListener {
            val isNowList = (videoViewMode == ViewMode.GRID)
            videoViewMode = if (isNowList) ViewMode.LIST else ViewMode.GRID
            btnVideosViewMode.text = if (isNowList) "☰ LIST" else "▦ GRID"
            rvVideos.recycledViewPool.clear()
            rvVideos.layoutManager = if (isNowList) LinearLayoutManager(this) else GridLayoutManager(this, 4)
            videoAdapter.notifyDataSetChanged()
        }
        btnVideosSort.setOnClickListener {
            showSortDialog("Videos", videoSortMode) { selected ->
                videoSortMode = selected
                btnVideosSort.text = "⫽ ${selected.name.replace('_', ' ')}"
                applySorting()
            }
        }
    }

    private fun setupAudioBrowserControls() {
        rvAudio.layoutManager = GridLayoutManager(this, 4)
        btnAudioViewMode.setOnClickListener {
            val isNowList = (audioViewMode == ViewMode.GRID)
            audioViewMode = if (isNowList) ViewMode.LIST else ViewMode.GRID
            btnAudioViewMode.text = if (isNowList) "☰ LIST" else "▦ GRID"
            rvAudio.recycledViewPool.clear()
            rvAudio.layoutManager = if (isNowList) LinearLayoutManager(this) else GridLayoutManager(this, 4)
            audioAdapter.notifyDataSetChanged()
        }
        btnAudioSort.setOnClickListener {
            showSortDialog("Audio", audioSortMode) { selected ->
                audioSortMode = selected
                btnAudioSort.text = "⫽ ${selected.name.replace('_', ' ')}"
                applySorting()
            }
        }
    }

    private fun showSortDialog(title: String, current: SortMode, onSelected: (SortMode) -> Unit) {
        val modes = SortMode.values()
        val names = arrayOf("Default", "Name (A-Z)", "Size (Largest First)", "Newest First")
        val currentIdx = modes.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Sort $title")
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                onSelected(modes[which])
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun applySorting() {
        displayedPhotoList.clear()
        displayedPhotoList.addAll(sortItems(repository.photoItems, photoSortMode))
        photoAdapter.notifyDataSetChanged()
        displayedVideoList.clear()
        displayedVideoList.addAll(sortItems(repository.videoItems, videoSortMode))
        videoAdapter.notifyDataSetChanged()
        displayedAudioList.clear()
        displayedAudioList.addAll(sortItems(repository.audioItems, audioSortMode))
        audioAdapter.notifyDataSetChanged()
    }

    private fun sortItems(items: List<PtpMediaItem>, sortMode: SortMode): List<PtpMediaItem> {
        return when (sortMode) {
            SortMode.DEFAULT -> items.toList()
            SortMode.NAME_ASC -> items.sortedBy { it.displayName.lowercase() }
            SortMode.SIZE_DESC -> items.sortedByDescending { it.sizeBytes }
            SortMode.DATE_DESC -> items.sortedByDescending { it.handle }
        }
    }

    private fun initMediaSession() {
        try {
            mediaSession = MediaSessionCompat(this, "DirectUSB_MediaSession").apply {
                setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() { handlePlayPauseAction() }
                    override fun onPause() { handlePlayPauseAction() }
                    override fun onSkipToNext() { handleNextAction() }
                    override fun onSkipToPrevious() { handlePreviousAction() }
                    override fun onFastForward() { handleForward10Action() }
                    override fun onRewind() { handleRewind10Action() }
                    override fun onStop() {
                        if (currentScreen == Screen.VIDEO_PLAYER) {
                            stopAndClearVideoPlayer()
                        } else if (currentScreen == Screen.AUDIO_PLAYER) {
                            stopAndClearAudioPlayer()
                        }
                    }
                })
                isActive = true
            }
            updateMediaSessionState(PlaybackStateCompat.STATE_NONE, 0L)
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Could not initialize MediaSession", e)
        }
    }

    private fun updateMediaSessionState(state: Int, position: Long) {
        try {
            val playbackState = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, position, currentPlaybackSpeed)
                .build()
            mediaSession?.setPlaybackState(playbackState)
        } catch (_: Exception) {}
    }

    private fun setupVideoControls() {
        btnVideoPlayPause.setOnClickListener { resetVideoHudTimer(); handlePlayPauseAction() }
        btnVideoRewind10.setOnClickListener { resetVideoHudTimer(); handleRewind10Action() }
        btnVideoForward10.setOnClickListener { resetVideoHudTimer(); handleForward10Action() }
        btnVideoPrev.setOnClickListener { resetVideoHudTimer(); handlePreviousAction() }
        btnVideoNext.setOnClickListener { resetVideoHudTimer(); handleNextAction() }
        btnVideoSpeed.setOnClickListener { resetVideoHudTimer(); cyclePlaybackSpeed() }
        btnVideoLoop.setOnClickListener { resetVideoHudTimer(); videoLoopMode=when(videoLoopMode){LoopMode.OFF->LoopMode.SINGLE;LoopMode.SINGLE->LoopMode.ALL;LoopMode.ALL->LoopMode.OFF};updateLoopButtonUi() }
        btnVideoBgPlay.setOnClickListener { resetVideoHudTimer(); isBackgroundPlayEnabled=!isBackgroundPlayEnabled; updateBgPlayButtonUi() }
        btnVideoQueue.setOnClickListener { resetVideoHudTimer(); showQueueOverlay(displayedVideoList,currentVideoIndex) }
        btnVideoSubs.setOnClickListener { resetVideoHudTimer(); showSubtitlesDialog() }
        btnVideoAudioTrack.setOnClickListener { resetVideoHudTimer(); showAudioTrackDialog() }
        sbVideoSeek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(sb:SeekBar?,progress:Int,fromUser:Boolean){
                if(fromUser){
                    val total=vlcPlayer?.length?:0L
                    if(total>0){
                        resetVideoHudTimer()
                        tvVideoTimeCurrent.text=formatTime((progress.toLong()*total/1000L).toInt())
                    }
                }
            }
            override fun onStartTrackingTouch(sb:SeekBar?){
                isVideoTracking=true
                resetVideoHudTimer()
            }
            override fun onStopTrackingTouch(sb:SeekBar?){
                isVideoTracking=false
                resetVideoHudTimer()
                val p=vlcPlayer?:return
                val total=try{p.length}catch(_:Throwable){0L}
                if(total<=0L)return
                val target=(sb?.progress?.toLong()?:0L)*total/1000L
                try{
                    isVlcSeeking=true
                    layoutVideoBuffering.visibility=View.VISIBLE
                    tvBuffering.text="Seeking / buffering..."
                    p.setTime(target.coerceIn(0L,total))
                    lifecycleScope.launch{
                        delay(1200)
                        if(isVlcSeeking&&p===vlcPlayer){
                            isVlcSeeking=false
                            layoutVideoBuffering.visibility=View.GONE
                        }
                    }
                }catch(e:Throwable){
                    isVlcSeeking=false
                    layoutVideoBuffering.visibility=View.GONE
                    Log.w(PtpConstants.TAG,"VLC seek failed",e)
                }
            }
        })
    }

    private fun cyclePlaybackSpeed() {
        val speeds = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f)
        val currentIdx = speeds.indexOfFirst { kotlin.math.abs(it - currentPlaybackSpeed) < 0.05f }.let { if (it < 0) 0 else it }
        val nextIdx = (currentIdx + 1) % speeds.size
        setPlaybackSpeed(speeds[nextIdx])
    }

    private fun setPlaybackSpeed(speed:Float){
        currentPlaybackSpeed=speed
        val label="${speed}x"
        btnVideoSpeed.text="⚡ $label"
        tvVideoHudSpeed.text=label
        setVlcPlaybackRate(speed)
    }

    private fun setupAudioControls() {
        btnAudioPlayPause.setOnClickListener {
            resetAudioHudTimer()
            handlePlayPauseAction()
        }
        btnAudioRewind10.setOnClickListener {
            resetAudioHudTimer()
            handleRewind10Action()
        }
        btnAudioForward10.setOnClickListener {
            resetAudioHudTimer()
            handleForward10Action()
        }
        btnAudioPrev.setOnClickListener {
            resetAudioHudTimer()
            handlePreviousAction()
        }
        btnAudioNext.setOnClickListener {
            resetAudioHudTimer()
            handleNextAction()
        }

        btnAudioLoop.setOnClickListener {
            resetAudioHudTimer()
            audioLoopMode = when (audioLoopMode) {
                LoopMode.OFF -> LoopMode.SINGLE
                LoopMode.SINGLE -> LoopMode.ALL
                LoopMode.ALL -> LoopMode.OFF
            }
            updateLoopButtonUi()
        }

        btnAudioBgPlay.setOnClickListener {
            resetAudioHudTimer()
            isBackgroundPlayEnabled = !isBackgroundPlayEnabled
            updateBgPlayButtonUi()
        }

        btnAudioQueue.setOnClickListener {
            resetAudioHudTimer()
            showQueueOverlay(displayedAudioList, currentAudioIndex)
        }

        progressAudioSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                audioPlayer?.let { mp ->
                    if (fromUser && mp.duration > 0) {
                        resetAudioHudTimer()
                        val targetMs = (progress.toLong() * mp.duration / 1000L).toInt()
                        tvAudioTimeCurrent.text = formatTime(targetMs)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isAudioTracking = true
                resetAudioHudTimer()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isAudioTracking = false
                resetAudioHudTimer()
                audioPlayer?.let { mp ->
                    seekBar?.let { sb ->
                        if (mp.duration > 0) {
                            val targetMs = (sb.progress.toLong() * mp.duration / 1000L).toInt()
                            mp.seekTo(targetMs)
                        }
                    }
                }
            }
        })
    }

    // Auto-hide OSD/HUD methods
    private fun showVideoHud() {
        layoutVideoTopBar.visibility = View.VISIBLE
        layoutVideoControls.visibility = View.VISIBLE
        resetVideoHudTimer()
    }

    private fun hideVideoHud() {
        if (currentScreen == Screen.VIDEO_PLAYER && vlcPlayer?.isPlaying == true && !isVideoTracking && overlayQueue.visibility != View.VISIBLE) {
            layoutVideoTopBar.visibility = View.GONE
            layoutVideoControls.visibility = View.GONE
        }
    }

    private fun resetVideoHudTimer() {
        hudHandler.removeCallbacks(hideVideoHudRunnable)
        if (currentScreen == Screen.VIDEO_PLAYER) {
            hudHandler.postDelayed(hideVideoHudRunnable, 4500)
        }
    }

    private fun showAudioHud() {
        layoutAudioControls.visibility = View.VISIBLE
        resetAudioHudTimer()
    }

    private fun hideAudioHud() {
        if (currentScreen == Screen.AUDIO_PLAYER && audioPlayer?.isPlaying == true && !isAudioTracking && overlayQueue.visibility != View.VISIBLE) {
            layoutAudioControls.visibility = View.GONE
        }
    }

    private fun resetAudioHudTimer() {
        hudHandler.removeCallbacks(hideAudioHudRunnable)
        if (currentScreen == Screen.AUDIO_PLAYER) {
            hudHandler.postDelayed(hideAudioHudRunnable, 4000)
        }
    }

    private fun updateLoopButtonUi() {
        val vText = when (videoLoopMode) {
            LoopMode.OFF -> "🔁 LOOP"
            LoopMode.SINGLE -> "🔂 LOOP: 1"
            LoopMode.ALL -> "🔁 LOOP: ALL"
        }
        btnVideoLoop.text = vText
        tvVideoHudLoop.text = when (videoLoopMode) {
            LoopMode.OFF -> "LOOP: OFF"
            LoopMode.SINGLE -> "LOOP: SINGLE"
            LoopMode.ALL -> "LOOP: ALL"
        }

        val aText = when (audioLoopMode) {
            LoopMode.OFF -> "🔁 LOOP"
            LoopMode.SINGLE -> "🔂 LOOP: 1"
            LoopMode.ALL -> "🔁 LOOP: ALL"
        }
        btnAudioLoop.text = aText
    }

    private fun updateBgPlayButtonUi() {
        val label = if (isBackgroundPlayEnabled) "🎧 BG: ON" else "🎧 BG: OFF"
        btnVideoBgPlay.text = label
        btnAudioBgPlay.text = label
    }

    private fun showQueueOverlay(items: List<PtpMediaItem>, activeIndex: Int) {
        hudHandler.removeCallbacks(hideVideoHudRunnable)
        hudHandler.removeCallbacks(hideAudioHudRunnable)
        queueAdapter.updateItems(items, activeIndex)
        overlayQueue.visibility = View.VISIBLE
        btnCloseQueue.requestFocus()
    }

    private fun handlePlayPauseAction(){
        if(currentScreen==Screen.VIDEO_PLAYER){
            val p=vlcPlayer?:return
            try{
                if(p.isPlaying){
                    p.pause()
                    btnVideoPlayPause.text="▶ PLAY"
                    showVideoHud()
                    updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED,p.time)
                }else{
                    p.play()
                    btnVideoPlayPause.text="⏸ PAUSE"
                    resetVideoHudTimer()
                    updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING,p.time)
                }
            }catch(e:Throwable){Log.w(PtpConstants.TAG,"VLC play/pause failed",e)}
        }else if(currentScreen==Screen.AUDIO_PLAYER){
            audioPlayer?.let{mp->try{if(mp.isPlaying){mp.pause();btnAudioPlayPause.text="▶";tvAudioPlayerStatus.text="[ PAUSED ]";showAudioHud();updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED,mp.currentPosition.toLong())}else{mp.start();btnAudioPlayPause.text="⏸";tvAudioPlayerStatus.text="[ PLAYING ]";resetAudioHudTimer();updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING,mp.currentPosition.toLong())}}catch(_:Exception){}}
        }
    }

    private fun handleRewind10Action(){
        if(currentScreen==Screen.VIDEO_PLAYER){
            try{
                val p=vlcPlayer?:return
                val target=(p.time-10000L).coerceAtLeast(0L)
                isVlcSeeking=true
                layoutVideoBuffering.visibility=View.VISIBLE
                tvBuffering.text="Seeking / buffering..."
                p.setTime(target)
                lifecycleScope.launch{delay(1200);if(isVlcSeeking&&p===vlcPlayer){isVlcSeeking=false;layoutVideoBuffering.visibility=View.GONE}}
                showVideoHud()
            }catch(e:Throwable){Log.w(PtpConstants.TAG,"VLC rewind failed",e)}
        }else if(currentScreen==Screen.AUDIO_PLAYER){audioPlayer?.let{mp->try{mp.seekTo((mp.currentPosition-10000).coerceAtLeast(0));showAudioHud()}catch(_:Exception){}}}
    }

    private fun handleForward10Action(){
        if(currentScreen==Screen.VIDEO_PLAYER){
            try{
                val p=vlcPlayer?:return
                val target=(p.time+10000L).coerceAtMost(p.length)
                isVlcSeeking=true
                layoutVideoBuffering.visibility=View.VISIBLE
                tvBuffering.text="Seeking / buffering..."
                p.setTime(target)
                lifecycleScope.launch{delay(1200);if(isVlcSeeking&&p===vlcPlayer){isVlcSeeking=false;layoutVideoBuffering.visibility=View.GONE}}
                showVideoHud()
            }catch(e:Throwable){Log.w(PtpConstants.TAG,"VLC forward failed",e)}
        }else if(currentScreen==Screen.AUDIO_PLAYER){audioPlayer?.let{mp->try{mp.seekTo((mp.currentPosition+10000).coerceAtMost(mp.duration));showAudioHud()}catch(_:Exception){}}}
    }

    private fun handlePreviousAction() {
        if (currentScreen == Screen.VIDEO_PLAYER) {
            if (currentVideoIndex > 0) {
                playVideoAtIndex(currentVideoIndex - 1)
            } else if (videoLoopMode == LoopMode.ALL && displayedVideoList.isNotEmpty()) {
                playVideoAtIndex(displayedVideoList.size - 1)
            }
        } else if (currentScreen == Screen.AUDIO_PLAYER) {
            if (currentAudioIndex > 0) {
                playAudioAtIndex(currentAudioIndex - 1)
            } else if (audioLoopMode == LoopMode.ALL && displayedAudioList.isNotEmpty()) {
                playAudioAtIndex(displayedAudioList.size - 1)
            }
        }
    }

    private fun handleNextAction() {
        if (currentScreen == Screen.VIDEO_PLAYER) {
            if (currentVideoIndex < displayedVideoList.size - 1) {
                playVideoAtIndex(currentVideoIndex + 1)
            } else if (videoLoopMode == LoopMode.ALL && displayedVideoList.isNotEmpty()) {
                playVideoAtIndex(0)
            }
        } else if (currentScreen == Screen.AUDIO_PLAYER) {
            if (currentAudioIndex < displayedAudioList.size - 1) {
                playAudioAtIndex(currentAudioIndex + 1)
            } else if (audioLoopMode == LoopMode.ALL && displayedAudioList.isNotEmpty()) {
                playAudioAtIndex(0)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        usbHostManager.start()
    }

    override fun onStop() {
        super.onStop()
        if (!isBackgroundPlayEnabled) {
            stopAndClearVideoPlayer()
            stopAndClearAudioPlayer()
            stopBackgroundService()
        } else {
            val activeTitle = if (currentScreen == Screen.AUDIO_PLAYER && currentAudioIndex in displayedAudioList.indices) {
                displayedAudioList[currentAudioIndex].displayName
            } else if (currentScreen == Screen.VIDEO_PLAYER && currentVideoIndex in displayedVideoList.indices) {
                displayedVideoList[currentVideoIndex].displayName
            } else {
                "Playing media"
            }
            startBackgroundService(activeTitle)
        }

        photoLoadJob?.cancel()
        legacyPhotoThumbLoader.clear()
        mediaThumbnailLoader.clear()
        usbHostManager.stop()
    }

    override fun onDestroy(){
        hudHandler.removeCallbacks(hideVideoHudRunnable);hudHandler.removeCallbacks(hideAudioHudRunnable);stopBackgroundService()
        stopAndClearVideoPlayer();stopAndClearAudioPlayer()
        try{bridge.stop()}catch(_:Throwable){}
        try{libVlc?.release()}catch(_:Throwable){}
        vlcPlayer=null
        libVlc=null
        mediaSession?.release()
        audioCacheManager.clearCache()
        lifecycleScope.launch{repository.clear()}
        super.onDestroy()
    }

    private fun startBackgroundService(title: String) {
        try {
            val serviceIntent = Intent(this, BackgroundPlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Could not start background playback service", e)
        }
    }

    private fun stopBackgroundService() {
        try {
            val serviceIntent = Intent(this, BackgroundPlayService::class.java)
            stopService(serviceIntent)
        } catch (_: Exception) {}
    }

    private var initJob: Job? = null

    private fun initVlc(){
        if(libVlc==null){
            libVlc=LibVLC(this,arrayListOf("--network-caching=750","--file-caching=750","--avcodec-hw=any"))
        }
        if(vlcPlayer==null){
            vlcPlayer=createVlcPlayer()
        }
    }

    private fun setVlcPlaybackRate(speed:Float){
        try{
            val p=vlcPlayer?:return
            val methods=p.javaClass.methods
            val m=methods.firstOrNull{it.name=="setRate"&&it.parameterTypes.size==1}?:return
            val type=m.parameterTypes[0]
            val value:Any=when{
                type==java.lang.Float.TYPE||type==java.lang.Float::class.java->speed
                type==java.lang.Double.TYPE||type==java.lang.Double::class.java->speed.toDouble()
                type==java.lang.Integer.TYPE||type==java.lang.Integer::class.java->speed.toInt()
                else->speed
            }
            m.invoke(p,value)
        }catch(_:Throwable){}
    }

    private data class VlcTrackChoice(val id:String,val label:String)

    private fun readTrackValue(track:Any,names:Array<String>):Any?{
        for(name in names){
            try{
                val m=track.javaClass.methods.firstOrNull{it.name==name&&it.parameterTypes.isEmpty()}
                val v=m?.invoke(track)
                if(v!=null)return v
            }catch(_:Throwable){}
            try{
                val fieldName=name.removePrefix("get").replaceFirstChar{it.lowercase()}
                val f=track.javaClass.fields.firstOrNull{it.name==fieldName}
                val v=f?.get(track)
                if(v!=null)return v
            }catch(_:Throwable){}
        }
        return null
    }

    private fun parseVlcTrackResult(result:Any?):List<VlcTrackChoice>{
        if(result==null)return emptyList()
        val out=mutableListOf<VlcTrackChoice>()
        if(result is Map<*,*>){
            result.forEach{(k,v)->
                if(k!=null&&v!=null){
                    val id=k.toString()
                    val label=v.toString().ifBlank{"Track "+id}
                    if(id!="-1")out.add(VlcTrackChoice(id,label))
                }
            }
            return out
        }
        val items=when(result){
            is Array<*>->result.asList()
            is Iterable<*>->result.toList()
            else->emptyList<Any?>()
        }
        for(track in items){
            if(track==null)continue
            val rawId=readTrackValue(track,arrayOf("getId","getTrackId","id","trackId"))?:continue
            val id=rawId.toString()
            if(id=="-1")continue
            val label=readTrackValue(track,arrayOf("getName","getDescription","getLabel","name","description","label","language"))?.toString()?.ifBlank{null}?:("Track "+id)
            out.add(VlcTrackChoice(id,label))
        }
        return out
    }

    private fun getMediaVlcTracks(typeName:String):List<VlcTrackChoice>{
        val media=currentVlcMedia?:return emptyList()
        return try{
            val countMethod=media.javaClass.methods.firstOrNull{it.name=="getTrackCount"&&it.parameterTypes.isEmpty()}?:return emptyList()
            val getMethod=media.javaClass.methods.firstOrNull{it.name=="getTrack"&&it.parameterTypes.size==1}?:return emptyList()
            val count=(countMethod.invoke(media) as? Number)?.toInt()?:0
            val out=mutableListOf<VlcTrackChoice>()
            for(i in 0 until count){
                val track=getMethod.invoke(media,i)?:continue
                val type=readTrackValue(track,arrayOf("getType","type"))?.toString()?.lowercase()?:""
                val matches=when(typeName.lowercase()){
                    "audio"->type.contains("audio")
                    else->type.contains("text")||type.contains("subtitle")||type.contains("spu")
                }
                if(!matches)continue
                val id=readTrackValue(track,arrayOf("getId","id"))?.toString()?:continue
                val label=readTrackValue(track,arrayOf("getDescription","description","getLanguage","language","getName","name"))?.toString()?.ifBlank{null}?:("Track "+id)
                out.add(VlcTrackChoice(id,label))
            }
            out
        }catch(_:Throwable){emptyList()}
    }

    private fun getGenericVlcTracks(typeName:String):List<VlcTrackChoice>{
        val p=vlcPlayer?:return emptyList()
        val methods=p.javaClass.methods.filter{it.name=="getTracks"&&it.parameterTypes.size==1}
        for(method in methods){
            val parameterType=method.parameterTypes[0]
            if(!parameterType.isEnum)continue
            val constants=parameterType.enumConstants?:continue
            for(constant in constants){
                val name=constant.toString().lowercase()
                val matches=if(typeName.equals("audio",true))name.contains("audio") else name.contains("text")||name.contains("subtitle")||name.contains("spu")
                if(!matches)continue
                try{
                    val parsed=parseVlcTrackResult(method.invoke(p,constant))
                    if(parsed.isNotEmpty())return parsed
                }catch(_:Throwable){}
            }
        }
        return emptyList()
    }

    private fun getVlcTracks(methodName:String,typeName:String):List<VlcTrackChoice>{
        val p=vlcPlayer?:return emptyList()
        val direct=try{
            val m=p.javaClass.methods.firstOrNull{it.name==methodName&&it.parameterTypes.isEmpty()}
            parseVlcTrackResult(m?.invoke(p))
        }catch(_:Throwable){emptyList()}
        if(direct.isNotEmpty())return direct

        val descriptionMethod=if(typeName.equals("audio",true))"getAudioTrackDescription" else "getSpuTrackDescription"
        val described=try{
            val m=p.javaClass.methods.firstOrNull{it.name==descriptionMethod&&it.parameterTypes.isEmpty()}
            parseVlcTrackResult(m?.invoke(p))
        }catch(_:Throwable){emptyList()}
        if(described.isNotEmpty())return described

        val generic=getGenericVlcTracks(typeName)
        if(generic.isNotEmpty())return generic
        return getMediaVlcTracks(typeName)
    }

    private fun setVlcTrack(methodNames:Array<String>,trackId:String){
        val p=vlcPlayer?:return
        for(method in p.javaClass.methods){
            if(method.name !in methodNames||method.parameterTypes.size!=1)continue
            val type=method.parameterTypes[0]
            val arg:Any?=when{
                type==String::class.java->trackId
                type==java.lang.Integer.TYPE||type==java.lang.Integer::class.java->trackId.toIntOrNull()
                type==java.lang.Long.TYPE||type==java.lang.Long::class.java->trackId.toLongOrNull()
                else->null
            }
            if(arg==null)continue
            try{method.invoke(p,arg);return}catch(_:Throwable){}
        }
    }

    private fun unselectVlcTrack(typeName:String){
        val p=vlcPlayer?:return
        for(method in p.javaClass.methods){
            if(method.name!="unselectTrackType"||method.parameterTypes.size!=1)continue
            val type=method.parameterTypes[0]
            if(type.isEnum){
                for(constant in type.enumConstants?:emptyArray()){
                    val n=constant.toString().lowercase()
                    val matches=if(typeName.equals("audio",true))n.contains("audio") else n.contains("text")||n.contains("subtitle")||n.contains("spu")
                    if(matches){try{method.invoke(p,constant);return}catch(_:Throwable){}}
                }
            }else if(type==String::class.java){
                try{method.invoke(p,if(typeName.equals("audio",true))"Audio" else "Text");return}catch(_:Throwable){}
            }
        }
        if(typeName.equals("audio",true)){setVlcTrack(arrayOf("setAudioTrack","selectTrack"),"-1")}else{setVlcTrack(arrayOf("setSpuTrack","selectTrack"),"-1")}
    }

    private fun refreshVlcTrackButtonsSoon(sessionId:Long){
        lifecycleScope.launch{
            repeat(6){attempt->
                delay(if(attempt==0)200L else 350L)
                if(sessionId!=currentVideoSessionId||currentScreen!=Screen.VIDEO_PLAYER)return@launch
                val audio=getVlcTracks("getAudioTracks","audio")
                val subs=getVlcTracks("getSpuTracks","subtitle")
                if(audio.isNotEmpty())btnVideoAudioTrack.text="🔊 AUDIO ("+audio.size+")"
                if(subs.isNotEmpty())btnVideoSubs.text="💬 SUBS ("+subs.size+")"
                if(audio.isNotEmpty()||subs.isNotEmpty())return@launch
            }
        }
    }

    private fun showSubtitlesDialog(){
        lifecycleScope.launch{
            var tracks=emptyList<VlcTrackChoice>()
            repeat(6){attempt->
                tracks=getVlcTracks("getSpuTracks","subtitle")
                if(tracks.isNotEmpty())return@repeat
                delay(if(attempt==0)100L else 250L)
            }
            if(tracks.isEmpty()){
                Toast.makeText(this@MainActivity,"No subtitle tracks found in this video",Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels=arrayOf("Subtitles: Off")+tracks.map{it.label}
            AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.subtitles_track)).setItems(labels){d,which->
                if(which==0){unselectVlcTrack("subtitle");btnVideoSubs.text="💬 SUBS"}
                else{val t=tracks[which-1];setVlcTrack(arrayOf("setSpuTrack","selectTrack"),t.id);btnVideoSubs.text="💬 "+t.label}
                d.dismiss();btnVideoSubs.requestFocus()
            }.setNegativeButton("CANCEL",null).show()
        }
    }

    private fun showAudioTrackDialog(){
        lifecycleScope.launch{
            var tracks=emptyList<VlcTrackChoice>()
            repeat(6){attempt->
                tracks=getVlcTracks("getAudioTracks","audio")
                if(tracks.isNotEmpty())return@repeat
                delay(if(attempt==0)100L else 250L)
            }
            if(tracks.isEmpty()){
                Toast.makeText(this@MainActivity,"No audio tracks found in this video",Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels=tracks.map{it.label}.toTypedArray()
            AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.audio_track)).setItems(labels){d,which->
                val t=tracks[which]
                setVlcTrack(arrayOf("setAudioTrack","selectTrack"),t.id)
                btnVideoAudioTrack.text="🔊 "+t.label
                d.dismiss();btnVideoAudioTrack.requestFocus()
            }.setNegativeButton("CANCEL",null).show()
        }
    }
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
                stopBackgroundService()
                photoLoadJob?.cancel()
                audioCachingJob?.cancel()
                audioProgressJob?.cancel()
                legacyPhotoThumbLoader.clear()
                mediaThumbnailLoader.clear()
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
        applySorting()

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

        overlayQueue.visibility = View.GONE
        overlayCopy.visibility = View.GONE

        if (screen == Screen.PHOTO_VIEWER) {
            screenPhotoViewer.requestFocus()
        } else if (screen == Screen.VIDEO_PLAYER) {
            showVideoHud()
            btnVideoPlayPause.requestFocus()
        } else if (screen == Screen.AUDIO_PLAYER) {
            showAudioHud()
            btnAudioPlayPause.requestFocus()
        }
    }

    private fun loadSelectedPhoto(index: Int) {
        if (index < 0 || index >= displayedPhotoList.size) return
        val item = displayedPhotoList[index]

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

    fun playVideoAtIndex(index: Int) {
        if(index<0||index>=displayedVideoList.size)return
        currentVideoIndex=index
        startVideoPlayback(displayedVideoList[index])
    }

    private fun releaseVlcPlayerForSwitch(){
        isVlcSeeking=false
        bridge.invalidatePlayback()
        vlcGenerationCounter++
        val old=vlcPlayer
        vlcPlayer=null
        try{old?.stop()}catch(_:Throwable){}
        try{old?.detachViews()}catch(_:Throwable){}
        try{old?.release()}catch(_:Throwable){}
        try{currentVlcMedia?.release()}catch(_:Throwable){}
        currentVlcMedia=null
    }

    private fun createVlcPlayer():VlcMediaPlayer?{
        val engine=libVlc?:return null
        val generation=++vlcGenerationCounter
        val player=try{VlcMediaPlayer(engine)}catch(e:Throwable){Log.e(PtpConstants.TAG,"Could not create VLC player",e);return null}
        player.setEventListener{event->runOnUiThread{
            try{
                if(player!==vlcPlayer||generation!=vlcGenerationCounter||currentScreen!=Screen.VIDEO_PLAYER)return@runOnUiThread
                when(event.type){
                    VlcMediaPlayer.Event.Playing->{
                        tvVideoError.visibility=View.GONE
                        btnVideoPlayPause.text="⏸ PAUSE"
                        if(!isVlcSeeking){layoutVideoBuffering.visibility=View.GONE}
                        resetVideoHudTimer()
                        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING,player.time)
                    }
                    VlcMediaPlayer.Event.Paused->{
                        if(!isVlcSeeking){layoutVideoBuffering.visibility=View.GONE}
                        btnVideoPlayPause.text="▶ PLAY"
                        showVideoHud()
                        updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED,player.time)
                    }
                    VlcMediaPlayer.Event.Buffering->{
                        val pct=event.getBuffering().toInt().coerceIn(0,100)
                        if(pct<100||isVlcSeeking){
                            layoutVideoBuffering.visibility=View.VISIBLE
                            tvBuffering.text=if(isVlcSeeking)"Seeking / buffering..." else "Buffering $pct%"
                        }else{
                            isVlcSeeking=false
                            layoutVideoBuffering.visibility=View.GONE
                        }
                    }
                    VlcMediaPlayer.Event.EncounteredError->{
                        isVlcSeeking=false
                        layoutVideoBuffering.visibility=View.GONE
                        tvVideoError.text="Video could not be loaded from the phone"
                        tvVideoError.visibility=View.VISIBLE
                        btnVideoPlayPause.text="▶ PLAY"
                        showVideoHud()
                        updateMediaSessionState(PlaybackStateCompat.STATE_ERROR,player.time)
                    }
                    VlcMediaPlayer.Event.EndReached->{
                        isVlcSeeking=false
                        layoutVideoBuffering.visibility=View.GONE
                        when(videoLoopMode){
                            LoopMode.OFF->{
                                if(currentVideoIndex<displayedVideoList.size-1)playVideoAtIndex(currentVideoIndex+1)
                                else{btnVideoPlayPause.text="▶ PLAY";showVideoHud();updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED,try{player.length}catch(_:Throwable){0L})}
                            }
                            LoopMode.SINGLE->{try{player.setTime(0L);player.play()}catch(_:Throwable){}}
                            LoopMode.ALL->{if(displayedVideoList.isNotEmpty())playVideoAtIndex((currentVideoIndex+1)%displayedVideoList.size)}
                        }
                    }
                }
            }catch(e:Throwable){Log.w(PtpConstants.TAG,"VLC UI event handling failed",e)}
        }}
        return player
    }

    private fun startVideoPlayback(item:PtpMediaItem){
        val sessionId=++currentVideoSessionId
        videoProgressJob?.cancel()
        releaseVlcPlayerForSwitch()
        showScreen(Screen.VIDEO_PLAYER)
        tvVideoError.visibility=View.GONE
        tvVideoError.text=""
        tvVideoTitle.text=item.displayName
        tvVideoBadgeFormat.text=item.filename.substringAfterLast(".","VIDEO").uppercase()
        tvVideoTimeCurrent.text="00:00"
        tvVideoTimeTotal.text="00:00"
        sbVideoSeek.progress=0
        btnVideoPlayPause.text="⏸ PAUSE"
        btnVideoSubs.text="💬 SUBS"
        btnVideoAudioTrack.text="🔊 AUDIO"
        currentSubtitlesTrack=-1
        currentPlaybackSpeed=1.0f
        setPlaybackSpeed(1.0f)
        updateLoopButtonUi()
        tvBuffering.text=getString(R.string.buffering)
        layoutVideoBuffering.visibility=View.VISIBLE
        showVideoHud()

        val engine=libVlc
        if(engine==null||bridge.port<=0){
            layoutVideoBuffering.visibility=View.GONE
            tvVideoError.text=getString(R.string.video_load_failed)
            tvVideoError.visibility=View.VISIBLE
            btnVideoPlayPause.text="▶ PLAY"
            return
        }

        bridge.beginPlayback(item.handle)
        val p=createVlcPlayer()
        if(p==null){
            layoutVideoBuffering.visibility=View.GONE
            tvVideoError.text=getString(R.string.video_load_failed)
            tvVideoError.visibility=View.VISIBLE
            btnVideoPlayPause.text="▶ PLAY"
            return
        }
        vlcPlayer=p

        try{
            val media=Media(engine,Uri.parse(bridge.urlFor(item))).apply{
                addOption(":http-reconnect=true")
                addOption(":http-continuous=true")
                addOption(":network-caching=750")
                addOption(":file-caching=750")
            }
            currentVlcMedia=media
            p.attachViews(videoLayout,null,true,false)
            p.setMedia(media)
            p.play()
            startVideoProgressLoop(sessionId)
            refreshVlcTrackButtonsSoon(sessionId)
        }catch(e:Throwable){
            if(sessionId==currentVideoSessionId){
                Log.e(PtpConstants.TAG,"Failed to open PTP media in VLC",e)
                layoutVideoBuffering.visibility=View.GONE
                tvVideoError.text=getString(R.string.video_load_failed)
                tvVideoError.visibility=View.VISIBLE
                btnVideoPlayPause.text="▶ PLAY"
            }
        }
    }

    private fun startVideoProgressLoop(sessionId:Long=currentVideoSessionId){
        videoProgressJob?.cancel()
        videoProgressJob=lifecycleScope.launch{
            while(currentScreen==Screen.VIDEO_PLAYER&&sessionId==currentVideoSessionId){
                try{
                    val p=vlcPlayer
                    if(p!=null&&p.length>0&&!isVideoTracking){
                        val total=p.length
                        if(total>0){
                            sbVideoSeek.progress=(p.position*1000f).toInt().coerceIn(0,1000)
                            tvVideoTimeCurrent.text=formatTime(p.time.toInt().coerceAtLeast(0))
                            tvVideoTimeTotal.text=formatTime(total.toInt().coerceAtLeast(0))
                        }
                    }
                }catch(_:Throwable){}
                delay(300)
            }
        }
    }

    private fun stopAndClearVideoPlayer(){
        currentVideoSessionId++
        hudHandler.removeCallbacks(hideVideoHudRunnable)
        videoProgressJob?.cancel()
        releaseVlcPlayerForSwitch()
        layoutVideoBuffering.visibility=View.GONE
        tvVideoError.visibility=View.GONE
        tvVideoError.text=""
        updateMediaSessionState(PlaybackStateCompat.STATE_NONE,0L)
    }
    fun playAudioAtIndex(index: Int) {
        if (index < 0 || index >= displayedAudioList.size) return
        currentAudioIndex = index
        val item = displayedAudioList[index]
        startAudioPlayback(item)
    }

    private fun startAudioPlayback(item: PtpMediaItem) {
        val client = repository.client ?: return
        val sessionId = ++currentAudioSessionId

        stopAudioPlaybackOnly()
        audioCachingJob?.cancel()
        audioProgressJob?.cancel()
        audioCacheManager.cancelBuffering()
        audioCacheManager.clearCache()

        showScreen(Screen.AUDIO_PLAYER)

        tvAudioError.visibility = View.GONE
        tvAudioError.text = ""
        tvAudioPlayerTitle.text = item.displayName
        tvAudioPlayerStatus.text = ""
        tvAudioTimeCurrent.text = "00:00"
        tvAudioTimeTotal.text = "00:00"
        progressAudioSeek.progress = 0
        btnAudioPlayPause.text = "⏸"
        updateLoopButtonUi()

        tvAudioBuffering.text = getString(R.string.buffering)
        layoutAudioBuffering.visibility = View.VISIBLE
        showAudioHud()

        // Load artwork into hero & ambient background
        mediaThumbnailLoader.loadThumbnail(item, ivAudioPlayerArt, R.drawable.ic_audio_placeholder)
        mediaThumbnailLoader.loadThumbnail(item, ivAudioAmbientBg, R.drawable.ic_audio_placeholder)

        audioCachingJob = lifecycleScope.launch {
            val file = audioCacheManager.cacheAudio(
                client = client,
                handle = item.handle,
                filename = item.filename,
                sessionId = sessionId
            )
            if (sessionId != currentAudioSessionId) return@launch

            if (file != null && file.exists() && file.length() > 0) {
                try {
                    val mp = MediaPlayer()
                    audioPlayer = mp
                    mp.setDataSource(file.absolutePath)
                    mp.setOnPreparedListener { player ->
                        if (sessionId != currentAudioSessionId) return@setOnPreparedListener
                        tvAudioError.visibility = View.GONE
                        layoutAudioBuffering.visibility = View.GONE
                        player.isLooping = (audioLoopMode == LoopMode.SINGLE)
                        player.start()
                        btnAudioPlayPause.text = "⏸"
                        tvAudioPlayerStatus.text = "[ PLAYING ]"
                        tvAudioTimeTotal.text = formatTime(player.duration)
                        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING, 0L)
                        startAudioProgressLoop(player, sessionId)
                        resetAudioHudTimer()
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        if (sessionId != currentAudioSessionId) return@setOnErrorListener true
                        Log.e(PtpConstants.TAG, "Audio playback error: what=$what extra=$extra")
                        layoutAudioBuffering.visibility = View.GONE
                        tvAudioError.text = getString(R.string.audio_codec_unsupported)
                        tvAudioError.visibility = View.VISIBLE
                        tvAudioPlayerStatus.text = "[ ERROR ]"
                        btnAudioPlayPause.text = "▶"
                        showAudioHud()
                        updateMediaSessionState(PlaybackStateCompat.STATE_ERROR, 0L)
                        true
                    }
                    mp.setOnCompletionListener {
                        if (sessionId != currentAudioSessionId) return@setOnCompletionListener
                        tvAudioPlayerStatus.text = "[ FINISHED ]"
                        progressAudioSeek.progress = 1000
                        updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED, mp.duration.toLong())

                        when (audioLoopMode) {
                            LoopMode.OFF -> {
                                btnAudioPlayPause.text = "▶"
                                showAudioHud()
                                if (currentAudioIndex < displayedAudioList.size - 1) {
                                    playAudioAtIndex(currentAudioIndex + 1)
                                }
                            }
                            LoopMode.SINGLE -> {
                                mp.seekTo(0)
                                mp.start()
                            }
                            LoopMode.ALL -> {
                                val nextIdx = (currentAudioIndex + 1) % displayedAudioList.size
                                playAudioAtIndex(nextIdx)
                            }
                        }
                    }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    if (sessionId == currentAudioSessionId) {
                        Log.e(PtpConstants.TAG, "Error initializing MediaPlayer for audio", e)
                        layoutAudioBuffering.visibility = View.GONE
                        tvAudioError.text = getString(R.string.audio_codec_unsupported)
                        tvAudioError.visibility = View.VISIBLE
                        tvAudioPlayerStatus.text = "[ ERROR ]"
                        showAudioHud()
                    }
                }
            } else {
                if (sessionId == currentAudioSessionId) {
                    layoutAudioBuffering.visibility = View.GONE
                    tvAudioError.text = getString(R.string.audio_load_failed)
                    tvAudioError.visibility = View.VISIBLE
                    tvAudioPlayerStatus.text = "[ FAILED ]"
                    showAudioHud()
                }
            }
        }
    }

    private fun startAudioProgressLoop(player: MediaPlayer, sessionId: Long = currentAudioSessionId) {
        audioProgressJob?.cancel()
        audioProgressJob = lifecycleScope.launch {
            while (audioPlayer == player && sessionId == currentAudioSessionId) {
                try {
                    if (player.isPlaying && !isAudioTracking) {
                        if (tvAudioError.visibility == View.VISIBLE) {
                            tvAudioError.visibility = View.GONE
                        }
                        val current = player.currentPosition
                        val total = player.duration
                        if (total > 0) {
                            val ratio = (current.toLong() * 1000L / total).toInt()
                            progressAudioSeek.progress = ratio
                            tvAudioTimeCurrent.text = formatTime(current)
                            tvAudioTimeTotal.text = formatTime(total)
                        }
                    }
                } catch (_: Exception) {}
                delay(500)
            }
        }
    }

    private fun formatTime(millis: Int): String {
        val totalSecs = (millis / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format("%02d:%02d", mins, secs)
    }

    private fun stopAudioPlaybackOnly() {
        hudHandler.removeCallbacks(hideAudioHudRunnable)
        audioProgressJob?.cancel()
        audioPlayer?.let {
            try {
                it.setOnPreparedListener(null)
                it.setOnErrorListener(null)
                it.setOnCompletionListener(null)
                if (it.isPlaying) it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        audioPlayer = null
        updateMediaSessionState(PlaybackStateCompat.STATE_NONE, 0L)
    }

    private fun stopAndClearAudioPlayer() {
        currentAudioSessionId++
        audioCachingJob?.cancel()
        audioCacheManager.cancelBuffering()
        stopAudioPlaybackOnly()
        layoutAudioBuffering.visibility = View.GONE
        tvAudioError.visibility = View.GONE
        tvAudioError.text = ""
        tvAudioPlayerStatus.text = ""
        audioCacheManager.clearCache()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If Queue Overlay is open, handle Back to dismiss it
        if (overlayQueue.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                overlayQueue.visibility = View.GONE
                if (currentScreen == Screen.VIDEO_PLAYER) {
                    btnVideoQueue.requestFocus()
                } else if (currentScreen == Screen.AUDIO_PLAYER) {
                    btnAudioQueue.requestFocus()
                }
                return true
            }
        }

        // If Copy Overlay is open, handle Back to cancel it
        if (overlayCopy.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                contextMenuHelper.cancelCopy()
                overlayCopy.visibility = View.GONE
                return true
            }
        }

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
                        if (currentPhotoIndex < displayedPhotoList.size - 1) {
                            currentPhotoIndex++
                            loadSelectedPhoto(currentPhotoIndex)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        if (currentPhotoIndex in displayedPhotoList.indices) {
                            contextMenuHelper.showContextMenu(displayedPhotoList[currentPhotoIndex])
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
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    showVideoHud()
                    btnVideoPlayPause.requestFocus()
                    return true
                }

                // Any DPAD key interaction shows HUD and resets timer
                if (layoutVideoControls.visibility != View.VISIBLE) {
                    showVideoHud()
                    btnVideoPlayPause.requestFocus()
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    ) {
                        return true
                    }
                } else {
                    resetVideoHudTimer()
                }

                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        handlePlayPauseAction()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        handleNextAction()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        handlePreviousAction()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        handleForward10Action()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        handleRewind10Action()
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
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    showAudioHud()
                    btnAudioPlayPause.requestFocus()
                    return true
                }

                // Any interaction reveals controls and resets timer
                if (layoutAudioControls.visibility != View.VISIBLE) {
                    showAudioHud()
                    btnAudioPlayPause.requestFocus()
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    ) {
                        return true
                    }
                } else {
                    resetAudioHudTimer()
                }

                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        handlePlayPauseAction()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        handleNextAction()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        handlePreviousAction()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        handleForward10Action()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        handleRewind10Action()
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

// =================== RECYCLERVIEW ADAPTERS ===================

class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val ivThumb: ImageView = view.findViewById(R.id.iv_thumb)
    val tvName: TextView? = view.findViewById(R.id.tv_photo_name)
    val tvSize: TextView? = view.findViewById(R.id.tv_photo_size)
}

class PhotoAdapter(
    private val items: List<PtpMediaItem>,
    private val thumbnailLoader: PhotoThumbnailLoader,
    private val isListView: () -> Boolean,
    private val onItemClicked: (Int) -> Unit,
    private val onItemMenu: (PtpMediaItem) -> Unit
) : RecyclerView.Adapter<PhotoViewHolder>() {

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_LIST = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (isListView()) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val layoutId = if (viewType == VIEW_TYPE_LIST) R.layout.item_photo_list else R.layout.item_photo
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val item = items[position]
        holder.itemView.tag = item.handle
        holder.tvName?.text = item.displayName
        holder.tvSize?.text = item.formattedSize
        holder.itemView.setOnClickListener { onItemClicked(position) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                onItemMenu(item)
                true
            } else {
                false
            }
        }
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
    private val thumbnailLoader: MediaThumbnailLoader,
    private val isListView: () -> Boolean,
    private val onItemBound: (PtpMediaItem) -> Unit,
    private val onItemClicked: (PtpMediaItem) -> Unit,
    private val onItemMenu: (PtpMediaItem) -> Unit
) : RecyclerView.Adapter<VideoViewHolder>() {

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_LIST = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (isListView()) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val layoutId = if (viewType == VIEW_TYPE_LIST) R.layout.item_video_list else R.layout.item_video
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val item = items[position]
        holder.itemView.tag = item.handle
        holder.tvName.text = item.displayName
        holder.tvSize.text = item.formattedSize

        holder.itemView.setOnClickListener { onItemClicked(item) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                onItemMenu(item)
                true
            } else {
                false
            }
        }

        thumbnailLoader.loadThumbnail(item, holder.ivThumb, R.drawable.ic_video_placeholder)

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
    private val thumbnailLoader: MediaThumbnailLoader,
    private val isListView: () -> Boolean,
    private val onItemBound: (PtpMediaItem) -> Unit,
    private val onItemClicked: (PtpMediaItem) -> Unit,
    private val onItemMenu: (PtpMediaItem) -> Unit
) : RecyclerView.Adapter<AudioViewHolder>() {

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_LIST = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (isListView()) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AudioViewHolder {
        val layoutId = if (viewType == VIEW_TYPE_LIST) R.layout.item_audio_list else R.layout.item_audio
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return AudioViewHolder(view)
    }

    override fun onBindViewHolder(holder: AudioViewHolder, position: Int) {
        val item = items[position]
        holder.itemView.tag = item.handle
        holder.tvName.text = item.displayName
        holder.tvSize.text = item.formattedSize

        holder.itemView.setOnClickListener { onItemClicked(item) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                onItemMenu(item)
                true
            } else {
                false
            }
        }

        thumbnailLoader.loadThumbnail(item, holder.ivThumb, R.drawable.ic_audio_placeholder)

        if (!item.isMetadataLoaded) {
            onItemBound(item)
        }
    }

    override fun getItemCount(): Int = items.size
}

class QueueViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val tvIndex: TextView = view.findViewById(R.id.tv_queue_index)
    val tvTitle: TextView = view.findViewById(R.id.tv_queue_item_title)
    val tvPlaying: TextView = view.findViewById(R.id.tv_queue_item_playing)
}

class QueueAdapter(
    private var items: List<PtpMediaItem>,
    private var currentIndex: Int,
    private val onItemClicked: (Int) -> Unit
) : RecyclerView.Adapter<QueueViewHolder>() {

    fun updateItems(newItems: List<PtpMediaItem>, newCurrentIndex: Int) {
        this.items = newItems
        this.currentIndex = newCurrentIndex
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val item = items[position]
        holder.tvIndex.text = "${position + 1}."
        holder.tvTitle.text = item.displayName
        holder.tvPlaying.visibility = if (position == currentIndex) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onItemClicked(position) }
    }

    override fun getItemCount(): Int = items.size
}

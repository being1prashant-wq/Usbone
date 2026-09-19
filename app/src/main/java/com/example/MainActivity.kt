package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
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
import android.widget.VideoView
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
import com.example.media.VideoCacheManager
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import com.example.usb.UsbConnectionState
import com.example.usb.UsbHostManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

enum class LoopMode {
    OFF,
    SINGLE,
    ALL
}

class MainActivity : AppCompatActivity() {

    private lateinit var usbHostManager: UsbHostManager
    private lateinit var repository: PtpMediaRepository
    private lateinit var legacyPhotoThumbLoader: PhotoThumbnailLoader
    private lateinit var mediaThumbnailLoader: MediaThumbnailLoader
    private lateinit var videoCacheManager: VideoCacheManager
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
    private lateinit var tvBuffering: TextView
    private lateinit var tvVideoError: TextView
    private lateinit var layoutVideoControls: View
    private lateinit var tvVideoTitle: TextView
    private lateinit var tvVideoTime: TextView
    private lateinit var sbVideoSeek: SeekBar

    // Video Player Buttons
    private lateinit var btnVideoPrev: Button
    private lateinit var btnVideoRewind10: Button
    private lateinit var btnVideoPlayPause: Button
    private lateinit var btnVideoForward10: Button
    private lateinit var btnVideoNext: Button
    private lateinit var btnVideoSubs: Button
    private lateinit var btnVideoAudioTrack: Button
    private lateinit var btnVideoLoop: Button
    private lateinit var btnVideoBgPlay: Button
    private lateinit var btnVideoQueue: Button

    private var videoCachingJob: Job? = null
    private var videoProgressJob: Job? = null
    private var currentVideoPlayer: MediaPlayer? = null
    private var currentVideoIndex = -1
    private var isVideoTracking = false

    // Audio Browser Views
    private lateinit var rvAudio: RecyclerView
    private lateinit var tvAudioCount: TextView
    private lateinit var tvEmptyAudio: TextView
    private lateinit var audioAdapter: AudioAdapter

    // Audio Player Views
    private lateinit var ivAudioPlayerArt: ImageView
    private lateinit var tvAudioPlayerStatus: TextView
    private lateinit var tvAudioPlayerTime: TextView
    private lateinit var progressAudioSeek: SeekBar
    private lateinit var layoutAudioBuffering: View
    private lateinit var tvAudioBuffering: TextView
    private lateinit var tvAudioError: TextView

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
        rvPhotos.layoutManager = GridLayoutManager(this, 4)

        // Photo Viewer
        ivFullPhoto = findViewById(R.id.iv_full_photo)
        progressPhoto = findViewById(R.id.progress_photo)
        tvPhotoError = findViewById(R.id.tv_photo_error)

        // Video Browser
        rvVideos = findViewById(R.id.rv_videos)
        tvVideosCount = findViewById(R.id.tv_videos_count)
        tvEmptyVideos = findViewById(R.id.tv_empty_videos)
        rvVideos.layoutManager = GridLayoutManager(this, 4)

        // Video Player UI
        videoView = findViewById(R.id.video_view)
        layoutVideoBuffering = findViewById(R.id.layout_video_buffering)
        tvBuffering = findViewById(R.id.tv_buffering)
        tvVideoError = findViewById(R.id.tv_video_error)
        layoutVideoControls = findViewById(R.id.layout_video_controls)
        tvVideoTitle = findViewById(R.id.tv_video_title)
        tvVideoTime = findViewById(R.id.tv_video_time)
        sbVideoSeek = findViewById(R.id.sb_video_seek)

        btnVideoPrev = findViewById(R.id.btn_video_prev)
        btnVideoRewind10 = findViewById(R.id.btn_video_rewind10)
        btnVideoPlayPause = findViewById(R.id.btn_video_play_pause)
        btnVideoForward10 = findViewById(R.id.btn_video_forward10)
        btnVideoNext = findViewById(R.id.btn_video_next)
        btnVideoSubs = findViewById(R.id.btn_video_subs)
        btnVideoAudioTrack = findViewById(R.id.btn_video_audio_track)
        btnVideoLoop = findViewById(R.id.btn_video_loop)
        btnVideoBgPlay = findViewById(R.id.btn_video_bg_play)
        btnVideoQueue = findViewById(R.id.btn_video_queue)

        setupVideoControls()

        // Audio Browser
        rvAudio = findViewById(R.id.rv_audio)
        tvAudioCount = findViewById(R.id.tv_audio_count)
        tvEmptyAudio = findViewById(R.id.tv_empty_audio)
        rvAudio.layoutManager = GridLayoutManager(this, 4)

        // Audio Player
        ivAudioPlayerArt = findViewById(R.id.iv_audio_player_art)
        tvAudioPlayerStatus = findViewById(R.id.tv_audio_player_status)
        tvAudioPlayerTime = findViewById(R.id.tv_audio_player_time)
        progressAudioSeek = findViewById(R.id.progress_audio_seek)
        layoutAudioBuffering = findViewById(R.id.layout_audio_buffering)
        tvAudioBuffering = findViewById(R.id.tv_audio_buffering)
        tvAudioError = findViewById(R.id.tv_audio_error)

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
        videoCacheManager = VideoCacheManager(this)
        audioCacheManager = AudioCacheManager(this)

        contextMenuHelper = MediaContextMenuHelper(
            context = this,
            scope = lifecycleScope,
            ptpClientProvider = { repository.client },
            onCopyStart = { filename ->
                tvCopyStatus.text = getString(R.string.copying_file)
                tvCopyFileName.text = filename
                overlayCopy.visibility = View.VISIBLE
                btnCancelCopy.requestFocus()
            },
            onCopyProgress = { _, _ -> },
            onCopyComplete = { success, msg ->
                overlayCopy.visibility = View.GONE
                if (success) {
                    Toast.makeText(this, "${getString(R.string.copy_success)}\n$msg", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "${getString(R.string.copy_failed)}: $msg", Toast.LENGTH_SHORT).show()
                }
            }
        )

        photoAdapter = PhotoAdapter(
            items = repository.photoItems,
            thumbnailLoader = legacyPhotoThumbLoader,
            onItemClicked = { index ->
                currentPhotoIndex = index
                showScreen(Screen.PHOTO_VIEWER)
                loadSelectedPhoto(index)
            },
            onItemMenu = { item ->
                contextMenuHelper.showContextMenu(item)
            }
        )
        rvPhotos.adapter = photoAdapter

        videoAdapter = VideoAdapter(
            items = repository.videoItems,
            thumbnailLoader = mediaThumbnailLoader,
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
                val idx = repository.videoItems.indexOf(item)
                if (idx >= 0) {
                    playVideoAtIndex(idx)
                }
            },
            onItemMenu = { item ->
                contextMenuHelper.showContextMenu(item)
            }
        )
        rvVideos.adapter = videoAdapter

        audioAdapter = AudioAdapter(
            items = repository.audioItems,
            thumbnailLoader = mediaThumbnailLoader,
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
                val idx = repository.audioItems.indexOf(item)
                if (idx >= 0) {
                    playAudioAtIndex(idx)
                }
            },
            onItemMenu = { item ->
                contextMenuHelper.showContextMenu(item)
            }
        )
        rvAudio.adapter = audioAdapter

        queueAdapter = QueueAdapter(
            items = emptyList(),
            currentIndex = -1,
            onItemClicked = { index ->
                overlayQueue.visibility = View.GONE
                if (currentScreen == Screen.VIDEO_PLAYER) {
                    playVideoAtIndex(index)
                } else if (currentScreen == Screen.AUDIO_PLAYER) {
                    playAudioAtIndex(index)
                }
            }
        )
        rvQueue.adapter = queueAdapter

        usbHostManager = UsbHostManager(this) { state ->
            handleUsbState(state)
        }
    }

    private fun initMediaSession() {
        try {
            mediaSession = MediaSessionCompat(this, "DirectUSB_MediaSession").apply {
                setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        handlePlayPauseAction()
                    }

                    override fun onPause() {
                        handlePlayPauseAction()
                    }

                    override fun onSkipToNext() {
                        handleNextAction()
                    }

                    override fun onSkipToPrevious() {
                        handlePreviousAction()
                    }

                    override fun onFastForward() {
                        handleForward10Action()
                    }

                    override fun onRewind() {
                        handleRewind10Action()
                    }

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
                .setState(state, position, 1.0f)
                .build()
            mediaSession?.setPlaybackState(playbackState)
        } catch (_: Exception) {}
    }

    private fun setupVideoControls() {
        btnVideoPlayPause.setOnClickListener { handlePlayPauseAction() }
        btnVideoRewind10.setOnClickListener { handleRewind10Action() }
        btnVideoForward10.setOnClickListener { handleForward10Action() }
        btnVideoPrev.setOnClickListener { handlePreviousAction() }
        btnVideoNext.setOnClickListener { handleNextAction() }

        btnVideoLoop.setOnClickListener {
            videoLoopMode = when (videoLoopMode) {
                LoopMode.OFF -> LoopMode.SINGLE
                LoopMode.SINGLE -> LoopMode.ALL
                LoopMode.ALL -> LoopMode.OFF
            }
            updateLoopButtonUi()
        }

        btnVideoBgPlay.setOnClickListener {
            isBackgroundPlayEnabled = !isBackgroundPlayEnabled
            updateBgPlayButtonUi()
        }

        btnVideoQueue.setOnClickListener {
            showQueueOverlay(repository.videoItems, currentVideoIndex)
        }

        btnVideoSubs.setOnClickListener { showSubtitlesDialog() }
        btnVideoAudioTrack.setOnClickListener { showAudioTrackDialog() }

        sbVideoSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && videoView.duration > 0) {
                    val targetMs = (progress.toLong() * videoView.duration / 1000L).toInt()
                    tvVideoTime.text = "${formatTime(targetMs)} / ${formatTime(videoView.duration)}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isVideoTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isVideoTracking = false
                seekBar?.let {
                    if (videoView.duration > 0) {
                        val targetMs = (it.progress.toLong() * videoView.duration / 1000L).toInt()
                        videoView.seekTo(targetMs)
                    }
                }
            }
        })
    }

    private fun setupAudioControls() {
        btnAudioPlayPause.setOnClickListener { handlePlayPauseAction() }
        btnAudioRewind10.setOnClickListener { handleRewind10Action() }
        btnAudioForward10.setOnClickListener { handleForward10Action() }
        btnAudioPrev.setOnClickListener { handlePreviousAction() }
        btnAudioNext.setOnClickListener { handleNextAction() }

        btnAudioLoop.setOnClickListener {
            audioLoopMode = when (audioLoopMode) {
                LoopMode.OFF -> LoopMode.SINGLE
                LoopMode.SINGLE -> LoopMode.ALL
                LoopMode.ALL -> LoopMode.OFF
            }
            updateLoopButtonUi()
        }

        btnAudioBgPlay.setOnClickListener {
            isBackgroundPlayEnabled = !isBackgroundPlayEnabled
            updateBgPlayButtonUi()
        }

        btnAudioQueue.setOnClickListener {
            showQueueOverlay(repository.audioItems, currentAudioIndex)
        }

        progressAudioSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                audioPlayer?.let { mp ->
                    if (fromUser && mp.duration > 0) {
                        val targetMs = (progress.toLong() * mp.duration / 1000L).toInt()
                        tvAudioPlayerTime.text = "${formatTime(targetMs)} / ${formatTime(mp.duration)}"
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isAudioTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isAudioTracking = false
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

    private fun updateLoopButtonUi() {
        btnVideoLoop.text = when (videoLoopMode) {
            LoopMode.OFF -> getString(R.string.btn_loop)
            LoopMode.SINGLE -> "LOOP: 1"
            LoopMode.ALL -> "LOOP: ALL"
        }
        btnAudioLoop.text = when (audioLoopMode) {
            LoopMode.OFF -> getString(R.string.btn_loop)
            LoopMode.SINGLE -> "LOOP: 1"
            LoopMode.ALL -> "LOOP: ALL"
        }
    }

    private fun updateBgPlayButtonUi() {
        val label = if (isBackgroundPlayEnabled) "BG PLAY: ON" else "BG PLAY: OFF"
        btnVideoBgPlay.text = label
        btnAudioBgPlay.text = label
    }

    private fun showQueueOverlay(items: List<PtpMediaItem>, activeIndex: Int) {
        queueAdapter.updateItems(items, activeIndex)
        overlayQueue.visibility = View.VISIBLE
        btnCloseQueue.requestFocus()
    }

    private fun showSubtitlesDialog() {
        val mp = currentVideoPlayer
        if (mp == null) {
            Toast.makeText(this, "Subtitles not available", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val trackInfo = mp.trackInfo
            val subtitleTracks = mutableListOf<Pair<Int, String>>()
            subtitleTracks.add(Pair(-1, getString(R.string.subtitles_off)))

            var subCounter = 1
            for (i in trackInfo.indices) {
                if (trackInfo[i].trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT ||
                    trackInfo[i].trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                ) {
                    val lang = trackInfo[i].language.ifBlank { "Track $subCounter" }
                    subtitleTracks.add(Pair(i, lang))
                    subCounter++
                }
            }

            if (subtitleTracks.size == 1) {
                Toast.makeText(this, "No subtitle tracks embedded in this video", Toast.LENGTH_SHORT).show()
                return
            }

            val names = subtitleTracks.map { it.second }.toTypedArray()
            val selectedIdx = subtitleTracks.indexOfFirst { it.first == currentSubtitlesTrack }.coerceAtLeast(0)

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.subtitles_track))
                .setSingleChoiceItems(names, selectedIdx) { dialog, which ->
                    val chosen = subtitleTracks[which].first
                    currentSubtitlesTrack = chosen
                    try {
                        if (chosen == -1) {
                            // deselect all
                            for (p in subtitleTracks) {
                                if (p.first != -1) mp.deselectTrack(p.first)
                            }
                            btnVideoSubs.text = "SUBS"
                        } else {
                            mp.selectTrack(chosen)
                            btnVideoSubs.text = "SUBS: ${subtitleTracks[which].second}"
                        }
                    } catch (e: Exception) {
                        Log.w(PtpConstants.TAG, "Subtitle track selection failed", e)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("CANCEL", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Subtitles not supported for this media", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAudioTrackDialog() {
        val mp = currentVideoPlayer
        if (mp == null) {
            Toast.makeText(this, "Audio tracks not available", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val trackInfo = mp.trackInfo
            val audioTracks = mutableListOf<Pair<Int, String>>()
            var audioCounter = 1
            for (i in trackInfo.indices) {
                if (trackInfo[i].trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    val lang = trackInfo[i].language.ifBlank { "Track $audioCounter" }
                    audioTracks.add(Pair(i, lang))
                    audioCounter++
                }
            }

            if (audioTracks.size <= 1) {
                Toast.makeText(this, "Single audio stream available", Toast.LENGTH_SHORT).show()
                return
            }

            val names = audioTracks.map { it.second }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.audio_track))
                .setItems(names) { dialog, which ->
                    try {
                        mp.selectTrack(audioTracks[which].first)
                        btnVideoAudioTrack.text = "AUDIO: ${audioTracks[which].second}"
                    } catch (e: Exception) {
                        Log.w(PtpConstants.TAG, "Audio track switch failed", e)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("CANCEL", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Audio tracks query not supported", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handlePlayPauseAction() {
        if (currentScreen == Screen.VIDEO_PLAYER) {
            if (videoView.isPlaying) {
                videoView.pause()
                btnVideoPlayPause.text = "▶ PLAY"
                updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED, videoView.currentPosition.toLong())
            } else {
                videoView.start()
                btnVideoPlayPause.text = "⏸ PAUSE"
                updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING, videoView.currentPosition.toLong())
            }
        } else if (currentScreen == Screen.AUDIO_PLAYER) {
            audioPlayer?.let { mp ->
                try {
                    if (mp.isPlaying) {
                        mp.pause()
                        btnAudioPlayPause.text = "▶"
                        tvAudioPlayerStatus.text = "[ PAUSED ]"
                        updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED, mp.currentPosition.toLong())
                    } else {
                        mp.start()
                        btnAudioPlayPause.text = "⏸"
                        tvAudioPlayerStatus.text = "[ PLAYING ]"
                        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING, mp.currentPosition.toLong())
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun handleRewind10Action() {
        if (currentScreen == Screen.VIDEO_PLAYER) {
            val pos = (videoView.currentPosition - 10000).coerceAtLeast(0)
            videoView.seekTo(pos)
        } else if (currentScreen == Screen.AUDIO_PLAYER) {
            audioPlayer?.let { mp ->
                try {
                    val pos = (mp.currentPosition - 10000).coerceAtLeast(0)
                    mp.seekTo(pos)
                } catch (_: Exception) {}
            }
        }
    }

    private fun handleForward10Action() {
        if (currentScreen == Screen.VIDEO_PLAYER) {
            val pos = (videoView.currentPosition + 10000).coerceAtMost(videoView.duration)
            videoView.seekTo(pos)
        } else if (currentScreen == Screen.AUDIO_PLAYER) {
            audioPlayer?.let { mp ->
                try {
                    val pos = (mp.currentPosition + 10000).coerceAtMost(mp.duration)
                    mp.seekTo(pos)
                } catch (_: Exception) {}
            }
        }
    }

    private fun handlePreviousAction() {
        if (currentScreen == Screen.VIDEO_PLAYER) {
            if (currentVideoIndex > 0) {
                playVideoAtIndex(currentVideoIndex - 1)
            } else if (videoLoopMode == LoopMode.ALL && repository.videoItems.isNotEmpty()) {
                playVideoAtIndex(repository.videoItems.size - 1)
            }
        } else if (currentScreen == Screen.AUDIO_PLAYER) {
            if (currentAudioIndex > 0) {
                playAudioAtIndex(currentAudioIndex - 1)
            } else if (audioLoopMode == LoopMode.ALL && repository.audioItems.isNotEmpty()) {
                playAudioAtIndex(repository.audioItems.size - 1)
            }
        }
    }

    private fun handleNextAction() {
        if (currentScreen == Screen.VIDEO_PLAYER) {
            if (currentVideoIndex < repository.videoItems.size - 1) {
                playVideoAtIndex(currentVideoIndex + 1)
            } else if (videoLoopMode == LoopMode.ALL && repository.videoItems.isNotEmpty()) {
                playVideoAtIndex(0)
            }
        } else if (currentScreen == Screen.AUDIO_PLAYER) {
            if (currentAudioIndex < repository.audioItems.size - 1) {
                playAudioAtIndex(currentAudioIndex + 1)
            } else if (audioLoopMode == LoopMode.ALL && repository.audioItems.isNotEmpty()) {
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
            // Keep playing audio or audio-only in background
            val activeTitle = if (currentScreen == Screen.AUDIO_PLAYER && currentAudioIndex >= 0 && currentAudioIndex < repository.audioItems.size) {
                repository.audioItems[currentAudioIndex].displayName
            } else if (currentScreen == Screen.VIDEO_PLAYER && currentVideoIndex >= 0 && currentVideoIndex < repository.videoItems.size) {
                repository.videoItems[currentVideoIndex].displayName
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

    override fun onDestroy() {
        super.onDestroy()
        stopBackgroundService()
        mediaSession?.release()
        videoCacheManager.clearCache()
        audioCacheManager.clearCache()
        lifecycleScope.launch {
            repository.clear()
        }
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
                videoCachingJob?.cancel()
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

        overlayQueue.visibility = View.GONE
        overlayCopy.visibility = View.GONE

        if (screen == Screen.PHOTO_VIEWER) {
            screenPhotoViewer.requestFocus()
        } else if (screen == Screen.VIDEO_PLAYER) {
            btnVideoPlayPause.requestFocus()
        } else if (screen == Screen.AUDIO_PLAYER) {
            btnAudioPlayPause.requestFocus()
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

    fun playVideoAtIndex(index: Int) {
        if (index < 0 || index >= repository.videoItems.size) return
        currentVideoIndex = index
        val item = repository.videoItems[index]
        startVideoPlayback(item)
    }

    private fun startVideoPlayback(item: PtpMediaItem) {
        val client = repository.client ?: return
        showScreen(Screen.VIDEO_PLAYER)

        // Strict bugfix: Reset ALL error messages unconditionally before attempting playback
        tvVideoError.visibility = View.GONE
        tvVideoError.text = ""
        tvVideoTitle.text = item.displayName
        tvVideoTime.text = "00:00 / 00:00"
        sbVideoSeek.progress = 0
        btnVideoPlayPause.text = "⏸ PAUSE"

        tvBuffering.text = getString(R.string.buffering)
        layoutVideoBuffering.visibility = View.VISIBLE

        videoCachingJob?.cancel()
        videoProgressJob?.cancel()

        videoCachingJob = lifecycleScope.launch {
            val file = videoCacheManager.cacheVideo(
                client = client,
                handle = item.handle,
                filename = item.filename,
                onProgress = { written, _ ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (layoutVideoBuffering.visibility == View.VISIBLE) {
                            val mb = written / (1024.0 * 1024.0)
                            tvBuffering.text = String.format("Buffering %.1f MB...", mb)
                        }
                    }
                }
            )

            if (file != null && file.exists() && file.length() > 0) {
                try {
                    videoView.setVideoPath(file.absolutePath)
                    videoView.setOnPreparedListener { mp ->
                        currentVideoPlayer = mp
                        // Strict rule: If playback prepared, NEVER show unsupported codec error
                        tvVideoError.visibility = View.GONE
                        layoutVideoBuffering.visibility = View.GONE

                        mp.isLooping = (videoLoopMode == LoopMode.SINGLE)
                        videoView.start()
                        btnVideoPlayPause.text = "⏸ PAUSE"

                        val duration = videoView.duration
                        if (duration > 0) {
                            tvVideoTime.text = "00:00 / ${formatTime(duration)}"
                        }

                        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING, 0L)
                        startVideoProgressLoop()
                    }

                    videoView.setOnErrorListener { _, what, extra ->
                        Log.e(PtpConstants.TAG, "VideoView playback error: what=$what extra=$extra")
                        // Only show error if playback is truly stopped/failed
                        layoutVideoBuffering.visibility = View.GONE
                        tvVideoError.text = getString(R.string.video_codec_unsupported)
                        tvVideoError.visibility = View.VISIBLE
                        btnVideoPlayPause.text = "▶ PLAY"
                        updateMediaSessionState(PlaybackStateCompat.STATE_ERROR, 0L)
                        true
                    }

                    videoView.setOnCompletionListener {
                        updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED, videoView.duration.toLong())
                        when (videoLoopMode) {
                            LoopMode.OFF -> {
                                btnVideoPlayPause.text = "▶ PLAY"
                                if (currentVideoIndex < repository.videoItems.size - 1) {
                                    playVideoAtIndex(currentVideoIndex + 1)
                                }
                            }
                            LoopMode.SINGLE -> {
                                videoView.seekTo(0)
                                videoView.start()
                            }
                            LoopMode.ALL -> {
                                val nextIdx = (currentVideoIndex + 1) % repository.videoItems.size
                                playVideoAtIndex(nextIdx)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(PtpConstants.TAG, "Exception initializing VideoView", e)
                    layoutVideoBuffering.visibility = View.GONE
                    tvVideoError.text = getString(R.string.video_codec_unsupported)
                    tvVideoError.visibility = View.VISIBLE
                }
            } else {
                layoutVideoBuffering.visibility = View.GONE
                tvVideoError.text = getString(R.string.video_load_failed)
                tvVideoError.visibility = View.VISIBLE
            }
        }
    }

    private fun startVideoProgressLoop() {
        videoProgressJob?.cancel()
        videoProgressJob = lifecycleScope.launch {
            while (currentScreen == Screen.VIDEO_PLAYER) {
                try {
                    if (videoView.isPlaying && !isVideoTracking) {
                        // Strict assertion: while playing, error warning MUST be hidden
                        if (tvVideoError.visibility == View.VISIBLE) {
                            tvVideoError.visibility = View.GONE
                        }
                        val current = videoView.currentPosition
                        val total = videoView.duration
                        if (total > 0) {
                            val ratio = (current.toLong() * 1000L / total).toInt()
                            sbVideoSeek.progress = ratio
                            tvVideoTime.text = "${formatTime(current)} / ${formatTime(total)}"
                        }
                    }
                } catch (_: Exception) {}
                delay(500)
            }
        }
    }

    private fun stopAndClearVideoPlayer() {
        videoProgressJob?.cancel()
        videoCachingJob?.cancel()
        try {
            if (videoView.isPlaying) {
                videoView.stopPlayback()
            }
        } catch (_: Exception) {}
        currentVideoPlayer = null
        videoCacheManager.clearCache()
        updateMediaSessionState(PlaybackStateCompat.STATE_NONE, 0L)
    }

    fun playAudioAtIndex(index: Int) {
        if (index < 0 || index >= repository.audioItems.size) return
        currentAudioIndex = index
        val item = repository.audioItems[index]
        startAudioPlayback(item)
    }

    private fun startAudioPlayback(item: PtpMediaItem) {
        val client = repository.client ?: return
        showScreen(Screen.AUDIO_PLAYER)

        // Strict bugfix: clear any previous error text unconditionally
        tvAudioError.visibility = View.GONE
        tvAudioError.text = ""
        tvAudioPlayerStatus.text = "Buffering track..."
        tvAudioPlayerTime.text = "00:00 / 00:00"
        progressAudioSeek.progress = 0
        btnAudioPlayPause.text = "⏸"

        tvAudioBuffering.text = getString(R.string.buffering)
        layoutAudioBuffering.visibility = View.VISIBLE

        // Load thumbnail into art
        mediaThumbnailLoader.loadThumbnail(item, ivAudioPlayerArt, R.drawable.ic_audio_placeholder)

        stopAudioPlaybackOnly()

        audioCachingJob?.cancel()
        audioCachingJob = lifecycleScope.launch {
            val file = audioCacheManager.cacheAudio(client, item.handle, item.filename)
            if (file != null && file.exists() && file.length() > 0) {
                try {
                    val mp = MediaPlayer()
                    audioPlayer = mp
                    mp.setDataSource(file.absolutePath)
                    mp.setOnPreparedListener { player ->
                        // Strict rule: NEVER show unsupported error if playback starts successfully
                        tvAudioError.visibility = View.GONE
                        layoutAudioBuffering.visibility = View.GONE
                        player.isLooping = (audioLoopMode == LoopMode.SINGLE)
                        player.start()
                        btnAudioPlayPause.text = "⏸"
                        tvAudioPlayerStatus.text = "[ PLAYING ]"
                        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING, 0L)
                        startAudioProgressLoop(player)
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        Log.e(PtpConstants.TAG, "Audio playback error: what=$what extra=$extra")
                        layoutAudioBuffering.visibility = View.GONE
                        tvAudioError.text = getString(R.string.audio_codec_unsupported)
                        tvAudioError.visibility = View.VISIBLE
                        tvAudioPlayerStatus.text = "[ ERROR ]"
                        btnAudioPlayPause.text = "▶"
                        updateMediaSessionState(PlaybackStateCompat.STATE_ERROR, 0L)
                        true
                    }
                    mp.setOnCompletionListener {
                        tvAudioPlayerStatus.text = "[ FINISHED ]"
                        progressAudioSeek.progress = 1000
                        updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED, mp.duration.toLong())

                        when (audioLoopMode) {
                            LoopMode.OFF -> {
                                btnAudioPlayPause.text = "▶"
                                if (currentAudioIndex < repository.audioItems.size - 1) {
                                    playAudioAtIndex(currentAudioIndex + 1)
                                }
                            }
                            LoopMode.SINGLE -> {
                                mp.seekTo(0)
                                mp.start()
                            }
                            LoopMode.ALL -> {
                                val nextIdx = (currentAudioIndex + 1) % repository.audioItems.size
                                playAudioAtIndex(nextIdx)
                            }
                        }
                    }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    Log.e(PtpConstants.TAG, "Error initializing MediaPlayer for audio", e)
                    layoutAudioBuffering.visibility = View.GONE
                    tvAudioError.text = getString(R.string.audio_codec_unsupported)
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
            while (audioPlayer == player) {
                try {
                    if (player.isPlaying && !isAudioTracking) {
                        // Strict assertion: while playing, error warning MUST be hidden
                        if (tvAudioError.visibility == View.VISIBLE) {
                            tvAudioError.visibility = View.GONE
                        }
                        val current = player.currentPosition
                        val total = player.duration
                        if (total > 0) {
                            val ratio = (current.toLong() * 1000L / total).toInt()
                            progressAudioSeek.progress = ratio
                            tvAudioPlayerTime.text = "${formatTime(current)} / ${formatTime(total)}"
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
        audioProgressJob?.cancel()
        audioPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        audioPlayer = null
        updateMediaSessionState(PlaybackStateCompat.STATE_NONE, 0L)
    }

    private fun stopAndClearAudioPlayer() {
        audioCachingJob?.cancel()
        stopAudioPlaybackOnly()
        audioCacheManager.clearCache()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If Queue Overlay is open, handle Back to dismiss it
        if (overlayQueue.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                overlayQueue.visibility = View.GONE
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
                        if (currentPhotoIndex < repository.photoItems.size - 1) {
                            currentPhotoIndex++
                            loadSelectedPhoto(currentPhotoIndex)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        if (currentPhotoIndex in repository.photoItems.indices) {
                            contextMenuHelper.showContextMenu(repository.photoItems[currentPhotoIndex])
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
}

class PhotoAdapter(
    private val items: List<PtpMediaItem>,
    private val thumbnailLoader: PhotoThumbnailLoader,
    private val onItemClicked: (Int) -> Unit,
    private val onItemMenu: (PtpMediaItem) -> Unit
) : RecyclerView.Adapter<PhotoViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val item = items[position]
        holder.itemView.tag = item.handle
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
    private val onItemBound: (PtpMediaItem) -> Unit,
    private val onItemClicked: (PtpMediaItem) -> Unit,
    private val onItemMenu: (PtpMediaItem) -> Unit
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
    private val onItemBound: (PtpMediaItem) -> Unit,
    private val onItemClicked: (PtpMediaItem) -> Unit,
    private val onItemMenu: (PtpMediaItem) -> Unit
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

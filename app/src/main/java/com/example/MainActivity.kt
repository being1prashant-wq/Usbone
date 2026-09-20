package com.example

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.media.PtpLoopbackServer
import com.example.media.PtpMediaItem
import com.example.media.PtpMediaRepository
import com.example.usb.UsbConnectionState
import com.example.usb.UsbHostManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var repository: PtpMediaRepository
    private lateinit var usb: UsbHostManager
    private lateinit var bridge: PtpLoopbackServer
    private lateinit var browser: View
    private lateinit var playerScreen: View
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var photoView: ImageView
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var playerName: TextView
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private lateinit var playButton: Button

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentMedia: Media? = null
    private var progressJob: Job? = null
    private var currentMode = Mode.VIDEOS
    private var trackingSeek = false

    private enum class Mode { PHOTOS, VIDEOS, AUDIO }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vlc_ptp)

        browser = findViewById(R.id.browser)
        playerScreen = findViewById(R.id.player_screen)
        status = findViewById(R.id.tv_status)
        list = findViewById(R.id.media_list)
        photoView = findViewById(R.id.photo_view)
        videoLayout = findViewById(R.id.vlc_video)
        playerName = findViewById(R.id.player_name)
        seek = findViewById(R.id.player_seek)
        time = findViewById(R.id.player_time)
        playButton = findViewById(R.id.player_play)

        list.layoutManager = LinearLayoutManager(this)
        list.setHasFixedSize(true)

        findViewById<Button>(R.id.tab_photos).setOnClickListener { showMode(Mode.PHOTOS) }
        findViewById<Button>(R.id.tab_videos).setOnClickListener { showMode(Mode.VIDEOS) }
        findViewById<Button>(R.id.tab_audio).setOnClickListener { showMode(Mode.AUDIO) }
        findViewById<Button>(R.id.player_back).setOnClickListener { exitPlayer() }

        playButton.setOnClickListener {
            mediaPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || trackingSeek) return
                val p = mediaPlayer ?: return
                time.text = formatMs((p.length * progress / 1000f).toLong()) + " / " + formatMs(p.length)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { trackingSeek = true }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                mediaPlayer?.setPosition((bar?.progress ?: 0) / 1000f)
                trackingSeek = false
            }
        })

        repository = PtpMediaRepository(this)
        bridge = PtpLoopbackServer(lifecycleScope, repository)
        bridge.start()

        usb = UsbHostManager(this) { state ->
            runOnUiThread { handleUsbState(state) }
        }
        usb.start()

        initVlc()
        showMode(currentMode)
    }

    private fun initVlc() {
        val options = arrayListOf(
            "--network-caching=1000",
            "--file-caching=1000",
            "--avcodec-hw=any"
        )
        libVlc = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVlc)

        mediaPlayer?.setEventListener { event ->
            runOnUiThread {
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        playButton.text = "Pause"
                        status.text = "VLC • PTP playback"
                    }
                    MediaPlayer.Event.Paused -> playButton.text = "Play"
                    MediaPlayer.Event.EndReached -> {
                        playButton.text = "Play"
                        seek.progress = 1000
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        playButton.text = "Play"
                        status.text = "VLC could not decode this PTP media"
                    }
                    MediaPlayer.Event.Buffering ->
                        status.text = "PTP → VLC buffering " + event.getBuffering().toInt() + "%"
                }
            }
        }
    }

    private fun handleUsbState(state: UsbConnectionState) {
        when (state) {
            is UsbConnectionState.DeviceAttached ->
                status.text = "USB phone detected • select PTP / Transfer photos"
            is UsbConnectionState.PermissionRequired ->
                status.text = "USB permission required"
            is UsbConnectionState.PermissionDenied ->
                status.text = "USB permission denied"
            is UsbConnectionState.Connected -> initializePtp(state)
            is UsbConnectionState.Disconnected -> {
                lifecycleScope.launch { repository.clear() }
                status.text = "Phone disconnected"
                showMode(currentMode)
            }
            is UsbConnectionState.Error -> status.text = state.message
            UsbConnectionState.Idle -> status.text = "Connect phone in PTP mode"
        }
    }

    private fun initializePtp(state: UsbConnectionState.Connected) {
        lifecycleScope.launch {
            status.text = "PTP initializing…"
            val ok = repository.initialize(state.client, state.deviceName)
            if (ok) {
                status.text = repository.deviceName + " • " +
                    repository.videoItems.size + " videos • " +
                    repository.audioItems.size + " audio • " +
                    repository.photoItems.size + " photos"
                showMode(currentMode)
            } else {
                status.text = "PTP initialization failed"
            }
        }
    }

    private fun showMode(mode: Mode) {
        currentMode = mode
        val items = when (mode) {
            Mode.PHOTOS -> repository.photoItems
            Mode.VIDEOS -> repository.videoItems
            Mode.AUDIO -> repository.audioItems
        }
        list.adapter = PtpAdapter(items) { item ->
            when (mode) {
                Mode.PHOTOS -> showPhoto(item)
                Mode.VIDEOS, Mode.AUDIO -> playPtp(item)
            }
        }
    }

    private fun showPhoto(item: PtpMediaItem) {
        playerScreen.visibility = View.VISIBLE
        browser.visibility = View.GONE
        photoView.visibility = View.VISIBLE
        videoLayout.visibility = View.GONE
        playerName.text = item.displayName

        lifecycleScope.launch {
            status.text = "Loading photo from PTP…"
            val bitmap = repository.loadFullPhoto(item.handle, 1920)
            if (bitmap != null) {
                photoView.setImageBitmap(bitmap)
                status.text = "PTP photo"
            } else {
                status.text = "Could not load photo"
            }
        }
    }

    private fun playPtp(item: PtpMediaItem) {
        val url = bridge.urlFor(item)
        playerScreen.visibility = View.VISIBLE
        browser.visibility = View.GONE
        photoView.visibility = View.GONE
        videoLayout.visibility = View.VISIBLE
        playerName.text = item.displayName
        status.text = "Opening PTP media in VLC…"

        try {
            mediaPlayer?.stop()
            currentMedia?.release()
            currentMedia = Media(libVlc, android.net.Uri.parse(url)).apply {
                addOption(":http-reconnect=true")
                addOption(":network-caching=1000")
            }
            mediaPlayer?.attachViews(videoLayout, null, true, false)
            mediaPlayer?.setMedia(currentMedia)
            mediaPlayer?.play()
            startProgressLoop()
        } catch (e: Exception) {
            status.text = "VLC start failed: " + e.javaClass.simpleName
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (playerScreen.visibility == View.VISIBLE) {
                val p = mediaPlayer
                if (p != null && p.length > 0 && !trackingSeek) {
                    seek.progress = (p.position * 1000f).toInt().coerceIn(0, 1000)
                    time.text = formatMs(p.time) + " / " + formatMs(p.length)
                }
                delay(500)
            }
        }
    }

    private fun exitPlayer() {
        progressJob?.cancel()
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        currentMedia?.release()
        currentMedia = null
        playerScreen.visibility = View.GONE
        browser.visibility = View.VISIBLE
        photoView.setImageBitmap(null)
        showMode(currentMode)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (playerScreen.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { exitPlayer(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    mediaPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    mediaPlayer?.let { it.setTime((it.time - 10_000L).coerceAtLeast(0L)) }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    mediaPlayer?.let { it.setTime((it.time + 10_000L).coerceAtMost(it.length)) }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        progressJob?.cancel()
        bridge.stop()
        usb.stop()
        try { mediaPlayer?.detachViews() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        try { libVlc?.release() } catch (_: Exception) {}
        currentMedia?.release()
        mediaPlayer = null
        libVlc = null
        super.onDestroy()
    }

    private fun formatMs(ms: Long): String {
        val seconds = (ms / 1000L).coerceAtLeast(0L)
        val h = seconds / 3600L
        val m = (seconds % 3600L) / 60L
        val s = seconds % 60L
        return if (h > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private class PtpAdapter(
        private val items: List<PtpMediaItem>,
        private val onClick: (PtpMediaItem) -> Unit
    ) : RecyclerView.Adapter<PtpViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PtpViewHolder {
            val row = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, 64)
                setPadding(24, 8, 24, 8)
                setTextColor(android.graphics.Color.WHITE)
                textSize = 17f
                isFocusable = true
                isFocusableInTouchMode = true
            }
            return PtpViewHolder(row)
        }

        override fun onBindViewHolder(holder: PtpViewHolder, position: Int) {
            val item = items[position]
            holder.text.text = item.displayName +
                if (item.formattedSize.isBlank()) "" else "   •   " + item.formattedSize
            holder.text.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private class PtpViewHolder(val text: TextView) : RecyclerView.ViewHolder(text)
}

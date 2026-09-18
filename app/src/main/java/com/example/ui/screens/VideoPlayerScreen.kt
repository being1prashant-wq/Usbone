package com.example.ui.screens

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.example.media.MtpDataSource
import com.example.ui.DirectUsbViewModel
import com.example.ui.components.FormatUtils
import com.example.ui.components.TvFocusableButton
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    viewModel: DirectUsbViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeItem by viewModel.activeMediaItem.collectAsState()
    val isBufferingToCache by viewModel.isBufferingToCache.collectAsState()
    val cacheProgress by viewModel.cacheProgress.collectAsState()
    val client = viewModel.usbHostManager.activeMtpClient

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlayerBuffering by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build()
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isPlayerBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Auto-hide controls overlay after 5 seconds of inactivity
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(5000)
            showControls = false
        }
    }

    // Progress updater
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosMs = exoPlayer.currentPosition
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    // Load video stream from MTP
    LaunchedEffect(activeItem) {
        val item = activeItem ?: return@LaunchedEffect
        if (client != null) {
            val uri = Uri.parse("directusb://object/${item.objectHandle}?length=${item.size}")
            val dataSourceFactory = MtpDataSource.Factory(client)
            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(uri))

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .focusable()
            .onKeyEvent { event ->
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                        if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                        showControls = true
                        true
                    }
                    Key.DirectionLeft -> {
                        exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                        showControls = true
                        true
                    }
                    Key.DirectionRight -> {
                        exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(durationMs))
                        showControls = true
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        showControls = !showControls
                        true
                    }
                    Key.Back -> {
                        viewModel.handleBack()
                        true
                    }
                    else -> false
                }
            }
            .testTag("video_player_screen")
    ) {
        // Player Surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Buffering Indicator in center
        if (isPlayerBuffering || isBufferingToCache) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = CyanAccent, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    val msg = if (isBufferingToCache) {
                        "Buffering to TV Storage: ${(cacheProgress * 100).toInt()}%"
                    } else {
                        "Buffering video from USB..."
                    }
                    Text(text = msg, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Overlay Controls
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(32.dp)
            ) {
                // Top Overlay: Title & Back Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvFocusableButton(
                        text = "Back",
                        onClick = { viewModel.handleBack() },
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = CyanAccent,
                                modifier = Modifier.size(18.dp).padding(end = 6.dp)
                            )
                        },
                        testTag = "btn_video_back"
                    )

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = activeItem?.filename ?: "Video",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${FormatUtils.formatBytes(activeItem?.size ?: 0L)} • Direct MTP USB Stream",
                            color = CyanAccent,
                            fontSize = 12.sp
                        )
                    }
                }

                // Bottom Overlay: Progress Bar & Controls
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    val progress = if (durationMs > 0) (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = CyanAccent,
                        trackColor = DarkBorder
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = FormatUtils.formatDuration(currentPosMs), color = TextSecondary, fontSize = 13.sp)
                        Text(text = FormatUtils.formatDuration(durationMs), color = TextSecondary, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvFocusableButton(
                            text = "-10s",
                            onClick = { exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0)) },
                            modifier = Modifier.padding(horizontal = 8.dp),
                            testTag = "btn_video_rewind"
                        )

                        TvFocusableButton(
                            text = if (isPlaying) "Pause" else "Play",
                            onClick = {
                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                            isPrimary = true,
                            modifier = Modifier.padding(horizontal = 8.dp),
                            testTag = "btn_video_play_pause"
                        )

                        TvFocusableButton(
                            text = "+10s",
                            onClick = { exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(durationMs)) },
                            modifier = Modifier.padding(horizontal = 8.dp),
                            testTag = "btn_video_forward"
                        )

                        // Cache to TV option if streaming is slow
                        activeItem?.let { item ->
                            TvFocusableButton(
                                text = "Buffer to TV",
                                onClick = {
                                    viewModel.bufferCurrentMediaToCache(item) { cachedFile ->
                                        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(cachedFile)))
                                        exoPlayer.prepare()
                                        exoPlayer.play()
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 8.dp),
                                testTag = "btn_video_cache"
                            )
                        }
                    }
                }
            }
        }
    }
}

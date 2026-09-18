package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun ImageViewerScreen(
    viewModel: DirectUsbViewModel,
    modifier: Modifier = Modifier
) {
    val activeItem by viewModel.activeMediaItem.collectAsState()
    val queue by viewModel.mediaQueue.collectAsState()
    val queueIndex by viewModel.queueIndex.collectAsState()
    val client = viewModel.usbHostManager.activeMtpClient

    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showOverlay by remember { mutableStateOf(true) }

    // Auto-hide info overlay after 4 seconds
    LaunchedEffect(showOverlay, activeItem) {
        if (showOverlay) {
            delay(4000)
            showOverlay = false
        }
    }

    // Load Image bytes from phone over MTP
    LaunchedEffect(activeItem) {
        val item = activeItem ?: return@LaunchedEffect
        isLoading = true
        bitmap = null

        withContext(Dispatchers.IO) {
            if (client != null) {
                // Read full image object or thumbnail
                val ext = item.filename.substringAfterLast('.', "jpg")
                val cachedFile = viewModel.repository.cacheManager.getCacheFileForObject(item.objectHandle, ext)
                if (cachedFile.exists() && cachedFile.length() > 0) {
                    val bmp = BitmapFactory.decodeFile(cachedFile.absolutePath)
                    bitmap = bmp
                } else {
                    val file = viewModel.repository.cacheManager.cacheMediaLocally(client, item.objectHandle, ext) { _, _ -> }
                    if (file != null) {
                        val bmp = BitmapFactory.decodeFile(file.absolutePath)
                        bitmap = bmp
                    } else {
                        // Fallback: fetch thumbnail
                        val thumbBytes = client.getThumb(item.objectHandle)
                        if (thumbBytes != null) {
                            val bmp = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                            bitmap = bmp
                        }
                    }
                }
            }
            isLoading = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .focusable()
            .onKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft -> {
                        viewModel.playPrevious()
                        showOverlay = true
                        true
                    }
                    Key.DirectionRight -> {
                        viewModel.playNext()
                        showOverlay = true
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Enter -> {
                        showOverlay = !showOverlay
                        true
                    }
                    Key.Back -> {
                        viewModel.handleBack()
                        true
                    }
                    else -> false
                }
            }
            .testTag("image_viewer_screen")
    ) {
        // Fullscreen Image Display
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = activeItem?.filename,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = CyanAccent, modifier = Modifier.size(44.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Loading photo from phone...", color = TextPrimary, fontSize = 14.sp)
                }
            }
        }

        // Overlay Header
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 32.dp, vertical = 20.dp),
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
                    testTag = "btn_image_back"
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = activeItem?.filename ?: "Photo",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${FormatUtils.formatBytes(activeItem?.size ?: 0L)} • ${queueIndex + 1} of ${queue.size}",
                        color = CyanAccent,
                        fontSize = 12.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvFocusableButton(
                        text = "Prev",
                        onClick = { viewModel.playPrevious() },
                        testTag = "btn_image_prev"
                    )
                    TvFocusableButton(
                        text = "Next",
                        onClick = { viewModel.playNext() },
                        testTag = "btn_image_next"
                    )
                }
            }
        }
    }
}

package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.MtpObjectEntity
import com.example.data.RecentMediaEntity
import com.example.ui.DirectUsbViewModel
import com.example.ui.components.FormatUtils
import com.example.ui.components.TvFocusableButton
import com.example.ui.components.TvFocusableCard
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun RecentMediaScreen(
    viewModel: DirectUsbViewModel,
    modifier: Modifier = Modifier
) {
    val recentItems by viewModel.recentMedia.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .padding(horizontal = 40.dp, vertical = 20.dp)
            .testTag("recent_media_screen")
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                testTag = "btn_recent_back"
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "RECENTLY PLAYED MEDIA",
                    color = CyanAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                if (recentItems.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(16.dp))
                    TvFocusableButton(
                        text = "Clear History",
                        onClick = { viewModel.clearRecentHistory() },
                        testTag = "btn_clear_history"
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        if (recentItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "No Recently Played Media", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "Media you play or view from your phone will appear here for fast resuming.", color = TextMuted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(recentItems, key = { it.objectHandle }) { item ->
                    RecentMediaCard(
                        item = item,
                        onClick = {
                            val entity = MtpObjectEntity(
                                objectHandle = item.objectHandle,
                                storageId = item.storageId,
                                parentHandle = 0,
                                filename = item.filename,
                                size = item.size,
                                mimeType = "",
                                formatCode = 0,
                                isFolder = false,
                                modificationDate = item.lastPlayedTime,
                                mediaCategory = item.mediaCategory,
                                durationMs = item.durationMs
                            )
                            viewModel.selectMedia(entity)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentMediaCard(
    item: RecentMediaEntity,
    onClick: () -> Unit
) {
    TvFocusableCard(
        onClick = onClick,
        testTag = "recent_item_${item.objectHandle}",
        modifier = Modifier.fillMaxWidth().height(62.dp)
    ) { isFocused ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(DarkSurfaceElevated, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    val icon: ImageVector = when (item.mediaCategory) {
                        "AUDIO" -> Icons.Default.PlayArrow
                        "VIDEO" -> Icons.Default.PlayArrow
                        "IMAGE" -> Icons.Default.Face
                        else -> Icons.Default.PlayArrow
                    }
                    Icon(imageVector = icon, contentDescription = null, tint = if (isFocused) CyanAccent else TextSecondary, modifier = Modifier.size(18.dp))
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = item.filename,
                        color = if (isFocused) CyanAccent else TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                    Text(
                        text = "${item.mediaCategory} • Last played ${FormatUtils.formatDate(item.lastPlayedTime)}",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }

            Text(
                text = "Resume",
                color = if (isFocused) CyanAccent else TextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

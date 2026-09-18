package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.ui.DirectUsbViewModel
import com.example.ui.SortBy
import com.example.ui.components.FormatUtils
import com.example.ui.components.TvFocusableButton
import com.example.ui.components.TvFocusableCard
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun FileBrowserScreen(
    viewModel: DirectUsbViewModel,
    modifier: Modifier = Modifier
) {
    val storages by viewModel.storages.collectAsState()
    val currentStorageId by viewModel.currentStorageId.collectAsState()
    val breadcrumbs by viewModel.folderBreadcrumbs.collectAsState()
    val folderObjects by viewModel.folderObjects.collectAsState()
    val sortBy by viewModel.sortBy.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val infoItem by viewModel.infoDialogItem.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .padding(horizontal = 40.dp, vertical = 20.dp)
            .testTag("file_browser_screen")
    ) {
        // Navigation & Storage Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    testTag = "btn_browser_back"
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Storage Selectors
                storages.forEach { s ->
                    val isSelected = s.storageId == currentStorageId
                    TvFocusableButton(
                        text = s.displayTitle,
                        onClick = { viewModel.selectStorage(s.storageId, s.displayTitle) },
                        isPrimary = isSelected,
                        modifier = Modifier.padding(end = 8.dp),
                        testTag = "btn_storage_${s.storageId}"
                    )
                }
            }

            // Sort Selector
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sort: ", color = TextMuted, fontSize = 13.sp)
                TvFocusableButton(
                    text = "${sortBy.name} (${if (sortOrder.name == "ASC") "↑" else "↓"})",
                    onClick = {
                        val nextSort = when (sortBy) {
                            SortBy.NAME -> SortBy.DATE
                            SortBy.DATE -> SortBy.SIZE
                            SortBy.SIZE -> SortBy.TYPE
                            SortBy.TYPE -> SortBy.NAME
                        }
                        viewModel.setSort(nextSort)
                    },
                    testTag = "btn_sort"
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Breadcrumbs Path
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurfaceElevated, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            breadcrumbs.forEachIndexed { index, crumb ->
                Text(
                    text = crumb.second,
                    color = if (index == breadcrumbs.size - 1) CyanAccent else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (index == breadcrumbs.size - 1) FontWeight.Bold else FontWeight.Normal
                )
                if (index < breadcrumbs.size - 1) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp).padding(horizontal = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Files List
        if (folderObjects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "This folder is empty",
                        color = TextMuted,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "No compatible media or files found in this directory.",
                        color = TextMuted.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(folderObjects, key = { it.objectHandle }) { item ->
                    FileListItem(
                        item = item,
                        onClick = {
                            if (item.isFolder) {
                                viewModel.openFolder(item)
                            } else {
                                viewModel.selectMedia(item, folderObjects)
                            }
                        },
                        onInfoClick = { viewModel.showFileInfo(item) }
                    )
                }
            }
        }
    }

    // File Info Dialog
    if (infoItem != null) {
        val file = infoItem!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissFileInfo() },
            containerColor = DarkSurface,
            title = {
                Text(
                    text = "File Details",
                    color = CyanAccent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailRow("Name:", file.filename)
                    DetailRow("Type:", "${file.mediaCategory} (${file.mimeType})")
                    DetailRow("Size:", FormatUtils.formatBytes(file.size))
                    DetailRow("Modified:", FormatUtils.formatDate(file.modificationDate))
                    DetailRow("MTP Handle:", "0x${Integer.toHexString(file.objectHandle).uppercase()}")
                    DetailRow("Storage ID:", "0x${Integer.toHexString(file.storageId).uppercase()}")
                    DetailRow("Format Code:", "0x${Integer.toHexString(file.formatCode).uppercase()}")
                }
            },
            confirmButton = {
                TvFocusableButton(
                    text = "Close",
                    onClick = { viewModel.dismissFileInfo() },
                    isPrimary = true,
                    testTag = "btn_close_info"
                )
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, color = TextMuted, fontSize = 13.sp, modifier = Modifier.width(110.dp))
        Text(text = value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FileListItem(
    item: MtpObjectEntity,
    onClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    TvFocusableCard(
        onClick = onClick,
        testTag = "file_item_${item.objectHandle}",
        modifier = Modifier.fillMaxWidth().height(64.dp)
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
                // Category Icon
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (item.isFolder) CyanAccent.copy(alpha = 0.2f) else DarkSurfaceElevated,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val icon: ImageVector = when {
                        item.isFolder -> Icons.Default.PlayArrow
                        item.mediaCategory == "AUDIO" -> Icons.Default.PlayArrow
                        item.mediaCategory == "VIDEO" -> Icons.Default.PlayArrow
                        item.mediaCategory == "IMAGE" -> Icons.Default.Face
                        else -> Icons.Default.Info
                    }
                    val iconTint = if (item.isFolder) CyanAccent else if (isFocused) CyanAccent else TextSecondary
                    Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = item.filename,
                        color = if (isFocused) CyanAccent else TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = if (isFocused || item.isFolder) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val sub = if (item.isFolder) {
                        "Folder"
                    } else {
                        "${FormatUtils.formatBytes(item.size)} • ${FormatUtils.formatDate(item.modificationDate)}"
                    }
                    Text(text = sub, color = TextMuted, fontSize = 12.sp)
                }
            }

            // Category tag or action
            Text(
                text = if (item.isFolder) "Open" else item.mediaCategory,
                color = if (isFocused) CyanAccent else TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

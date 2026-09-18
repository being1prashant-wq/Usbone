package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.DirectUsbViewModel
import com.example.ui.NavigationScreen
import com.example.ui.components.FormatUtils
import com.example.ui.components.TvFocusableButton
import com.example.ui.components.TvFocusableCard
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import com.example.usb.UsbConnectionState

private data class HomeMenuItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val screen: NavigationScreen,
    val tag: String
)

@Composable
fun HomeScreen(
    viewModel: DirectUsbViewModel,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val indexingProgress by viewModel.indexingProgress.collectAsState()
    val storages by viewModel.storages.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()

    val menuItems = listOf(
        HomeMenuItem("Browse All Files", "Explore folders & phone storage", Icons.Default.PlayArrow, NavigationScreen.FILE_BROWSER, "menu_browse"),
        HomeMenuItem("Music Library", "${indexingProgress.audioCount} songs found", Icons.Default.PlayArrow, NavigationScreen.MUSIC_PLAYER, "menu_music"),
        HomeMenuItem("Video Library", "${indexingProgress.videoCount} videos found", Icons.Default.PlayArrow, NavigationScreen.VIDEO_PLAYER, "menu_videos"),
        HomeMenuItem("Photos & Images", "${indexingProgress.imageCount} photos found", Icons.Default.Face, NavigationScreen.IMAGE_VIEWER, "menu_images"),
        HomeMenuItem("Recent Media", "Quickly resume playback", Icons.Default.Refresh, NavigationScreen.RECENT_MEDIA, "menu_recent"),
        HomeMenuItem("Search Phone", "Find files by name", Icons.Default.Search, NavigationScreen.SEARCH, "menu_search"),
        HomeMenuItem("USB Diagnostics", "Bus speed & endpoint monitor", Icons.Default.Build, NavigationScreen.USB_DIAGNOSTICS, "menu_diagnostics"),
        HomeMenuItem("Settings", "Storage rescan & cache options", Icons.Default.Settings, NavigationScreen.SETTINGS, "menu_settings")
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .padding(horizontal = 40.dp, vertical = 24.dp)
            .testTag("home_screen")
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(CyanAccent.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                        .border(1.5.dp, CyanAccent, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("USB", color = CyanAccent, fontWeight = FontWeight.Black, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "DIRECTUSB",
                        color = TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "Native Android TV MTP Host Controller",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }

            // Connection Status Pill
            ConnectionStatusPill(
                state = connectionState,
                onRequestPermission = { viewModel.requestUsbPermission() },
                onConnectManual = { viewModel.connectManually() }
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Indexing Progress Bar
        AnimatedVisibility(visible = indexingProgress.isScanning) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurfaceElevated, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = indexingProgress.statusMessage,
                        color = CyanAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${indexingProgress.totalFound} items",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = CyanAccent,
                    trackColor = DarkBorder
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Hero Info Area
        when (val state = connectionState) {
            is UsbConnectionState.Ready -> {
                ConnectedPhoneHeroCard(
                    displayName = state.displayName,
                    storages = storages,
                    totalFiles = indexingProgress.totalFound,
                    audioCount = indexingProgress.audioCount,
                    videoCount = indexingProgress.videoCount,
                    imageCount = indexingProgress.imageCount
                )
            }
            is UsbConnectionState.PermissionRequired -> {
                PermissionRequiredCard(
                    displayName = state.displayName,
                    onRequestPermission = { viewModel.requestUsbPermission() }
                )
            }
            is UsbConnectionState.OpeningDevice,
            is UsbConnectionState.IdentifyingProtocol,
            is UsbConnectionState.MtpInitializing -> {
                ConnectingCard()
            }
            is UsbConnectionState.Error -> {
                ErrorCard(
                    message = state.message,
                    onRetry = { viewModel.connectManually() }
                )
            }
            else -> {
                DisconnectedGuideCard()
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Menu Grid
        Text(
            text = "STORAGE & MEDIA",
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(10.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(menuItems) { item ->
                TvFocusableCard(
                    onClick = { viewModel.navigateTo(item.screen) },
                    testTag = item.tag,
                    modifier = Modifier.height(100.dp)
                ) { isFocused ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = item.title,
                                color = if (isFocused) CyanAccent else TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = if (isFocused) CyanAccent else TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = item.subtitle,
                            color = if (isFocused) TextPrimary else TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusPill(
    state: UsbConnectionState,
    onRequestPermission: () -> Unit,
    onConnectManual: () -> Unit
) {
    val (statusText, color) = when (state) {
        is UsbConnectionState.Ready -> Pair("Connected: ${state.displayName}", SuccessGreen)
        is UsbConnectionState.PermissionRequired -> Pair("Permission Required", WarningAmber)
        is UsbConnectionState.OpeningDevice,
        is UsbConnectionState.IdentifyingProtocol,
        is UsbConnectionState.MtpInitializing -> Pair("Negotiating MTP...", CyanAccent)
        is UsbConnectionState.Error -> Pair("Connection Error", ErrorRed)
        else -> Pair("No USB Phone Connected", TextMuted)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(DarkSurfaceElevated, RoundedCornerShape(20.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = statusText, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ConnectedPhoneHeroCard(
    displayName: String,
    storages: List<com.example.mtp.MtpStorageInfo>,
    totalFiles: Int,
    audioCount: Int,
    videoCount: Int,
    imageCount: Int
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurfaceElevated, RoundedCornerShape(14.dp))
            .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                val storageSummary = if (storages.isNotEmpty()) {
                    storages.joinToString(" • ") { s ->
                        "${s.displayTitle}: ${FormatUtils.formatBytes(s.freeSpaceInBytes)} free of ${FormatUtils.formatBytes(s.maxCapacity)}"
                    }
                } else {
                    "MTP Session Established"
                }
                Text(text = storageSummary, color = CyanAccent, fontSize = 13.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                StatBadge(label = "MUSIC", value = audioCount.toString())
                StatBadge(label = "VIDEOS", value = videoCount.toString())
                StatBadge(label = "PHOTOS", value = imageCount.toString())
                StatBadge(label = "TOTAL", value = totalFiles.toString())
            }
        }
    }
}

@Composable
private fun StatBadge(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DisconnectedGuideCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface, RoundedCornerShape(14.dp))
            .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "HOW TO CONNECT YOUR PHONE OVER USB",
                    color = CyanAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StepItem(step = "1", title = "Plug USB Data Cable", subtitle = "Connect phone to TV's USB port")
                StepItem(step = "2", title = "Unlock Phone", subtitle = "Dismiss the phone lockscreen")
                StepItem(step = "3", title = "Select File Transfer", subtitle = "Pull down notification & tap USB")
                StepItem(step = "4", title = "Instant Access", subtitle = "Phone storage opens here directly")
            }
        }
    }
}

@Composable
private fun StepItem(step: String, title: String, subtitle: String) {
    Row(modifier = Modifier.width(230.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(CyanAccent.copy(alpha = 0.2f), CircleShape)
                .border(1.dp, CyanAccent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = step, color = CyanAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(text = title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PermissionRequiredCard(
    displayName: String,
    onRequestPermission: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurfaceElevated, RoundedCornerShape(14.dp))
            .border(1.dp, WarningAmber, RoundedCornerShape(14.dp))
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(text = "USB Permission Required", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(text = "Allow DirectUSB to communicate with $displayName", color = TextSecondary, fontSize = 13.sp)
                }
            }
            TvFocusableButton(
                text = "Grant USB Permission",
                onClick = onRequestPermission,
                isPrimary = true,
                testTag = "btn_grant_usb"
            )
        }
    }
}

@Composable
private fun ConnectingCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurfaceElevated, RoundedCornerShape(14.dp))
            .border(1.dp, CyanAccent.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Connecting to phone...", color = CyanAccent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurfaceElevated, RoundedCornerShape(14.dp))
            .border(1.dp, ErrorRed, RoundedCornerShape(14.dp))
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Connection Warning", color = ErrorRed, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = message, color = TextSecondary, fontSize = 13.sp)
            }
            TvFocusableButton(
                text = "Retry",
                onClick = onRetry,
                isPrimary = true,
                testTag = "btn_retry_conn"
            )
        }
    }
}

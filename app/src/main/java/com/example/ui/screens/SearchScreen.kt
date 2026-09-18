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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.MtpObjectEntity
import com.example.ui.DirectUsbViewModel
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
fun SearchScreen(
    viewModel: DirectUsbViewModel,
    modifier: Modifier = Modifier
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    var selectedFilter by remember { mutableStateOf("ALL") }

    val filteredResults = if (selectedFilter == "ALL") {
        searchResults
    } else {
        searchResults.filter { it.mediaCategory == selectedFilter }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .padding(horizontal = 40.dp, vertical = 20.dp)
            .testTag("search_screen")
    ) {
        // Top Header
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
                testTag = "btn_search_back"
            )

            Text(
                text = "SEARCH PHONE STORAGE",
                color = CyanAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Search Input Field & Clear
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Type file name to search...", color = TextMuted) },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = CyanAccent)
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        TvFocusableButton(
                            text = "Clear",
                            onClick = { viewModel.setSearchQuery("") },
                            testTag = "btn_clear_search"
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanAccent,
                    unfocusedBorderColor = DarkBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = DarkSurfaceElevated,
                    unfocusedContainerColor = DarkSurfaceElevated
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).testTag("search_text_input")
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Filter Chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val filters = listOf("ALL", "AUDIO", "VIDEO", "IMAGE", "DOCUMENT")
            filters.forEach { f ->
                val isSelected = selectedFilter == f
                TvFocusableButton(
                    text = f,
                    onClick = { selectedFilter = f },
                    isPrimary = isSelected,
                    testTag = "btn_filter_$f"
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Search Results List
        if (searchQuery.isBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Enter a keyword above to find files on your phone.",
                    color = TextMuted,
                    fontSize = 15.sp
                )
            }
        } else if (filteredResults.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No files found matching \"$searchQuery\"",
                    color = TextMuted,
                    fontSize = 15.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(filteredResults, key = { it.objectHandle }) { item ->
                    SearchResultItem(
                        item = item,
                        onClick = { viewModel.selectMedia(item, filteredResults) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    item: MtpObjectEntity,
    onClick: () -> Unit
) {
    TvFocusableCard(
        onClick = onClick,
        testTag = "search_item_${item.objectHandle}",
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
                        else -> Icons.Default.Info
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
                        text = "${item.mediaCategory} • ${FormatUtils.formatBytes(item.size)}",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }

            Text(
                text = "Play",
                color = if (isFocused) CyanAccent else TextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

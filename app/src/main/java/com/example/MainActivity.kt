package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.ui.DirectUsbViewModel
import com.example.ui.NavigationScreen
import com.example.ui.screens.FileBrowserScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ImageViewerScreen
import com.example.ui.screens.MusicPlayerScreen
import com.example.ui.screens.RecentMediaScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.UsbDiagnosticsScreen
import com.example.ui.screens.VideoPlayerScreen
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DirectUsbViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle Android TV remote Back key
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val handled = viewModel.handleBack()
                if (!handled) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AmoledBlack)
                        .safeDrawingPadding(),
                    color = AmoledBlack
                ) {
                    DirectUsbApp(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun DirectUsbApp(viewModel: DirectUsbViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsState()

    Crossfade(targetState = currentScreen, label = "screen_transition") { screen ->
        when (screen) {
            NavigationScreen.HOME -> HomeScreen(viewModel = viewModel)
            NavigationScreen.FILE_BROWSER -> FileBrowserScreen(viewModel = viewModel)
            NavigationScreen.MUSIC_PLAYER -> MusicPlayerScreen(viewModel = viewModel)
            NavigationScreen.VIDEO_PLAYER -> VideoPlayerScreen(viewModel = viewModel)
            NavigationScreen.IMAGE_VIEWER -> ImageViewerScreen(viewModel = viewModel)
            NavigationScreen.RECENT_MEDIA -> RecentMediaScreen(viewModel = viewModel)
            NavigationScreen.SEARCH -> SearchScreen(viewModel = viewModel)
            NavigationScreen.USB_DIAGNOSTICS -> UsbDiagnosticsScreen(viewModel = viewModel)
            NavigationScreen.SETTINGS -> SettingsScreen(viewModel = viewModel)
        }
    }
}


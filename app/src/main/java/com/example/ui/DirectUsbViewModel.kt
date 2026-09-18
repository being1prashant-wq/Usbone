package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.DirectUsbRepository
import com.example.data.IndexingProgress
import com.example.data.MtpObjectEntity
import com.example.data.RecentMediaEntity
import com.example.mtp.MtpConstants
import com.example.mtp.MtpDeviceInfo
import com.example.mtp.MtpStorageInfo
import com.example.usb.UsbConnectionState
import com.example.usb.UsbDeviceDiagnostics
import com.example.usb.UsbHostManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class NavigationScreen {
    HOME,
    FILE_BROWSER,
    MUSIC_PLAYER,
    VIDEO_PLAYER,
    IMAGE_VIEWER,
    RECENT_MEDIA,
    SEARCH,
    SETTINGS,
    USB_DIAGNOSTICS
}

enum class SortBy {
    NAME,
    DATE,
    SIZE,
    TYPE
}

enum class SortOrder {
    ASC,
    DESC
}

class DirectUsbViewModel(application: Application) : AndroidViewModel(application) {
    private val tag = "DirectUsbViewModel"

    private val db = AppDatabase.getInstance(application)
    val usbHostManager = UsbHostManager(application)
    val repository = DirectUsbRepository(application, usbHostManager, db)

    val connectionState: StateFlow<UsbConnectionState> = usbHostManager.connectionState
    val diagnostics: StateFlow<UsbDeviceDiagnostics?> = usbHostManager.diagnostics
    val indexingProgress: StateFlow<IndexingProgress> = repository.indexingProgress
    val storages: StateFlow<List<MtpStorageInfo>> = repository.storages
    val deviceInfo: StateFlow<MtpDeviceInfo?> = repository.deviceInfo

    private val _currentScreen = MutableStateFlow(NavigationScreen.HOME)
    val currentScreen: StateFlow<NavigationScreen> = _currentScreen.asStateFlow()

    // Navigation history stack for TV Back button
    private val screenBackStack = mutableListOf<NavigationScreen>()

    // File Browser State
    private val _currentStorageId = MutableStateFlow(0)
    val currentStorageId: StateFlow<Int> = _currentStorageId.asStateFlow()

    private val _currentFolderHandle = MutableStateFlow(0)
    val currentFolderHandle: StateFlow<Int> = _currentFolderHandle.asStateFlow()

    private val _folderBreadcrumbs = MutableStateFlow<List<Pair<Int, String>>>(listOf(Pair(0, "Phone Storage")))
    val folderBreadcrumbs: StateFlow<List<Pair<Int, String>>> = _folderBreadcrumbs.asStateFlow()

    private val _sortBy = MutableStateFlow(SortBy.NAME)
    val sortBy: StateFlow<SortBy> = _sortBy.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.ASC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val rawFolderObjects = MutableStateFlow<List<MtpObjectEntity>>(emptyList())
    val folderObjects: StateFlow<List<MtpObjectEntity>> = combine(
        rawFolderObjects,
        _sortBy,
        _sortOrder
    ) { list, sort, order ->
        val sorted = when (sort) {
            SortBy.NAME -> if (order == SortOrder.ASC) list.sortedBy { it.filename.lowercase() } else list.sortedByDescending { it.filename.lowercase() }
            SortBy.DATE -> if (order == SortOrder.ASC) list.sortedBy { it.modificationDate } else list.sortedByDescending { it.modificationDate }
            SortBy.SIZE -> if (order == SortOrder.ASC) list.sortedBy { it.size } else list.sortedByDescending { it.size }
            SortBy.TYPE -> if (order == SortOrder.ASC) list.sortedBy { it.mediaCategory } else list.sortedByDescending { it.mediaCategory }
        }
        // Always keep folders first
        sorted.sortedByDescending { it.isFolder }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Category lists
    private val _selectedCategory = MutableStateFlow("ALL")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    val categoryObjects: StateFlow<List<MtpObjectEntity>> = MutableStateFlow(emptyList())

    // Search State
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val searchResults: StateFlow<List<MtpObjectEntity>> = MutableStateFlow(emptyList())

    val recentMedia: StateFlow<List<RecentMediaEntity>> = repository.getRecentMedia()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active Media Playback State
    private val _activeMediaItem = MutableStateFlow<MtpObjectEntity?>(null)
    val activeMediaItem: StateFlow<MtpObjectEntity?> = _activeMediaItem.asStateFlow()

    private val _mediaQueue = MutableStateFlow<List<MtpObjectEntity>>(emptyList())
    val mediaQueue: StateFlow<List<MtpObjectEntity>> = _mediaQueue.asStateFlow()

    private val _queueIndex = MutableStateFlow(0)
    val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    // Temporary local cache file for the active media item (if needed for stutter-free playback)
    private val _cachedMediaFile = MutableStateFlow<File?>(null)
    val cachedMediaFile: StateFlow<File?> = _cachedMediaFile.asStateFlow()

    private val _isBufferingToCache = MutableStateFlow(false)
    val isBufferingToCache: StateFlow<Boolean> = _isBufferingToCache.asStateFlow()

    private val _cacheProgress = MutableStateFlow(0f)
    val cacheProgress: StateFlow<Float> = _cacheProgress.asStateFlow()

    // Selected file for INFO dialog
    private val _infoDialogItem = MutableStateFlow<MtpObjectEntity?>(null)
    val infoDialogItem: StateFlow<MtpObjectEntity?> = _infoDialogItem.asStateFlow()

    // Speed test result
    private val _speedTestMbPerSec = MutableStateFlow<Float?>(null)
    val speedTestMbPerSec: StateFlow<Float?> = _speedTestMbPerSec.asStateFlow()

    private val _isTestingSpeed = MutableStateFlow(false)
    val isTestingSpeed: StateFlow<Boolean> = _isTestingSpeed.asStateFlow()

    init {
        usbHostManager.start()

        // Observe connection state
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is UsbConnectionState.Ready -> {
                        Log.d(tag, "USB Ready, starting storage discovery...")
                        repository.startStorageDiscovery(state.client)
                    }
                    is UsbConnectionState.Disconnected -> {
                        // Immediately stop any active playback dependent on the phone
                        _activeMediaItem.value = null
                        _cachedMediaFile.value = null
                        _isBufferingToCache.value = false
                        if (_currentScreen.value == NavigationScreen.MUSIC_PLAYER ||
                            _currentScreen.value == NavigationScreen.VIDEO_PLAYER ||
                            _currentScreen.value == NavigationScreen.IMAGE_VIEWER
                        ) {
                            navigateTo(NavigationScreen.HOME)
                        }
                    }
                    else -> Unit
                }
            }
        }

        // Observe storages and set currentStorageId when available
        viewModelScope.launch {
            storages.collect { storageList ->
                if (storageList.isNotEmpty() && _currentStorageId.value == 0) {
                    _currentStorageId.value = storageList[0].storageId
                    _folderBreadcrumbs.value = listOf(Pair(0, storageList[0].displayTitle))
                    loadFolderObjects(storageList[0].storageId, 0)
                }
            }
        }
    }

    fun navigateTo(screen: NavigationScreen) {
        if (_currentScreen.value != screen) {
            screenBackStack.add(_currentScreen.value)
            _currentScreen.value = screen
        }
    }

    fun handleBack(): Boolean {
        // If an info dialog is showing, dismiss it first
        if (_infoDialogItem.value != null) {
            _infoDialogItem.value = null
            return true
        }

        // If in file browser and inside a subfolder, go up
        if (_currentScreen.value == NavigationScreen.FILE_BROWSER && _currentFolderHandle.value != 0) {
            navigateFolderUp()
            return true
        }

        // Go back in stack
        if (screenBackStack.isNotEmpty()) {
            val prev = screenBackStack.removeAt(screenBackStack.size - 1)
            _currentScreen.value = prev
            return true
        }

        if (_currentScreen.value != NavigationScreen.HOME) {
            _currentScreen.value = NavigationScreen.HOME
            return true
        }

        return false
    }

    fun selectStorage(storageId: Int, title: String) {
        _currentStorageId.value = storageId
        _currentFolderHandle.value = 0
        _folderBreadcrumbs.value = listOf(Pair(0, title))
        loadFolderObjects(storageId, 0)
    }

    fun openFolder(folder: MtpObjectEntity) {
        if (!folder.isFolder) return
        val currentCrumbs = _folderBreadcrumbs.value.toMutableList()
        currentCrumbs.add(Pair(folder.objectHandle, folder.filename))
        _folderBreadcrumbs.value = currentCrumbs
        _currentFolderHandle.value = folder.objectHandle
        loadFolderObjects(folder.storageId, folder.objectHandle)
    }

    fun navigateFolderUp() {
        val crumbs = _folderBreadcrumbs.value.toMutableList()
        if (crumbs.size > 1) {
            crumbs.removeAt(crumbs.size - 1)
            val parent = crumbs.last()
            _folderBreadcrumbs.value = crumbs
            _currentFolderHandle.value = parent.first
            loadFolderObjects(_currentStorageId.value, parent.first)
        }
    }

    private fun loadFolderObjects(storageId: Int, parentHandle: Int) {
        viewModelScope.launch {
            repository.getObjectsInFolder(storageId, parentHandle).collect { list ->
                rawFolderObjects.value = list
            }
        }
    }

    fun selectMedia(item: MtpObjectEntity, contextList: List<MtpObjectEntity> = emptyList()) {
        viewModelScope.launch {
            repository.recordRecentPlay(item)
        }

        val queue = if (contextList.isNotEmpty()) {
            contextList.filter { it.mediaCategory == item.mediaCategory }
        } else {
            rawFolderObjects.value.filter { it.mediaCategory == item.mediaCategory }
        }

        _mediaQueue.value = queue
        val idx = queue.indexOfFirst { it.objectHandle == item.objectHandle }
        _queueIndex.value = if (idx >= 0) idx else 0
        _activeMediaItem.value = item

        when (item.mediaCategory) {
            "AUDIO" -> navigateTo(NavigationScreen.MUSIC_PLAYER)
            "VIDEO" -> navigateTo(NavigationScreen.VIDEO_PLAYER)
            "IMAGE" -> navigateTo(NavigationScreen.IMAGE_VIEWER)
            else -> showFileInfo(item)
        }
    }

    fun playNext() {
        val queue = _mediaQueue.value
        if (queue.isEmpty()) return
        val nextIdx = (_queueIndex.value + 1) % queue.size
        _queueIndex.value = nextIdx
        val nextItem = queue[nextIdx]
        _activeMediaItem.value = nextItem
        viewModelScope.launch {
            repository.recordRecentPlay(nextItem)
        }
    }

    fun playPrevious() {
        val queue = _mediaQueue.value
        if (queue.isEmpty()) return
        val prevIdx = if (_queueIndex.value - 1 < 0) queue.size - 1 else _queueIndex.value - 1
        _queueIndex.value = prevIdx
        val prevItem = queue[prevIdx]
        _activeMediaItem.value = prevItem
        viewModelScope.launch {
            repository.recordRecentPlay(prevItem)
        }
    }

    fun showFileInfo(item: MtpObjectEntity) {
        _infoDialogItem.value = item
    }

    fun dismissFileInfo() {
        _infoDialogItem.value = null
    }

    fun setSort(by: SortBy) {
        if (_sortBy.value == by) {
            _sortOrder.value = if (_sortOrder.value == SortOrder.ASC) SortOrder.DESC else SortOrder.ASC
        } else {
            _sortBy.value = by
            _sortOrder.value = SortOrder.ASC
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        viewModelScope.launch {
            if (query.isBlank()) {
                (searchResults as MutableStateFlow).value = emptyList()
            } else {
                repository.searchObjects(query).collect { list ->
                    (searchResults as MutableStateFlow).value = list
                }
            }
        }
    }

    fun loadCategory(category: String) {
        _selectedCategory.value = category
        viewModelScope.launch {
            repository.getObjectsByCategory(category).collect { list ->
                (categoryObjects as MutableStateFlow).value = list
            }
        }
    }

    fun triggerSpeedTest() {
        val client = usbHostManager.activeMtpClient ?: return
        if (_isTestingSpeed.value) return
        _isTestingSpeed.value = true
        viewModelScope.launch {
            val speed = repository.runSpeedTest(client)
            _speedTestMbPerSec.value = speed
            _isTestingSpeed.value = false
        }
    }

    fun bufferCurrentMediaToCache(item: MtpObjectEntity, onComplete: (File) -> Unit) {
        val client = usbHostManager.activeMtpClient ?: return
        if (_isBufferingToCache.value) return
        _isBufferingToCache.value = true
        _cacheProgress.value = 0f

        viewModelScope.launch(repository.usbDispatcher) {
            val ext = item.filename.substringAfterLast('.', "")
            val file = repository.cacheManager.cacheMediaLocally(
                client = client,
                objectHandle = item.objectHandle,
                extension = ext
            ) { bytesRead, totalBytes ->
                if (totalBytes > 0) {
                    _cacheProgress.value = (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                }
            }
            _isBufferingToCache.value = false
            if (file != null) {
                _cachedMediaFile.value = file
                onComplete(file)
            }
        }
    }

    fun reIndexStorage() {
        val client = usbHostManager.activeMtpClient ?: return
        viewModelScope.launch {
            repository.clearIndex()
            repository.startStorageDiscovery(client, forceRescan = true)
        }
    }

    fun clearAllCaches() {
        repository.clearCaches()
        _cachedMediaFile.value = null
    }

    fun clearRecentHistory() {
        viewModelScope.launch {
            repository.clearRecentHistory()
        }
    }

    fun requestUsbPermission() {
        usbHostManager.requestPermissionForCurrentDevice()
    }

    fun connectManually() {
        usbHostManager.connectManually()
    }

    override fun onCleared() {
        super.onCleared()
        usbHostManager.stop()
    }
}

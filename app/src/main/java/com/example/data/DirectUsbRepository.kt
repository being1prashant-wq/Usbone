package com.example.data

import android.content.Context
import android.util.Log
import com.example.media.MediaCacheManager
import com.example.mtp.MtpClient
import com.example.mtp.MtpConstants
import com.example.mtp.MtpDeviceInfo
import com.example.mtp.MtpObjectInfo
import com.example.mtp.MtpStorageInfo
import com.example.usb.UsbHostManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

data class IndexingProgress(
    val isScanning: Boolean = false,
    val totalFound: Int = 0,
    val audioCount: Int = 0,
    val videoCount: Int = 0,
    val imageCount: Int = 0,
    val statusMessage: String = ""
)

class DirectUsbRepository(
    private val context: Context,
    val usbHostManager: UsbHostManager,
    private val database: AppDatabase
) {
    private val tag = "DirectUsbRepository"
    private val usbExecutor = Executors.newSingleThreadExecutor()
    val usbDispatcher = usbExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(usbDispatcher + Job())

    val cacheManager = MediaCacheManager(context)

    private val _indexingProgress = MutableStateFlow(IndexingProgress())
    val indexingProgress: StateFlow<IndexingProgress> = _indexingProgress.asStateFlow()

    private val _storages = MutableStateFlow<List<MtpStorageInfo>>(emptyList())
    val storages: StateFlow<List<MtpStorageInfo>> = _storages.asStateFlow()

    private val _deviceInfo = MutableStateFlow<MtpDeviceInfo?>(null)
    val deviceInfo: StateFlow<MtpDeviceInfo?> = _deviceInfo.asStateFlow()

    private var scanJob: Job? = null

    // Room DB queries
    fun getObjectsInFolder(storageId: Int, parentHandle: Int): Flow<List<MtpObjectEntity>> {
        return database.mtpObjectDao().getObjectsInFolder(storageId, parentHandle)
    }

    fun getObjectsByCategory(category: String): Flow<List<MtpObjectEntity>> {
        return database.mtpObjectDao().getObjectsByCategory(category)
    }

    fun searchObjects(query: String): Flow<List<MtpObjectEntity>> {
        return database.mtpObjectDao().searchObjects(query)
    }

    suspend fun getObjectByHandle(handle: Int): MtpObjectEntity? {
        return database.mtpObjectDao().getObjectByHandle(handle)
    }

    fun getRecentMedia(): Flow<List<RecentMediaEntity>> {
        return database.recentMediaDao().getRecentMedia()
    }

    suspend fun recordRecentPlay(item: MtpObjectEntity, positionMs: Long = 0L) {
        database.recentMediaDao().insert(
            RecentMediaEntity(
                objectHandle = item.objectHandle,
                filename = item.filename,
                storageId = item.storageId,
                mediaCategory = item.mediaCategory,
                size = item.size,
                lastPlayedTime = System.currentTimeMillis(),
                playbackPositionMs = positionMs,
                durationMs = item.durationMs
            )
        )
    }

    suspend fun updateRecentPosition(handle: Int, positionMs: Long) {
        database.recentMediaDao().updatePosition(handle, positionMs)
    }

    fun startStorageDiscovery(client: MtpClient, forceRescan: Boolean = false) {
        scanJob?.cancel()
        scanJob = scope.launch {
            try {
                _indexingProgress.value = IndexingProgress(
                    isScanning = true,
                    statusMessage = "Reading phone device information..."
                )

                val info = client.getDeviceInfo()
                _deviceInfo.value = info

                val storageIds = client.getStorageIds()
                Log.d(tag, "Found ${storageIds.size} storages: $storageIds")

                val storageInfoList = mutableListOf<MtpStorageInfo>()
                for (id in storageIds) {
                    val sInfo = client.getStorageInfo(id)
                    if (sInfo != null) {
                        storageInfoList.add(sInfo)
                    }
                }
                _storages.value = storageInfoList

                if (!forceRescan) {
                    // Check if we already have items cached in database
                    // If we do, we can finish discovery quickly
                }

                _indexingProgress.value = IndexingProgress(
                    isScanning = true,
                    statusMessage = "Scanning phone storage..."
                )

                var totalCount = 0
                var audioCount = 0
                var videoCount = 0
                var imageCount = 0

                val serial = info?.serialNumber ?: ""
                val batch = mutableListOf<MtpObjectEntity>()

                for (sId in storageIds) {
                    if (!isActive) break

                    // Attempt getting all handles or starting from root
                    val allHandles = client.getObjectHandles(sId, MtpConstants.FORMAT_ALL, MtpConstants.PARENT_ALL)
                    val handlesToScan = if (allHandles.isNotEmpty()) {
                        allHandles
                    } else {
                        // Fallback: root handles
                        client.getObjectHandles(sId, MtpConstants.FORMAT_ALL, MtpConstants.PARENT_ROOT)
                    }

                    Log.d(tag, "Storage $sId has ${handlesToScan.size} object handles")

                    for (handle in handlesToScan) {
                        if (!isActive) break
                        val obj = client.getObjectInfo(handle) ?: continue

                        val (category, mime) = MediaCategorizer.categorize(obj.objectFormat, obj.filename)
                        if (!obj.isFolder) {
                            totalCount++
                            when (category) {
                                "AUDIO" -> audioCount++
                                "VIDEO" -> videoCount++
                                "IMAGE" -> imageCount++
                            }
                        }

                        val modDate = parseMtpDate(obj.modificationDate)
                        batch.add(
                            MtpObjectEntity(
                                objectHandle = obj.objectHandle,
                                storageId = obj.storageId,
                                parentHandle = obj.parentObject,
                                filename = obj.filename,
                                size = obj.compressedSize,
                                mimeType = mime,
                                formatCode = obj.objectFormat,
                                isFolder = obj.isFolder,
                                modificationDate = modDate,
                                mediaCategory = category,
                                durationMs = 0L,
                                deviceSerial = serial
                            )
                        )

                        if (batch.size >= 50) {
                            database.mtpObjectDao().insertAll(batch.toList())
                            batch.clear()

                            _indexingProgress.value = IndexingProgress(
                                isScanning = true,
                                totalFound = totalCount,
                                audioCount = audioCount,
                                videoCount = videoCount,
                                imageCount = imageCount,
                                statusMessage = "Scanning phone: $totalCount files found"
                            )
                        }
                    }
                }

                if (batch.isNotEmpty()) {
                    database.mtpObjectDao().insertAll(batch)
                    batch.clear()
                }

                _indexingProgress.value = IndexingProgress(
                    isScanning = false,
                    totalFound = totalCount,
                    audioCount = audioCount,
                    videoCount = videoCount,
                    imageCount = imageCount,
                    statusMessage = "Ready. $totalCount files indexed."
                )

            } catch (e: Exception) {
                Log.e(tag, "Error indexing storage", e)
                _indexingProgress.value = _indexingProgress.value.copy(
                    isScanning = false,
                    statusMessage = "Scan completed with warnings: ${e.message}"
                )
            }
        }
    }

    suspend fun runSpeedTest(client: MtpClient): Float = withContext(usbDispatcher) {
        try {
            // Find a readable file with at least 512KB
            val storages = client.getStorageIds()
            if (storages.isEmpty()) return@withContext 0f
            val handles = client.getObjectHandles(storages[0], MtpConstants.FORMAT_ALL, MtpConstants.PARENT_ALL)
            var targetHandle = 0
            for (h in handles) {
                val info = client.getObjectInfo(h)
                if (info != null && !info.isFolder && info.compressedSize > 256 * 1024) {
                    targetHandle = h
                    break
                }
            }

            if (targetHandle == 0 && handles.isNotEmpty()) {
                targetHandle = handles.first()
            }

            if (targetHandle == 0) return@withContext 0f

            val testBytesToRead = 1024 * 1024 // 1 MB test
            val startTime = System.nanoTime()
            val chunk = client.getPartialObject(targetHandle, 0, testBytesToRead) ?: return@withContext 0f
            val durationSec = (System.nanoTime() - startTime) / 1_000_000_000.0f
            if (durationSec > 0.001f) {
                val mb = chunk.size / (1024f * 1024f)
                val speed = mb / durationSec
                usbHostManager.updateSpeedMeasurement(speed)
                return@withContext speed
            }
        } catch (e: Exception) {
            Log.e(tag, "Speed test failed", e)
        }
        0f
    }

    suspend fun clearIndex() = withContext(Dispatchers.IO) {
        database.mtpObjectDao().clearAll()
        _indexingProgress.value = IndexingProgress()
    }

    suspend fun clearRecentHistory() = withContext(Dispatchers.IO) {
        database.recentMediaDao().clearAll()
    }

    fun clearCaches() {
        cacheManager.clearAllCache()
    }

    private fun parseMtpDate(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis()
        return try {
            // MTP date format: YYYYMMDDThhmmss.s or similar
            if (dateStr.length >= 8) {
                val year = dateStr.substring(0, 4).toInt()
                val month = dateStr.substring(4, 6).toInt()
                val day = dateStr.substring(6, 8).toInt()
                // Simple approx timestamp
                java.util.Calendar.getInstance().apply {
                    set(year, month - 1, day)
                }.timeInMillis
            } else {
                System.currentTimeMillis()
            }
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}

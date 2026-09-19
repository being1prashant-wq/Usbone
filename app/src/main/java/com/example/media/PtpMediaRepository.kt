package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

class PtpMediaRepository(private val context: Context) {

    private var activeClient: PtpClient? = null
    private var reportedDeviceName: String = "PTP Device"

    val photoItems = mutableListOf<PtpMediaItem>()
    val videoItems = mutableListOf<PtpMediaItem>()

    var isVideoScanInProgress: Boolean = false
        private set

    val isSessionReady: Boolean
        get() = activeClient?.isSessionOpen == true

    val deviceName: String
        get() = reportedDeviceName

    val client: PtpClient?
        get() = activeClient

    suspend fun initialize(ptpClient: PtpClient, fallbackName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            activeClient = ptpClient
            photoItems.clear()
            videoItems.clear()

            Log.i(PtpConstants.TAG, "Step 1: OpenSession...")
            if (!ptpClient.openSession()) {
                Log.e(PtpConstants.TAG, "OpenSession failed")
                return@withContext false
            }

            // 1. Get Device Info
            Log.i(PtpConstants.TAG, "Step 2: GetDeviceInfo...")
            val devInfo = ptpClient.getDeviceInfo()
            reportedDeviceName = if (devInfo != null && (devInfo.manufacturer.isNotBlank() || devInfo.model.isNotBlank())) {
                "${devInfo.manufacturer} ${devInfo.model}".trim()
            } else {
                fallbackName
            }
            Log.i(PtpConstants.TAG, "PTP session opened. Device name: $reportedDeviceName")

            // 2. Get Storage IDs
            Log.i(PtpConstants.TAG, "Step 3: GetStorageIDs...")
            val storageIds = ptpClient.getStorageIds()
            val primaryStorageId = if (storageIds.isNotEmpty()) storageIds[0] else PtpConstants.STORAGE_ALL

            // 3. Fast initial discovery for instantaneous UI response
            Log.i(PtpConstants.TAG, "Step 4: Fast initial media discovery...")
            discoverInitialMedia(ptpClient, primaryStorageId)

            Log.i(PtpConstants.TAG, "PTP Initial Ready: ${photoItems.size} photos, ${videoItems.size} videos")
            true
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "PTP initialization failed with exception", e)
            false
        }
    }

    private suspend fun discoverInitialMedia(ptpClient: PtpClient, storageId: Int) {
        val foundPhotos = mutableSetOf<Int>()
        val foundVideos = mutableSetOf<Int>()

        // 1. Quick JPEG format query
        try {
            val jpegHandles = ptpClient.getObjectHandles(
                storageId = storageId,
                formatCode = PtpConstants.FORMAT_EXIF_JPEG,
                parentHandle = PtpConstants.PARENT_ALL
            )
            for (h in jpegHandles) foundPhotos.add(h)
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Quick JPEG discovery error", e)
        }

        // 2. Quick MP4 format query
        try {
            val mp4Handles = ptpClient.getObjectHandles(
                storageId = storageId,
                formatCode = PtpConstants.FORMAT_MP4,
                parentHandle = PtpConstants.PARENT_ALL
            )
            for (h in mp4Handles) foundVideos.add(h)
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Quick MP4 discovery error", e)
        }

        for (h in foundPhotos) {
            photoItems.add(PtpMediaItem(handle = h, isVideo = false))
        }
        for (h in foundVideos) {
            videoItems.add(PtpMediaItem(handle = h, isVideo = true))
        }

        // If both format queries returned nothing, retrieve first batch of handles
        if (foundPhotos.isEmpty() && foundVideos.isEmpty()) {
            try {
                val allHandles = ptpClient.getObjectHandles(
                    storageId = storageId,
                    formatCode = PtpConstants.FORMAT_ALL,
                    parentHandle = PtpConstants.PARENT_ALL
                )
                val count = allHandles.size.coerceAtMost(1000)
                for (i in 0 until count) {
                    photoItems.add(PtpMediaItem(handle = allHandles[i], isVideo = false))
                }
            } catch (e: Exception) {
                Log.w(PtpConstants.TAG, "Fallback handle retrieval error", e)
            }
        }
    }

    /**
     * Comprehensive recursive video discovery.
     * Scans all PTP storages and folders recursively without format or extension exclusions.
     * Protected against duplicate handles and circular folder references.
     * Never loads file payloads during scan (only lightweight ObjectInfo).
     */
    suspend fun scanAllVideos(
        onProgress: ((foundCount: Int, isDone: Boolean) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        val client = activeClient ?: return@withContext 0
        isVideoScanInProgress = true
        Log.i(PtpConstants.TAG, "Starting comprehensive recursive video discovery...")

        try {
            val storageIds = try {
                client.getStorageIds()
            } catch (e: Exception) {
                Log.w(PtpConstants.TAG, "Error getting storage IDs for video scan", e)
                IntArray(0)
            }
            val effectiveStorageIds = if (storageIds.isNotEmpty()) storageIds else intArrayOf(PtpConstants.STORAGE_ALL)

            val visitedHandles = HashSet<Int>()
            val visitedFolders = HashSet<Int>()
            val folderQueue = ArrayDeque<Pair<Int, Int>>() // Pair(storageId, folderHandle)

            // Seed with known handles
            for (item in videoItems) {
                visitedHandles.add(item.handle)
            }
            for (item in photoItems) {
                visitedHandles.add(item.handle)
            }

            for (sId in effectiveStorageIds) {
                if (!coroutineContext.isActive) break

                // 1. Try querying PARENT_ALL (-1 / 0xFFFFFFFF)
                val allHandles = try {
                    client.getObjectHandles(
                        storageId = sId,
                        formatCode = PtpConstants.FORMAT_ALL,
                        parentHandle = PtpConstants.PARENT_ALL
                    )
                } catch (e: Exception) {
                    Log.w(PtpConstants.TAG, "PARENT_ALL query failed on storage 0x${sId.toString(16)}", e)
                    IntArray(0)
                }

                if (allHandles.isNotEmpty()) {
                    Log.i(PtpConstants.TAG, "Storage 0x${sId.toString(16)} returned ${allHandles.size} handles via PARENT_ALL")
                    for (h in allHandles) {
                        if (!coroutineContext.isActive) break
                        if (!visitedHandles.add(h)) continue

                        val info = try {
                            client.getObjectInfo(h)
                        } catch (e: Exception) {
                            Log.w(PtpConstants.TAG, "Error fetching ObjectInfo for handle $h", e)
                            null
                        } ?: continue

                        if (info.isFolder) {
                            if (visitedFolders.add(h)) {
                                folderQueue.add(Pair(sId, h))
                            }
                        } else if (info.isVideo) {
                            val item = PtpMediaItem(
                                handle = h,
                                isVideo = true,
                                filename = info.filename,
                                sizeBytes = info.compressedSize,
                                format = info.format,
                                isMetadataLoaded = true
                            )
                            withContext(Dispatchers.Main) {
                                if (videoItems.none { it.handle == h }) {
                                    videoItems.add(item)
                                }
                            }
                            onProgress?.invoke(videoItems.size, false)
                        } else if (info.isImage) {
                            withContext(Dispatchers.Main) {
                                if (photoItems.none { it.handle == h }) {
                                    photoItems.add(
                                        PtpMediaItem(
                                            handle = h,
                                            isVideo = false,
                                            filename = info.filename,
                                            sizeBytes = info.compressedSize,
                                            format = info.format,
                                            isMetadataLoaded = true
                                        )
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Fallback to PARENT_ROOT (0x00000000)
                    Log.i(PtpConstants.TAG, "Querying root handles for storage 0x${sId.toString(16)}")
                    val rootHandles = try {
                        client.getObjectHandles(
                            storageId = sId,
                            formatCode = PtpConstants.FORMAT_ALL,
                            parentHandle = PtpConstants.PARENT_ROOT
                        )
                    } catch (e: Exception) {
                        Log.w(PtpConstants.TAG, "PARENT_ROOT query failed on storage 0x${sId.toString(16)}", e)
                        IntArray(0)
                    }
                    for (h in rootHandles) {
                        if (!coroutineContext.isActive) break
                        if (!visitedHandles.add(h)) continue

                        val info = try {
                            client.getObjectInfo(h)
                        } catch (e: Exception) {
                            null
                        } ?: continue

                        if (info.isFolder) {
                            if (visitedFolders.add(h)) {
                                folderQueue.add(Pair(sId, h))
                            }
                        } else if (info.isVideo) {
                            val item = PtpMediaItem(
                                handle = h,
                                isVideo = true,
                                filename = info.filename,
                                sizeBytes = info.compressedSize,
                                format = info.format,
                                isMetadataLoaded = true
                            )
                            withContext(Dispatchers.Main) {
                                if (videoItems.none { it.handle == h }) {
                                    videoItems.add(item)
                                }
                            }
                            onProgress?.invoke(videoItems.size, false)
                        }
                    }
                }
            }

            // Process subfolder queue recursively with depth and loop guards
            var processedFolderCount = 0
            val maxFolders = 500
            while (folderQueue.isNotEmpty() && processedFolderCount < maxFolders && coroutineContext.isActive) {
                val (sId, folderH) = folderQueue.removeFirst()
                processedFolderCount++

                val childHandles = try {
                    client.getObjectHandles(
                        storageId = sId,
                        formatCode = PtpConstants.FORMAT_ALL,
                        parentHandle = folderH
                    )
                } catch (e: Exception) {
                    Log.w(PtpConstants.TAG, "Failed to query child handles for folder $folderH", e)
                    continue
                }

                for (ch in childHandles) {
                    if (!coroutineContext.isActive) break
                    if (!visitedHandles.add(ch)) continue

                    val info = try {
                        client.getObjectInfo(ch)
                    } catch (e: Exception) {
                        Log.w(PtpConstants.TAG, "Error fetching ObjectInfo for child $ch in folder $folderH", e)
                        null
                    } ?: continue

                    if (info.isFolder) {
                        if (visitedFolders.add(ch)) {
                            folderQueue.add(Pair(sId, ch))
                        }
                    } else if (info.isVideo) {
                        val item = PtpMediaItem(
                            handle = ch,
                            isVideo = true,
                            filename = info.filename,
                            sizeBytes = info.compressedSize,
                            format = info.format,
                            isMetadataLoaded = true
                        )
                        withContext(Dispatchers.Main) {
                            if (videoItems.none { it.handle == ch }) {
                                videoItems.add(item)
                            }
                        }
                        onProgress?.invoke(videoItems.size, false)
                    } else if (info.isImage) {
                        withContext(Dispatchers.Main) {
                            if (photoItems.none { it.handle == ch }) {
                                photoItems.add(
                                    PtpMediaItem(
                                        handle = ch,
                                        isVideo = false,
                                        filename = info.filename,
                                        sizeBytes = info.compressedSize,
                                        format = info.format,
                                        isMetadataLoaded = true
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Log.i(PtpConstants.TAG, "Video discovery finished: found ${videoItems.size} videos (scanned $processedFolderCount folders).")
            onProgress?.invoke(videoItems.size, true)
            videoItems.size
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Error during recursive video discovery", e)
            onProgress?.invoke(videoItems.size, true)
            videoItems.size
        } finally {
            isVideoScanInProgress = false
        }
    }

    suspend fun fetchMetadataIfNeeded(item: PtpMediaItem): PtpMediaItem = withContext(Dispatchers.IO) {
        if (item.isMetadataLoaded) return@withContext item
        val client = activeClient ?: return@withContext item

        val info = client.getObjectInfo(item.handle)
        if (info != null) {
            item.filename = info.filename
            item.sizeBytes = info.compressedSize
            item.format = info.format
            item.isMetadataLoaded = true
        }
        item
    }

    suspend fun loadFullPhoto(handle: Int, maxDim: Int = 1920): Bitmap? = withContext(Dispatchers.IO) {
        val client = activeClient ?: return@withContext null
        val tempFile = File(context.cacheDir, "full_photo_temp.jpg")
        if (tempFile.exists()) tempFile.delete()

        try {
            FileOutputStream(tempFile).use { fos ->
                val success = client.streamObject(handle, fos)
                if (!success) return@withContext null
            }

            // Decode dimensions
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tempFile.absolutePath, boundsOpts)

            val sampleSize = PtpClient.calculateInSampleSize(boundsOpts, maxDim, maxDim)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(tempFile.absolutePath, decodeOpts)
        } catch (e: OutOfMemoryError) {
            Log.e(PtpConstants.TAG, "OOM loading full photo $handle", e)
            null
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Error loading full photo $handle", e)
            null
        } finally {
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (_: Exception) {}
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            try {
                activeClient?.closeSession()
            } catch (_: Exception) {}
            activeClient = null
            photoItems.clear()
            videoItems.clear()
            isVideoScanInProgress = false
        }
    }
}

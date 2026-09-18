package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class PtpMediaRepository(private val context: Context) {

    private var activeClient: PtpClient? = null
    private var reportedDeviceName: String = "PTP Device"

    val photoItems = mutableListOf<PtpMediaItem>()
    val videoItems = mutableListOf<PtpMediaItem>()

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

            // 3. Minimal media-handle discovery
            Log.i(PtpConstants.TAG, "Step 4: Minimal media-handle discovery...")
            discoverMediaHandles(ptpClient, primaryStorageId)

            Log.i(PtpConstants.TAG, "PTP Ready: ${photoItems.size} photos, ${videoItems.size} videos")
            true
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "PTP initialization failed with exception", e)
            false
        }
    }

    private suspend fun discoverMediaHandles(ptpClient: PtpClient, storageId: Int) {
        val foundPhotos = mutableSetOf<Int>()
        val foundVideos = mutableSetOf<Int>()

        // 1. Try format filtering for JPEG (most common photo format)
        val jpegHandles = ptpClient.getObjectHandles(
            storageId = storageId,
            formatCode = PtpConstants.FORMAT_EXIF_JPEG,
            parentHandle = PtpConstants.PARENT_ALL
        )
        if (jpegHandles.isNotEmpty()) {
            for (h in jpegHandles) foundPhotos.add(h)
        }

        // 2. Try format filtering for MP4 (most common video format)
        val mp4Handles = ptpClient.getObjectHandles(
            storageId = storageId,
            formatCode = PtpConstants.FORMAT_MP4,
            parentHandle = PtpConstants.PARENT_ALL
        )
        if (mp4Handles.isNotEmpty()) {
            for (h in mp4Handles) foundVideos.add(h)
        }

        // If format filtering returned items, we are done with only 2 quick queries!
        if (foundPhotos.isNotEmpty() || foundVideos.isNotEmpty()) {
            for (h in foundPhotos) {
                photoItems.add(PtpMediaItem(handle = h, isVideo = false))
            }
            for (h in foundVideos) {
                videoItems.add(PtpMediaItem(handle = h, isVideo = true))
            }
            return
        }

        // If format filtering returned nothing (or format-filter unsupported by phone PTP stack),
        // query all handles on storage
        Log.i(PtpConstants.TAG, "Format filtering returned no handles, querying all PTP handles")
        val allHandles = ptpClient.getObjectHandles(
            storageId = storageId,
            formatCode = PtpConstants.FORMAT_ALL,
            parentHandle = PtpConstants.PARENT_ALL
        )

        Log.i(PtpConstants.TAG, "Total PTP handles retrieved: ${allHandles.size}")

        // Protect low-end TV RAM: store up to 2000 handles
        val count = allHandles.size.coerceAtMost(2000)
        for (i in 0 until count) {
            photoItems.add(PtpMediaItem(handle = allHandles[i], isVideo = false))
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
            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOpts)
            bitmap
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
        }
    }
}

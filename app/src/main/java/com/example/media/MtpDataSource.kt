package com.example.media

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.example.mtp.MtpClient
import java.io.IOException

class MtpDataSource(
    private val mtpClient: MtpClient,
    private val supports64: Boolean = true,
    private val onSpeedMeasured: ((Float) -> Unit)? = null
) : BaseDataSource(/* isNetwork = */ false) {

    private val tag = "MtpDataSource"

    private var currentDataSpec: DataSpec? = null
    private var currentUri: Uri? = null
    private var objectHandle: Int = 0
    private var objectLength: Long = C.LENGTH_UNSET.toLong()
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var currentPosition: Long = 0L
    private var isOpen = false

    // Small read-ahead buffer (128 KB) to reduce USB round-trips
    private val internalBuffer = ByteArray(128 * 1024)
    private var internalBufferPos = 0
    private var internalBufferLength = 0
    private var internalBufferOffset = -1L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentDataSpec = dataSpec
        currentUri = dataSpec.uri

        // URI scheme directusb://object/{handle}?length={length}
        val handleStr = dataSpec.uri.lastPathSegment ?: throw IOException("Invalid MTP URI: ${dataSpec.uri}")
        objectHandle = handleStr.toIntOrNull() ?: throw IOException("Invalid MTP object handle: $handleStr")

        val queryLength = dataSpec.uri.getQueryParameter("length")?.toLongOrNull()
        objectLength = if (queryLength != null && queryLength > 0) queryLength else C.LENGTH_UNSET.toLong()

        currentPosition = dataSpec.position
        if (objectLength != C.LENGTH_UNSET.toLong()) {
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                objectLength - currentPosition
            }
        } else {
            bytesRemaining = dataSpec.length
        }

        // Invalidate buffer
        internalBufferLength = 0
        internalBufferPos = 0
        internalBufferOffset = -1L

        isOpen = true
        transferStarted(dataSpec)
        Log.d(tag, "Opened MtpDataSource for handle $objectHandle, pos=$currentPosition, remaining=$bytesRemaining")
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            length.toLong().coerceAtMost(bytesRemaining).toInt()
        } else {
            length
        }

        // Check if currentPosition is within internalBuffer
        if (currentPosition >= internalBufferOffset &&
            currentPosition < internalBufferOffset + internalBufferLength
        ) {
            val offsetInBuffer = (currentPosition - internalBufferOffset).toInt()
            val available = internalBufferLength - offsetInBuffer
            val copyLen = bytesToRead.coerceAtMost(available)

            System.arraycopy(internalBuffer, offsetInBuffer, buffer, offset, copyLen)
            currentPosition += copyLen
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= copyLen
            }
            bytesTransferred(copyLen)
            return copyLen
        }

        // Fetch next chunk from phone via MTP partial object read
        val chunkSize = internalBuffer.size.coerceAtLeast(bytesToRead)
        val startTime = System.nanoTime()
        val chunkBytes = try {
            mtpClient.readPartial(objectHandle, currentPosition, chunkSize, supports64)
        } catch (e: Exception) {
            Log.e(tag, "Exception reading partial MTP object $objectHandle at $currentPosition", e)
            throw IOException("USB read failure on MTP object $objectHandle", e)
        }

        if (chunkBytes == null || chunkBytes.isEmpty()) {
            if (bytesRemaining != C.LENGTH_UNSET.toLong() && bytesRemaining > 0) {
                // Short read before expected end
                Log.w(tag, "Empty read before expected EOF for handle $objectHandle")
            }
            return C.RESULT_END_OF_INPUT
        }

        val elapsedSec = (System.nanoTime() - startTime) / 1_000_000_000.0f
        if (elapsedSec > 0.001f) {
            val speedMb = (chunkBytes.size / (1024f * 1024f)) / elapsedSec
            onSpeedMeasured?.invoke(speedMb)
        }

        // Store into internal buffer
        System.arraycopy(chunkBytes, 0, internalBuffer, 0, chunkBytes.size)
        internalBufferLength = chunkBytes.size
        internalBufferOffset = currentPosition
        internalBufferPos = 0

        val copyLen = bytesToRead.coerceAtMost(internalBufferLength)
        System.arraycopy(internalBuffer, 0, buffer, offset, copyLen)

        currentPosition += copyLen
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= copyLen
        }
        bytesTransferred(copyLen)
        return copyLen
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        if (isOpen) {
            isOpen = false
            internalBufferLength = 0
            transferEnded()
            Log.d(tag, "Closed MtpDataSource for handle $objectHandle")
        }
    }

    class Factory(
        private val mtpClient: MtpClient,
        private val supports64: Boolean = true,
        private val onSpeedMeasured: ((Float) -> Unit)? = null
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return MtpDataSource(mtpClient, supports64, onSpeedMeasured)
        }
    }
}

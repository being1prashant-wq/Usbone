package com.example.usb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

data class PtpDeviceInfo(
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
    val operationsSupported: Set<Int>
)

data class PtpObjectInfo(
    val handle: Int,
    val storageId: Int,
    val format: Int,
    val compressedSize: Long,
    val thumbFormat: Int,
    val thumbCompressedSize: Int,
    val thumbPixWidth: Int,
    val thumbPixHeight: Int,
    val imagePixWidth: Int,
    val imagePixHeight: Int,
    val filename: String,
    val dateModified: String
) {
    val isImage: Boolean
        get() = PtpConstants.isImageFormat(format) || PtpConstants.isImageExtension(filename)

    val isVideo: Boolean
        get() = PtpConstants.isVideoFormat(format) || PtpConstants.isVideoExtension(filename)
}

class PtpClient(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint
) {
    private val transactionCounter = AtomicInteger(1)
    private val mutex = Mutex()
    private val usbTimeout = 10000 // 10s timeout
    private var sessionId: Int = 1
    var isSessionOpen = false
        private set

    var deviceInfo: PtpDeviceInfo? = null
        private set

    private fun nextTransactionId(): Int {
        val id = transactionCounter.getAndIncrement()
        if (id >= 0x7FFFFFFF) {
            transactionCounter.set(1)
        }
        return id
    }

    /**
     * Send command to Bulk OUT
     */
    private fun sendCommand(opCode: Int, transactionId: Int, vararg params: Int): Boolean {
        val cmd = PtpPacket.buildCommand(opCode, transactionId, *params)
        val transferred = connection.bulkTransfer(bulkOut, cmd, cmd.size, usbTimeout)
        if (transferred < 0) {
            Log.e(PtpConstants.TAG, "bulkTransfer OUT failed for op: 0x${opCode.toString(16)}")
            return false
        }
        return true
    }

    /**
     * Execute a command that does not return a Data container, only a Response container.
     */
    suspend fun executeSimpleCommand(opCode: Int, vararg params: Int): PtpResponse = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tid = nextTransactionId()
            if (!sendCommand(opCode, tid, *params)) {
                return@withContext PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0))
            }
            readResponse(tid)
        }
    }

    /**
     * Read a response packet from bulkIn
     */
    private fun readResponse(expectedTid: Int): PtpResponse {
        val buf = ByteArray(1024)
        val read = connection.bulkTransfer(bulkIn, buf, buf.size, usbTimeout)
        if (read < PtpConstants.HEADER_SIZE) {
            Log.e(PtpConstants.TAG, "readResponse: read $read bytes, expected >= ${PtpConstants.HEADER_SIZE}")
            return PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, expectedTid, IntArray(0))
        }
        val byteBuffer = ByteBuffer.wrap(buf, 0, read).order(ByteOrder.LITTLE_ENDIAN)
        val header = PtpPacket.parseHeader(byteBuffer)
        if (header.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
            val paramCount = (read - PtpConstants.HEADER_SIZE) / 4
            val params = IntArray(paramCount)
            for (i in 0 until paramCount) {
                params[i] = byteBuffer.getInt()
            }
            return PtpResponse(header.code, header.transactionId, params)
        }
        Log.w(PtpConstants.TAG, "Unexpected container type in readResponse: ${header.type}")
        return PtpResponse(header.code, header.transactionId, IntArray(0))
    }

    /**
     * Execute a command that returns a Data container, then a Response container.
     */
    suspend fun executeDataCommand(opCode: Int, vararg params: Int): Pair<PtpResponse, ByteArray?> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tid = nextTransactionId()
            if (!sendCommand(opCode, tid, *params)) {
                return@withContext Pair(
                    PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)),
                    null
                )
            }

            // Read the initial chunk
            val initialBuffer = ByteArray(16384)
            val firstRead = connection.bulkTransfer(bulkIn, initialBuffer, initialBuffer.size, usbTimeout)
            if (firstRead < PtpConstants.HEADER_SIZE) {
                Log.e(PtpConstants.TAG, "executeDataCommand: first read too small ($firstRead bytes)")
                return@withContext Pair(
                    PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)),
                    null
                )
            }

            val bb = ByteBuffer.wrap(initialBuffer, 0, firstRead).order(ByteOrder.LITTLE_ENDIAN)
            val header = PtpPacket.parseHeader(bb)

            // If the device sent a Response container right away (e.g. error)
            if (header.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                val paramCount = (firstRead - PtpConstants.HEADER_SIZE) / 4
                val respParams = IntArray(paramCount)
                for (i in 0 until paramCount) {
                    respParams[i] = bb.getInt()
                }
                return@withContext Pair(PtpResponse(header.code, header.transactionId, respParams), null)
            }

            if (header.type != PtpConstants.CONTAINER_TYPE_DATA) {
                Log.e(PtpConstants.TAG, "Unexpected container type: ${header.type}")
                return@withContext Pair(
                    PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)),
                    null
                )
            }

            // Total data container length includes header
            val totalContainerLength = header.length
            val payloadLength = totalContainerLength - PtpConstants.HEADER_SIZE

            val payload = ByteArray(payloadLength)
            var bytesRead = firstRead - PtpConstants.HEADER_SIZE
            val initialPayloadBytes = bytesRead.coerceAtMost(payloadLength)
            System.arraycopy(initialBuffer, PtpConstants.HEADER_SIZE, payload, 0, initialPayloadBytes)
            bytesRead = initialPayloadBytes

            // Read remaining payload bytes if any
            val chunk = ByteArray(16384)
            while (bytesRead < payloadLength) {
                val toRead = (payloadLength - bytesRead).coerceAtMost(chunk.size)
                val readChunk = connection.bulkTransfer(bulkIn, chunk, toRead, usbTimeout)
                if (readChunk <= 0) {
                    Log.e(PtpConstants.TAG, "executeDataCommand: timeout reading payload")
                    break
                }
                System.arraycopy(chunk, 0, payload, bytesRead, readChunk)
                bytesRead += readChunk
            }

            // Finally, read the response container
            val finalResp = readResponse(tid)
            Pair(finalResp, payload)
        }
    }

    /**
     * Stream an object directly to an OutputStream (e.g. temporary cache file).
     * Avoids loading large files into RAM.
     */
    suspend fun streamObject(handle: Int, outputStream: OutputStream): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tid = nextTransactionId()
            if (!sendCommand(PtpConstants.OPERATION_GET_OBJECT, tid, handle)) {
                return@withContext false
            }

            val buffer = ByteArray(16384)
            val firstRead = connection.bulkTransfer(bulkIn, buffer, buffer.size, usbTimeout)
            if (firstRead < PtpConstants.HEADER_SIZE) return@withContext false

            val bb = ByteBuffer.wrap(buffer, 0, firstRead).order(ByteOrder.LITTLE_ENDIAN)
            val header = PtpPacket.parseHeader(bb)

            if (header.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                Log.e(PtpConstants.TAG, "streamObject error response code: 0x${header.code.toString(16)}")
                return@withContext false
            }
            if (header.type != PtpConstants.CONTAINER_TYPE_DATA) return@withContext false

            val totalPayload = header.length - PtpConstants.HEADER_SIZE
            var written = 0

            val initialBytes = firstRead - PtpConstants.HEADER_SIZE
            if (initialBytes > 0) {
                val toWrite = initialBytes.coerceAtMost(totalPayload)
                outputStream.write(buffer, PtpConstants.HEADER_SIZE, toWrite)
                written += toWrite
            }

            while (written < totalPayload) {
                val toRead = (totalPayload - written).coerceAtMost(buffer.size)
                val r = connection.bulkTransfer(bulkIn, buffer, toRead, usbTimeout)
                if (r <= 0) {
                    Log.e(PtpConstants.TAG, "streamObject: transfer halted at $written / $totalPayload bytes")
                    return@withContext false
                }
                outputStream.write(buffer, 0, r)
                written += r
            }

            outputStream.flush()
            val resp = readResponse(tid)
            resp.isOk
        }
    }

    /**
     * Open PTP Session
     */
    suspend fun openSession(): Boolean {
        sessionId = 1
        val resp = executeSimpleCommand(PtpConstants.OPERATION_OPEN_SESSION, sessionId)
        if (resp.isOk || resp.responseCode == PtpConstants.RESPONSE_SESSION_ALREADY_OPEN) {
            isSessionOpen = true
            Log.i(PtpConstants.TAG, "PTP session opened successfully")
            return true
        }
        Log.e(PtpConstants.TAG, "OpenSession failed: 0x${resp.responseCode.toString(16)}")
        return false
    }

    /**
     * Close PTP Session
     */
    suspend fun closeSession(): Boolean {
        if (!isSessionOpen) return true
        val resp = executeSimpleCommand(PtpConstants.OPERATION_CLOSE_SESSION)
        isSessionOpen = false
        Log.i(PtpConstants.TAG, "PTP session closed")
        return resp.isOk
    }

    /**
     * Get Device Info
     */
    suspend fun getDeviceInfo(): PtpDeviceInfo? {
        val (resp, payload) = executeDataCommand(PtpConstants.OPERATION_GET_DEVICE_INFO)
        if (!resp.isOk || payload == null) {
            Log.w(PtpConstants.TAG, "GetDeviceInfo failed: 0x${resp.responseCode.toString(16)}")
            return null
        }
        try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            bb.getShort() // StandardVersion
            bb.getInt() // VendorExtensionId
            bb.getShort() // VendorExtensionVersion
            PtpPacket.parseString(bb) // VendorExtensionDesc
            bb.getShort() // FunctionalMode

            val ops = PtpPacket.parseUInt32Array(bb).toSet()
            PtpPacket.parseUInt32Array(bb) // EventsSupported
            PtpPacket.parseUInt32Array(bb) // DevicePropertiesSupported
            PtpPacket.parseUInt32Array(bb) // CaptureFormats
            PtpPacket.parseUInt32Array(bb) // PlaybackFormats

            val manufacturer = PtpPacket.parseString(bb)
            val model = PtpPacket.parseString(bb)
            val deviceVersion = PtpPacket.parseString(bb)
            val serialNumber = PtpPacket.parseString(bb)

            val info = PtpDeviceInfo(manufacturer, model, deviceVersion, serialNumber, ops)
            deviceInfo = info
            Log.i(PtpConstants.TAG, "PTP DeviceInfo: manufacturer='$manufacturer', model='$model'")
            return info
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Failed to parse DeviceInfo", e)
            return null
        }
    }

    /**
     * Get Storage IDs
     */
    suspend fun getStorageIds(): IntArray {
        val (resp, payload) = executeDataCommand(PtpConstants.OPERATION_GET_STORAGE_IDS)
        if (!resp.isOk || payload == null) return IntArray(0)
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val ids = PtpPacket.parseUInt32Array(bb)
        Log.i(PtpConstants.TAG, "Storage IDs: ${ids.joinToString { "0x" + it.toString(16) }}")
        return ids
    }

    /**
     * Get Object Handles with optional format filter.
     */
    suspend fun getObjectHandles(
        storageId: Int = PtpConstants.STORAGE_ALL,
        formatCode: Int = PtpConstants.FORMAT_ALL,
        parentHandle: Int = PtpConstants.PARENT_ALL
    ): IntArray {
        val (resp, payload) = executeDataCommand(
            PtpConstants.OPERATION_GET_OBJECT_HANDLES,
            storageId,
            formatCode,
            parentHandle
        )
        if (!resp.isOk || payload == null) {
            Log.w(PtpConstants.TAG, "GetObjectHandles failed: 0x${resp.responseCode.toString(16)}")
            return IntArray(0)
        }
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return PtpPacket.parseUInt32Array(bb)
    }

    /**
     * Get Object Info for a specific object handle.
     */
    suspend fun getObjectInfo(handle: Int): PtpObjectInfo? {
        val (resp, payload) = executeDataCommand(PtpConstants.OPERATION_GET_OBJECT_INFO, handle)
        if (!resp.isOk || payload == null) return null
        try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val storageId = bb.getInt()
            val format = bb.getShort().toInt() and 0xFFFF
            bb.getShort() // protectionStatus
            val compressedSize = bb.getInt().toLong() and 0xFFFFFFFFL
            val thumbFormat = bb.getShort().toInt() and 0xFFFF
            val thumbCompressedSize = bb.getInt()
            val thumbPixWidth = bb.getInt()
            val thumbPixHeight = bb.getInt()
            val imagePixWidth = bb.getInt()
            val imagePixHeight = bb.getInt()
            bb.getInt() // imageBitDepth
            bb.getInt() // parentObject
            bb.getShort() // associationType
            bb.getInt() // associationDesc
            bb.getInt() // sequenceNumber

            val filename = PtpPacket.parseString(bb)
            bb.position(bb.position()) // preserve position
            PtpPacket.parseString(bb) // dateCreated
            val dateModified = PtpPacket.parseString(bb)

            return PtpObjectInfo(
                handle = handle,
                storageId = storageId,
                format = format,
                compressedSize = compressedSize,
                thumbFormat = thumbFormat,
                thumbCompressedSize = thumbCompressedSize,
                thumbPixWidth = thumbPixWidth,
                thumbPixHeight = thumbPixHeight,
                imagePixWidth = imagePixWidth,
                imagePixHeight = imagePixHeight,
                filename = filename,
                dateModified = dateModified
            )
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Error parsing ObjectInfo for handle $handle", e)
            return null
        }
    }

    /**
     * Get Thumbnail for an image.
     * Prefers GetThumb operation, decoding as RGB_565 with bounds.
     */
    suspend fun getThumb(handle: Int, targetWidth: Int = 320): Bitmap? {
        val (resp, payload) = executeDataCommand(PtpConstants.OPERATION_GET_THUMB, handle)
        if (!resp.isOk || payload == null || payload.isEmpty()) return null

        return withContext(Dispatchers.Default) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(payload, 0, payload.size, opts)

                val sampleSize = calculateInSampleSize(opts, targetWidth, targetWidth)
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeByteArray(payload, 0, payload.size, decodeOpts)
            } catch (e: OutOfMemoryError) {
                Log.e(PtpConstants.TAG, "OOM decoding thumbnail for handle $handle", e)
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Get Partial Object data (e.g. for reading header or preview).
     */
    suspend fun getPartialObject(handle: Int, offset: Int, maxBytes: Int): ByteArray? {
        val (resp, payload) = executeDataCommand(
            PtpConstants.OPERATION_GET_PARTIAL_OBJECT,
            handle,
            offset,
            maxBytes
        )
        return if (resp.isOk) payload else null
    }

    companion object {
        fun calculateInSampleSize(
            options: BitmapFactory.Options,
            reqWidth: Int,
            reqHeight: Int
        ): Int {
            val (height: Int, width: Int) = options.outHeight to options.outWidth
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize.coerceAtLeast(1)
        }
    }
}

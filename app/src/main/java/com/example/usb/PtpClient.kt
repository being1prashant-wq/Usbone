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
    private val connection: UsbDeviceConnection?,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint
) {
    private val transactionCounter = AtomicInteger(1)
    private val mutex = Mutex()
    private val usbTimeout = 5000 // 5s timeout
    private var sessionId: Int = 1
    var isSessionOpen = false
        internal set

    var deviceInfo: PtpDeviceInfo? = null
        private set

    private fun nextTransactionId(): Int {
        val id = transactionCounter.getAndIncrement()
        if (id >= 0x7FFFFFFF) {
            transactionCounter.set(1)
        }
        return id
    }

    private fun safeBulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
        return try {
            val conn = connection ?: return -1
            conn.bulkTransfer(endpoint, buffer, length, timeout)
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "bulkTransfer exception", e)
            -1
        }
    }

    /**
     * Send command to Bulk OUT
     */
    private fun sendCommand(opCode: Int, transactionId: Int, vararg params: Int): Boolean {
        return try {
            val cmd = PtpPacket.buildCommand(opCode, transactionId, *params)
            val transferred = safeBulkTransfer(bulkOut, cmd, cmd.size, usbTimeout)
            if (transferred < 0) {
                Log.e(PtpConstants.TAG, "bulkTransfer OUT failed for op: 0x${opCode.toString(16)}")
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "sendCommand exception", e)
            false
        }
    }

    /**
     * Execute a command that does not return a Data container, only a Response container.
     */
    suspend fun executeSimpleCommand(opCode: Int, vararg params: Int): PtpResponse = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tid = nextTransactionId()
            try {
                if (!sendCommand(opCode, tid, *params)) {
                    return@withContext PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0))
                }
                readResponse(tid)
            } catch (e: Exception) {
                Log.e(PtpConstants.TAG, "executeSimpleCommand exception op=0x${opCode.toString(16)}", e)
                PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0))
            }
        }
    }

    /**
     * Read a response packet from bulkIn
     */
    private fun readResponse(expectedTid: Int): PtpResponse {
        val buf = ByteArray(1024)
        val read = safeBulkTransfer(bulkIn, buf, buf.size, usbTimeout)
        if (read < PtpConstants.HEADER_SIZE) {
            Log.e(PtpConstants.TAG, "readResponse: read $read bytes, expected >= ${PtpConstants.HEADER_SIZE}")
            return PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, expectedTid, IntArray(0))
        }
        return try {
            val byteBuffer = ByteBuffer.wrap(buf, 0, read).order(ByteOrder.LITTLE_ENDIAN)
            val header = PtpPacket.parseHeader(byteBuffer)
                ?: return PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, expectedTid, IntArray(0))
            if (header.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                val paramCount = ((read - PtpConstants.HEADER_SIZE) / 4).coerceAtMost(byteBuffer.remaining() / 4)
                val params = IntArray(paramCount)
                for (i in 0 until paramCount) {
                    params[i] = byteBuffer.getInt()
                }
                PtpResponse(header.code, header.transactionId, params)
            } else {
                Log.w(PtpConstants.TAG, "Unexpected container type in readResponse: ${header.type}")
                PtpResponse(header.code, header.transactionId, IntArray(0))
            }
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Exception parsing response", e)
            PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, expectedTid, IntArray(0))
        }
    }

    /**
     * Execute a command that returns a Data container, then a Response container.
     * Safely handles devices where the Data container and Response container arrive in the same USB packet.
     */
    suspend fun executeDataCommand(opCode: Int, vararg params: Int): Pair<PtpResponse, ByteArray?> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tid = nextTransactionId()
            try {
                if (!sendCommand(opCode, tid, *params)) {
                    return@withContext Pair(
                        PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)),
                        null
                    )
                }

                // Read initial chunk
                val initialBuffer = ByteArray(32768)
                val firstRead = safeBulkTransfer(bulkIn, initialBuffer, initialBuffer.size, usbTimeout)
                if (firstRead < PtpConstants.HEADER_SIZE) {
                    Log.e(PtpConstants.TAG, "executeDataCommand: first read too small ($firstRead bytes)")
                    return@withContext Pair(
                        PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)),
                        null
                    )
                }

                val bb = ByteBuffer.wrap(initialBuffer, 0, firstRead).order(ByteOrder.LITTLE_ENDIAN)
                val header = PtpPacket.parseHeader(bb)
                    ?: return@withContext Pair(
                        PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)),
                        null
                    )

                // If the device sent a Response container right away (e.g. error)
                if (header.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                    val paramCount = ((firstRead - PtpConstants.HEADER_SIZE) / 4).coerceAtMost(bb.remaining() / 4)
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

                val totalContainerLength = header.length
                if (totalContainerLength < PtpConstants.HEADER_SIZE) {
                    Log.e(PtpConstants.TAG, "Invalid totalContainerLength: $totalContainerLength")
                    return@withContext Pair(
                        PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)),
                        null
                    )
                }

                val payloadLength = totalContainerLength - PtpConstants.HEADER_SIZE
                // Cap in-memory data payload to 8MB to prevent TV OOM
                if (payloadLength > 8 * 1024 * 1024) {
                    Log.e(PtpConstants.TAG, "Payload exceeds 8MB cap: $payloadLength")
                    return@withContext Pair(
                        PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)),
                        null
                    )
                }

                val payload = ByteArray(payloadLength)
                val availablePayloadInFirstRead = (firstRead - PtpConstants.HEADER_SIZE).coerceAtLeast(0)
                val initialBytesToCopy = availablePayloadInFirstRead.coerceAtMost(payloadLength)
                if (initialBytesToCopy > 0) {
                    System.arraycopy(initialBuffer, PtpConstants.HEADER_SIZE, payload, 0, initialBytesToCopy)
                }

                var bytesRead = initialBytesToCopy
                val chunk = ByteArray(16384)
                while (bytesRead < payloadLength) {
                    val toRead = (payloadLength - bytesRead).coerceAtMost(chunk.size)
                    val readChunk = safeBulkTransfer(bulkIn, chunk, toRead, usbTimeout)
                    if (readChunk <= 0) {
                        Log.e(PtpConstants.TAG, "executeDataCommand: timeout reading payload ($bytesRead / $payloadLength)")
                        break
                    }
                    System.arraycopy(chunk, 0, payload, bytesRead, readChunk)
                    bytesRead += readChunk
                }

                // Check if the response container was already received in initialBuffer
                val leftoverInFirstRead = firstRead - totalContainerLength
                if (leftoverInFirstRead >= PtpConstants.HEADER_SIZE) {
                    val respBb = ByteBuffer.wrap(initialBuffer, totalContainerLength, leftoverInFirstRead)
                        .order(ByteOrder.LITTLE_ENDIAN)
                    val respHeader = PtpPacket.parseHeader(respBb)
                    if (respHeader != null && respHeader.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                        val pCount = ((leftoverInFirstRead - PtpConstants.HEADER_SIZE) / 4)
                            .coerceAtMost(respBb.remaining() / 4)
                        val respParams = IntArray(pCount)
                        for (i in 0 until pCount) {
                            respParams[i] = respBb.getInt()
                        }
                        return@withContext Pair(
                            PtpResponse(respHeader.code, respHeader.transactionId, respParams),
                            payload
                        )
                    }
                }

                // Otherwise read response container from Bulk IN
                val finalResp = readResponse(tid)
                Pair(finalResp, payload)
            } catch (e: Exception) {
                Log.e(PtpConstants.TAG, "Exception in executeDataCommand op=0x${opCode.toString(16)}", e)
                Pair(PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)), null)
            }
        }
    }

    /**
     * Stream an object directly to an OutputStream (e.g. temporary cache file).
     * Avoids loading large files into RAM.
     */
    suspend fun streamObject(handle: Int, outputStream: OutputStream): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tid = nextTransactionId()
            try {
                if (!sendCommand(PtpConstants.OPERATION_GET_OBJECT, tid, handle)) {
                    return@withContext false
                }

                val buffer = ByteArray(16384)
                val firstRead = safeBulkTransfer(bulkIn, buffer, buffer.size, usbTimeout)
                if (firstRead < PtpConstants.HEADER_SIZE) return@withContext false

                val bb = ByteBuffer.wrap(buffer, 0, firstRead).order(ByteOrder.LITTLE_ENDIAN)
                val header = PtpPacket.parseHeader(bb) ?: return@withContext false

                if (header.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                    Log.e(PtpConstants.TAG, "streamObject error response code: 0x${header.code.toString(16)}")
                    return@withContext false
                }
                if (header.type != PtpConstants.CONTAINER_TYPE_DATA) return@withContext false

                val totalPayload = header.length - PtpConstants.HEADER_SIZE
                if (totalPayload < 0) return@withContext false

                var written = 0
                val initialBytes = firstRead - PtpConstants.HEADER_SIZE
                if (initialBytes > 0) {
                    val toWrite = initialBytes.coerceAtMost(totalPayload)
                    outputStream.write(buffer, PtpConstants.HEADER_SIZE, toWrite)
                    written += toWrite
                }

                while (written < totalPayload) {
                    val toRead = (totalPayload - written).coerceAtMost(buffer.size)
                    val r = safeBulkTransfer(bulkIn, buffer, toRead, usbTimeout)
                    if (r <= 0) {
                        Log.e(PtpConstants.TAG, "streamObject: transfer halted at $written / $totalPayload bytes")
                        return@withContext false
                    }
                    outputStream.write(buffer, 0, r)
                    written += r
                }

                outputStream.flush()

                // If response container was in the first read after data
                val leftover = firstRead - header.length
                if (leftover >= PtpConstants.HEADER_SIZE) {
                    val respBb = ByteBuffer.wrap(buffer, header.length, leftover).order(ByteOrder.LITTLE_ENDIAN)
                    val respHeader = PtpPacket.parseHeader(respBb)
                    if (respHeader != null && respHeader.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                        return@withContext (respHeader.code == PtpConstants.RESPONSE_OK)
                    }
                }

                val resp = readResponse(tid)
                resp.isOk
            } catch (e: Exception) {
                Log.e(PtpConstants.TAG, "Exception streaming object $handle", e)
                false
            }
        }
    }

    /**
     * Open PTP Session
     */
    suspend fun openSession(): Boolean {
        return try {
            sessionId = 1
            val resp = executeSimpleCommand(PtpConstants.OPERATION_OPEN_SESSION, sessionId)
            if (resp.isOk || resp.responseCode == PtpConstants.RESPONSE_SESSION_ALREADY_OPEN) {
                isSessionOpen = true
                Log.i(PtpConstants.TAG, "PTP session opened successfully")
                true
            } else {
                Log.e(PtpConstants.TAG, "OpenSession failed: 0x${resp.responseCode.toString(16)}")
                false
            }
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "openSession exception", e)
            false
        }
    }

    /**
     * Close PTP Session
     */
    suspend fun closeSession(): Boolean {
        if (!isSessionOpen) return true
        return try {
            val resp = executeSimpleCommand(PtpConstants.OPERATION_CLOSE_SESSION)
            isSessionOpen = false
            Log.i(PtpConstants.TAG, "PTP session closed")
            resp.isOk
        } catch (e: Exception) {
            isSessionOpen = false
            false
        }
    }

    /**
     * Get Device Info
     */
    suspend fun getDeviceInfo(): PtpDeviceInfo? {
        val (resp, payload) = executeDataCommand(PtpConstants.OPERATION_GET_DEVICE_INFO)
        if (!resp.isOk || payload == null || payload.isEmpty()) {
            Log.w(PtpConstants.TAG, "GetDeviceInfo failed: 0x${resp.responseCode.toString(16)}")
            return null
        }
        return try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.remaining() < 8) return null
            bb.getShort() // StandardVersion
            bb.getInt() // VendorExtensionId
            bb.getShort() // VendorExtensionVersion
            PtpPacket.parseString(bb) // VendorExtensionDesc
            if (bb.remaining() < 2) return null
            bb.getShort() // FunctionalMode

            // Operations, Events, DeviceProperties, CaptureFormats, and PlaybackFormats are UINT16 arrays in PTP ISO 15740
            val ops = PtpPacket.parseUInt16Array(bb).toSet()
            PtpPacket.parseUInt16Array(bb) // EventsSupported
            PtpPacket.parseUInt16Array(bb) // DevicePropertiesSupported
            PtpPacket.parseUInt16Array(bb) // CaptureFormats
            PtpPacket.parseUInt16Array(bb) // PlaybackFormats

            val manufacturer = PtpPacket.parseString(bb)
            val model = PtpPacket.parseString(bb)
            val deviceVersion = PtpPacket.parseString(bb)
            val serialNumber = PtpPacket.parseString(bb)

            val info = PtpDeviceInfo(manufacturer, model, deviceVersion, serialNumber, ops)
            deviceInfo = info
            Log.i(PtpConstants.TAG, "PTP DeviceInfo: manufacturer='$manufacturer', model='$model'")
            info
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Failed to parse DeviceInfo", e)
            null
        }
    }

    /**
     * Get Storage IDs
     */
    suspend fun getStorageIds(): IntArray {
        val (resp, payload) = executeDataCommand(PtpConstants.OPERATION_GET_STORAGE_IDS)
        if (!resp.isOk || payload == null || payload.isEmpty()) return IntArray(0)
        return try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val ids = PtpPacket.parseUInt32Array(bb)
            Log.i(PtpConstants.TAG, "Storage IDs: ${ids.joinToString { "0x" + it.toString(16) }}")
            ids
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Failed to parse Storage IDs", e)
            IntArray(0)
        }
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
        if (!resp.isOk || payload == null || payload.isEmpty()) {
            Log.w(PtpConstants.TAG, "GetObjectHandles failed: 0x${resp.responseCode.toString(16)}")
            return IntArray(0)
        }
        return try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            PtpPacket.parseUInt32Array(bb)
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Failed to parse ObjectHandles", e)
            IntArray(0)
        }
    }

    /**
     * Get Object Info for a specific object handle.
     */
    suspend fun getObjectInfo(handle: Int): PtpObjectInfo? {
        val (resp, payload) = executeDataCommand(PtpConstants.OPERATION_GET_OBJECT_INFO, handle)
        if (!resp.isOk || payload == null || payload.isEmpty()) return null
        return try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.remaining() < 52) return null
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
            PtpPacket.parseString(bb) // dateCreated
            val dateModified = PtpPacket.parseString(bb)

            PtpObjectInfo(
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
            null
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

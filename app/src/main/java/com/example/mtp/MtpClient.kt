package com.example.mtp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class MtpClient(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) {
    private val tag = "MtpClient"
    private val transactionCounter = AtomicInteger(1)
    private val lock = Any()

    var sessionId: Int = 0
        private set

    val isSessionOpen: Boolean
        get() = sessionId != 0

    private val inMaxPacketSize = endpointIn.maxPacketSize.coerceAtLeast(512)
    private val readBuffer = ByteArray(64 * 1024) // 64KB read buffer

    fun openSession(targetSessionId: Int = 1): Boolean = synchronized(lock) {
        val txId = transactionCounter.getAndIncrement()
        val cmd = MtpPacket.buildCommandPacket(
            MtpConstants.OPERATION_OPEN_SESSION,
            txId,
            intArrayOf(targetSessionId)
        )

        val writeResult = connection.bulkTransfer(endpointOut, cmd, cmd.size, 3000)
        if (writeResult < 0) {
            Log.e(tag, "Failed to send OpenSession command, writeResult=$writeResult")
            return false
        }

        val resp = readResponsePacket(txId, 3000)
        if (resp != null && (resp.responseCode == MtpConstants.RESPONSE_OK || resp.responseCode == MtpConstants.RESPONSE_SESSION_ALREADY_OPEN)) {
            sessionId = targetSessionId
            Log.d(tag, "MTP Session $sessionId opened successfully")
            return true
        } else {
            Log.e(tag, "OpenSession failed with response: ${resp?.responseCode}")
            return false
        }
    }

    fun closeSession(): Boolean = synchronized(lock) {
        if (!isSessionOpen) return true
        val txId = transactionCounter.getAndIncrement()
        val cmd = MtpPacket.buildCommandPacket(MtpConstants.OPERATION_CLOSE_SESSION, txId)
        connection.bulkTransfer(endpointOut, cmd, cmd.size, 2000)
        val resp = readResponsePacket(txId, 2000)
        sessionId = 0
        return resp?.responseCode == MtpConstants.RESPONSE_OK
    }

    fun getDeviceInfo(): MtpDeviceInfo? = synchronized(lock) {
        val txId = transactionCounter.getAndIncrement()
        val cmd = MtpPacket.buildCommandPacket(MtpConstants.OPERATION_GET_DEVICE_INFO, txId)
        val data = executeDataCommand(cmd, txId, 4000) ?: return null
        return try {
            MtpPacket.parseDeviceInfo(data)
        } catch (e: Exception) {
            Log.e(tag, "Error parsing DeviceInfo", e)
            null
        }
    }

    fun getStorageIds(): List<Int> = synchronized(lock) {
        val txId = transactionCounter.getAndIncrement()
        val cmd = MtpPacket.buildCommandPacket(MtpConstants.OPERATION_GET_STORAGE_IDS, txId)
        val data = executeDataCommand(cmd, txId, 4000) ?: return emptyList()
        return try {
            val reader = MtpDataReader(data)
            reader.readUInt32Array()
        } catch (e: Exception) {
            Log.e(tag, "Error parsing StorageIDs", e)
            emptyList()
        }
    }

    fun getStorageInfo(storageId: Int): MtpStorageInfo? = synchronized(lock) {
        val txId = transactionCounter.getAndIncrement()
        val cmd = MtpPacket.buildCommandPacket(
            MtpConstants.OPERATION_GET_STORAGE_INFO,
            txId,
            intArrayOf(storageId)
        )
        val data = executeDataCommand(cmd, txId, 4000) ?: return null
        return try {
            MtpPacket.parseStorageInfo(storageId, data)
        } catch (e: Exception) {
            Log.e(tag, "Error parsing StorageInfo for $storageId", e)
            null
        }
    }

    fun getNumObjects(storageId: Int, format: Int = 0, parentHandle: Int = -1): Int = synchronized(lock) {
        val txId = transactionCounter.getAndIncrement()
        val cmd = MtpPacket.buildCommandPacket(
            MtpConstants.OPERATION_GET_NUM_OBJECTS,
            txId,
            intArrayOf(storageId, format, parentHandle)
        )
        connection.bulkTransfer(endpointOut, cmd, cmd.size, 3000)
        val resp = readResponsePacket(txId, 3000)
        if (resp != null && resp.responseCode == MtpConstants.RESPONSE_OK && resp.params.isNotEmpty()) {
            return resp.params[0]
        }
        return -1
    }

    fun getObjectHandles(storageId: Int, format: Int = 0, parentHandle: Int = -1): List<Int> = synchronized(lock) {
        val txId = transactionCounter.getAndIncrement()
        val cmd = MtpPacket.buildCommandPacket(
            MtpConstants.OPERATION_GET_OBJECT_HANDLES,
            txId,
            intArrayOf(storageId, format, parentHandle)
        )
        val data = executeDataCommand(cmd, txId, 6000) ?: return emptyList()
        return try {
            val reader = MtpDataReader(data)
            reader.readUInt32Array()
        } catch (e: Exception) {
            Log.e(tag, "Error parsing ObjectHandles", e)
            emptyList()
        }
    }

    fun getObjectInfo(objectHandle: Int): MtpObjectInfo? = synchronized(lock) {
        val txId = transactionCounter.getAndIncrement()
        val cmd = MtpPacket.buildCommandPacket(
            MtpConstants.OPERATION_GET_OBJECT_INFO,
            txId,
            intArrayOf(objectHandle)
        )
        val data = executeDataCommand(cmd, txId, 4000) ?: return null
        return try {
            MtpPacket.parseObjectInfo(objectHandle, data)
        } catch (e: Exception) {
            Log.e(tag, "Error parsing ObjectInfo for handle $objectHandle", e)
            null
        }
    }

    fun getThumb(objectHandle: Int): ByteArray? = synchronized(lock) {
        val txId = transactionCounter.getAndIncrement()
        val cmd = MtpPacket.buildCommandPacket(
            MtpConstants.OPERATION_GET_THUMB,
            txId,
            intArrayOf(objectHandle)
        )
        return executeDataCommand(cmd, txId, 5000)
    }

    fun getPartialObject(objectHandle: Int, offset: Long, maxBytes: Int): ByteArray? = synchronized(lock) {
        val txId = transactionCounter.getAndIncrement()
        val cmd = MtpPacket.buildCommandPacket(
            MtpConstants.OPERATION_GET_PARTIAL_OBJECT,
            txId,
            intArrayOf(objectHandle, offset.toInt(), maxBytes)
        )
        return executeDataCommand(cmd, txId, 6000)
    }

    fun getPartialObject64(objectHandle: Int, offset: Long, maxBytes: Int): ByteArray? = synchronized(lock) {
        val txId = transactionCounter.getAndIncrement()
        val offsetLow = (offset and 0xFFFFFFFFL).toInt()
        val offsetHigh = (offset ushr 32).toInt()
        val cmd = MtpPacket.buildCommandPacket(
            MtpConstants.OPERATION_GET_PARTIAL_OBJECT_64,
            txId,
            intArrayOf(objectHandle, offsetLow, offsetHigh, maxBytes)
        )
        return executeDataCommand(cmd, txId, 6000)
    }

    fun readPartial(objectHandle: Int, offset: Long, length: Int, supports64: Boolean): ByteArray? {
        if (supports64 || offset > 0x7FFFFFFFL) {
            val res = getPartialObject64(objectHandle, offset, length)
            if (res != null) return res
        }
        return getPartialObject(objectHandle, offset, length)
    }

    fun getObject(
        objectHandle: Int,
        outputStream: OutputStream,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Boolean = synchronized(lock) {
        val txId = transactionCounter.getAndIncrement()
        val cmd = MtpPacket.buildCommandPacket(
            MtpConstants.OPERATION_GET_OBJECT,
            txId,
            intArrayOf(objectHandle)
        )

        val writeResult = connection.bulkTransfer(endpointOut, cmd, cmd.size, 3000)
        if (writeResult < 0) return false

        // Read initial data packet
        val readBytes = connection.bulkTransfer(endpointIn, readBuffer, readBuffer.size, 8000)
        if (readBytes < 12) return false

        val headerBuffer = ByteBuffer.wrap(readBuffer, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val containerLength = headerBuffer.getInt().toLong() and 0xFFFFFFFFL
        val containerType = headerBuffer.getShort()
        val code = headerBuffer.getShort().toInt() and 0xFFFF
        val respTxId = headerBuffer.getInt()

        if (containerType == MtpConstants.CONTAINER_TYPE_RESPONSE) {
            Log.e(tag, "GetObject directly returned response error: $code")
            return false
        }

        if (containerType != MtpConstants.CONTAINER_TYPE_DATA) {
            Log.e(tag, "Expected DATA container, got $containerType")
            return false
        }

        val totalDataLength = if (containerLength == 0xFFFFFFFFL) -1L else (containerLength - 12)
        var receivedDataBytes = (readBytes - 12).toLong()

        if (readBytes > 12) {
            outputStream.write(readBuffer, 12, readBytes - 12)
        }
        onProgress?.invoke(receivedDataBytes, totalDataLength)

        // Read remaining payload bytes
        while (totalDataLength == -1L || receivedDataBytes < totalDataLength) {
            val chunk = connection.bulkTransfer(endpointIn, readBuffer, readBuffer.size, 10000)
            if (chunk <= 0) break
            outputStream.write(readBuffer, 0, chunk)
            receivedDataBytes += chunk
            onProgress?.invoke(receivedDataBytes, totalDataLength)
            if (totalDataLength == -1L && chunk < inMaxPacketSize) {
                // Short packet signifies end of stream
                break
            }
        }

        // Finally read response container
        val resp = readResponsePacket(txId, 4000)
        return resp?.responseCode == MtpConstants.RESPONSE_OK
    }

    private fun executeDataCommand(
        cmd: ByteArray,
        expectedTxId: Int,
        timeoutMs: Int
    ): ByteArray? {
        val writeResult = connection.bulkTransfer(endpointOut, cmd, cmd.size, timeoutMs)
        if (writeResult < 0) {
            Log.e(tag, "Failed to write command $cmd, writeResult=$writeResult")
            return null
        }

        // Read first packet
        val readBytes = connection.bulkTransfer(endpointIn, readBuffer, readBuffer.size, timeoutMs)
        if (readBytes < 12) {
            Log.e(tag, "Too short packet received: $readBytes bytes")
            return null
        }

        val headerBuffer = ByteBuffer.wrap(readBuffer, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val containerLength = headerBuffer.getInt().toLong() and 0xFFFFFFFFL
        val containerType = headerBuffer.getShort()
        val code = headerBuffer.getShort().toInt() and 0xFFFF
        val respTxId = headerBuffer.getInt()

        if (containerType == MtpConstants.CONTAINER_TYPE_RESPONSE) {
            Log.w(tag, "Command returned RESPONSE instead of DATA. code=$code, txId=$respTxId")
            return null
        }

        if (containerType != MtpConstants.CONTAINER_TYPE_DATA) {
            Log.e(tag, "Unexpected container type: $containerType")
            return null
        }

        val totalDataLength = (containerLength - 12).toInt()
        val outStream = ByteArrayOutputStream(totalDataLength.coerceAtLeast(1024))

        val initialPayloadSize = readBytes - 12
        if (initialPayloadSize > 0) {
            val bytesToWrite = initialPayloadSize.coerceAtMost(totalDataLength)
            outStream.write(readBuffer, 12, bytesToWrite)
        }

        var bytesReceived = initialPayloadSize
        while (bytesReceived < totalDataLength) {
            val needed = totalDataLength - bytesReceived
            val toRead = needed.coerceAtMost(readBuffer.size)
            val chunk = connection.bulkTransfer(endpointIn, readBuffer, toRead, timeoutMs)
            if (chunk <= 0) {
                Log.e(tag, "Short read while fetching payload: read $chunk, needed $needed")
                break
            }
            outStream.write(readBuffer, 0, chunk)
            bytesReceived += chunk
        }

        // After DATA, read the trailing RESPONSE packet
        val resp = readResponsePacket(expectedTxId, timeoutMs)
        if (resp == null || resp.responseCode != MtpConstants.RESPONSE_OK) {
            Log.w(tag, "Trailing response was not OK: ${resp?.responseCode}")
        }

        return outStream.toByteArray()
    }

    private data class ResponsePacket(
        val responseCode: Int,
        val transactionId: Int,
        val params: List<Int>
    )

    private fun readResponsePacket(expectedTxId: Int, timeoutMs: Int): ResponsePacket? {
        val respBuffer = ByteArray(512)
        val readBytes = connection.bulkTransfer(endpointIn, respBuffer, respBuffer.size, timeoutMs)
        if (readBytes < 12) return null

        val bb = ByteBuffer.wrap(respBuffer, 0, readBytes).order(ByteOrder.LITTLE_ENDIAN)
        val length = bb.getInt()
        val containerType = bb.getShort()
        val responseCode = bb.getShort().toInt() and 0xFFFF
        val txId = bb.getInt()

        if (containerType != MtpConstants.CONTAINER_TYPE_RESPONSE) {
            Log.w(tag, "readResponsePacket: expected response container, got $containerType")
            return null
        }

        val paramsCount = (length - 12) / 4
        val params = mutableListOf<Int>()
        for (i in 0 until paramsCount) {
            if (bb.remaining() >= 4) {
                params.add(bb.getInt())
            }
        }

        return ResponsePacket(responseCode, txId, params)
    }
}

package com.example.mtp

import java.nio.ByteBuffer
import java.nio.ByteOrder

class MtpDataReader(private val buffer: ByteArray, startOffset: Int = 0) {
    private var offset = startOffset

    val remaining: Int
        get() = (buffer.size - offset).coerceAtLeast(0)

    fun readUInt8(): Int {
        if (offset >= buffer.size) return 0
        return buffer[offset++].toInt() and 0xFF
    }

    fun readUInt16(): Int {
        if (offset + 2 > buffer.size) return 0
        val v0 = buffer[offset++].toInt() and 0xFF
        val v1 = buffer[offset++].toInt() and 0xFF
        return (v1 shl 8) or v0
    }

    fun readUInt32(): Long {
        if (offset + 4 > buffer.size) return 0L
        val v0 = buffer[offset++].toLong() and 0xFFL
        val v1 = buffer[offset++].toLong() and 0xFFL
        val v2 = buffer[offset++].toLong() and 0xFFL
        val v3 = buffer[offset++].toLong() and 0xFFL
        return (v3 shl 24) or (v2 shl 16) or (v1 shl 8) or v0
    }

    fun readInt32(): Int {
        return readUInt32().toInt()
    }

    fun readUInt64(): Long {
        val low = readUInt32()
        val high = readUInt32()
        return (high shl 32) or (low and 0xFFFFFFFFL)
    }

    fun readPtpString(): String {
        val numChars = readUInt8()
        if (numChars == 0) return ""
        val byteLen = numChars * 2
        if (offset + byteLen > buffer.size) return ""
        val strBytes = buffer.copyOfRange(offset, offset + byteLen)
        offset += byteLen
        // Decode UTF-16LE, remove trailing null terminator if present
        val str = String(strBytes, Charsets.UTF_16LE)
        return str.trimEnd('\u0000')
    }

    fun readUInt16Array(): Set<Int> {
        val count = readUInt32().toInt()
        val set = mutableSetOf<Int>()
        for (i in 0 until count) {
            if (offset + 2 > buffer.size) break
            set.add(readUInt16())
        }
        return set
    }

    fun readUInt32Array(): List<Int> {
        val count = readUInt32().toInt()
        val list = mutableListOf<Int>()
        for (i in 0 until count) {
            if (offset + 4 > buffer.size) break
            list.add(readInt32())
        }
        return list
    }

    fun skip(bytes: Int) {
        offset = (offset + bytes).coerceAtMost(buffer.size)
    }
}

object MtpPacket {
    fun buildCommandPacket(
        operationCode: Int,
        transactionId: Int,
        params: IntArray = intArrayOf()
    ): ByteArray {
        val length = 12 + params.size * 4
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(length)
        buffer.putShort(MtpConstants.CONTAINER_TYPE_COMMAND)
        buffer.putShort(operationCode.toShort())
        buffer.putInt(transactionId)
        for (param in params) {
            buffer.putInt(param)
        }
        return buffer.array()
    }

    fun parseDeviceInfo(payload: ByteArray): MtpDeviceInfo {
        val reader = MtpDataReader(payload)
        val standardVersion = reader.readUInt16()
        val vendorExtensionId = reader.readUInt32()
        val mtpVersion = reader.readUInt16()
        val extensions = reader.readPtpString()
        val functionalMode = reader.readUInt16()
        val operationsSupported = reader.readUInt16Array()
        val eventsSupported = reader.readUInt16Array()
        val devicePropertiesSupported = reader.readUInt16Array()
        val captureFormats = reader.readUInt16Array()
        val playbackFormats = reader.readUInt16Array()
        val manufacturer = reader.readPtpString()
        val model = reader.readPtpString()
        val deviceVersion = reader.readPtpString()
        val serialNumber = reader.readPtpString()

        return MtpDeviceInfo(
            standardVersion = standardVersion,
            vendorExtensionId = vendorExtensionId,
            mtpVersion = mtpVersion,
            extensions = extensions,
            functionalMode = functionalMode,
            operationsSupported = operationsSupported,
            eventsSupported = eventsSupported,
            devicePropertiesSupported = devicePropertiesSupported,
            captureFormats = captureFormats,
            playbackFormats = playbackFormats,
            manufacturer = manufacturer,
            model = model,
            deviceVersion = deviceVersion,
            serialNumber = serialNumber
        )
    }

    fun parseStorageInfo(storageId: Int, payload: ByteArray): MtpStorageInfo {
        val reader = MtpDataReader(payload)
        val storageType = reader.readUInt16()
        val filesystemType = reader.readUInt16()
        val accessCapability = reader.readUInt16()
        val maxCapacity = reader.readUInt64()
        val freeSpaceInBytes = reader.readUInt64()
        val freeSpaceInObjects = reader.readUInt32()
        val storageDescription = reader.readPtpString()
        val volumeIdentifier = reader.readPtpString()

        return MtpStorageInfo(
            storageId = storageId,
            storageType = storageType,
            filesystemType = filesystemType,
            accessCapability = accessCapability,
            maxCapacity = maxCapacity,
            freeSpaceInBytes = freeSpaceInBytes,
            freeSpaceInObjects = freeSpaceInObjects,
            storageDescription = storageDescription,
            volumeIdentifier = volumeIdentifier
        )
    }

    fun parseObjectInfo(objectHandle: Int, payload: ByteArray): MtpObjectInfo {
        val reader = MtpDataReader(payload)
        val storageId = reader.readInt32()
        val objectFormat = reader.readUInt16()
        val protectionStatus = reader.readUInt16()
        val compressedSize = reader.readUInt32()
        val thumbFormat = reader.readUInt16()
        val thumbCompressedSize = reader.readUInt32()
        val thumbPixWidth = reader.readUInt32()
        val thumbPixHeight = reader.readUInt32()
        val imagePixWidth = reader.readUInt32()
        val imagePixHeight = reader.readUInt32()
        val imageBitDepth = reader.readUInt32()
        val parentObject = reader.readInt32()
        val associationType = reader.readUInt16()
        val associationDesc = reader.readUInt32()
        val sequenceNumber = reader.readUInt32()
        val filename = reader.readPtpString()
        val captureDate = reader.readPtpString()
        val modificationDate = reader.readPtpString()

        return MtpObjectInfo(
            objectHandle = objectHandle,
            storageId = storageId,
            objectFormat = objectFormat,
            protectionStatus = protectionStatus,
            compressedSize = compressedSize,
            thumbFormat = thumbFormat,
            thumbCompressedSize = thumbCompressedSize,
            thumbPixWidth = thumbPixWidth,
            thumbPixHeight = thumbPixHeight,
            imagePixWidth = imagePixWidth,
            imagePixHeight = imagePixHeight,
            imageBitDepth = imageBitDepth,
            parentObject = parentObject,
            associationType = associationType,
            associationDesc = associationDesc,
            sequenceNumber = sequenceNumber,
            filename = filename,
            captureDate = captureDate,
            modificationDate = modificationDate
        )
    }
}

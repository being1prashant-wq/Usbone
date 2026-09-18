package com.example.mtp

data class MtpDeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Long,
    val mtpVersion: Int,
    val extensions: String,
    val functionalMode: Int,
    val operationsSupported: Set<Int>,
    val eventsSupported: Set<Int>,
    val devicePropertiesSupported: Set<Int>,
    val captureFormats: Set<Int>,
    val playbackFormats: Set<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String
) {
    val supportsPartialObject: Boolean
        get() = operationsSupported.contains(MtpConstants.OPERATION_GET_PARTIAL_OBJECT)

    val supportsPartialObject64: Boolean
        get() = operationsSupported.contains(MtpConstants.OPERATION_GET_PARTIAL_OBJECT_64)

    val displayName: String
        get() = if (manufacturer.isNotBlank() && model.isNotBlank()) {
            if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        } else if (model.isNotBlank()) {
            model
        } else if (manufacturer.isNotBlank()) {
            manufacturer
        } else {
            "Android Phone"
        }
}

data class MtpStorageInfo(
    val storageId: Int,
    val storageType: Int,
    val filesystemType: Int,
    val accessCapability: Int,
    val maxCapacity: Long,
    val freeSpaceInBytes: Long,
    val freeSpaceInObjects: Long,
    val storageDescription: String,
    val volumeIdentifier: String
) {
    val isRemovable: Boolean
        get() = storageType == 2 || storageType == 4

    val displayTitle: String
        get() = when {
            storageDescription.isNotBlank() -> storageDescription
            isRemovable -> "SD Card"
            else -> "Phone Storage"
        }
}

data class MtpObjectInfo(
    val objectHandle: Int,
    val storageId: Int,
    val objectFormat: Int,
    val protectionStatus: Int,
    val compressedSize: Long,
    val thumbFormat: Int,
    val thumbCompressedSize: Long,
    val thumbPixWidth: Long,
    val thumbPixHeight: Long,
    val imagePixWidth: Long,
    val imagePixHeight: Long,
    val imageBitDepth: Long,
    val parentObject: Int,
    val associationType: Int,
    val associationDesc: Long,
    val sequenceNumber: Long,
    val filename: String,
    val captureDate: String,
    val modificationDate: String
) {
    val isFolder: Boolean
        get() = objectFormat == MtpConstants.FORMAT_ASSOCIATION
}

package com.example.usb

object PtpConstants {
    const val TAG = "DirectUSB-PTP"

    // Container Types
    const val CONTAINER_TYPE_UNDEFINED: Short = 0
    const val CONTAINER_TYPE_COMMAND: Short = 1
    const val CONTAINER_TYPE_DATA: Short = 2
    const val CONTAINER_TYPE_RESPONSE: Short = 3
    const val CONTAINER_TYPE_EVENT: Short = 4

    // Header size in bytes
    const val HEADER_SIZE = 12

    // Operations
    const val OPERATION_GET_DEVICE_INFO = 0x1001
    const val OPERATION_OPEN_SESSION = 0x1002
    const val OPERATION_CLOSE_SESSION = 0x1003
    const val OPERATION_GET_STORAGE_IDS = 0x1004
    const val OPERATION_GET_STORAGE_INFO = 0x1005
    const val OPERATION_GET_NUM_OBJECTS = 0x1006
    const val OPERATION_GET_OBJECT_HANDLES = 0x1007
    const val OPERATION_GET_OBJECT_INFO = 0x1008
    const val OPERATION_GET_OBJECT = 0x1009
    const val OPERATION_GET_THUMB = 0x100A
    const val OPERATION_GET_PARTIAL_OBJECT = 0x101B
    const val OPERATION_GET_PARTIAL_OBJECT_64 = 0x95C1

    // Response Codes
    const val RESPONSE_OK = 0x2001
    const val RESPONSE_GENERAL_ERROR = 0x2002
    const val RESPONSE_SESSION_NOT_OPEN = 0x2003
    const val RESPONSE_INVALID_TRANSACTION_ID = 0x2004
    const val RESPONSE_OPERATION_NOT_SUPPORTED = 0x2005
    const val RESPONSE_PARAMETER_NOT_SUPPORTED = 0x2006
    const val RESPONSE_INCOMPLETE_TRANSFER = 0x2007
    const val RESPONSE_INVALID_STORAGE_ID = 0x2008
    const val RESPONSE_INVALID_OBJECT_HANDLE = 0x2009
    const val RESPONSE_ACCESS_DENIED = 0x200F
    const val RESPONSE_NO_THUMBNAIL_PRESENT = 0x2010
    const val RESPONSE_SPECIFICATION_BY_FORMAT_UNSUPPORTED = 0x2014
    const val RESPONSE_DEVICE_BUSY = 0x2019
    const val RESPONSE_SESSION_ALREADY_OPEN = 0x201E

    // Special IDs
    const val STORAGE_ALL = -1 // 0xFFFFFFFF
    const val FORMAT_ALL = 0x00000000
    const val PARENT_ALL = 0x00000000

    // Formats - Images
    const val FORMAT_EXIF_JPEG = 0x3801
    const val FORMAT_TIFF_EP = 0x3802
    const val FORMAT_BMP = 0x3804
    const val FORMAT_GIF = 0x3807
    const val FORMAT_JFIF = 0x3808
    const val FORMAT_PNG = 0x380B
    const val FORMAT_TIFF = 0x380D
    const val FORMAT_JP2 = 0xB881
    const val FORMAT_HEIF = 0xB802
    const val FORMAT_WEBP = 0xB803

    // Formats - Videos
    const val FORMAT_AVI = 0x300A
    const val FORMAT_MPEG = 0x300B
    const val FORMAT_ASF = 0x300C
    const val FORMAT_WMV = 0xB981
    const val FORMAT_MP4 = 0xB982
    const val FORMAT_3GP = 0xB983
    const val FORMAT_3G2 = 0xB984
    const val FORMAT_MOV = 0xB988
    const val FORMAT_MKV = 0xBA05
    const val FORMAT_WEBM = 0xBA82

    val IMAGE_FORMATS = setOf(
        FORMAT_EXIF_JPEG,
        FORMAT_TIFF_EP,
        FORMAT_BMP,
        FORMAT_GIF,
        FORMAT_JFIF,
        FORMAT_PNG,
        FORMAT_TIFF,
        FORMAT_JP2,
        FORMAT_HEIF,
        FORMAT_WEBP
    )

    val VIDEO_FORMATS = setOf(
        FORMAT_AVI,
        FORMAT_MPEG,
        FORMAT_ASF,
        FORMAT_WMV,
        FORMAT_MP4,
        FORMAT_3GP,
        FORMAT_3G2,
        FORMAT_MOV,
        FORMAT_MKV,
        FORMAT_WEBM
    )

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tif", "tiff"
    )

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "mov", "avi", "3gp", "webm", "ts", "m4v", "wmv", "mpg", "mpeg"
    )

    fun isImageFormat(format: Int): Boolean = format in IMAGE_FORMATS

    fun isVideoFormat(format: Int): Boolean = format in VIDEO_FORMATS

    fun isImageExtension(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    fun isVideoExtension(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }
}

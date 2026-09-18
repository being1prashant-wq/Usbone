package com.example.mtp

object MtpConstants {
    // Container Types
    const val CONTAINER_TYPE_UNDEFINED: Short = 0
    const val CONTAINER_TYPE_COMMAND: Short = 1
    const val CONTAINER_TYPE_DATA: Short = 2
    const val CONTAINER_TYPE_RESPONSE: Short = 3
    const val CONTAINER_TYPE_EVENT: Short = 4

    // Operation Codes
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
    const val OPERATION_GET_OBJECT_PROPS_SUPPORTED = 0x9801
    const val OPERATION_GET_OBJECT_PROP_DESC = 0x9802
    const val OPERATION_GET_OBJECT_PROP_VALUE = 0x9803
    const val OPERATION_GET_OBJECT_PROP_LIST = 0x9806

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
    const val RESPONSE_DEVICE_PROP_NOT_SUPPORTED = 0x200A
    const val RESPONSE_INVALID_OBJECT_FORMAT_CODE = 0x200B
    const val RESPONSE_STORE_FULL = 0x200C
    const val RESPONSE_OBJECT_WRITE_PROTECTED = 0x200D
    const val RESPONSE_STORE_READ_ONLY = 0x200E
    const val RESPONSE_ACCESS_DENIED = 0x200F
    const val RESPONSE_NO_THUMBNAIL_PRESENT = 0x2010
    const val RESPONSE_DEVICE_BUSY = 0x2017
    const val RESPONSE_INVALID_PARENT_OBJECT = 0x2018
    const val RESPONSE_SESSION_ALREADY_OPEN = 0x201E

    // Object Format Codes
    const val FORMAT_UNDEFINED = 0x3000
    const val FORMAT_ASSOCIATION = 0x3001 // Folder / Directory
    const val FORMAT_TEXT = 0x3004
    const val FORMAT_HTML = 0x3005
    const val FORMAT_WAV = 0x3008
    const val FORMAT_MP3 = 0x3009
    const val FORMAT_AVI = 0x300A
    const val FORMAT_MPEG = 0x300B
    const val FORMAT_ASF = 0x300C
    const val FORMAT_JPEG = 0x3801
    const val FORMAT_TIFF = 0x3802
    const val FORMAT_BMP = 0x3804
    const val FORMAT_GIF = 0x3807
    const val FORMAT_PNG = 0x380B
    const val FORMAT_AAC = 0xB903
    const val FORMAT_FLAC = 0xB906
    const val FORMAT_OGG = 0xB902
    const val FORMAT_WMA = 0xB901
    const val FORMAT_MP4_CONTAINER = 0xBA05
    const val FORMAT_3GP_CONTAINER = 0xBA07
    const val FORMAT_WMV = 0xBA81

    // Object Property Codes
    const val PROPERTY_STORAGE_ID = 0xDC01
    const val PROPERTY_OBJECT_FORMAT = 0xDC02
    const val PROPERTY_PROTECTION_STATUS = 0xDC03
    const val PROPERTY_OBJECT_SIZE = 0xDC04
    const val PROPERTY_OBJECT_FILE_NAME = 0xDC07
    const val PROPERTY_DATE_MODIFIED = 0xDC09
    const val PROPERTY_PARENT_OBJECT = 0xDC0B
    const val PROPERTY_PERSISTENT_UID = 0xDC41
    const val PROPERTY_NAME = 0xDC44
    const val PROPERTY_ARTIST = 0xDC46
    const val PROPERTY_DURATION = 0xDC89
    const val PROPERTY_TRACK = 0xDC8B
    const val PROPERTY_GENRE = 0xDC8C
    const val PROPERTY_ALBUM_NAME = 0xDC9A
    const val PROPERTY_ALBUM_ARTIST = 0xDC9B

    // Special constants
    const val PARENT_ROOT = 0
    const val PARENT_ALL = -1 // 0xFFFFFFFF
    const val STORAGE_ALL = -1 // 0xFFFFFFFF
    const val FORMAT_ALL = 0
}

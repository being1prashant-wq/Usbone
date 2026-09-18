package com.example.data

import com.example.mtp.MtpConstants
import java.util.Locale

object MediaCategorizer {
    fun categorize(formatCode: Int, filename: String): Pair<String, String> {
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)

        // 1. Check format code first
        when (formatCode) {
            MtpConstants.FORMAT_ASSOCIATION -> return Pair("FOLDER", "inode/directory")
            MtpConstants.FORMAT_MP3 -> return Pair("AUDIO", "audio/mpeg")
            MtpConstants.FORMAT_WAV -> return Pair("AUDIO", "audio/wav")
            MtpConstants.FORMAT_AAC -> return Pair("AUDIO", "audio/aac")
            MtpConstants.FORMAT_FLAC -> return Pair("AUDIO", "audio/flac")
            MtpConstants.FORMAT_OGG -> return Pair("AUDIO", "audio/ogg")
            MtpConstants.FORMAT_WMA -> return Pair("AUDIO", "audio/x-ms-wma")
            MtpConstants.FORMAT_JPEG -> return Pair("IMAGE", "image/jpeg")
            MtpConstants.FORMAT_PNG -> return Pair("IMAGE", "image/png")
            MtpConstants.FORMAT_GIF -> return Pair("IMAGE", "image/gif")
            MtpConstants.FORMAT_BMP -> return Pair("IMAGE", "image/bmp")
            MtpConstants.FORMAT_TIFF -> return Pair("IMAGE", "image/tiff")
            MtpConstants.FORMAT_AVI -> return Pair("VIDEO", "video/x-msvideo")
            MtpConstants.FORMAT_MPEG -> return Pair("VIDEO", "video/mpeg")
            MtpConstants.FORMAT_MP4_CONTAINER -> return Pair("VIDEO", "video/mp4")
            MtpConstants.FORMAT_3GP_CONTAINER -> return Pair("VIDEO", "video/3gpp")
            MtpConstants.FORMAT_WMV -> return Pair("VIDEO", "video/x-ms-wmv")
            MtpConstants.FORMAT_TEXT -> return Pair("DOCUMENT", "text/plain")
            MtpConstants.FORMAT_HTML -> return Pair("DOCUMENT", "text/html")
        }

        // 2. Check extension
        return when (ext) {
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "alac", "aiff" -> {
                val mime = when (ext) {
                    "mp3" -> "audio/mpeg"
                    "flac" -> "audio/flac"
                    "wav" -> "audio/wav"
                    "ogg", "opus" -> "audio/ogg"
                    "m4a", "aac" -> "audio/mp4"
                    else -> "audio/*"
                }
                Pair("AUDIO", mime)
            }
            "mp4", "mkv", "webm", "avi", "mov", "wmv", "flv", "ts", "mts", "m2ts", "3gp", "vob" -> {
                val mime = when (ext) {
                    "mp4" -> "video/mp4"
                    "mkv" -> "video/x-matroska"
                    "webm" -> "video/webm"
                    "avi" -> "video/x-msvideo"
                    "mov" -> "video/quicktime"
                    else -> "video/*"
                }
                Pair("VIDEO", mime)
            }
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg" -> {
                val mime = when (ext) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    "bmp" -> "image/bmp"
                    "heic", "heif" -> "image/heif"
                    else -> "image/*"
                }
                Pair("IMAGE", mime)
            }
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv" -> {
                Pair("DOCUMENT", "application/$ext")
            }
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> {
                Pair("ARCHIVE", "application/zip")
            }
            else -> Pair("OTHER", "application/octet-stream")
        }
    }
}

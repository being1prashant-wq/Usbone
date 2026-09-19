package com.example.ftp

data class FtpFileItem(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val path: String = ""
) {
    val isVideo: Boolean
        get() = !isDirectory && (
            name.endsWith(".mp4", ignoreCase = true) ||
            name.endsWith(".mkv", ignoreCase = true) ||
            name.endsWith(".avi", ignoreCase = true) ||
            name.endsWith(".mov", ignoreCase = true) ||
            name.endsWith(".3gp", ignoreCase = true) ||
            name.endsWith(".webm", ignoreCase = true) ||
            name.endsWith(".wmv", ignoreCase = true) ||
            name.endsWith(".flv", ignoreCase = true) ||
            name.endsWith(".ts", ignoreCase = true)
        )

    val formattedSize: String
        get() {
            if (isDirectory) return "Folder"
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1000.0) {
                String.format("%.2f GB", mb / 1024.0)
            } else if (mb >= 1.0) {
                String.format("%.1f MB", mb)
            } else {
                val kb = sizeBytes / 1024.0
                String.format("%.1f KB", kb)
            }
        }
}

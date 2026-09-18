package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "mtp_objects",
    indices = [
        Index(value = ["storageId", "parentHandle"]),
        Index(value = ["mediaCategory"]),
        Index(value = ["filename"])
    ]
)
data class MtpObjectEntity(
    @PrimaryKey
    val objectHandle: Int,
    val storageId: Int,
    val parentHandle: Int,
    val filename: String,
    val size: Long,
    val mimeType: String,
    val formatCode: Int,
    val isFolder: Boolean,
    val modificationDate: Long,
    val mediaCategory: String,
    val durationMs: Long = 0L,
    val deviceSerial: String = ""
)

data class CategoryCount(
    val mediaCategory: String,
    val count: Int
)

@Entity(tableName = "recent_media")
data class RecentMediaEntity(
    @PrimaryKey
    val objectHandle: Int,
    val filename: String,
    val storageId: Int,
    val mediaCategory: String,
    val size: Long,
    val lastPlayedTime: Long = System.currentTimeMillis(),
    val playbackPositionMs: Long = 0L,
    val durationMs: Long = 0L
)

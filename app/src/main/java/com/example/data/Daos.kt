package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MtpObjectDao {
    @Query("SELECT * FROM mtp_objects WHERE storageId = :storageId AND parentHandle = :parentHandle ORDER BY isFolder DESC, filename ASC")
    fun getObjectsInFolder(storageId: Int, parentHandle: Int): Flow<List<MtpObjectEntity>>

    @Query("SELECT * FROM mtp_objects WHERE mediaCategory = :category AND isFolder = 0 ORDER BY filename ASC")
    fun getObjectsByCategory(category: String): Flow<List<MtpObjectEntity>>

    @Query("SELECT * FROM mtp_objects WHERE filename LIKE '%' || :query || '%' ORDER BY isFolder DESC, filename ASC LIMIT 100")
    fun searchObjects(query: String): Flow<List<MtpObjectEntity>>

    @Query("SELECT * FROM mtp_objects WHERE objectHandle = :handle LIMIT 1")
    suspend fun getObjectByHandle(handle: Int): MtpObjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(objects: List<MtpObjectEntity>)

    @Query("DELETE FROM mtp_objects")
    suspend fun clearAll()

    @Query("DELETE FROM mtp_objects WHERE deviceSerial = :deviceSerial")
    suspend fun clearForDevice(deviceSerial: String)

    @Query("SELECT COUNT(*) FROM mtp_objects WHERE isFolder = 0")
    fun getTotalFileCount(): Flow<Int>

    @Query("SELECT mediaCategory, COUNT(*) as count FROM mtp_objects WHERE isFolder = 0 GROUP BY mediaCategory")
    fun getCategoryCounts(): Flow<List<CategoryCount>>
}

@Dao
interface RecentMediaDao {
    @Query("SELECT * FROM recent_media ORDER BY lastPlayedTime DESC LIMIT 50")
    fun getRecentMedia(): Flow<List<RecentMediaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: RecentMediaEntity)

    @Query("UPDATE recent_media SET playbackPositionMs = :positionMs WHERE objectHandle = :handle")
    suspend fun updatePosition(handle: Int, positionMs: Long)

    @Query("DELETE FROM recent_media")
    suspend fun clearAll()
}

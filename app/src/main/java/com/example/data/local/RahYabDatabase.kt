package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

enum class SavedPlaceType {
    HOME, WORK, FAVORITE, RECENT
}

@Entity(tableName = "saved_places")
data class SavedPlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // SavedPlaceType.name
    val name: String,
    val address: String,
    val category: String,
    val lat: Double,
    val lon: Double,
    val stateCode: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * All 8 Waze-style offline driving report types with sensible auto-expiration durations:
 *  - Police: 1 hour (60 min)
 *  - Hazard on road: 2 hours (120 min)
 *  - Crash: 2 hours (120 min)
 *  - Road closed: 12 hours (720 min) — avoided by Valhalla route calculation until expired
 *  - Speed trap: 1 hour (60 min)
 *  - Construction: 14 days (20,160 min)
 *  - Object on road: 2 hours (120 min)
 *  - Stalled vehicle: 1 hour (60 min)
 */
enum class ReportType(val defaultTtlMinutes: Int) {
    POLICE(60),
    HAZARD(120),
    CRASH(120),
    ROAD_CLOSURE(720),
    SPEED_TRAP(60),
    CONSTRUCTION(14 * 24 * 60),
    OBJECT_ON_ROAD(120),
    STALLED_VEHICLE(60)
}

/**
 * Local driving report stored in Room with sync-ready schema fields (`uuid`, `bearing`,
 * `note`, `syncStatus`, `remoteUpdatedAt`) so an online sync server can be plugged in later
 * without changing local storage, while operating 100% offline today.
 */
@Entity(tableName = "user_reports")
data class UserReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val type: String, // ReportType.name
    val lat: Double,
    val lon: Double,
    val bearing: Float = -1f, // Direction of travel in degrees (-1 = any direction)
    val roadName: String = "",
    val note: String = "",    // Optional short note added after driving
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long,
    val confirmations: Int = 1,
    val syncStatus: String = "LOCAL_ONLY", // LOCAL_ONLY | PENDING_UPLOAD | SYNCED (future sync server)
    val remoteUpdatedAt: Long = 0L
)

enum class PackInstallStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    INSTALLED,
    UPDATE_AVAILABLE,
    CORRUPTED
}

@Entity(tableName = "installed_packs")
data class InstalledPackEntity(
    @PrimaryKey val stateCode: String,
    val status: String, // PackInstallStatus.name
    val installedVersion: String = "",
    val buildVersion: String = "",
    val availableVersion: String = "",
    val parentStateCode: String? = null,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val hasPmtiles: Boolean = false,
    val hasValhalla: Boolean = false,
    val hasPlacesDb: Boolean = false,
    val errorMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface SavedPlaceDao {
    @Query("SELECT * FROM saved_places ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SavedPlaceEntity>>

    @Query("SELECT * FROM saved_places WHERE type = :type ORDER BY updatedAt DESC")
    fun observeByType(type: String): Flow<List<SavedPlaceEntity>>

    @Query("SELECT * FROM saved_places ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<SavedPlaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(place: SavedPlaceEntity): Long

    @Query("DELETE FROM saved_places WHERE type = :type")
    suspend fun deleteByType(type: String)

    @Query("DELETE FROM saved_places WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM saved_places WHERE type = 'RECENT' AND id NOT IN (SELECT id FROM saved_places WHERE type = 'RECENT' ORDER BY updatedAt DESC LIMIT 25)")
    suspend fun trimRecent()
}

@Dao
interface UserReportDao {
    @Query("SELECT * FROM user_reports WHERE expiresAt > :nowMillis ORDER BY createdAt DESC")
    fun observeActive(nowMillis: Long): Flow<List<UserReportEntity>>

    @Query("SELECT * FROM user_reports WHERE expiresAt > :nowMillis ORDER BY createdAt DESC")
    suspend fun getActiveOnce(nowMillis: Long): List<UserReportEntity>

    @Query("SELECT * FROM user_reports WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): UserReportEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(report: UserReportEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reports: List<UserReportEntity>)

    @Query("UPDATE user_reports SET confirmations = confirmations + 1, expiresAt = :newExpiresAt WHERE id = :id")
    suspend fun confirmReport(id: Long, newExpiresAt: Long)

    @Query("UPDATE user_reports SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String)

    @Query("DELETE FROM user_reports WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM user_reports WHERE expiresAt <= :nowMillis")
    suspend fun purgeExpired(nowMillis: Long)
}

@Dao
interface InstalledPackDao {
    @Query("SELECT * FROM installed_packs")
    fun observeAll(): Flow<List<InstalledPackEntity>>

    @Query("SELECT * FROM installed_packs")
    suspend fun getAllOnce(): List<InstalledPackEntity>

    @Query("SELECT * FROM installed_packs WHERE stateCode = :stateCode")
    suspend fun getByState(stateCode: String): InstalledPackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pack: InstalledPackEntity)

    @Query("DELETE FROM installed_packs WHERE stateCode = :stateCode")
    suspend fun delete(stateCode: String)
}

@Database(
    entities = [
        SavedPlaceEntity::class,
        UserReportEntity::class,
        InstalledPackEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class RahYabDatabase : RoomDatabase() {
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun userReportDao(): UserReportDao
    abstract fun installedPackDao(): InstalledPackDao

    companion object {
        @Volatile
        private var instance: RahYabDatabase? = null

        fun getInstance(context: Context): RahYabDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RahYabDatabase::class.java,
                    "rahyab_local.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also {
                    instance = it
                }
            }
        }
    }
}

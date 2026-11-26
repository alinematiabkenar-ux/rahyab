package com.example.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * L5: User-submitted "Wrong lane" report stored locally in Room.
 * Suppresses colored lane guidance at this intersection (within 30 m & 30° bearing)
 * until the installed map version changes.
 */
@Entity(tableName = "wrong_lane_reports")
data class WrongLaneReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long = System.currentTimeMillis(),
    val lat: Double,
    val lon: Double,
    val bearingDegrees: Float,
    val mapVersion: String,
    val maneuverType: String,
    val instructionText: String,
    val roadNames: String,
    val laneSource: String,
    val confidence: String,
    val confidenceReasons: String,
    val rawLaneJson: String,
    val computedTargets: String,
    val edgeLaneCount: Int,
    val approachWayId: Long
)

@Dao
interface WrongLaneReportDao {
    @Query("SELECT * FROM wrong_lane_reports ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<WrongLaneReportEntity>>

    @Query("SELECT * FROM wrong_lane_reports ORDER BY timestampMs DESC")
    suspend fun getAllOnce(): List<WrongLaneReportEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(report: WrongLaneReportEntity): Long

    @Query("DELETE FROM wrong_lane_reports")
    suspend fun clearAll()
}

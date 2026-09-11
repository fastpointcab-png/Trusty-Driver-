package com.trustyyellowcabs.driver.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun getAllTrips(): Flow<List<Trip>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: Trip): Long

    @Update
    suspend fun updateTrip(trip: Trip)

    @Query("SELECT * FROM trips WHERE isSyncedToSheets = 0")
    suspend fun getUnsyncedTrips(): List<Trip>

    @Query("DELETE FROM trips WHERE id NOT IN (SELECT id FROM trips ORDER BY startTime DESC LIMIT 100)")
    suspend fun pruneOldTrips()

    @Query("DELETE FROM trips")
    suspend fun deleteAllTrips()

    @Transaction
    suspend fun insertAndPrune(trip: Trip): Long {
        val insertedId = insertTrip(trip)
        pruneOldTrips()
        return insertedId
    }
}

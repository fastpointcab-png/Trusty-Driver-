package com.trustyyellowcabs.driver.data

import kotlinx.coroutines.flow.Flow

class TripRepository(private val tripDao: TripDao) {
    val allTrips: Flow<List<Trip>> = tripDao.getAllTrips()

    suspend fun insertTrip(trip: Trip): Long {
        return tripDao.insertAndPrune(trip)
    }

    suspend fun updateTrip(trip: Trip) {
        tripDao.updateTrip(trip)
    }

    suspend fun getUnsyncedTrips(): List<Trip> {
        return tripDao.getUnsyncedTrips()
    }

    suspend fun clearHistory() {
        tripDao.deleteAllTrips()
    }
}

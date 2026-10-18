package com.cowork.bikerecoder.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TripEntity::class,
        WaypointEntity::class,
        OfflineRegionRefEntity::class,
        RideSegmentEntity::class,
        TrackPointEntity::class,
        FavoritePlaceEntity::class,
    ],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun offlineRegionRefDao(): OfflineRegionRefDao
    abstract fun rideDao(): RideDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        const val NAME = "bikerecoder.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()
    }
}

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
        RidePhotoEntity::class,
        SegmentExtrasEntity::class,
        HeartRateSampleEntity::class,
        SegmentRouteSimplifiedEntity::class,
        TripMediaEntity::class,
    ],
    version = 5,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
    ],
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun offlineRegionRefDao(): OfflineRegionRefDao
    abstract fun rideDao(): RideDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun postDao(): PostDao
    abstract fun tripMediaDao(): TripMediaDao

    companion object {
        const val NAME = "bikerecoder.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()
    }
}

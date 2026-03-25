package de.floorballcompanion.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import de.floorballcompanion.data.local.dao.CacheDao
import de.floorballcompanion.data.local.dao.FavoriteDao
import de.floorballcompanion.data.local.entity.*

@Database(
    entities = [
        FavoriteEntity::class,
        CachedTableEntry::class,
        CachedGameEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun cacheDao(): CacheDao
}
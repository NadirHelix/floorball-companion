package de.floorballcompanion.data.local.dao

import androidx.room.*
import de.floorballcompanion.data.local.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY type, name")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE type = :type")
    fun observeByType(type: String): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE type = 'league'")
    fun observeLeagues(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE type = 'team'")
    fun observeTeams(): Flow<List<FavoriteEntity>>

    @Query("SELECT externalId FROM favorites WHERE type = 'team'")
    suspend fun getFavoriteTeamIds(): List<Int>

    @Query("SELECT externalId FROM favorites WHERE type = 'league'")
    suspend fun getFavoriteLeagueIds(): List<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE type = :type AND externalId = :externalId)")
    fun isFavorite(type: String, externalId: Int): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE type = :type AND externalId = :externalId")
    suspend fun remove(type: String, externalId: Int)
}
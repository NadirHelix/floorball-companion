package de.floorballcompanion.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import de.floorballcompanion.data.local.entity.ClubEntity
import de.floorballcompanion.data.local.entity.ClubTeamEntity
import de.floorballcompanion.data.local.entity.ClubWithTeams
import kotlinx.coroutines.flow.Flow

@Dao
interface ClubDao {

    @Query("SELECT * FROM clubs ORDER BY name")
    fun observeAllClubs(): Flow<List<ClubEntity>>

    @Transaction
    @Query("SELECT * FROM clubs ORDER BY name")
    fun observeClubsWithTeams(): Flow<List<ClubWithTeams>>

    @Query("SELECT DISTINCT gameOperationName FROM club_teams ORDER BY gameOperationName")
    fun observeAllGameOperations(): Flow<List<String>>

    @Query("SELECT * FROM club_teams WHERE clubLogoUrl = :logoUrl ORDER BY gameOperationName, leagueName")
    fun observeTeamsForClub(logoUrl: String): Flow<List<ClubTeamEntity>>

    @Query("SELECT * FROM club_teams WHERE clubLogoUrl = :logoUrl ORDER BY gameOperationName, leagueName")
    suspend fun getTeamsForClub(logoUrl: String): List<ClubTeamEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClubs(clubs: List<ClubEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClubTeams(teams: List<ClubTeamEntity>)

    @Query("SELECT COUNT(*) FROM clubs")
    suspend fun getClubCount(): Int
}

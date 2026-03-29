package de.floorballcompanion.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.floorballcompanion.data.local.entity.CachedGameEntity
import de.floorballcompanion.data.local.entity.CachedTableEntry
import de.floorballcompanion.data.local.entity.TeamLeagueMapping
import kotlinx.coroutines.flow.Flow

@Dao
interface CacheDao {

    @Query("SELECT * FROM cached_table WHERE leagueId = :leagueId ORDER BY position")
    fun observeTable(leagueId: Int): Flow<List<CachedTableEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTableEntries(entries: List<CachedTableEntry>)

    @Query("DELETE FROM cached_table WHERE leagueId = :leagueId")
    suspend fun clearTable(leagueId: Int)

    @Query("SELECT * FROM cached_games WHERE leagueId = :leagueId ORDER BY date, startTime")
    fun observeGames(leagueId: Int): Flow<List<CachedGameEntity>>

    @Query("""
        SELECT * FROM cached_games 
        WHERE homeTeamId IN (:teamIds) OR guestTeamId IN (:teamIds) 
        ORDER BY date DESC, startTime DESC
    """)
    fun observeGamesForTeams(teamIds: List<Int>): Flow<List<CachedGameEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGames(games: List<CachedGameEntity>)

    // ── Team-League Mapping ────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTeamLeagueMappings(mappings: List<TeamLeagueMapping>)

    @Query("SELECT * FROM team_league_mapping WHERE teamId = :teamId")
    suspend fun getLeaguesForTeam(teamId: Int): List<TeamLeagueMapping>

    @Query("SELECT * FROM team_league_mapping WHERE teamId = :teamId")
    fun observeLeaguesForTeam(teamId: Int): Flow<List<TeamLeagueMapping>>
}
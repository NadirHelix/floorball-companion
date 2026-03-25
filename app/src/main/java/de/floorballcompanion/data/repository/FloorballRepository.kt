package de.floorballcompanion.data.repository

import de.floorballcompanion.data.local.dao.CacheDao
import de.floorballcompanion.data.local.dao.FavoriteDao
import de.floorballcompanion.data.local.entity.*
import de.floorballcompanion.data.remote.SaisonmanagerApi
import de.floorballcompanion.data.remote.model.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FloorballRepository @Inject constructor(
    private val api: SaisonmanagerApi,
    private val favoriteDao: FavoriteDao,
    private val cacheDao: CacheDao,
) {

    // ── Remote: Initialisierung ──────────────────────────────

    suspend fun getInit(): InitResponse = api.getInit()

    suspend fun getLeaguesByOperation(operationId: Int): List<LeaguePreview> =
        api.getLeaguesByOperation(operationId)

    suspend fun getLeagueDetail(leagueId: Int): LeagueDetail =
        api.getLeagueDetail(leagueId)

    // ── Remote: Tabelle (mit Cache) ──────────────────────────

    suspend fun refreshTable(leagueId: Int): List<TableEntry> {
        val table = api.getTable(leagueId)
        // In Cache schreiben
        cacheDao.clearTable(leagueId)
        cacheDao.insertTableEntries(
            table.map { it.toCachedEntity(leagueId) }
        )
        return table
    }

    fun observeCachedTable(leagueId: Int): Flow<List<CachedTableEntry>> =
        cacheDao.observeTable(leagueId)

    // ── Remote: Scorer ───────────────────────────────────────

    suspend fun getScorer(leagueId: Int): List<ScorerEntry> =
        api.getScorer(leagueId)

    // ── Remote: Spielplan ────────────────────────────────────

    suspend fun getSchedule(leagueId: Int): List<ScheduledGame> =
        api.getSchedule(leagueId)

    suspend fun getCurrentGameDay(leagueId: Int): List<ScheduledGame> =
        api.getCurrentGameDay(leagueId)

    suspend fun refreshSchedule(leagueId: Int) {
        val games = api.getSchedule(leagueId)
        cacheDao.insertGames(
            games.map { it.toCachedEntity(leagueId) }
        )
    }

    // ── Remote: Einzelspiel (Live) ───────────────────────────

    suspend fun getGameDetail(gameId: Int): GameDetail =
        api.getGame(gameId)

    // ── Favoriten ────────────────────────────────────────────

    fun observeFavorites(): Flow<List<FavoriteEntity>> =
        favoriteDao.observeAll()

    fun observeFavoriteLeagues(): Flow<List<FavoriteEntity>> =
        favoriteDao.observeLeagues()

    fun observeFavoriteTeams(): Flow<List<FavoriteEntity>> =
        favoriteDao.observeTeams()

    fun isFavorite(type: String, externalId: Int): Flow<Boolean> =
        favoriteDao.isFavorite(type, externalId)

    suspend fun addFavorite(favorite: FavoriteEntity) =
        favoriteDao.insert(favorite)

    suspend fun removeFavorite(type: String, externalId: Int) =
        favoriteDao.remove(type, externalId)

    suspend fun getFavoriteTeamIds(): List<Int> =
        favoriteDao.getFavoriteTeamIds()

    suspend fun getFavoriteLeagueIds(): List<Int> =
        favoriteDao.getFavoriteLeagueIds()

    suspend fun updateFavoriteSortOrder(type: String, externalId: Int, sortOrder: Int) =
        favoriteDao.updateSortOrder(type, externalId, sortOrder)

    suspend fun getMaxFavoriteSortOrder(type: String): Int =
        favoriteDao.getMaxSortOrder(type)

    // ── Cache: Spiele für favorisierte Teams ─────────────────

    fun observeGamesForFavoriteTeams(teamIds: List<Int>): Flow<List<CachedGameEntity>> =
        cacheDao.observeGamesForTeams(teamIds)
}

// ── Mapping-Extensions ───────────────────────────────────────

private fun TableEntry.toCachedEntity(leagueId: Int) = CachedTableEntry(
    leagueId = leagueId,
    teamId = teamId,
    position = position,
    teamName = teamName,
    teamLogo = teamLogo,
    games = games,
    won = won,
    draw = draw,
    lost = lost,
    wonOt = wonOt,
    lostOt = lostOt,
    goalsScored = goalsScored,
    goalsReceived = goalsReceived,
    goalsDiff = goalsDiff,
    points = points,
)

private fun ScheduledGame.toCachedEntity(leagueId: Int) = CachedGameEntity(
    gameId = resolvedGameId,
    leagueId = leagueId,
    date = date,
    startTime = startTime,
    homeTeamId = homeTeamId,
    homeTeamName = homeTeamName,
    homeTeamLogo = homeTeamLogo,
    guestTeamId = guestTeamId,
    guestTeamName = guestTeamName,
    guestTeamLogo = guestTeamLogo,
    homeGoals = homeGoals,
    guestGoals = guestGoals,
    gameStatus = gameStatus,
)
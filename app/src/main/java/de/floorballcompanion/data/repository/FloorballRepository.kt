package de.floorballcompanion.data.repository

import de.floorballcompanion.data.local.dao.CacheDao
import de.floorballcompanion.data.local.dao.ClubDao
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
    private val clubDao: ClubDao,
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

    /** Extended version that also builds club data from table entries */
    suspend fun refreshTableWithClubDiscovery(
        leagueId: Int,
        leagueName: String,
        gameOperationName: String,
    ): List<TableEntry> {
        val table = refreshTable(leagueId)
        updateClubData(table, leagueId, leagueName, gameOperationName)
        return table
    }

    private suspend fun updateClubData(
        table: List<TableEntry>,
        leagueId: Int,
        leagueName: String,
        gameOperationName: String,
    ) {
        val teamsWithLogo = table.filter { !it.teamLogo.isNullOrBlank() }
        if (teamsWithLogo.isEmpty()) return

        val clubs = teamsWithLogo
            .groupBy { normalizeLogoUrl(it.teamLogo!!) }
            .map { (logoUrl, entries) ->
                ClubEntity(
                    logoUrl = logoUrl,
                    name = extractClubName(entries.first().teamName),
                )
            }
        clubDao.insertClubs(clubs)

        val clubTeams = teamsWithLogo.map {
            ClubTeamEntity(
                clubLogoUrl = normalizeLogoUrl(it.teamLogo!!),
                teamId = it.teamId,
                teamName = it.teamName,
                leagueId = leagueId,
                leagueName = leagueName,
                gameOperationName = gameOperationName,
            )
        }
        clubDao.insertClubTeams(clubTeams)
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

    // ── Team-League Mappings ───────────────────────────────

    suspend fun getLeaguesForTeam(teamId: Int): List<TeamLeagueMapping> =
        cacheDao.getLeaguesForTeam(teamId)

    fun observeLeaguesForTeam(teamId: Int): Flow<List<TeamLeagueMapping>> =
        cacheDao.observeLeaguesForTeam(teamId)

    suspend fun saveTeamLeagueMappings(mappings: List<TeamLeagueMapping>) =
        cacheDao.insertTeamLeagueMappings(mappings)

    // ── Vereine (Clubs) ────────────────────────────────────

    fun observeAllClubs(): Flow<List<ClubEntity>> =
        clubDao.observeAllClubs()

    fun observeTeamsForClub(logoUrl: String): Flow<List<ClubTeamEntity>> =
        clubDao.observeTeamsForClub(logoUrl)

    suspend fun getTeamsForClub(logoUrl: String): List<ClubTeamEntity> =
        clubDao.getTeamsForClub(logoUrl)

    suspend fun getClubCount(): Int =
        clubDao.getClubCount()
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

/** Normalize logo URLs by stripping query parameters */
private fun normalizeLogoUrl(url: String): String {
    val idx = url.indexOf('?')
    return if (idx > 0) url.substring(0, idx) else url
}

/** Extract a club name from a team name (remove common suffixes like "I", "II", age groups) */
private fun extractClubName(teamName: String): String {
    return teamName
        .replace(Regex("\\s+(I{1,3}|IV|V)$"), "") // Roman numeral suffixes
        .replace(Regex("\\s+[0-9]+$"), "")          // Numeric suffixes
        .trim()
}

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
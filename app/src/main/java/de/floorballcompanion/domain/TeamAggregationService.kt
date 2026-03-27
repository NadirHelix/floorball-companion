package de.floorballcompanion.domain

import de.floorballcompanion.data.local.dao.CacheDao
import de.floorballcompanion.data.local.entity.TeamLeagueMapping
import de.floorballcompanion.data.remote.SaisonmanagerApi
import de.floorballcompanion.data.remote.model.ScheduledGame
import de.floorballcompanion.data.remote.model.ScorerEntry
import de.floorballcompanion.data.remote.model.TableEntry
import javax.inject.Inject
import javax.inject.Singleton

data class TeamLeagueData(
    val leagueId: Int,
    val leagueName: String,
    val gameOperationName: String,
    val schedule: List<ScheduledGame> = emptyList(),
    val table: List<TableEntry> = emptyList(),
    val scorers: List<ScorerEntry> = emptyList(),
)

@Singleton
class TeamAggregationService @Inject constructor(
    private val api: SaisonmanagerApi,
    private val cacheDao: CacheDao,
) {
    /**
     * Discovers all leagues a team plays in within the same game operation.
     * Returns only leagues OTHER than [knownLeagueId].
     *
     * Strategy:
     * 1. Get league detail to find gameOperationId
     * 2. Get all leagues in that operation
     * 3. For each league, fetch table and check if teamId appears
     * 4. For matching leagues, fetch schedule + scorer
     * 5. Cache results in TeamLeagueMapping
     */
    suspend fun discoverLeaguesForTeam(
        teamId: Int,
        knownLeagueId: Int,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): List<TeamLeagueData> {
        // Check cache first
        val cached = cacheDao.getLeaguesForTeam(teamId)
        if (cached.isNotEmpty()) {
            val otherLeagues = cached.filter { it.leagueId != knownLeagueId }
            if (otherLeagues.isNotEmpty()) {
                return otherLeagues.map { mapping ->
                    loadLeagueDataForTeam(teamId, mapping.leagueId, mapping.leagueName, mapping.gameOperationName)
                }
            }
        }

        // Discover from API
        val detail = api.getLeagueDetail(knownLeagueId)
        val allLeagues = api.getLeaguesByOperation(detail.gameOperationId)
            .filter { it.name.length > 2 } // Filter test leagues

        onProgress(0, allLeagues.size)

        val discoveredMappings = mutableListOf<TeamLeagueMapping>()
        val results = mutableListOf<TeamLeagueData>()

        // Always add the known league to the mapping
        discoveredMappings.add(
            TeamLeagueMapping(
                teamId = teamId,
                leagueId = knownLeagueId,
                leagueName = detail.name,
                gameOperationId = detail.gameOperationId,
                gameOperationName = detail.gameOperationName,
            )
        )

        allLeagues.forEachIndexed { index, league ->
            onProgress(index + 1, allLeagues.size)
            if (league.id == knownLeagueId) return@forEachIndexed

            try {
                val table = api.getTable(league.id)
                val teamInTable = table.any { it.teamId == teamId }

                if (teamInTable) {
                    discoveredMappings.add(
                        TeamLeagueMapping(
                            teamId = teamId,
                            leagueId = league.id,
                            leagueName = league.name,
                            gameOperationId = detail.gameOperationId,
                            gameOperationName = detail.gameOperationName,
                        )
                    )

                    val data = loadLeagueDataForTeam(
                        teamId, league.id, league.name, detail.gameOperationName
                    )
                    results.add(data)
                }
            } catch (_: Exception) {
                // Skip leagues that fail to load (e.g. no table yet)
            }
        }

        // If we didn't find the team in any table, also check schedules
        // (team might be in a cup/playoff without a table)
        if (results.isEmpty()) {
            allLeagues.forEachIndexed { index, league ->
                if (league.id == knownLeagueId) return@forEachIndexed
                if (discoveredMappings.any { it.leagueId == league.id }) return@forEachIndexed

                try {
                    val schedule = api.getSchedule(league.id)
                    val teamInSchedule = schedule.any {
                        it.homeTeamId == teamId || it.guestTeamId == teamId
                    }

                    if (teamInSchedule) {
                        discoveredMappings.add(
                            TeamLeagueMapping(
                                teamId = teamId,
                                leagueId = league.id,
                                leagueName = league.name,
                                gameOperationId = detail.gameOperationId,
                                gameOperationName = detail.gameOperationName,
                            )
                        )

                        val teamScorers = try {
                            api.getScorer(league.id).filter { it.teamId == teamId }
                        } catch (_: Exception) { emptyList() }

                        results.add(
                            TeamLeagueData(
                                leagueId = league.id,
                                leagueName = league.name,
                                gameOperationName = detail.gameOperationName,
                                schedule = schedule.filter {
                                    it.homeTeamId == teamId || it.guestTeamId == teamId
                                },
                                scorers = teamScorers,
                            )
                        )
                    }
                } catch (_: Exception) {
                    // Skip
                }
            }
        }

        // Persist mappings
        if (discoveredMappings.isNotEmpty()) {
            cacheDao.insertTeamLeagueMappings(discoveredMappings)
        }

        return results
    }

    private suspend fun loadLeagueDataForTeam(
        teamId: Int,
        leagueId: Int,
        leagueName: String,
        gameOperationName: String,
    ): TeamLeagueData {
        val schedule = try {
            api.getSchedule(leagueId).filter {
                it.homeTeamId == teamId || it.guestTeamId == teamId
            }
        } catch (_: Exception) { emptyList() }

        val table = try { api.getTable(leagueId) } catch (_: Exception) { emptyList() }

        val scorers = try {
            api.getScorer(leagueId).filter { it.teamId == teamId }
        } catch (_: Exception) { emptyList() }

        return TeamLeagueData(
            leagueId = leagueId,
            leagueName = leagueName,
            gameOperationName = gameOperationName,
            schedule = schedule,
            table = table,
            scorers = scorers,
        )
    }
}

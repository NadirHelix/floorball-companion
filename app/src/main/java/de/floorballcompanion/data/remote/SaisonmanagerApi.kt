package de.floorballcompanion.data.remote

import de.floorballcompanion.data.remote.model.*
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Retrofit-Interface für die Saisonmanager API v2.
 *
 * Base-URL: https://saisonmanager.de/api/v2/
 */
interface SaisonmanagerApi {

    companion object {
        const val BASE_URL = "https://saisonmanager.de/api/v2/"
    }

    // ── Initialisierung ──────────────────────────────────────

    /** Saisons, Verbände, Top-Ligen */
    @GET("init.json")
    suspend fun getInit(): InitResponse

    // ── Ligen ────────────────────────────────────────────────
    /** Alle Ligen eines Verbands */
    @GET("game_operations/{operationId}/leagues.json")
    suspend fun getLeaguesByOperation(
        @Path("operationId") operationId: Int,
    ): List<LeaguePreview>

    /** Liga-Details + ähnliche Ligen */
    @GET("leagues/{leagueId}.json")
    suspend fun getLeagueDetail(
        @Path("leagueId") leagueId: Int,
    ): LeagueDetail

    // ── Tabelle & Scorer ─────────────────────────────────────

    /** Tabelle einer Liga */
    @GET("leagues/{leagueId}/table.json")
    suspend fun getTable(
        @Path("leagueId") leagueId: Int,
    ): List<TableEntry>

    /** Scorer-Liste einer Liga */
    @GET("leagues/{leagueId}/scorer.json")
    suspend fun getScorer(
        @Path("leagueId") leagueId: Int,
    ): List<ScorerEntry>

    // ── Spielplan ────────────────────────────────────────────

    /** Gesamtspielplan einer Liga */
    @GET("leagues/{leagueId}/schedule.json")
    suspend fun getSchedule(
        @Path("leagueId") leagueId: Int,
    ): List<ScheduledGame>

    /** Spielplan eines bestimmten Spieltags */
    @GET("leagues/{leagueId}/game_days/{dayNumber}/schedule.json")
    suspend fun getGameDaySchedule(
        @Path("leagueId") leagueId: Int,
        @Path("dayNumber") dayNumber: Int,
    ): List<ScheduledGame>

    /** Spielplan des aktuellen Spieltags */
    @GET("leagues/{leagueId}/game_days/current/schedule.json")
    suspend fun getCurrentGameDay(
        @Path("leagueId") leagueId: Int,
    ): List<ScheduledGame>

    // ── Einzelspiel (Liveticker) ─────────────────────────────

    /** Spiel-Details mit Events (Tore, Strafen, Timeouts) */
    @GET("games/{gameId}.json")
    suspend fun getGame(
        @Path("gameId") gameId: Int,
    ): GameDetail

    /** Alle Spiele (Vorschau, ohne Events) */
    @GET("games.json")
    suspend fun getAllGames(): List<ScheduledGame>
}
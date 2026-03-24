package de.floorballcompanion.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable;

@Serializable
data class LeaguePreview(
    val id: Int,
    @SerialName("game_operation_id") val gameOperationId: Int,
    @SerialName("game_operation_name") val gameOperationName: String,
    @SerialName("game_operation_short_name") val gameOperationShortName: String,
    @SerialName("game_operation_slug") val gameOperationSlug: String,
    @SerialName("league_type") val leagueType: String,
    val name: String,
    val female: Boolean,
    @SerialName("enable_scorer") val enableScorer: Boolean,
    @SerialName("short_name") val shortName: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("field_size") val fieldSize: String,       // "GF" | "KF"
    @SerialName("league_modus") val leagueModus: String,   // "league" | "cup"
    @SerialName("has_preround") val hasPreround: Boolean,
    @SerialName("table_modus") val tableModus: String,
    val periods: Int,
    @SerialName("period_length") val periodLength: Int,
    @SerialName("overtime_length") val overtimeLength: Int,
    @SerialName("legacy_league") val legacyLeague: Boolean,
    @SerialName("game_day_numbers") val gameDayNumbers: List<Int> = emptyList(),
    @SerialName("game_day_titles") val gameDayTitles: List<GameDayTitle> = emptyList(),
    @SerialName("league_category_id") val leagueCategoryId: String? = null,
    @SerialName("league_class_id") val leagueClassId: String? = null,
    @SerialName("league_system_id") val leagueSystemId: String? = null,
    @SerialName("order_key") val orderKey: String? = null,
    val deadline: String? = null,
    @SerialName("before_deadline") val beforeDeadline: Boolean? = null,
)

@Serializable
data class GameDayTitle(
    @SerialName("game_day_number") val gameDayNumber: Int,
    val title: String,
)

@Serializable
data class LeagueDetail(
    val id: Int,
    @SerialName("game_operation_id") val gameOperationId: Int,
    @SerialName("game_operation_name") val gameOperationName: String,
    @SerialName("game_operation_slug") val gameOperationSlug: String,
    val name: String,
    @SerialName("short_name") val shortName: String,
    @SerialName("league_type") val leagueType: String,
    val female: Boolean,
    @SerialName("field_size") val fieldSize: String,
    @SerialName("league_modus") val leagueModus: String,
    val periods: Int,
    @SerialName("period_length") val periodLength: Int,
    @SerialName("game_day_numbers") val gameDayNumbers: List<Int> = emptyList(),
    @SerialName("game_day_titles") val gameDayTitles: List<GameDayTitle> = emptyList(),
    @SerialName("similar_leagues") val similarLeagues: List<LeaguePreview> = emptyList(),
)
package de.floorballcompanion.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InitResponse(
    val seasons: List<Season>,
    @SerialName("current_season_id") val currentSeasonId: Int,
    @SerialName("game_operations") val gameOperations: List<GameOperation>,
)

@Serializable
data class Season(
    val id: Int,
    val name: String,
    val current: Boolean,
)

@Serializable
data class GameOperation(
    val id: Int,
    val name: String,
    @SerialName("short_name") val shortName: String,
    val path: String,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("logo_quad_url") val logoQuadUrl: String? = null,
    @SerialName("top_leagues") val topLeagues: List<LeaguePreview> = emptyList(),
)
package de.floorballcompanion.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TableEntry(
    val position: Int,
    val sort: Int,
    @SerialName("team_name") val teamName: String,
    @SerialName("team_id") val teamId: Int,
    @SerialName("team_logo") val teamLogo: String? = null,
    @SerialName("team_logo_small") val teamLogoSmall: String? = null,
    val games: Int,
    val won: Int,
    val draw: Int,
    val lost: Int,
    @SerialName("won_ot") val wonOt: Int,
    @SerialName("lost_ot") val lostOt: Int,
    @SerialName("goals_scored") val goalsScored: Int,
    @SerialName("goals_received") val goalsReceived: Int,
    @SerialName("goals_diff") val goalsDiff: Int,
    val points: Int,
    @SerialName("point_corrections") val pointCorrections: Int? = null,
)
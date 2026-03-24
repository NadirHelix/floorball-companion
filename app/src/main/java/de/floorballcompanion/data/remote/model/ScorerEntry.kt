package de.floorballcompanion.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ScorerEntry(
    val position: Int,
    @SerialName("player_id") val playerId: Int,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    @SerialName("team_id") val teamId: Int,
    @SerialName("team_name") val teamName: String,
    val games: Int,
    val goals: Int,
    val assists: Int,
    val image: String? = null,
    @SerialName("penalty_2") val penalty2: Int = 0,
    @SerialName("penalty_2and2") val penalty2and2: Int = 0,
    @SerialName("penalty_5") val penalty5: Int = 0,
    @SerialName("penalty_10") val penalty10: Int = 0,
    @SerialName("penalty_ms_tech") val penaltyMsTech: Int = 0,
    @SerialName("penalty_ms_full") val penaltyMsFull: Int = 0,
)
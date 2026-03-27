package de.floorballcompanion.data.local.entity

import androidx.room.Entity

@Entity(
    tableName = "team_league_mapping",
    primaryKeys = ["teamId", "leagueId"],
)
data class TeamLeagueMapping(
    val teamId: Int,
    val leagueId: Int,
    val leagueName: String,
    val gameOperationId: Int,
    val gameOperationName: String,
    val lastUpdated: Long = System.currentTimeMillis(),
)

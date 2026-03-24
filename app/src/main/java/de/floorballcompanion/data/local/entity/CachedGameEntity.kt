package de.floorballcompanion.data.local.entity

import androidx.room.Entity

@Entity(tableName = "cached_games", primaryKeys = ["gameId"])
data class CachedGameEntity(
    val gameId: Int,
    val leagueId: Int,
    val date: String,
    val startTime: String,
    val homeTeamId: Int,
    val homeTeamName: String,
    val homeTeamLogo: String? = null,
    val guestTeamId: Int,
    val guestTeamName: String,
    val guestTeamLogo: String? = null,
    val homeGoals: Int? = null,
    val guestGoals: Int? = null,
    val gameStatus: String? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
)
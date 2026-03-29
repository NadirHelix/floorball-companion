package de.floorballcompanion.data.local.entity

import androidx.room.Entity

@Entity(
    tableName = "clubs",
    primaryKeys = ["logoUrl"],
)
data class ClubEntity(
    val logoUrl: String,
    val name: String,
    val lastUpdated: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "club_teams",
    primaryKeys = ["teamId", "leagueId"],
)
data class ClubTeamEntity(
    val clubLogoUrl: String,
    val teamId: Int,
    val teamName: String,
    val leagueId: Int,
    val leagueName: String,
    val gameOperationName: String,
    val lastUpdated: Long = System.currentTimeMillis(),
)

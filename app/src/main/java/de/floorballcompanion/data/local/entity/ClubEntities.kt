package de.floorballcompanion.data.local.entity

import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.Index
import androidx.room.Relation

data class ClubWithTeams(
    @Embedded val club: ClubEntity,
    @Relation(
        parentColumn = "logoUrl",
        entityColumn = "clubLogoUrl"
    )
    val teams: List<ClubTeamEntity>
)

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
    indices = [ // <- NEU
        Index("clubLogoUrl"),
        Index("teamName"),
        Index("gameOperationName"),
    ]
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

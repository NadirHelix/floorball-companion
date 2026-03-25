package de.floorballcompanion.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: String,              // "league" | "team" | "club"
    val externalId: Int,           // Liga-ID, Team-ID oder Vereins-ID
    val name: String,
    val logoUrl: String? = null,
    val gameOperationSlug: String? = null,  // z.B. "fvbb"
    val gameOperationName: String? = null,
    val leagueId: Int? = null,     // Bei Team-Favoriten: zugehörige Liga
    val leagueName: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = Int.MAX_VALUE,
)
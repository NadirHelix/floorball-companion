package de.floorballcompanion.data.local.entity

import androidx.room.Entity

@Entity(tableName = "cached_table", primaryKeys = ["leagueId", "teamId"])
data class CachedTableEntry(
    val leagueId: Int,
    val teamId: Int,
    val position: Int,
    val teamName: String,
    val teamLogo: String? = null,
    val games: Int,
    val won: Int,
    val draw: Int,
    val lost: Int,
    val wonOt: Int,
    val lostOt: Int,
    val goalsScored: Int,
    val goalsReceived: Int,
    val goalsDiff: Int,
    val points: Int,
    val lastUpdated: Long = System.currentTimeMillis(),
)
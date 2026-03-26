package de.floorballcompanion.domain.model

import de.floorballcompanion.data.remote.model.LeaguePreview

/**
 * Phase/Rolle einer Liga innerhalb einer Gruppe.
 */
enum class LeaguePhase {
    REGULAR,
    PLAYOFF,
    PLAYDOWN,
    RELEGATION,
}

/**
 * Gruppiert eine Hauptliga mit ihren zugehörigen Playoff/Playdown/Relegation-Ligen.
 *
 * Beispiel:
 *   mainLeague = "1. FBL" (id=601)
 *   relatedLeagues = [
 *     (PLAYOFF,    "1. FBL - Playoffs",         id=727),
 *     (PLAYDOWN,   "1. FBL - Playdowns",        id=728),
 *     (RELEGATION, "Relegation 1. FBL / 2. FBL", id=744),
 *   ]
 */
data class LeagueGroup(
    val mainLeague: LeaguePreview,
    val relatedLeagues: List<Pair<LeaguePhase, LeaguePreview>> = emptyList(),
) {
    val hasPostSeason: Boolean get() = relatedLeagues.isNotEmpty()

    val allLeagues: List<LeaguePreview>
        get() = buildList {
            add(mainLeague)
            addAll(relatedLeagues.map { it.second })
        }.sortedBy { it.orderKey?.toIntOrNull() ?: 0 }
}

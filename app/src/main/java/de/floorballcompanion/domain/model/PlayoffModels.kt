package de.floorballcompanion.domain.model

import de.floorballcompanion.data.remote.model.ScheduledGame

data class TeamInfo(
    val id: Int,
    val name: String,
    val logo: String?,
)

/**
 * Eine Best-of-Serie zwischen zwei Teams in einer Playoff-/Playdown-Runde.
 */
data class PlayoffSeries(
    val higherSeed: TeamInfo,
    val lowerSeed: TeamInfo,
    val games: List<ScheduledGame>,
    val bestOf: Int,
    val higherSeedWins: Int,
    val lowerSeedWins: Int,
) {
    val winsNeeded: Int get() = (bestOf / 2) + 1
    val isCompleted: Boolean get() = higherSeedWins >= winsNeeded || lowerSeedWins >= winsNeeded

    /** Einzelspiel (kein Best-of-Format, z.B. Final4) */
    val isSingleGame: Boolean get() = bestOf == 1

    val winner: TeamInfo?
        get() = when {
            higherSeedWins >= winsNeeded -> higherSeed
            lowerSeedWins >= winsNeeded -> lowerSeed
            else -> null
        }

    /** Prueft ob ein Spiel vom Higher Seed als Heim gespielt wird */
    private fun isHomeHigherSeed(game: ScheduledGame): Boolean {
        if (higherSeed.id != 0 && game.homeTeamId != 0) return game.homeTeamId == higherSeed.id
        return game.homeTeamName == higherSeed.name
    }

    /** Spiele die tatsaechlich relevant sind (nicht uebersprungene optionale). */
    val relevantGames: List<ScheduledGame>
        get() {
            if (!isCompleted) return games
            var hWins = 0
            var lWins = 0
            val relevant = mutableListOf<ScheduledGame>()
            for (game in games) {
                relevant.add(game)
                val result = game.result ?: continue
                val homeIsHigher = isHomeHigherSeed(game)
                val homeWon = result.homeGoals > result.guestGoals
                if ((homeIsHigher && homeWon) || (!homeIsHigher && !homeWon)) hWins++ else lWins++
                if (hWins >= winsNeeded || lWins >= winsNeeded) break
            }
            return relevant
        }

    /** Ist ein bestimmtes Spiel innerhalb der Serie "optional" (noch nicht freigeschaltet)? */
    fun isOptionalGame(gameIndex: Int): Boolean {
        if (bestOf <= 1) return false
        if (gameIndex == 0) return false
        var hWins = 0
        var lWins = 0
        for (i in 0 until gameIndex.coerceAtMost(games.size)) {
            val result = games[i].result ?: break
            val homeIsHigher = isHomeHigherSeed(games[i])
            val homeWon = result.homeGoals > result.guestGoals
            if ((homeIsHigher && homeWon) || (!homeIsHigher && !homeWon)) hWins++ else lWins++
        }
        return when {
            hWins >= winsNeeded || lWins >= winsNeeded -> true
            gameIndex >= bestOf - 1 && bestOf == 3 -> hWins != lWins
            gameIndex >= bestOf - 2 && bestOf == 5 -> {
                val maxBehind = winsNeeded - maxOf(hWins, lWins)
                val gamesLeft = bestOf - gameIndex
                gamesLeft > maxBehind
            }
            else -> false
        }
    }
}

/**
 * Eine Runde im Playoff-Bracket (z.B. Viertelfinale, Halbfinale, Finale).
 */
data class PlayoffRound(
    val name: String,
    val series: List<PlayoffSeries>,
)

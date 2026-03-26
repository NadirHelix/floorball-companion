package de.floorballcompanion.domain

import de.floorballcompanion.data.remote.model.ScheduledGame
import de.floorballcompanion.domain.model.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verarbeitet Playoff-/Playdown-Spielplaene in Serien und Runden.
 */
@Singleton
class PlayoffService @Inject constructor() {

    /**
     * Gruppiert Playoff-Spiele in Runden mit Best-of-Serien.
     * Erkennt Rundenstruktur anhand series_title oder Teamanzahl.
     */
    fun buildRounds(games: List<ScheduledGame>): List<PlayoffRound> {
        // Spiele ohne Spielnummer ausfiltern (Platzhalter)
        val validGames = games.filter { (it.gameNumber ?: 0) > 0 }
        if (validGames.isEmpty()) return emptyList()

        // Wenn series_title vorhanden: direkt nach Titel gruppieren
        val hasTitles = validGames.any { !it.seriesTitle.isNullOrBlank() }
        if (hasTitles) {
            return buildRoundsFromSeriesTitles(validGames)
        }

        // Sonst: Spiele in Serien gruppieren (gleiche Team-Paarung)
        val series = groupIntoSeries(validGames)
        if (series.isEmpty()) return emptyList()

        // Alle Serien gehoeren zur selben Runde (game_day ist fuer Playoffs nicht zuverlaessig)
        val roundName = deriveRoundName(series.size)
        return listOf(PlayoffRound(name = roundName, series = series))
    }

    /**
     * Prueft, ob ein Spielplan ein Championship/Final4-Format ist.
     */
    fun isChampionshipFormat(games: List<ScheduledGame>, leagueModus: String?): Boolean {
        if (leagueModus == "champ") return true
        if (games.isEmpty()) return false
        // Pruefen ob series_titles auf Turnier-Format hindeuten (Halbfinale + Finale an einem Tag)
        val hasTitles = games.any { !it.seriesTitle.isNullOrBlank() }
        val dates = games.map { it.date }.distinct()
        return hasTitles && dates.size == 1
    }

    /**
     * Baut Runden anhand von series_title (z.B. "Halbfinale", "Finale", "Spiel um Platz 3").
     * Fuer Championship/Final4-Formate.
     */
    private fun buildRoundsFromSeriesTitles(games: List<ScheduledGame>): List<PlayoffRound> {
        // Nach series_title gruppieren, Reihenfolge beibehalten
        val titleOrder = games
            .mapNotNull { it.seriesTitle?.trim() }
            .distinct()

        return titleOrder.map { title ->
            val roundGames = games.filter { it.seriesTitle?.trim() == title }
            // Innerhalb einer Runde: versuchen Serien zu bilden
            val series = groupIntoSeries(roundGames)
            if (series.isNotEmpty()) {
                PlayoffRound(name = title, series = series)
            } else {
                // Einzelspiele (kein Serien-Matching moeglich, z.B. TBD-Teams)
                // Als Einzel-Serien behandeln
                val singleSeries = roundGames.map { game ->
                    val home = TeamInfo(
                        id = game.homeTeamId,
                        name = game.homeTeamName.ifEmpty {
                            game.homeTeamFillingTitle ?: "Noch nicht bekannt"
                        },
                        logo = game.homeTeamLogo,
                    )
                    val guest = TeamInfo(
                        id = game.guestTeamId,
                        name = game.guestTeamName.ifEmpty {
                            game.guestTeamFillingTitle ?: "Noch nicht bekannt"
                        },
                        logo = game.guestTeamLogo,
                    )
                    PlayoffSeries(
                        higherSeed = home,
                        lowerSeed = guest,
                        games = listOf(game),
                        bestOf = 1,
                        higherSeedWins = if (game.result != null && game.result.homeGoals > game.result.guestGoals) 1 else 0,
                        lowerSeedWins = if (game.result != null && game.result.guestGoals > game.result.homeGoals) 1 else 0,
                    )
                }
                PlayoffRound(name = title, series = singleSeries)
            }
        }
    }

    /**
     * Gruppiert Spiele in Serien anhand der Team-Paarung.
     * Nutzt Team-Namen als Fallback wenn IDs fehlen (Playoff-API).
     */
    private fun groupIntoSeries(games: List<ScheduledGame>): List<PlayoffSeries> {
        fun pairKey(g: ScheduledGame): String {
            // Primaer nach Team-IDs wenn vorhanden, sonst nach Namen
            return if (g.homeTeamId != 0 && g.guestTeamId != 0) {
                val a = minOf(g.homeTeamId, g.guestTeamId)
                val b = maxOf(g.homeTeamId, g.guestTeamId)
                "id:$a-$b"
            } else if (g.homeTeamName.isNotEmpty() && g.guestTeamName.isNotEmpty()) {
                val names = listOf(g.homeTeamName, g.guestTeamName).sorted()
                "name:${names[0]}-${names[1]}"
            } else {
                // TBD-Teams: nicht gruppierbar
                "unknown:${g.resolvedGameId}"
            }
        }

        // Nur Spiele mit mindestens einem bekannten Team-Paar
        val pairable = games.filter {
            (it.homeTeamId != 0 && it.guestTeamId != 0) ||
                (it.homeTeamName.isNotEmpty() && it.guestTeamName.isNotEmpty())
        }

        val grouped = pairable.groupBy { pairKey(it) }

        return grouped.map { (_, seriesGames) ->
            val sorted = seriesGames.sortedWith(compareBy({ it.date }, { it.time }, { it.gameNumber }))
            buildSeries(sorted)
        }
    }

    private fun buildSeries(games: List<ScheduledGame>): PlayoffSeries {
        // Bestimme Higher Seed: Team mit mehr Heimspielen
        val homeCountByName = mutableMapOf<String, Int>()
        for (game in games) {
            if (game.homeTeamName.isNotEmpty()) {
                homeCountByName[game.homeTeamName] = (homeCountByName[game.homeTeamName] ?: 0) + 1
            }
        }

        val teamNames = (games.map { it.homeTeamName } + games.map { it.guestTeamName })
            .filter { it.isNotEmpty() }
            .distinct()

        val higherSeedName = if (teamNames.size == 2) {
            teamNames.maxByOrNull { homeCountByName[it] ?: 0 } ?: teamNames[0]
        } else {
            teamNames.firstOrNull() ?: ""
        }

        // Team-Infos aus den Spielen extrahieren
        val higherSeed = games.firstNotNullOfOrNull { game ->
            when {
                game.homeTeamName == higherSeedName ->
                    TeamInfo(game.homeTeamId, game.homeTeamName, game.homeTeamLogo)
                game.guestTeamName == higherSeedName ->
                    TeamInfo(game.guestTeamId, game.guestTeamName, game.guestTeamLogo)
                else -> null
            }
        } ?: TeamInfo(0, higherSeedName, null)

        val lowerSeedName = teamNames.firstOrNull { it != higherSeedName } ?: ""
        val lowerSeed = games.firstNotNullOfOrNull { game ->
            when {
                game.homeTeamName == lowerSeedName ->
                    TeamInfo(game.homeTeamId, game.homeTeamName, game.homeTeamLogo)
                game.guestTeamName == lowerSeedName ->
                    TeamInfo(game.guestTeamId, game.guestTeamName, game.guestTeamLogo)
                else -> null
            }
        } ?: TeamInfo(0, lowerSeedName, null)

        // Siege zaehlen
        var higherWins = 0
        var lowerWins = 0
        for (game in games) {
            val result = game.result ?: continue
            val homeIsHigher = game.homeTeamName == higherSeedName ||
                (game.homeTeamId != 0 && game.homeTeamId == higherSeed.id)
            val homeWon = result.homeGoals > result.guestGoals
            if ((homeIsHigher && homeWon) || (!homeIsHigher && !homeWon)) {
                higherWins++
            } else {
                lowerWins++
            }
        }

        return PlayoffSeries(
            higherSeed = higherSeed,
            lowerSeed = lowerSeed,
            games = games,
            bestOf = games.size,
            higherSeedWins = higherWins,
            lowerSeedWins = lowerWins,
        )
    }

    private fun deriveRoundName(seriesCount: Int): String = when (seriesCount) {
        1 -> "Finale"
        2 -> "Halbfinale"
        4 -> "Viertelfinale"
        8 -> "Achtelfinale"
        else -> "Runde"
    }
}

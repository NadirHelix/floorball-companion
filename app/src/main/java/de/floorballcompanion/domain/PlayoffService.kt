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
     * @param gameDayTitles optionale Spieltag-Titel aus LeagueDetail (gameDayNumber → Titel)
     */
    fun buildRounds(games: List<ScheduledGame>, gameDayTitles: Map<Int, String> = emptyMap()): List<PlayoffRound> {
        // Platzhalter-Spiele ausfiltern: nur Spiele ohne Spielnummer UND ohne bekannte Teams entfernen
        // Spiele mit gameNumber=0/null aber echten Team-IDs sind geplante (noch nicht gespielte) Spiele
        val validGames = games.filter { game ->
            (game.gameNumber ?: 0) > 0 ||
                (game.homeTeamId != 0 && game.guestTeamId != 0) ||
                (game.homeTeamName.isNotEmpty() && game.guestTeamName.isNotEmpty())
        }
        if (validGames.isEmpty()) return emptyList()

        // Wenn series_title vorhanden: direkt nach Titel gruppieren
        val hasTitles = validGames.any { !it.seriesTitle.isNullOrBlank() }
        if (hasTitles) {
            return buildRoundsFromSeriesTitles(validGames)
        }

        // Wenn mehrere Spieltage vorhanden: pro Spieltag eine Runde
        val gameDays = validGames.mapNotNull { it.gameDayNumber }.distinct().sorted()
        if (gameDays.size > 1) {
            return gameDays.map { day ->
                val dayGames = validGames.filter { it.gameDayNumber == day }
                val series = groupIntoSeries(dayGames)
                val name = gameDayTitles[day]
                    ?: if (series.isNotEmpty()) deriveRoundName(series.size) else "Spieltag $day"
                PlayoffRound(name = name, series = series)
            }
        }

        // Sonst: alle Spiele in eine Runde (gleiche Team-Paarung)
        val series = groupIntoSeries(validGames)
        if (series.isEmpty()) return emptyList()

        val singleDay = validGames.firstOrNull()?.gameDayNumber
        val roundName = (if (singleDay != null) gameDayTitles[singleDay] else null)
            ?: deriveRoundName(series.size)
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

    /**
     * Baut Pokal-Runden mit pokalspezifischer Benennung.
     * >8 Serien → "X. Runde", 8 → "Achtelfinale", 4 → "Viertelfinale", 2 → "Final 4", 1 → "Finale"
     */
    fun buildCupRounds(games: List<ScheduledGame>): List<PlayoffRound> {
        val validGames = games.filter { game ->
            (game.gameNumber ?: 0) > 0 ||
                (game.homeTeamId != 0 && game.guestTeamId != 0) ||
                (game.homeTeamName.isNotEmpty() && game.guestTeamName.isNotEmpty())
        }
        if (validGames.isEmpty()) return emptyList()

        val gameDays = validGames.mapNotNull { it.gameDayNumber }.distinct().sorted()

        if (gameDays.size <= 1) {
            val series = groupIntoSeries(validGames)
            if (series.isEmpty()) return emptyList()
            return listOf(PlayoffRound(name = deriveCupRoundName(0, series.size), series = series))
        }

        return gameDays.mapIndexed { index, day ->
            val dayGames = validGames.filter { it.gameDayNumber == day }
            val series = groupIntoSeries(dayGames)
            PlayoffRound(name = deriveCupRoundName(index, series.size), series = series)
        }
    }

    private fun deriveCupRoundName(roundIndex: Int, seriesCount: Int): String = when {
        seriesCount >= 9 -> "${roundIndex + 1}. Runde"
        seriesCount == 8 -> "Achtelfinale"
        seriesCount == 4 -> "Viertelfinale"
        seriesCount == 2 -> "Final 4"
        seriesCount == 1 -> "Finale"
        else -> "${roundIndex + 1}. Runde"
    }

    private fun deriveRoundName(seriesCount: Int): String = when (seriesCount) {
        1 -> "Finale"
        2 -> "Halbfinale"
        4 -> "Viertelfinale"
        8 -> "Achtelfinale"
        else -> "Runde"
    }
}

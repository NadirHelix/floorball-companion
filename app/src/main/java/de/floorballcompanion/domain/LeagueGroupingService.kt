package de.floorballcompanion.domain

import de.floorballcompanion.data.remote.model.LeaguePreview
import de.floorballcompanion.domain.model.LeagueGroup
import de.floorballcompanion.domain.model.LeaguePhase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gruppiert Ligen aus der Saisonmanager-API in logische Einheiten:
 * Hauptliga + zugehoerige Playoff/Playdown/Relegation-Phasen.
 *
 * Strategie:
 * 1. Innerhalb derselben gameOperationId + seasonId Phasen-Keywords im Namen erkennen.
 * 2. Abkuerzungen expandieren (RL->Regionalliga, VL->Verbandsliga, GF->Grossfeld, KF->Kleinfeld).
 * 3. Jede Phasen-Liga dem besten passenden Elternteil zuordnen (Name-Overlap + leagueClassId).
 * 4. Ligen ohne Keyword bleiben eigenstaendig (REGULAR).
 */
@Singleton
class LeagueGroupingService @Inject constructor() {

    companion object {
        /** Keywords fuer Nicht-Regular-Phasen, spezifischere zuerst. */
        private val PHASE_KEYWORDS: List<Pair<String, LeaguePhase>> = listOf(
            "platzierungsrunde" to LeaguePhase.PLAYDOWN,
            "play-offs/play-down" to LeaguePhase.PLAYOFF, // Kombi-Liga: als PLAYOFF behandeln
            "play-downs"        to LeaguePhase.PLAYDOWN,
            "play-down"         to LeaguePhase.PLAYDOWN,
            "playdowns"         to LeaguePhase.PLAYDOWN,
            "playdown"          to LeaguePhase.PLAYDOWN,
            "abstiegsrunde"     to LeaguePhase.PLAYDOWN,
            "play-offs"         to LeaguePhase.PLAYOFF,
            "play-off"          to LeaguePhase.PLAYOFF,
            "playoffs"          to LeaguePhase.PLAYOFF,
            "playoff"           to LeaguePhase.PLAYOFF,
            "meisterrunde"      to LeaguePhase.PLAYOFF,
            "relegation"        to LeaguePhase.RELEGATION,
        )

        /** Gaengige Abkuerzungen in Liganamen (BB-Konvention u.a.) */
        private val ABBREVIATIONS: List<Pair<Regex, String>> = listOf(
            Regex("\\bRL\\b")  to "Regionalliga",
            Regex("\\bVL\\b")  to "Verbandsliga",
            Regex("\\bGF\\b")  to "Großfeld",
            Regex("\\bKF\\b")  to "Kleinfeld",
            Regex("\\bOL\\b")  to "Oberliga",
            Regex("\\bLL\\b")  to "Landesliga",
            Regex("\\bFBL\\b") to "Floorball Bundesliga",
        )
    }

    /**
     * Gruppiert eine flache Liste von [LeaguePreview] in [LeagueGroup]s.
     * Alle Ligen sollten aus demselben Verband (gameOperationId) stammen.
     */
    fun groupLeagues(leagues: List<LeaguePreview>): List<LeagueGroup> {
        return leagues
            .groupBy { it.gameOperationId to it.seasonId }
            .flatMap { (_, bucket) -> groupBucket(bucket) }
            .sortedBy { it.mainLeague.orderKey?.toIntOrNull() ?: 0 }
    }

    private fun groupBucket(bucket: List<LeaguePreview>): List<LeagueGroup> {
        val classified = bucket.map { league ->
            val (phase, baseName) = detectPhase(league.name)
            Triple(league, phase, baseName)
        }

        val regulars = classified.filter { it.second == LeaguePhase.REGULAR }
        val phases = classified.filter { it.second != LeaguePhase.REGULAR }

        val groupMap = mutableMapOf<Int, MutableList<Pair<LeaguePhase, LeaguePreview>>>()
        val unmatched = mutableListOf<Triple<LeaguePreview, LeaguePhase, String>>()

        for ((phaseLeague, phase, baseName) in phases) {
            val parent = findBestParent(baseName, phaseLeague, regulars.map { it.first })
            if (parent != null) {
                groupMap.getOrPut(parent.id) { mutableListOf() }
                    .add(phase to phaseLeague)
            } else {
                unmatched.add(Triple(phaseLeague, phase, baseName))
            }
        }

        val groups = mutableListOf<LeagueGroup>()

        for ((league, _, _) in regulars) {
            groups.add(
                LeagueGroup(
                    mainLeague = league,
                    relatedLeagues = groupMap[league.id]?.sortedBy {
                        it.second.orderKey?.toIntOrNull() ?: 0
                    } ?: emptyList()
                )
            )
        }

        // Nicht zugeordnete Phasen-Ligen als eigenstaendige Gruppen
        for ((league, _, _) in unmatched) {
            groups.add(LeagueGroup(mainLeague = league))
        }

        return groups
    }

    /**
     * Erkennt die Phase einer Liga anhand ihres Namens.
     * @return Pair von (Phase, bereinigter Basisname fuer Matching)
     */
    internal fun detectPhase(name: String): Pair<LeaguePhase, String> {
        val lower = name.lowercase().trim()

        for ((keyword, phase) in PHASE_KEYWORDS) {
            if (lower.contains(keyword)) {
                val escaped = Regex.escape(keyword)
                val baseName = name
                    .replace(Regex("(?i)\\s*[-–/]\\s*$escaped"), "")
                    .replace(Regex("(?i)$escaped\\s*[-–/]?\\s*"), "")
                    .replace(Regex("(?i)\\s*[-–/]\\s*$"), "")
                    .trim()

                return phase to baseName
            }
        }

        return LeaguePhase.REGULAR to name.trim()
    }

    /**
     * Findet die beste Eltern-Liga fuer eine Phasen-Liga.
     * Nutzt Name-Matching mit Abkuerzungs-Expansion und leagueClassId als Signal.
     */
    private fun findBestParent(
        baseName: String,
        phaseLeague: LeaguePreview,
        candidates: List<LeaguePreview>,
    ): LeaguePreview? {
        if (baseName.isBlank() && phaseLeague.leagueClassId == null) return null

        val baseNorm = normalize(expandAbbreviations(baseName))

        data class Scored(val league: LeaguePreview, val score: Double)

        val scored = candidates.mapNotNull { candidate ->
            val candNorm = normalize(expandAbbreviations(candidate.name))

            // Namens-Score berechnen
            var score = when {
                baseNorm.isBlank() -> 0.0
                baseNorm == candNorm -> 1.0
                baseNorm.startsWith(candNorm) ->
                    candNorm.length.toDouble() / baseNorm.length
                candNorm.startsWith(baseNorm) ->
                    baseNorm.length.toDouble() / candNorm.length
                baseNorm.contains(candNorm) ->
                    candNorm.length.toDouble() / baseNorm.length * 0.8
                candNorm.contains(baseNorm) ->
                    baseNorm.length.toDouble() / candNorm.length * 0.8
                else -> 0.0
            }

            // leagueClassId-Bonus: gleiche Klasse + gleiche Feldgroesse = starkes Signal
            if (phaseLeague.leagueClassId != null &&
                phaseLeague.leagueClassId == candidate.leagueClassId &&
                phaseLeague.fieldSize == candidate.fieldSize
            ) {
                score = if (score > 0.0) (score + 0.3).coerceAtMost(1.0) else 0.6
            }

            if (score >= 0.5) Scored(candidate, score) else null
        }

        return scored.maxByOrNull { it.score }?.league
    }

    /** Expandiert gaengige Abkuerzungen fuer besseres Matching. */
    internal fun expandAbbreviations(name: String): String {
        var result = name
        for ((pattern, expansion) in ABBREVIATIONS) {
            result = pattern.replace(result, expansion)
        }
        return result
    }

    private fun normalize(name: String): String =
        name.lowercase()
            .replace("ß", "ss")
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace(Regex("[\\s-–]+"), " ")
            .replace(Regex("[().]"), "")
            .trim()
}

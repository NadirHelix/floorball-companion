package de.floorballcompanion.domain

import de.floorballcompanion.data.remote.model.LeaguePreview
import de.floorballcompanion.domain.model.LeaguePhase
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LeagueGroupingServiceTest {

    private lateinit var service: LeagueGroupingService

    @Before
    fun setup() {
        service = LeagueGroupingService()
    }

    // -- Phase detection -------------------------------------------------------

    @Test
    fun `detectPhase - regular league`() {
        val (phase, base) = service.detectPhase("1. FBL")
        assertEquals(LeaguePhase.REGULAR, phase)
        assertEquals("1. FBL", base)
    }

    @Test
    fun `detectPhase - playoffs with dash separator`() {
        val (phase, base) = service.detectPhase("1. FBL - Playoffs")
        assertEquals(LeaguePhase.PLAYOFF, phase)
        assertEquals("1. FBL", base)
    }

    @Test
    fun `detectPhase - playoffs as prefix`() {
        val (phase, base) = service.detectPhase("Playoffs Regionalliga Ost Herren")
        assertEquals(LeaguePhase.PLAYOFF, phase)
        assertEquals("Regionalliga Ost Herren", base)
    }

    @Test
    fun `detectPhase - playdowns`() {
        val (phase, base) = service.detectPhase("1. FBL - Playdowns")
        assertEquals(LeaguePhase.PLAYDOWN, phase)
        assertEquals("1. FBL", base)
    }

    @Test
    fun `detectPhase - relegation`() {
        val (phase, base) = service.detectPhase("Relegation 1. FBL / 2. FBL")
        assertEquals(LeaguePhase.RELEGATION, phase)
        assertTrue(base.contains("1. FBL"))
    }

    @Test
    fun `detectPhase - Meisterrunde`() {
        val (phase, base) = service.detectPhase("VL Nordwest Herren (KF) - Meisterrunde")
        assertEquals(LeaguePhase.PLAYOFF, phase)
        assertEquals("VL Nordwest Herren (KF)", base)
    }

    @Test
    fun `detectPhase - Platzierungsrunde`() {
        val (phase, base) = service.detectPhase("VL Nordwest Herren (KF) - Platzierungsrunde")
        assertEquals(LeaguePhase.PLAYDOWN, phase)
        assertEquals("VL Nordwest Herren (KF)", base)
    }

    @Test
    fun `detectPhase - Play-Offs with hyphen`() {
        val (phase, _) = service.detectPhase("U13 Kleinfeld Regionalliga Nord Play-Offs")
        assertEquals(LeaguePhase.PLAYOFF, phase)
    }

    @Test
    fun `detectPhase - Playdowns as prefix`() {
        val (phase, base) = service.detectPhase("Playdowns Regionalliga Ost U15 Junioren Kleinfeld")
        assertEquals(LeaguePhase.PLAYDOWN, phase)
        assertEquals("Regionalliga Ost U15 Junioren Kleinfeld", base)
    }

    @Test
    fun `detectPhase - Play-offs with hyphen BB style`() {
        val (phase, base) = service.detectPhase("Play-offs RL Herren KF")
        assertEquals(LeaguePhase.PLAYOFF, phase)
        assertEquals("RL Herren KF", base)
    }

    @Test
    fun `detectPhase - Play-downs with hyphen`() {
        val (phase, base) = service.detectPhase("Play-downs VL Herren KF")
        assertEquals(LeaguePhase.PLAYDOWN, phase)
        assertEquals("VL Herren KF", base)
    }

    @Test
    fun `detectPhase - combined Play-offs Play-down`() {
        val (phase, base) = service.detectPhase("Play-offs/Play-down RL U13 KF")
        assertEquals(LeaguePhase.PLAYOFF, phase)
        assertEquals("RL U13 KF", base)
    }

    // -- Abbreviation expansion ------------------------------------------------

    @Test
    fun `expandAbbreviations - BB conventions`() {
        assertEquals("Regionalliga Herren Kleinfeld", service.expandAbbreviations("RL Herren KF"))
        assertEquals("Verbandsliga U13 Kleinfeld", service.expandAbbreviations("VL U13 KF"))
        // GF wird zu "Großfeld" expandiert
        assertTrue(service.expandAbbreviations("RL U13 GF").contains("roßfeld"))
    }

    // -- Grouping --------------------------------------------------------------

    @Test
    fun `groupLeagues - 1 FBL with Playoffs, Playdowns, Relegation`() {
        val leagues = listOf(
            preview(601, 1, "10", "1. FBL", "20"),
            preview(727, 1, "10", "1. FBL - Playoffs", "21"),
            preview(728, 1, "10", "1. FBL - Playdowns", "22"),
            preview(744, 1, "10", "Relegation 1. FBL / 2. FBL", "23"),
            preview(602, 1, "10", "1. FBL Damen", "30"),
            preview(730, 1, "10", "1. FBL Damen - Playoffs", "31"),
        )

        val groups = service.groupLeagues(leagues)

        assertEquals(2, groups.size)

        val fblHerren = groups.find { it.mainLeague.id == 601 }!!
        assertEquals(3, fblHerren.relatedLeagues.size)
        assertTrue(fblHerren.hasPostSeason)

        val phases = fblHerren.relatedLeagues.map { it.first }.toSet()
        assertTrue(phases.contains(LeaguePhase.PLAYOFF))
        assertTrue(phases.contains(LeaguePhase.PLAYDOWN))
        assertTrue(phases.contains(LeaguePhase.RELEGATION))

        val fblDamen = groups.find { it.mainLeague.id == 602 }!!
        assertEquals(1, fblDamen.relatedLeagues.size)
        assertEquals(LeaguePhase.PLAYOFF, fblDamen.relatedLeagues[0].first)
    }

    @Test
    fun `groupLeagues - BB pairs each league with its playoffs`() {
        val leagues = listOf(
            preview(614, 3, "10", "Herren Großfeld Regionalliga", "1"),
            preview(713, 3, "10", "Herren Großfeld Regionalliga Playoffs", "2"),
            preview(613, 3, "10", "Herren Kleinfeld Regionalliga", "3"),
            preview(714, 3, "10", "Herren Kleinfeld Regionalliga Playoffs", "4"),
        )

        val groups = service.groupLeagues(leagues)

        assertEquals(2, groups.size)
        groups.forEach { group ->
            assertEquals(1, group.relatedLeagues.size)
            assertEquals(LeaguePhase.PLAYOFF, group.relatedLeagues[0].first)
        }
    }

    @Test
    fun `groupLeagues - BB abbreviated Play-offs matched via expansion`() {
        val leagues = listOf(
            preview(1843, 3, "17", "Regionalliga Herren Großfeld", "1", classId = "30", fieldSize = "GF"),
            preview(1953, 3, "17", "Play-offs RL Herren KF", "350", classId = "30", fieldSize = "KF"),
            preview(1842, 3, "17", "Regionalliga Herren Kleinfeld", "1200", classId = "30", fieldSize = "KF"),
        )

        val groups = service.groupLeagues(leagues)

        // Play-offs RL Herren KF should match Regionalliga Herren Kleinfeld (same classId+KF)
        val rlKf = groups.find { it.mainLeague.id == 1842 }!!
        assertEquals(1, rlKf.relatedLeagues.size)
        assertEquals(1953, rlKf.relatedLeagues[0].second.id)
    }

    @Test
    fun `groupLeagues - BB combined Play-offs Play-down attached`() {
        val leagues = listOf(
            preview(1841, 3, "17", "Regionalliga U13 Großfeld", "3100", classId = "300", fieldSize = "GF"),
            preview(1950, 3, "17", "Play-offs RL U13 GF", "600", classId = "300", fieldSize = "GF"),
            preview(1949, 3, "17", "Play-offs/Play-down RL U13 KF", "650", classId = "300", fieldSize = "KF"),
            preview(1850, 3, "17", "Regionalliga U13 Kleinfeld", "3300", classId = "300", fieldSize = "KF"),
        )

        val groups = service.groupLeagues(leagues)

        // Play-offs RL U13 GF -> Regionalliga U13 Großfeld
        val rlGf = groups.find { it.mainLeague.id == 1841 }!!
        assertTrue(rlGf.relatedLeagues.any { it.second.id == 1950 })

        // Play-offs/Play-down RL U13 KF -> Regionalliga U13 Kleinfeld
        val rlKf = groups.find { it.mainLeague.id == 1850 }!!
        assertTrue(rlKf.relatedLeagues.any { it.second.id == 1949 })
    }

    @Test
    fun `groupLeagues - Meister- and Platzierungsrunde attached`() {
        val leagues = listOf(
            preview(692, 2, "10", "VL Nordwest Herren (KF) - Staffel Ost", "40"),
            preview(693, 2, "10", "VL Nordwest Herren (KF) - Staffel West", "41"),
            preview(712, 2, "10", "VL Nordwest Herren (KF) - Meisterrunde", "43"),
            preview(711, 2, "10", "VL Nordwest Herren (KF) - Platzierungsrunde", "44"),
        )

        val groups = service.groupLeagues(leagues)

        val allRelated = groups.flatMap { it.relatedLeagues }
        assertTrue("Meisterrunde should be grouped", allRelated.any { it.second.id == 712 })
        assertTrue("Platzierungsrunde should be grouped", allRelated.any { it.second.id == 711 })
    }

    @Test
    fun `groupLeagues - Playoffs as prefix matched`() {
        val leagues = listOf(
            preview(666, 6, "10", "Regionalliga Ost Herren", "22"),
            preview(732, 6, "10", "Playoffs Regionalliga Ost Herren", "5"),
        )

        val groups = service.groupLeagues(leagues)

        assertEquals(1, groups.size)
        assertEquals(666, groups[0].mainLeague.id)
        assertEquals(1, groups[0].relatedLeagues.size)
    }

    @Test
    fun `groupLeagues - standalone league stays standalone`() {
        val leagues = listOf(
            preview(605, 1, "10", "FD-Pokal Herren", "60"),
        )

        val groups = service.groupLeagues(leagues)

        assertEquals(1, groups.size)
        assertFalse(groups[0].hasPostSeason)
    }

    @Test
    fun `groupLeagues - different operations are not mixed`() {
        val leagues = listOf(
            preview(614, 3, "10", "Herren Großfeld Regionalliga", "1"),
            preview(666, 6, "10", "Regionalliga Ost Herren", "22"),
            preview(732, 6, "10", "Playoffs Regionalliga Ost Herren", "5"),
        )

        val groups = service.groupLeagues(leagues)

        assertEquals(2, groups.size)

        val bbGroup = groups.find { it.mainLeague.gameOperationId == 3 }!!
        assertFalse(bbGroup.hasPostSeason)

        val sbkGroup = groups.find { it.mainLeague.gameOperationId == 6 }!!
        assertTrue(sbkGroup.hasPostSeason)
    }

    // -- Helper ----------------------------------------------------------------

    private fun preview(
        id: Int,
        opId: Int,
        season: String,
        name: String,
        orderKey: String,
        classId: String? = null,
        fieldSize: String = "GF",
    ) = LeaguePreview(
        id = id,
        gameOperationId = opId,
        gameOperationName = "Test",
        gameOperationShortName = "TST",
        gameOperationSlug = "test",
        leagueType = "league",
        name = name,
        female = false,
        enableScorer = true,
        shortName = name,
        seasonId = season,
        fieldSize = fieldSize,
        leagueModus = "league",
        hasPreround = false,
        tableModus = "standard",
        periods = 3,
        periodLength = 20,
        overtimeLength = 10,
        legacyLeague = false,
        orderKey = orderKey,
        leagueClassId = classId,
    )
}

package de.floorballcompanion.ui.league

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.floorballcompanion.data.local.entity.FavoriteEntity
import de.floorballcompanion.data.remote.model.GameDayTitle
import de.floorballcompanion.data.remote.model.ScheduledGame
import de.floorballcompanion.data.remote.model.ScorerEntry
import de.floorballcompanion.data.remote.model.TableEntry
import de.floorballcompanion.data.repository.FloorballRepository
import de.floorballcompanion.domain.LeagueGroupingService
import de.floorballcompanion.domain.PlayoffService
import de.floorballcompanion.domain.model.*
import de.floorballcompanion.ui.components.TeamLogo
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject

// -- Phase Tab Model ----------------------------------------------------------

data class PhaseTabInfo(
    val phase: LeaguePhase,
    val leagueId: Int,
    val label: String,
)

// -- UI State -----------------------------------------------------------------

data class LeagueDetailUiState(
    val leagueName: String = "",
    val gameOperationSlug: String? = null,
    val gameOperationName: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,

    // Phase tabs
    val phaseTabs: List<PhaseTabInfo> = emptyList(),
    val selectedPhaseIndex: Int = 0,

    // Regular league content
    val allGames: List<ScheduledGame> = emptyList(),
    val availableGameDays: List<Int> = emptyList(),
    val gameDayTitles: Map<Int, String> = emptyMap(),
    val currentGameDayIndex: Int = 0,
    val table: List<TableEntry> = emptyList(),
    val scorers: List<ScorerEntry> = emptyList(),
    val selectedTab: Int = 0,

    // Playoff content
    val playoffRounds: List<PlayoffRound> = emptyList(),
    val currentRoundIndex: Int = 0,
    val playoffScorers: List<ScorerEntry> = emptyList(),
    val isChampionshipFormat: Boolean = false,
    val championshipGames: List<ScheduledGame> = emptyList(),
    val leagueModus: String? = null,

    val phaseLoading: Boolean = false,
) {
    val currentGameDayNumber: Int? get() = availableGameDays.getOrNull(currentGameDayIndex)
    val gamesForCurrentDay: List<ScheduledGame>
        get() = allGames.filter {
            it.gameDayNumber == currentGameDayNumber && (it.gameNumber ?: 0) > 0
        }

    val currentPhase: LeaguePhase
        get() = phaseTabs.getOrNull(selectedPhaseIndex)?.phase ?: LeaguePhase.REGULAR

    val isPlayoffPhase: Boolean
        get() = currentPhase in listOf(LeaguePhase.PLAYOFF, LeaguePhase.PLAYDOWN, LeaguePhase.RELEGATION)

    val hasPhases: Boolean get() = phaseTabs.size > 1
}

// -- ViewModel ----------------------------------------------------------------

@HiltViewModel
class LeagueDetailViewModel @Inject constructor(
    private val repository: FloorballRepository,
    private val groupingService: LeagueGroupingService,
    private val playoffService: PlayoffService,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val leagueId: Int = savedStateHandle.get<Int>("leagueId") ?: 0

    private val _uiState = MutableStateFlow(LeagueDetailUiState())
    val uiState = _uiState.asStateFlow()

    // Favorit-Status bezieht sich immer auf die Hauptliga
    private val _mainLeagueId = MutableStateFlow(leagueId)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isFavorite: StateFlow<Boolean> =
        _mainLeagueId
            .flatMapLatest { id -> repository.isFavorite("league", id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val detail = repository.getLeagueDetail(leagueId)

                _uiState.update {
                    it.copy(
                        leagueName = detail.name,
                        gameOperationSlug = detail.gameOperationSlug,
                        gameOperationName = detail.gameOperationName,
                        leagueModus = detail.leagueModus,
                    )
                }

                // Phase-Erkennung: zugehoerige Ligen finden
                discoverPhases(detail.gameOperationId, leagueId)

                // Daten fuer die initial ausgewaehlte Phase laden
                val currentPhaseTab = _uiState.value.phaseTabs.getOrNull(_uiState.value.selectedPhaseIndex)
                if (currentPhaseTab != null) {
                    loadPhaseData(currentPhaseTab)
                } else {
                    // Keine Phasen gefunden: normal laden
                    loadRegularData(leagueId, detail.gameDayTitles)
                }

                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Laden fehlgeschlagen")
                }
            }
        }
    }

    private suspend fun discoverPhases(gameOperationId: Int, currentLeagueId: Int) {
        try {
            val allLeagues = repository.getLeaguesByOperation(gameOperationId)
                .filter { it.name.length > 2 }
            val groups = groupingService.groupLeagues(allLeagues)

            // Finde die Gruppe, die unsere Liga enthaelt
            val group = groups.find { g ->
                g.mainLeague.id == currentLeagueId ||
                    g.relatedLeagues.any { it.second.id == currentLeagueId }
            }

            if (group != null && group.hasPostSeason) {
                val tabs = mutableListOf(
                    PhaseTabInfo(LeaguePhase.REGULAR, group.mainLeague.id, "Hauptrunde")
                )

                // Playoffs und Playdowns hinzufuegen (nach Phase sortiert)
                val playoffs = group.relatedLeagues.filter { it.first == LeaguePhase.PLAYOFF }
                val playdowns = group.relatedLeagues.filter { it.first == LeaguePhase.PLAYDOWN }
                val relegations = group.relatedLeagues.filter { it.first == LeaguePhase.RELEGATION }

                if (playoffs.isNotEmpty()) {
                    tabs.add(PhaseTabInfo(LeaguePhase.PLAYOFF, playoffs.first().second.id, "Playoffs"))
                }
                if (playdowns.isNotEmpty()) {
                    tabs.add(PhaseTabInfo(LeaguePhase.PLAYDOWN, playdowns.first().second.id, "Playdowns"))
                }
                if (relegations.isNotEmpty()) {
                    tabs.add(PhaseTabInfo(LeaguePhase.RELEGATION, relegations.first().second.id, "Relegation"))
                }

                // Bestimme welcher Tab initial ausgewaehlt sein soll
                val initialIndex = tabs.indexOfFirst { it.leagueId == currentLeagueId }
                    .coerceAtLeast(0)

                _mainLeagueId.value = group.mainLeague.id
                _uiState.update {
                    it.copy(
                        phaseTabs = tabs,
                        selectedPhaseIndex = initialIndex,
                        leagueName = group.mainLeague.name, // Immer Hauptliga-Name anzeigen
                    )
                }
            }
        } catch (_: Exception) {
            // Phase-Erkennung fehlgeschlagen: kein Problem, einfach ohne Phasen weiter
        }
    }

    private suspend fun loadRegularData(leagueId: Int, gameDayTitlesFromDetail: List<GameDayTitle>? = null) {
        val scheduleDeferred = viewModelScope.async { repository.getSchedule(leagueId) }
        val tableDeferred = viewModelScope.async { repository.refreshTable(leagueId) }
        val scorerDeferred = viewModelScope.async { repository.getScorer(leagueId) }

        val schedule = scheduleDeferred.await()
        val table = tableDeferred.await()
        val scorers = scorerDeferred.await()

        val availableGameDays = schedule
            .mapNotNull { it.gameDayNumber }
            .distinct()
            .sorted()

        val gameDayTitles = if (gameDayTitlesFromDetail != null) {
            gameDayTitlesFromDetail.associate { it.gameDayNumber to it.title }
        } else {
            try {
                val detail = repository.getLeagueDetail(leagueId)
                detail.gameDayTitles.associate { it.gameDayNumber to it.title }
            } catch (_: Exception) {
                emptyMap()
            }
        }

        val currentDayNumber = determineCurrentGameDayNumber(schedule)
        val currentIndex = availableGameDays.indexOf(currentDayNumber).coerceAtLeast(0)

        _uiState.update {
            it.copy(
                allGames = schedule,
                availableGameDays = availableGameDays,
                gameDayTitles = gameDayTitles,
                currentGameDayIndex = currentIndex,
                table = table,
                scorers = scorers,
                selectedTab = 0,
            )
        }
    }

    private suspend fun loadPlayoffData(leagueId: Int) {
        val scheduleDeferred = viewModelScope.async { repository.getSchedule(leagueId) }
        val scorerDeferred = viewModelScope.async { repository.getScorer(leagueId) }

        val schedule = scheduleDeferred.await()
        val scorers = scorerDeferred.await()

        // Pruefen ob Championship-Format (Final4)
        val detail = try {
            repository.getLeagueDetail(leagueId)
        } catch (_: Exception) {
            null
        }
        val modus = detail?.leagueModus ?: _uiState.value.leagueModus
        val isChamp = playoffService.isChampionshipFormat(schedule, modus)

        if (isChamp) {
            // Platzhalter filtern
            val validGames = schedule.filter { (it.gameNumber ?: 0) > 0 }
            _uiState.update {
                it.copy(
                    isChampionshipFormat = true,
                    championshipGames = validGames.sortedWith(compareBy({ it.date }, { it.time }, { it.gameNumber })),
                    playoffScorers = scorers,
                    playoffRounds = emptyList(),
                    selectedTab = 0,
                )
            }
        } else {
            val rounds = playoffService.buildRounds(schedule)
            _uiState.update {
                it.copy(
                    isChampionshipFormat = false,
                    playoffRounds = rounds,
                    currentRoundIndex = 0,
                    playoffScorers = scorers,
                    championshipGames = emptyList(),
                    selectedTab = 0,
                )
            }
        }
    }

    private suspend fun loadPhaseData(tab: PhaseTabInfo) {
        if (tab.phase == LeaguePhase.REGULAR) {
            val detail = repository.getLeagueDetail(tab.leagueId)
            loadRegularData(tab.leagueId, detail.gameDayTitles)
        } else {
            loadPlayoffData(tab.leagueId)
        }
    }

    fun selectPhaseTab(index: Int) {
        if (index == _uiState.value.selectedPhaseIndex) return
        _uiState.update { it.copy(selectedPhaseIndex = index, phaseLoading = true) }

        viewModelScope.launch {
            try {
                val tab = _uiState.value.phaseTabs[index]
                loadPhaseData(tab)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(phaseLoading = false) }
            }
        }
    }

    fun selectTab(index: Int) = _uiState.update { it.copy(selectedTab = index) }

    fun previousGameDay() = _uiState.update {
        if (it.currentGameDayIndex > 0) it.copy(currentGameDayIndex = it.currentGameDayIndex - 1) else it
    }

    fun nextGameDay() = _uiState.update {
        if (it.currentGameDayIndex < it.availableGameDays.size - 1)
            it.copy(currentGameDayIndex = it.currentGameDayIndex + 1)
        else it
    }

    fun previousRound() = _uiState.update {
        if (it.currentRoundIndex > 0) it.copy(currentRoundIndex = it.currentRoundIndex - 1) else it
    }

    fun nextRound() = _uiState.update {
        if (it.currentRoundIndex < it.playoffRounds.size - 1)
            it.copy(currentRoundIndex = it.currentRoundIndex + 1)
        else it
    }

    fun retry() = loadData()

    fun toggleFavorite() {
        viewModelScope.launch {
            // Immer auf die Hauptliga beziehen
            val favId = _mainLeagueId.value
            val isFav = isFavorite.value
            if (isFav) {
                repository.removeFavorite("league", favId)
            } else {
                val state = _uiState.value
                val nextOrder = repository.getMaxFavoriteSortOrder("league") + 1
                repository.addFavorite(
                    FavoriteEntity(
                        type = "league",
                        externalId = favId,
                        name = state.leagueName,
                        gameOperationSlug = state.gameOperationSlug,
                        gameOperationName = state.gameOperationName,
                        sortOrder = nextOrder,
                    )
                )
            }
        }
    }
}

// -- Hilfsfunktionen ----------------------------------------------------------

private val isoDateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val deDotDateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun parseDate(dateStr: String): LocalDate? {
    for (fmt in listOf(isoDateFmt, deDotDateFmt)) {
        try { return LocalDate.parse(dateStr, fmt) } catch (_: DateTimeParseException) {}
    }
    return null
}

private fun determineCurrentGameDayNumber(games: List<ScheduledGame>): Int? {
    val today = LocalDate.now()
    val grouped = games.filter { it.gameDayNumber != null }.groupBy { it.gameDayNumber!! }
    if (grouped.isEmpty()) return null
    val sortedDays = grouped.keys.sorted()
    for (dayNumber in sortedDays) {
        val maxDateStr = grouped[dayNumber]!!.maxOfOrNull { it.date } ?: continue
        val maxDate = parseDate(maxDateStr) ?: continue
        if (maxDate.plusDays(3).isAfter(today)) return dayNumber
    }
    return sortedDays.last()
}

/** Roemische Ziffer (1-20) */
private fun romanNumeral(n: Int): String = when (n) {
    1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"; 5 -> "V"
    6 -> "VI"; 7 -> "VII"; 8 -> "VIII"; 9 -> "IX"; 10 -> "X"
    else -> "$n"
}

/** Drittelergebnisse als String: "0:1 5:1 2:0" — trailing 0:0 Padding wird entfernt */
private fun periodScoresText(game: ScheduledGame): String? {
    val result = game.result ?: return null
    if (result.homeGoalsPeriod.isEmpty()) return null

    var periods = result.homeGoalsPeriod.zip(result.guestGoalsPeriod)

    // Wenn kein Overtime: trailing 0:0 Perioden entfernen (API-Padding)
    if (!result.overtime) {
        while (periods.size > 1 && periods.last() == (0 to 0)) {
            periods = periods.dropLast(1)
        }
    }

    return periods.joinToString(" ") { (h, g) -> "$h:$g" }
}

/** Anzeigename fuer Team (mit Fallback fuer TBD-Teams) */
private fun displayTeamName(game: ScheduledGame, isHome: Boolean): String {
    val name = if (isHome) game.homeTeamName else game.guestTeamName
    if (name.isNotEmpty()) return name
    val filling = if (isHome) game.homeTeamFillingTitle else game.guestTeamFillingTitle
    return filling?.trim() ?: "Noch nicht bekannt"
}

/** Prueft ob ein Team ein TBD/Platzhalter ist */
private fun isTeamTbd(game: ScheduledGame, isHome: Boolean): Boolean {
    val name = if (isHome) game.homeTeamName else game.guestTeamName
    return name.isEmpty()
}

// -- Screen -------------------------------------------------------------------

@Composable
fun LeagueDetailScreen(
    onBack: () -> Unit,
    onGameClick: (gameId: Int) -> Unit = {},
    viewModel: LeagueDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val isFavorite by viewModel.isFavorite.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurueck")
            }
            Text(
                text = uiState.leagueName.ifEmpty { "Liga Details" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.toggleFavorite() }) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (isFavorite) "Favorit entfernen" else "Als Favorit markieren",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            uiState.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null -> {
                Box(
                    Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Fehler: ${uiState.error}",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { viewModel.retry() }) { Text("Erneut versuchen") }
                    }
                }
            }
            else -> {
                // Phase-Tabs (Hauptrunde / Playoffs / Playdowns)
                if (uiState.hasPhases) {
                    ScrollableTabRow(
                        selectedTabIndex = uiState.selectedPhaseIndex,
                        edgePadding = 8.dp,
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    ) {
                        uiState.phaseTabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = uiState.selectedPhaseIndex == index,
                                onClick = { viewModel.selectPhaseTab(index) },
                                text = {
                                    Text(
                                        tab.label,
                                        fontWeight = if (uiState.selectedPhaseIndex == index)
                                            FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                            )
                        }
                    }
                }

                if (uiState.phaseLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (uiState.isPlayoffPhase) {
                    PlayoffPhaseContent(uiState, viewModel, onGameClick)
                } else {
                    RegularPhaseContent(uiState, viewModel, onGameClick)
                }
            }
        }
    }
}

// -- Regular Phase (Spieltag / Tabelle / Scorer) ------------------------------

@Composable
private fun RegularPhaseContent(
    uiState: LeagueDetailUiState,
    viewModel: LeagueDetailViewModel,
    onGameClick: (Int) -> Unit,
) {
    TabRow(selectedTabIndex = uiState.selectedTab) {
        listOf("Spieltag", "Tabelle", "Scorer").forEachIndexed { index, title ->
            Tab(
                selected = uiState.selectedTab == index,
                onClick = { viewModel.selectTab(index) },
                text = { Text(title) },
            )
        }
    }
    when (uiState.selectedTab) {
        0 -> GameDayTab(uiState, { viewModel.previousGameDay() }, { viewModel.nextGameDay() }, onGameClick)
        1 -> TableTab(uiState.table)
        2 -> ScorerTab(uiState.scorers)
    }
}

// -- Playoff Phase (Runde / Scorer) -------------------------------------------

@Composable
private fun PlayoffPhaseContent(
    uiState: LeagueDetailUiState,
    viewModel: LeagueDetailViewModel,
    onGameClick: (Int) -> Unit,
) {
    val tabs = listOf("Runden", "Scorer")
    TabRow(selectedTabIndex = uiState.selectedTab) {
        tabs.forEachIndexed { index, title ->
            Tab(
                selected = uiState.selectedTab == index,
                onClick = { viewModel.selectTab(index) },
                text = { Text(title) },
            )
        }
    }
    when (uiState.selectedTab) {
        0 -> {
            if (uiState.isChampionshipFormat) {
                ChampionshipTab(uiState.championshipGames, onGameClick)
            } else {
                PlayoffRoundTab(uiState, { viewModel.previousRound() }, { viewModel.nextRound() }, onGameClick)
            }
        }
        1 -> ScorerTab(uiState.playoffScorers)
    }
}

// -- Championship/Final4 Tab --------------------------------------------------

@Composable
private fun ChampionshipTab(games: List<ScheduledGame>, onGameClick: (Int) -> Unit) {
    // Platzhalter-Spiele filtern (gameNumber == null oder 0)
    val validGames = remember(games) {
        games.filter { (it.gameNumber ?: 0) > 0 }
    }

    if (validGames.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Noch keine Spiele angesetzt", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // Nach series_title gruppieren fuer Runden-Header
    val groupedByTitle = remember(validGames) {
        val hasTitles = validGames.any { !it.seriesTitle.isNullOrBlank() }
        if (hasTitles) {
            // Reihenfolge der Titel beibehalten
            val titleOrder = validGames.mapNotNull { it.seriesTitle?.trim() }.distinct()
            titleOrder.map { title ->
                title to validGames.filter { it.seriesTitle?.trim() == title }
            }
        } else {
            listOf("Spiele" to validGames)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        groupedByTitle.forEach { (title, sectionGames) ->
            // Runden-Header
            item(key = "header-$title") {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(sectionGames, key = { it.resolvedGameId }) { game ->
                GameCard(game, onGameClick)
            }
        }
    }
}

// -- Playoff Round Tab --------------------------------------------------------

@Composable
private fun PlayoffRoundTab(
    uiState: LeagueDetailUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGameClick: (Int) -> Unit,
) {
    val rounds = uiState.playoffRounds
    if (rounds.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Noch keine Playoff-Daten", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val round = rounds.getOrNull(uiState.currentRoundIndex) ?: return

        // Runden-Navigation
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onPrevious,
                enabled = uiState.currentRoundIndex > 0,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Vorherige Runde")
            }
            Text(
                text = round.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            IconButton(
                onClick = onNext,
                enabled = uiState.currentRoundIndex < rounds.size - 1,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Naechste Runde")
            }
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(round.series, key = { "${it.higherSeed.name}-${it.lowerSeed.name}" }) { series ->
                SeriesCard(series, onGameClick)
            }
        }
    }
}

// -- Series Card (aufklappbar) ------------------------------------------------

@Composable
private fun SeriesCard(series: PlayoffSeries, onGameClick: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val isDecided = series.isCompleted

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // Serien-Label (nur bei Mehrspieler-Serien)
            if (!series.isSingleGame) {
                Text(
                    text = "Best of ${series.bestOf}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))

            // Hauptzeile: Higher Seed | Score | Lower Seed
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Higher Seed (links)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TeamLogo(
                        logoUrl = series.higherSeed.logo,
                        contentDescription = series.higherSeed.name,
                        size = 28.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = series.higherSeed.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (series.winner?.id == series.higherSeed.id)
                            FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (series.winner?.id == series.higherSeed.id)
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                    )
                }

                // Serien-Score
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = "${series.higherSeedWins} : ${series.lowerSeedWins}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    // Einzelergebnisse unter dem Score: ( 5:4 | 3:4 | -:- )
                    // Reihenfolge: immer "Team links" : "Team rechts" (higherSeed : lowerSeed)
                    if (!series.isSingleGame) {
                        val parts = series.games.map { game ->
                            val r = game.result
                            if (r != null) {
                                val homeIsHigher = if (series.higherSeed.id != 0 && game.homeTeamId != 0)
                                    game.homeTeamId == series.higherSeed.id
                                else game.homeTeamName == series.higherSeed.name
                                val hGoals = if (homeIsHigher) r.homeGoals else r.guestGoals
                                val gGoals = if (homeIsHigher) r.guestGoals else r.homeGoals
                                "$hGoals:$gGoals"
                            } else {
                                if (isDecided) null else "-:-"  // Entschiedene Serie: uebersprungene Spiele ausblenden
                            }
                        }.filterNotNull()
                        if (parts.isNotEmpty()) {
                            Text(
                                text = "( ${parts.joinToString(" | ")} )",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Lower Seed (rechts)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = series.lowerSeed.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (series.winner?.id == series.lowerSeed.id)
                            FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (series.winner?.id == series.lowerSeed.id)
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    TeamLogo(
                        logoUrl = series.lowerSeed.logo,
                        contentDescription = series.lowerSeed.name,
                        size = 28.dp,
                    )
                }
            }

            // Expand/Collapse Indikator + expandierte Einzelspiele (nur bei Mehrspieler-Serien)
            if (!series.isSingleGame) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Zuklappen" else "Spiele anzeigen",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                        series.games.forEachIndexed { index, game ->
                            val isOptional = series.isOptionalGame(index)
                            val isSkipped = isDecided && index >= series.relevantGames.size
                            if (!isSkipped) {
                                SeriesGameRow(
                                    game = game,
                                    number = index + 1,
                                    isOptional = isOptional && game.result == null,
                                    onGameClick = onGameClick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -- Einzelspiel innerhalb einer Serie ----------------------------------------

@Composable
private fun SeriesGameRow(
    game: ScheduledGame,
    number: Int,
    isOptional: Boolean,
    onGameClick: (Int) -> Unit,
) {
    val dateDisplay = remember(game.date) {
        parseDate(game.date)?.format(deDotDateFmt) ?: game.date
    }
    val hasResult = game.homeGoals != null && game.guestGoals != null
    val alpha = if (isOptional) 0.4f else 1f
    val gameId = game.resolvedGameId

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .then(if (gameId > 0) Modifier.clickable { onGameClick(gameId) } else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Roemische Nummer
        Text(
            text = romanNumeral(number),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(modifier = Modifier.weight(1f)) {
            // Datum + Uhrzeit
            Text(
                text = "$dateDisplay · ${game.startTime} Uhr",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Heim-Team
                val homeName = displayTeamName(game, isHome = true)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TeamLogo(logoUrl = game.homeTeamLogo, contentDescription = homeName, size = 22.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = homeName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }

                // Ergebnis
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    if (hasResult) {
                        val postfix = game.result?.postfixShort ?: ""
                        Text(
                            text = "${game.homeGoals} : ${game.guestGoals}" +
                                if (postfix.isNotEmpty()) " $postfix" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        // Drittelergebnisse
                        val periods = periodScoresText(game)
                        if (periods != null) {
                            Text(
                                text = "($periods)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            text = "-:-",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Gast-Team
                val guestName = displayTeamName(game, isHome = false)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = guestName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(4.dp))
                    TeamLogo(logoUrl = game.guestTeamLogo, contentDescription = guestName, size = 22.dp)
                }
            }
        }
    }
}

// -- Spieltag-Tab (Regular) ---------------------------------------------------

@Composable
private fun GameDayTab(
    uiState: LeagueDetailUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGameClick: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        val dayNumber = uiState.currentGameDayNumber
        val dayTitle = dayNumber?.let { uiState.gameDayTitles[it] ?: "$it. Spieltag" } ?: "Spieltag"

        // Navigation
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onPrevious,
                enabled = uiState.currentGameDayIndex > 0,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Vorheriger Spieltag")
            }
            Text(
                text = dayTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            IconButton(
                onClick = onNext,
                enabled = uiState.currentGameDayIndex < uiState.availableGameDays.size - 1,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Naechster Spieltag")
            }
        }

        HorizontalDivider()

        if (uiState.gamesForCurrentDay.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Keine Spiele fuer diesen Spieltag", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(uiState.gamesForCurrentDay, key = { it.resolvedGameId }) { game ->
                    GameCard(game, onGameClick)
                }
            }
        }
    }
}

// -- Game Card (mit Drittelergebnissen + n.V./n.PS.) --------------------------

@Composable
private fun GameCard(game: ScheduledGame, onGameClick: (Int) -> Unit = {}) {
    val dateDisplay = remember(game.date) {
        parseDate(game.date)?.format(deDotDateFmt) ?: game.date
    }
    val hasResult = game.homeGoals != null && game.guestGoals != null
    val isLive = game.gameStatus?.lowercase() in listOf("live", "1", "2", "3")
    val gameId = game.resolvedGameId

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .then(if (gameId > 0) Modifier.clickable { onGameClick(gameId) } else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (isLive)
                MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$dateDisplay · ${game.startTime} Uhr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (isLive) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.error,
                    ) {
                        Text(
                            "LIVE",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onError,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            val homeName = displayTeamName(game, isHome = true)
            val guestName = displayTeamName(game, isHome = false)
            val homeTbd = isTeamTbd(game, isHome = true)
            val guestTbd = isTeamTbd(game, isHome = false)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Home-Team
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TeamLogo(logoUrl = game.homeTeamLogo, contentDescription = homeName, size = 28.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = homeName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        color = if (homeTbd) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Ergebnis
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    if (hasResult) {
                        val postfix = game.result?.postfixShort ?: ""
                        Text(
                            text = "${game.homeGoals} : ${game.guestGoals}" +
                                if (postfix.isNotEmpty()) " $postfix" else "",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        // Drittelergebnisse
                        val periods = periodScoresText(game)
                        if (periods != null) {
                            Text(
                                text = "($periods)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            text = "–",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Gast-Team
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = guestName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f, fill = false),
                        color = if (guestTbd) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    TeamLogo(logoUrl = game.guestTeamLogo, contentDescription = guestName, size = 28.dp)
                }
            }
        }
    }
}

// -- Tabelle-Tab --------------------------------------------------------------

@Composable
private fun TableTab(table: List<TableEntry>) {
    if (table.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Keine Tabellendaten vorhanden", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val hScroll = rememberScrollState()
    val surfaceColor = MaterialTheme.colorScheme.surface

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
    ) {
        // Header-Zeile
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Fixiert links: # + Logo-Platz
            TableHeader("#", 24.dp)
            Spacer(Modifier.width(22.dp))

            // Scrollbarer Mittelteil
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(hScroll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TableHeader("Team", 130.dp, TextAlign.Start)
                TableHeader("Sp", 30.dp)
                TableHeader("S", 26.dp)
                TableHeader("SO", 28.dp)
                TableHeader("NO", 28.dp)
                TableHeader("N", 26.dp)
                TableHeader("Tore", 60.dp)
                TableHeader("Diff", 40.dp)
            }

            // Fixiert rechts: Pkt
            TableHeader("Pkt", 36.dp)
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))

        // Daten-Zeilen
        table.forEach { entry ->
            val diffText = if (entry.goalsDiff > 0) "+${entry.goalsDiff}" else "${entry.goalsDiff}"
            val diffColor = when {
                entry.goalsDiff > 0 -> MaterialTheme.colorScheme.primary
                entry.goalsDiff < 0 -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Fixiert links: Position + Logo
                TableCell("${entry.position}", 24.dp, fontWeight = FontWeight.Bold)
                TeamLogo(logoUrl = entry.teamLogo, contentDescription = entry.teamName, size = 20.dp)
                Spacer(Modifier.width(2.dp))

                // Scrollbarer Mittelteil
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(hScroll),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TableCell(entry.teamName, 130.dp, textAlign = TextAlign.Start, fontWeight = FontWeight.Medium, maxLines = 1)
                    TableCell("${entry.games}", 30.dp)
                    TableCell("${entry.won}", 26.dp)
                    TableCell("${entry.wonOt}", 28.dp)
                    TableCell("${entry.lostOt}", 28.dp)
                    TableCell("${entry.lost}", 26.dp)
                    TableCell("${entry.goalsScored}:${entry.goalsReceived}", 60.dp)
                    TableCell(diffText, 40.dp, color = diffColor, fontWeight = FontWeight.Medium)
                }

                // Fixiert rechts: Punkte
                TableCell("${entry.points}", 36.dp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RowScope.TableHeader(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    textAlign: TextAlign = TextAlign.Center,
) {
    Text(
        text = text,
        modifier = Modifier.width(width),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
    )
}

@Composable
private fun RowScope.TableCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    textAlign: TextAlign = TextAlign.Center,
    fontWeight: FontWeight? = null,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = Modifier.width(width),
        style = MaterialTheme.typography.bodySmall,
        textAlign = textAlign,
        fontWeight = fontWeight,
        color = color,
        maxLines = maxLines,
    )
}

// -- Scorer-Tab ---------------------------------------------------------------

private enum class ScorerMode { SCORER, STRAFEN, GEMISCHT }
private enum class StrafenSort { MS, MS_T, P10, P2_2, P2 }

@Composable
private fun ScorerTab(scorers: List<ScorerEntry>) {
    if (scorers.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Keine Scorerdaten vorhanden", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var mode by remember { mutableStateOf(ScorerMode.SCORER) }
    var strafenSort by remember { mutableStateOf(StrafenSort.MS) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Modus-Auswahl
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ScorerMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { mode = m },
                    label = {
                        Text(
                            when (m) {
                                ScorerMode.SCORER -> "Scorerpunkte"
                                ScorerMode.STRAFEN -> "Strafen"
                                ScorerMode.GEMISCHT -> "Alles"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        }

        // Sekundärsortierung immer nach Schwere: MS > MS-T > 10' > 2+2 > 2'
        val strafenComparator = remember(strafenSort) {
            when (strafenSort) {
                StrafenSort.MS -> compareByDescending<ScorerEntry> { it.penaltyMsFull }
                    .thenByDescending { it.penaltyMsTech }.thenByDescending { it.penalty10 }
                    .thenByDescending { it.penalty2and2 }.thenByDescending { it.penalty2 }
                StrafenSort.MS_T -> compareByDescending<ScorerEntry> { it.penaltyMsTech }
                    .thenByDescending { it.penaltyMsFull }.thenByDescending { it.penalty10 }
                    .thenByDescending { it.penalty2and2 }.thenByDescending { it.penalty2 }
                StrafenSort.P10 -> compareByDescending<ScorerEntry> { it.penalty10 }
                    .thenByDescending { it.penaltyMsFull }.thenByDescending { it.penaltyMsTech }
                    .thenByDescending { it.penalty2and2 }.thenByDescending { it.penalty2 }
                StrafenSort.P2_2 -> compareByDescending<ScorerEntry> { it.penalty2and2 }
                    .thenByDescending { it.penaltyMsFull }.thenByDescending { it.penaltyMsTech }
                    .thenByDescending { it.penalty10 }.thenByDescending { it.penalty2 }
                StrafenSort.P2 -> compareByDescending<ScorerEntry> { it.penalty2 }
                    .thenByDescending { it.penaltyMsFull }.thenByDescending { it.penaltyMsTech }
                    .thenByDescending { it.penalty10 }.thenByDescending { it.penalty2and2 }
            }
        }

        val displayList = remember(scorers, mode, strafenSort) {
            when (mode) {
                ScorerMode.SCORER -> scorers
                ScorerMode.STRAFEN -> scorers
                    .filter { it.penalty2 + it.penalty2and2 + it.penalty10 + it.penaltyMsTech + it.penaltyMsFull > 0 }
                    .sortedWith(strafenComparator)
                ScorerMode.GEMISCHT -> scorers
            }
        }

        val needsHScroll = mode != ScorerMode.SCORER
        val hScroll = rememberScrollState()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (needsHScroll) Modifier.horizontalScroll(hScroll) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (needsHScroll) {
                        ScorerHeader("#", 28.dp)
                        ScorerHeader("Spieler / Team", 140.dp, TextAlign.Start)
                        ScorerHeader("Sp", 30.dp)
                    } else {
                        ScorerHeader("#", 28.dp)
                        // Spieler/Team fills remaining space in scorer mode
                        Box(modifier = Modifier.weight(1f)) {
                            ScorerHeader("Spieler / Team", null, TextAlign.Start)
                        }
                        ScorerHeader("Sp", 34.dp)
                    }
                    if (mode != ScorerMode.STRAFEN) {
                        ScorerHeader("T", 28.dp)
                        ScorerHeader("V", 28.dp)
                        ScorerHeader("Pkt", 34.dp)
                    }
                    if (mode != ScorerMode.SCORER) {
                        val sortActive = mode == ScorerMode.STRAFEN
                        SortableScorerHeader("2'", 28.dp, sortActive && strafenSort == StrafenSort.P2) { strafenSort = StrafenSort.P2 }
                        SortableScorerHeader("2+2", 32.dp, sortActive && strafenSort == StrafenSort.P2_2) { strafenSort = StrafenSort.P2_2 }
                        SortableScorerHeader("10'", 30.dp, sortActive && strafenSort == StrafenSort.P10) { strafenSort = StrafenSort.P10 }
                        SortableScorerHeader("MS", 28.dp, sortActive && strafenSort == StrafenSort.MS) { strafenSort = StrafenSort.MS }
                        SortableScorerHeader("MS-T", 34.dp, sortActive && strafenSort == StrafenSort.MS_T) { strafenSort = StrafenSort.MS_T }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            itemsIndexed(displayList, key = { _, s -> "${mode.name}-${strafenSort.name}-${s.playerId}" }) { index, scorer ->
                val displayPos = if (mode == ScorerMode.STRAFEN) index + 1 else scorer.position
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (needsHScroll) Modifier.horizontalScroll(hScroll) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (needsHScroll) {
                        Text(
                            "$displayPos",
                            Modifier.width(28.dp),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                        )
                        Column(modifier = Modifier.width(140.dp)) {
                            Text(
                                "${scorer.firstName} ${scorer.lastName}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                            Text(
                                scorer.teamName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Text("${scorer.games}", Modifier.width(30.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    } else {
                        Text(
                            "$displayPos",
                            Modifier.width(28.dp),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${scorer.firstName} ${scorer.lastName}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                            Text(
                                scorer.teamName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Text("${scorer.games}", Modifier.width(34.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                    if (mode != ScorerMode.STRAFEN) {
                        Text(
                            "${scorer.goals}",
                            Modifier.width(28.dp),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("${scorer.assists}", Modifier.width(28.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        Text(
                            "${scorer.goals + scorer.assists}",
                            Modifier.width(34.dp),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (mode != ScorerMode.SCORER) {
                        Text("${scorer.penalty2}", Modifier.width(28.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        Text("${scorer.penalty2and2}", Modifier.width(32.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        Text("${scorer.penalty10}", Modifier.width(30.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        Text(
                            "${scorer.penaltyMsFull}",
                            Modifier.width(28.dp),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = if (scorer.penaltyMsFull > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (scorer.penaltyMsFull > 0) FontWeight.Bold else null,
                        )
                        Text(
                            "${scorer.penaltyMsTech}",
                            Modifier.width(34.dp),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = if (scorer.penaltyMsTech > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScorerHeader(
    text: String,
    width: Dp?,
    textAlign: TextAlign = TextAlign.Center,
) {
    Text(
        text = text,
        modifier = if (width != null) Modifier.width(width) else Modifier,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
    )
}

@Composable
private fun SortableScorerHeader(
    text: String,
    width: Dp,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = if (isActive) "▼ $text" else text,
        modifier = Modifier
            .width(width + if (isActive) 10.dp else 0.dp)
            .clickable(onClick = onClick),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

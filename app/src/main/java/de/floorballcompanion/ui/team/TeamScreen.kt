@file:OptIn(ExperimentalLayoutApi::class)

package de.floorballcompanion.ui.team

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.floorballcompanion.LocalOriginTabIcon
import de.floorballcompanion.data.local.entity.FavoriteEntity
import de.floorballcompanion.data.remote.model.ScheduledGame
import de.floorballcompanion.data.remote.model.ScorerEntry
import de.floorballcompanion.data.remote.model.TableEntry
import de.floorballcompanion.data.repository.FloorballRepository
import de.floorballcompanion.domain.LeagueGroupingService
import de.floorballcompanion.domain.TeamAggregationService
import de.floorballcompanion.domain.TeamLeagueData
import de.floorballcompanion.domain.model.LeaguePhase
import de.floorballcompanion.ui.components.TeamLogo
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject

// ── Constants ────────────────────────────────────────────────

val TeamFavoriteColor = Color(0xFF2E7D32)

// ── UI State ─────────────────────────────────────────────────

data class TeamDetailUiState(
    val teamId: Int = 0,
    val leagueId: Int = 0,
    val teamName: String = "",
    val teamLogo: String? = null,
    val leagueName: String = "",
    val gameOperationSlug: String? = null,
    val gameOperationName: String? = null,

    // Schedule (filtered for this team)
    val upcomingGames: List<ScheduledGame> = emptyList(),
    val pastGames: List<ScheduledGame> = emptyList(),

    // Table
    val table: List<TableEntry> = emptyList(),
    val teamPosition: Int? = null,
    val isLeagueModus: Boolean = true,

    // Scorers filtered by teamId
    val scorers: List<ScorerEntry> = emptyList(),

    // Cross-league data
    val additionalLeagues: List<TeamLeagueData> = emptyList(),
    val isSearchingMoreLeagues: Boolean = false,
    val searchProgress: String? = null,
    val hasSearchedMoreLeagues: Boolean = false,
    val searchError: String? = null,

    // Aggregated scorer tab
    val selectedScorerTab: Int = 0, // 0 = primary league, 1+ = additional leagues or "all"

    val isLoading: Boolean = true,
    val error: String? = null,
) {
    /** All games across all leagues, sorted for next/previous calculation */
    val allUpcomingGames: List<Pair<String, ScheduledGame>>
        get() {
            val primary = upcomingGames.map { leagueName to it }
            val additional = additionalLeagues.flatMap { league ->
                league.schedule.filter { game ->
                    game.result == null
                }.map { league.leagueName to it }
            }
            return (primary + additional).sortedBy { it.second.date }
        }

    val allPastGames: List<Pair<String, ScheduledGame>>
        get() {
            val primary = pastGames.map { leagueName to it }
            val additional = additionalLeagues.flatMap { league ->
                league.schedule.filter { game ->
                    game.result != null
                }.map { league.leagueName to it }
            }
            return (primary + additional).sortedByDescending { it.second.date }
        }

    /** Aggregated scorers across all leagues */
    val aggregatedScorers: List<ScorerEntry>
        get() {
            if (additionalLeagues.isEmpty()) return scorers
            val allScorers = scorers + additionalLeagues.flatMap { it.scorers }
            return allScorers
                .groupBy { it.playerId }
                .map { (_, entries) ->
                    val first = entries.first()
                    first.copy(
                        goals = entries.sumOf { it.goals },
                        assists = entries.sumOf { it.assists },
                        games = entries.sumOf { it.games },
                        penalty2 = entries.sumOf { it.penalty2 },
                        penalty2and2 = entries.sumOf { it.penalty2and2 },
                        penalty10 = entries.sumOf { it.penalty10 },
                        penaltyMsTech = entries.sumOf { it.penaltyMsTech },
                        penalty5 = entries.sumOf { it.penalty5 },
                        penaltyMsFull = entries.sumOf { it.penaltyMsFull },
                    )
                }
                .sortedByDescending { it.goals + it.assists }
                .mapIndexed { index, entry -> entry.copy(position = index + 1) }
        }
}

// ── ViewModel ────────────────────────────────────────────────

@HiltViewModel
class TeamDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupingService: LeagueGroupingService,
    private val repository: FloorballRepository,
    private val aggregationService: TeamAggregationService,
) : ViewModel() {

    private val teamId: Int = savedStateHandle["teamId"] ?: 0
    private val leagueId: Int = savedStateHandle["leagueId"] ?: 0

    private val _uiState = MutableStateFlow(TeamDetailUiState(teamId = teamId, leagueId = leagueId))
    val uiState = _uiState.asStateFlow()

    val isFavorite: StateFlow<Boolean> =
        repository.isFavorite("team", teamId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val detailDeferred = async { repository.getLeagueDetail(leagueId) }
                val scheduleDeferred = async { repository.getSchedule(leagueId) }
                val detail = detailDeferred.await()
                val tableDeferred = async {
                    try { repository.refreshTableWithClubDiscovery(leagueId, detail.name, detail.gameOperationName) } catch (_: Exception) { emptyList() }
                }
                val scorerDeferred = async { repository.getScorer(leagueId) }

                val schedule = scheduleDeferred.await()
                val table = tableDeferred.await()
                val allScorers = scorerDeferred.await()
                val tableEntry = table.find { it.teamId == teamId }
                val teamName = tableEntry?.teamName?: ""

                // Filter schedule for this team
                val teamGames = schedule.filter {
                    it.homeTeamName == teamName || it.guestTeamName == teamName
                }

                val today = LocalDate.now()
                val pastGames = teamGames.filter { game ->
                    game.result != null || parseDate(game.date)?.isBefore(today) == true
                }.sortedByDescending { it.date }

                val upcomingGames = teamGames.filter { game ->
                    game.result == null && (parseDate(game.date)?.let { !it.isBefore(today) } != false)
                }.sortedBy { it.date }

                val teamLogo = tableEntry?.teamLogo
                    ?: teamGames.firstOrNull()?.let {
                        if (it.homeTeamId == teamId) it.homeTeamLogo else it.guestTeamLogo
                    }

                // Filter scorers for this team
                val teamScorers = allScorers.filter { it.teamId == teamId }

                val isLeague = detail.leagueModus != "cup" && groupingService.detectPhase(detail.name).first == LeaguePhase.REGULAR

                // Check if we already have cached cross-league data
                val cached = repository.getLeaguesForTeam(teamId)
                val hasExistingData = cached.size > 1
                val additionalLeagues = aggregationService.getLeaguesForTeam(teamId, teamName, leagueId)

                _uiState.update {
                    it.copy(
                        teamName = teamName,
                        teamLogo = teamLogo,
                        leagueName = detail.name,
                        gameOperationSlug = detail.gameOperationSlug,
                        gameOperationName = detail.gameOperationName,
                        upcomingGames = upcomingGames,
                        pastGames = pastGames,
                        table = table,
                        teamPosition = tableEntry?.position,
                        isLeagueModus = isLeague,
                        additionalLeagues = additionalLeagues,
                        scorers = teamScorers,
                        hasSearchedMoreLeagues = hasExistingData,
                        isLoading = false,
                    )
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Fehler beim Laden")
                }
            }
        }
    }

    fun searchMoreLeagues(teamName: String) {
        if (_uiState.value.isSearchingMoreLeagues) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearchingMoreLeagues = true, searchProgress = "Lade alle Ligen...", searchError = null) }
            try {
                val results = aggregationService.discoverLeaguesForTeam(
                    teamId = teamId,
                    teamName = teamName,
                    knownLeagueId = leagueId,
                    onProgress = { current, total ->
                        _uiState.update { it.copy(searchProgress = "Prüfe $current von $total Ligen...") }
                    },
                )
                _uiState.update {
                    it.copy(
                        additionalLeagues = results,
                        isSearchingMoreLeagues = false,
                        hasSearchedMoreLeagues = true,
                        searchProgress = null,
                    )
                }
            } catch (e: Exception) {
                Log.d("TeamScreen", "Error: " + e.message + "\n" + e.printStackTrace())
                _uiState.update {
                    it.copy(
                        isSearchingMoreLeagues = false,
                        hasSearchedMoreLeagues = false,
                        searchProgress = null,
                        searchError = "Suche fehlgeschlagen. Bitte erneut versuchen.",
                    )
                }
            }
        }
    }

    fun selectScorerTab(index: Int) {
        _uiState.update { it.copy(selectedScorerTab = index) }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            if (isFavorite.value) {
                repository.removeFavorite("team", teamId)
            } else {
                val state = _uiState.value
                val nextOrder = repository.getMaxFavoriteSortOrder("team") + 1
                repository.addFavorite(
                    FavoriteEntity(
                        type = "team",
                        externalId = teamId,
                        name = state.teamName,
                        logoUrl = state.teamLogo,
                        leagueId = leagueId,
                        leagueName = state.leagueName,
                        gameOperationSlug = state.gameOperationSlug,
                        gameOperationName = state.gameOperationName,
                        sortOrder = nextOrder,
                    )
                )
            }
        }
    }

    fun retry() = loadData()
}

// ── Helpers ──────────────────────────────────────────────────

private val isoDateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val deDotDateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun parseDate(dateStr: String): LocalDate? {
    for (fmt in listOf(isoDateFmt, deDotDateFmt)) {
        try { return LocalDate.parse(dateStr, fmt) } catch (_: DateTimeParseException) {}
    }
    return null
}

private fun formatDate(dateStr: String): String =
    parseDate(dateStr)?.format(deDotDateFmt) ?: dateStr

// ── Screen ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(
    onBack: () -> Unit,
    onNavigateToRoot: (() -> Unit)? = null,
    onGameClick: (gameId: Int) -> Unit = {},
    onLeagueClick: (leagueId: Int) -> Unit = {},
    onTeamClick: (teamId: Int, leagueId: Int) -> Unit = { _, _ -> },
    onClubClick: (logoUrl: String) -> Unit = {},
    viewModel: TeamDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val isFavorite by viewModel.isFavorite.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TeamLogo(uiState.teamLogo, uiState.teamName, size = 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                uiState.teamName.ifEmpty { "Team" },
                                maxLines = 1,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (uiState.leagueName.isNotEmpty()) {
                                Text(
                                    uiState.leagueName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurueck")
                    }
                },
                actions = {
                    if (onNavigateToRoot != null) {
                        IconButton(onClick = onNavigateToRoot) {
                            Icon(LocalOriginTabIcon.current, contentDescription = "Zur Hauptseite")
                        }
                    }
                    IconButton(onClick = { viewModel.toggleFavorite() }) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (isFavorite) "Favorit entfernen" else "Als Favorit markieren",
                            tint = if (isFavorite) TeamFavoriteColor
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.retry() }) { Text("Erneut versuchen") }
                    }
                }
                else -> {
                    TeamContent(
                        uiState = uiState,
                        viewModel = viewModel,
                        onGameClick = onGameClick,
                        onLeagueClick = onLeagueClick,
                        onTeamClick = onTeamClick,
                        onClubClick = onClubClick,
                    )
                }
            }
        }
    }
}

// ── Main Content ─────────────────────────────────────────────

private enum class TeamScorerMode { SCORER, STRAFEN }
private enum class TeamStrafenSort { MS, MS_T, P10, P2_2, P2 }

@Composable
private fun TeamContent(
    uiState: TeamDetailUiState,
    viewModel: TeamDetailViewModel,
    onGameClick: (Int) -> Unit,
    onLeagueClick: (Int) -> Unit,
    onTeamClick: (Int, Int) -> Unit,
    onClubClick: (String) -> Unit,
) {
    // Use cross-league data if available
    val hasAdditional = uiState.additionalLeagues.isNotEmpty()
    val allUpcoming = if (hasAdditional) uiState.allUpcomingGames else uiState.upcomingGames.map { uiState.leagueName to it }
    val allPast = if (hasAdditional) uiState.allPastGames else uiState.pastGames.map { uiState.leagueName to it }

    var scorerMode by remember { mutableStateOf(TeamScorerMode.SCORER) }
    var strafenSort by remember { mutableStateOf(TeamStrafenSort.MS) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        // Verein anzeigen
        if (!uiState.teamLogo.isNullOrEmpty()) {
            item(key = "club-link") {
                TextButton(
                    onClick = { onClubClick(uiState.teamLogo) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text("Zum Verein")
                }
            }
        }

        // Naechstes Spiel + aufklappbar alle kommenden
        if (allUpcoming.isNotEmpty()) {
            item(key = "upcoming") {
                ExpandableGameSectionWithLeague(
                    title = "Nächstes Spiel",
                    primaryGame = allUpcoming.first(),
                    allGames = allUpcoming,
                    teamName = uiState.teamName,
                    onGameClick = onGameClick,
                )
            }
        }

        // Letztes Spiel + aufklappbar alle vergangenen
        if (allPast.isNotEmpty()) {
            item(key = "past") {
                ExpandableGameSectionWithLeague(
                    title = "Zuletzt gespielt",
                    primaryGame = allPast.first(),
                    allGames = allPast,
                    teamName = uiState.teamName,
                    onGameClick = onGameClick,
                )
            }
        }

        // Mini-Tabelle (nur bei Liga-Wettbewerben)
        if (uiState.isLeagueModus && uiState.table.isNotEmpty() && uiState.teamPosition != null) {
            item(key = "mini-table") {
                MiniTableCard(
                    table = uiState.table,
                    teamId = uiState.teamId,
                    teamPosition = uiState.teamPosition,
                    leagueId = uiState.leagueId,
                    leagueName = uiState.leagueName,
                    onLeagueClick = onLeagueClick,
                    onTeamClick = onTeamClick,
                )
            }
        }

        // Tabellen aus weiteren Wettbewerben
        if (hasAdditional) {
            uiState.additionalLeagues.forEachIndexed { idx, league ->
                val leagueTable = league.table
                val leaguePosition = leagueTable.find { it.teamId == uiState.teamId }?.position
                if (leagueTable.isNotEmpty() && leaguePosition != null) {
                    item(key = "mini-table-extra-$idx") {
                        MiniTableCard(
                            table = leagueTable,
                            teamId = uiState.teamId,
                            teamPosition = leaguePosition,
                            leagueId = league.leagueId,
                            leagueName = league.leagueName,
                            onLeagueClick = onLeagueClick,
                            onTeamClick = onTeamClick,
                        )
                    }
                }
            }
        }

        // "Weitere Wettbewerbe" Button
        if (!uiState.hasSearchedMoreLeagues && !uiState.isSearchingMoreLeagues) {
            item(key = "search-more") {
                OutlinedButton(
                    onClick = { viewModel.searchMoreLeagues(uiState.teamName) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("Weitere Wettbewerbe suchen")
                }
            }
        }

        // Search progress
        if (uiState.isSearchingMoreLeagues) {
            item(key = "search-progress") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        uiState.searchProgress ?: "Suche...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Search error
        if (uiState.searchError != null) {
            item(key = "search-error") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        uiState.searchError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Cross-league results summary — anklickbare Liga-Chips
        if (hasAdditional) {
            item(key = "cross-league-info") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        "Weitere Wettbewerbe:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        uiState.additionalLeagues.forEach { league ->
                            AssistChip(
                                onClick = { onLeagueClick(league.leagueId) },
                                label = {
                                    Text(
                                        league.leagueName,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        } else if (uiState.hasSearchedMoreLeagues && !uiState.isSearchingMoreLeagues) {
            item(key = "no-more-leagues") {
                Text(
                    "Keine weiteren Wettbewerbe gefunden",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        // Scorer section with tabs (all leagues, even those without scorer data)
        // Data: Pair<leagueName, scorers or null (= no scorer data available)>
        val scorerData: List<Pair<String, List<ScorerEntry>?>> = if (hasAdditional) {
            buildList {
                add("Alle" to uiState.aggregatedScorers)
                add(uiState.leagueName to uiState.scorers)
                uiState.additionalLeagues.forEach { league ->
                    // null = scorers not available (league found, but no data)
                    add(league.leagueName to league.scorers.ifEmpty { null })
                }
            }
        } else {
            listOf(uiState.leagueName to uiState.scorers.ifEmpty { null })
        }

        val hasAnyScorers = scorerData.any { it.second?.isNotEmpty() == true }
        val activeScorers = scorerData.getOrNull(uiState.selectedScorerTab)?.second

        if (hasAnyScorers || hasAdditional) {
            item(key = "scorer-header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Scorer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    // Modus-Toggle
                    TeamScorerMode.entries.forEach { m ->
                        FilterChip(
                            selected = scorerMode == m,
                            onClick = { scorerMode = m },
                            label = {
                                Text(
                                    if (m == TeamScorerMode.SCORER) "Punkte" else "Strafen",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }

            // Scorer tabs (only if multiple leagues)
            if (scorerData.size > 1) {
                item(key = "scorer-tabs") {
                    ScrollableTabRow(
                        selectedTabIndex = uiState.selectedScorerTab.coerceIn(0, scorerData.lastIndex),
                        edgePadding = 16.dp,
                    ) {
                        scorerData.forEachIndexed { index, (label, _) ->
                            Tab(
                                selected = uiState.selectedScorerTab == index,
                                onClick = { viewModel.selectScorerTab(index) },
                                text = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }

            if (activeScorers == null) {
                // Liga gefunden, aber keine Scorerdaten verfügbar
                item(key = "scorer-empty") {
                    Text(
                        "Keine Scorerdaten für diesen Wettbewerb verfügbar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else if (activeScorers.isEmpty()) {
                item(key = "scorer-empty") {
                    Text(
                        "Keine Scorerdaten vorhanden",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                val displayScorers = if (scorerMode == TeamScorerMode.STRAFEN) {
                    val comparator = when (strafenSort) {
                        TeamStrafenSort.MS -> compareByDescending<ScorerEntry> { it.penaltyMsFull }
                            .thenByDescending { it.penaltyMsTech }.thenByDescending { it.penalty10 }
                        TeamStrafenSort.MS_T -> compareByDescending<ScorerEntry> { it.penaltyMsTech }
                            .thenByDescending { it.penaltyMsFull }.thenByDescending { it.penalty10 }
                        TeamStrafenSort.P10 -> compareByDescending<ScorerEntry> { it.penalty10 }
                            .thenByDescending { it.penaltyMsFull }.thenByDescending { it.penaltyMsTech }
                        TeamStrafenSort.P2_2 -> compareByDescending<ScorerEntry> { it.penalty2and2 }
                            .thenByDescending { it.penalty10 }.thenByDescending { it.penaltyMsFull }
                        TeamStrafenSort.P2 -> compareByDescending<ScorerEntry> { it.penalty2 }
                            .thenByDescending { it.penalty2and2 }.thenByDescending { it.penalty10 }
                    }
                    activeScorers.filter {
                        it.penalty2 + it.penalty2and2 + it.penalty10 + it.penaltyMsTech + it.penaltyMsFull > 0
                    }.sortedWith(comparator)
                } else {
                    activeScorers.take(20)
                }
                item(key = "scorer-col-header") {
                    ScorerHeaderRow(
                        showPenalties = scorerMode == TeamScorerMode.STRAFEN,
                        strafenSort = strafenSort,
                        onStrafenSortChange = { strafenSort = it },
                    )
                }
                items(displayScorers, key = { "scorer-${uiState.selectedScorerTab}-${scorerMode}-${it.playerId}" }) { scorer ->
                    ScorerRow(scorer, showPenalties = scorerMode == TeamScorerMode.STRAFEN)
                }
            }
        }
    }
}

// ── Expandable Game Section (with league name per game) ──────

@Composable
private fun ExpandableGameSectionWithLeague(
    title: String,
    primaryGame: Pair<String, ScheduledGame>,
    allGames: List<Pair<String, ScheduledGame>>,
    teamName: String,
    onGameClick: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val remainingGames = if (allGames.size > 1) allGames.drop(1) else emptyList()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            // Primary game card
            TeamGameRow(
                game = primaryGame.second,
                teamName = teamName,
                leagueName = primaryGame.first,
                onGameClick = onGameClick,
            )

            // Expand/collapse for remaining games
            if (remainingGames.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (expanded) "Weniger anzeigen"
                        else "Alle ${allGames.size} Spiele anzeigen",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        remainingGames.forEach { (leagueName, game) ->
                            TeamGameRow(
                                game = game,
                                teamName = teamName,
                                leagueName = leagueName,
                                onGameClick = onGameClick,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

// ── Single Game Row (team-centric) ───────────────────────────

@Composable
private fun TeamGameRow(
    game: ScheduledGame,
    teamName: String,
    leagueName: String,
    onGameClick: (Int) -> Unit,
) {
    val isHome = game.homeTeamName == teamName
    val opponentName = if (isHome) game.guestTeamName else game.homeTeamName
    val opponentLogo = if (isHome) game.guestTeamLogo else game.homeTeamLogo
    val prefix = if (isHome) "vs." else "@"
    val gameId = game.resolvedGameId

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (gameId > 0) Modifier.clickable { onGameClick(gameId) } else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Opponent logo + name
        TeamLogo(opponentLogo, opponentName, size = 28.dp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "$prefix $opponentName",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                "${formatDate(game.date)} · ${game.startTime} Uhr",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (leagueName.isNotEmpty()) {
                Text(
                    leagueName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }

        // Result or status
        val result = game.result
        if (result != null) {
            val teamGoals = if (isHome) result.homeGoals else result.guestGoals
            val opponentGoals = if (isHome) result.guestGoals else result.homeGoals
            val isWin = teamGoals > opponentGoals
            val isDraw = teamGoals == opponentGoals
            val resultColor = when {
                isWin -> TeamFavoriteColor
                isDraw -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.error
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${result.homeGoals} : ${result.guestGoals}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = resultColor,
                )
                val postfix = result.postfixShort
                if (postfix.isNotEmpty()) {
                    Text(
                        postfix,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Mini Table Card ──────────────────────────────────────────

@Composable
private fun MiniTableCard(
    table: List<TableEntry>,
    teamId: Int,
    teamPosition: Int,
    leagueId: Int,
    leagueName: String = "",
    onLeagueClick: (Int) -> Unit,
    onTeamClick: (Int, Int) -> Unit,
) {
    val displayEntries = remember(table, teamPosition) {
        buildMiniTableEntries(table, teamPosition)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onLeagueClick(leagueId) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Tabelle",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (leagueName.isNotEmpty()) {
                        Text(
                            leagueName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    "Zur Liga →",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))

            // Header
            MiniTableHeaderRow()

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // Rows
            displayEntries.forEach { entry ->
                when (entry) {
                    is MiniTableItem.Separator -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            repeat(3) {
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .background(
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            shape = MaterialTheme.shapes.small,
                                        ),
                                )
                                if (it < 2) Spacer(Modifier.width(4.dp))
                            }
                        }
                    }
                    is MiniTableItem.Entry -> {
                        val isCurrentTeam = entry.tableEntry.teamId == teamId
                        MiniTableRow(
                            entry = entry.tableEntry,
                            isHighlighted = isCurrentTeam,
                            onClick = {
                                if (!isCurrentTeam) {
                                    onTeamClick(entry.tableEntry.teamId, leagueId)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private sealed class MiniTableItem {
    data object Separator : MiniTableItem()
    data class Entry(val tableEntry: TableEntry) : MiniTableItem()
}

private fun buildMiniTableEntries(table: List<TableEntry>, teamPosition: Int): List<MiniTableItem> {
    val sorted = table.sortedBy { it.position }
    val totalTeams = sorted.size

    if (totalTeams <= 6) {
        // Show all teams, separator after last
        return sorted.map { MiniTableItem.Entry(it) } + MiniTableItem.Separator
    }

    return if (teamPosition <= 5) {
        // Show top 6, separator after position 6
        sorted.take(6).map { MiniTableItem.Entry(it) } + MiniTableItem.Separator
    } else {
        // Show top 3 + separator + (pos-1, team, pos+1)
        val top3 = sorted.take(3).map { MiniTableItem.Entry(it) }
        val teamIndex = sorted.indexOfFirst { it.position == teamPosition }
        val surroundStart = (teamIndex - 1).coerceAtLeast(3)
        val surroundEnd = (teamIndex + 1).coerceAtMost(sorted.lastIndex)
        val surrounding = sorted.subList(surroundStart, surroundEnd + 1).map { MiniTableItem.Entry(it) }
        top3 + MiniTableItem.Separator + surrounding
    }
}

@Composable
private fun MiniTableHeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "#",
            modifier = Modifier.width(24.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(24.dp)) // logo space
        Text(
            "Team",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Sp",
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Diff",
            modifier = Modifier.width(36.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Pkt",
            modifier = Modifier.width(32.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MiniTableRow(
    entry: TableEntry,
    isHighlighted: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (isHighlighted)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    else Color.Transparent
    val fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal
    val diffText = if (entry.goalsDiff > 0) "+${entry.goalsDiff}" else "${entry.goalsDiff}"
    val diffColor = when {
        entry.goalsDiff > 0 -> MaterialTheme.colorScheme.primary
        entry.goalsDiff < 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${entry.position}",
            modifier = Modifier.width(24.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        TeamLogo(entry.teamLogo, entry.teamName, size = 20.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            entry.teamName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = fontWeight,
            maxLines = 1,
        )
        Text(
            "${entry.games}",
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Text(
            diffText,
            modifier = Modifier.width(36.dp),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium,
            color = diffColor,
        )
        Text(
            "${entry.points}",
            modifier = Modifier.width(32.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Scorer Header Row ────────────────────────────────────────

@Composable
private fun ScorerHeaderRow(
    showPenalties: Boolean = false,
    strafenSort: TeamStrafenSort = TeamStrafenSort.MS,
    onStrafenSortChange: (TeamStrafenSort) -> Unit = {},
) {
    val headerStyle = MaterialTheme.typography.labelSmall
    val headerColor = MaterialTheme.colorScheme.onSurfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#", Modifier.width(28.dp), style = headerStyle, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = headerColor)
        Text("Spieler", Modifier.weight(1f), style = headerStyle, fontWeight = FontWeight.Bold, color = headerColor)
        if (showPenalties) {
            SortableHeader("2'", 28.dp, strafenSort == TeamStrafenSort.P2, activeColor, headerColor, headerStyle) { onStrafenSortChange(TeamStrafenSort.P2) }
            SortableHeader("2+2", 32.dp, strafenSort == TeamStrafenSort.P2_2, activeColor, headerColor, headerStyle) { onStrafenSortChange(TeamStrafenSort.P2_2) }
            SortableHeader("10'", 30.dp, strafenSort == TeamStrafenSort.P10, activeColor, headerColor, headerStyle) { onStrafenSortChange(TeamStrafenSort.P10) }
            SortableHeader("MS-T", 36.dp, strafenSort == TeamStrafenSort.MS_T, activeColor, headerColor, headerStyle) { onStrafenSortChange(TeamStrafenSort.MS_T) }
            SortableHeader("MS", 28.dp, strafenSort == TeamStrafenSort.MS, activeColor, headerColor, headerStyle) { onStrafenSortChange(TeamStrafenSort.MS) }
        } else {
            Text("Sp", Modifier.width(30.dp), style = headerStyle, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = headerColor)
            Text("T", Modifier.width(28.dp), style = headerStyle, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = headerColor)
            Text("V", Modifier.width(28.dp), style = headerStyle, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = headerColor)
            Text("Pkt", Modifier.width(34.dp), style = headerStyle, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = headerColor)
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SortableHeader(
    text: String,
    width: Dp,
    isActive: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    style: androidx.compose.ui.text.TextStyle,
    onClick: () -> Unit,
) {
    Text(
        text,
        modifier = Modifier
            .width(width)
            .clickable(onClick = onClick),
        style = style,
        fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = if (isActive) activeColor else inactiveColor,
        textDecoration = if (isActive) TextDecoration.Underline else TextDecoration.None,
    )
}

// ── Scorer Row ───────────────────────────────────────────────

@Composable
private fun ScorerRow(scorer: ScorerEntry, showPenalties: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${scorer.position}",
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            "${scorer.firstName} ${scorer.lastName}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (showPenalties) {
            Text("${scorer.penalty2}", Modifier.width(28.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Text("${scorer.penalty2and2}", Modifier.width(32.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Text("${scorer.penalty10}", Modifier.width(30.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Text("${scorer.penaltyMsTech}", Modifier.width(36.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
            Text("${scorer.penaltyMsFull}", Modifier.width(28.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        } else {
            Text(
                "${scorer.games}",
                modifier = Modifier.width(30.dp),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "${scorer.goals}",
                modifier = Modifier.width(28.dp),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${scorer.assists}",
                modifier = Modifier.width(28.dp),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "${scorer.goals + scorer.assists}",
                modifier = Modifier.width(34.dp),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

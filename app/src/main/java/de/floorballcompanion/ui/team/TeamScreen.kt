package de.floorballcompanion.ui.team

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.floorballcompanion.data.local.entity.FavoriteEntity
import de.floorballcompanion.data.remote.model.ScheduledGame
import de.floorballcompanion.data.remote.model.ScorerEntry
import de.floorballcompanion.data.remote.model.TableEntry
import de.floorballcompanion.data.repository.FloorballRepository
import de.floorballcompanion.domain.TeamAggregationService
import de.floorballcompanion.domain.TeamLeagueData
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
                val tableDeferred = async { repository.refreshTable(leagueId) }
                val scorerDeferred = async { repository.getScorer(leagueId) }

                val detail = detailDeferred.await()
                val schedule = scheduleDeferred.await()
                val table = tableDeferred.await()
                val allScorers = scorerDeferred.await()

                // Filter schedule for this team
                val teamGames = schedule.filter {
                    it.homeTeamId == teamId || it.guestTeamId == teamId
                }

                val today = LocalDate.now()
                val pastGames = teamGames.filter { game ->
                    game.result != null || parseDate(game.date)?.isBefore(today) == true
                }.sortedByDescending { it.date }

                val upcomingGames = teamGames.filter { game ->
                    game.result == null && (parseDate(game.date)?.let { !it.isBefore(today) } != false)
                }.sortedBy { it.date }

                // Get team info from table or first game
                val tableEntry = table.find { it.teamId == teamId }
                val teamName = tableEntry?.teamName
                    ?: teamGames.firstOrNull()?.let {
                        if (it.homeTeamId == teamId) it.homeTeamName else it.guestTeamName
                    } ?: ""
                val teamLogo = tableEntry?.teamLogo
                    ?: teamGames.firstOrNull()?.let {
                        if (it.homeTeamId == teamId) it.homeTeamLogo else it.guestTeamLogo
                    }

                // Filter scorers for this team
                val teamScorers = allScorers.filter { it.teamId == teamId }

                val isLeague = detail.leagueModus != "cup"

                // Check if we already have cached cross-league data
                val cached = repository.getLeaguesForTeam(teamId)
                val hasExistingData = cached.size > 1

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
                        scorers = teamScorers,
                        hasSearchedMoreLeagues = hasExistingData,
                        isLoading = false,
                    )
                }

                // If cached data exists, load it automatically
                if (hasExistingData) {
                    loadCachedCrossLeagueData()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Fehler beim Laden")
                }
            }
        }
    }

    private suspend fun loadCachedCrossLeagueData() {
        try {
            val results = aggregationService.discoverLeaguesForTeam(teamId, leagueId)
            _uiState.update {
                it.copy(
                    additionalLeagues = results,
                    hasSearchedMoreLeagues = true,
                )
            }
        } catch (_: Exception) {
            // Silently fail for cached data loading
        }
    }

    fun searchMoreLeagues() {
        if (_uiState.value.isSearchingMoreLeagues) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearchingMoreLeagues = true, searchProgress = "Suche Wettbewerbe...") }
            try {
                val results = aggregationService.discoverLeaguesForTeam(
                    teamId = teamId,
                    knownLeagueId = leagueId,
                    onProgress = { current, total ->
                        _uiState.update { it.copy(searchProgress = "Pruefe Liga $current von $total...") }
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
                _uiState.update {
                    it.copy(
                        isSearchingMoreLeagues = false,
                        hasSearchedMoreLeagues = true,
                        searchProgress = null,
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
                        //onClubClick = onClubClick, TODO fix
                    )
                }
            }
        }
    }
}

// ── Main Content ─────────────────────────────────────────────

@Composable
private fun TeamContent(
    uiState: TeamDetailUiState,
    viewModel: TeamDetailViewModel,
    onGameClick: (Int) -> Unit,
    onLeagueClick: (Int) -> Unit,
    onTeamClick: (Int, Int) -> Unit,
) {
    // Use cross-league data if available
    val hasAdditional = uiState.additionalLeagues.isNotEmpty()
    val allUpcoming = if (hasAdditional) uiState.allUpcomingGames else uiState.upcomingGames.map { uiState.leagueName to it }
    val allPast = if (hasAdditional) uiState.allPastGames else uiState.pastGames.map { uiState.leagueName to it }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        // Naechstes Spiel + aufklappbar alle kommenden
        if (allUpcoming.isNotEmpty()) {
            item(key = "upcoming") {
                ExpandableGameSectionWithLeague(
                    title = "Nächstes Spiel",
                    primaryGame = allUpcoming.first(),
                    allGames = allUpcoming,
                    teamId = uiState.teamId,
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
                    teamId = uiState.teamId,
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
                    onLeagueClick = onLeagueClick,
                    onTeamClick = onTeamClick,
                )
            }
        }

        // "Weitere Wettbewerbe" Button
        if (!uiState.hasSearchedMoreLeagues && !uiState.isSearchingMoreLeagues) {
            item(key = "search-more") {
                OutlinedButton(
                    onClick = { viewModel.searchMoreLeagues() },
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

        // Cross-league results summary
        if (hasAdditional) {
            item(key = "cross-league-info") {
                Text(
                    "Weitere Wettbewerbe: ${uiState.additionalLeagues.joinToString { it.leagueName }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
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

        // Scorer section with tabs (if cross-league data available)
        val scorerData = if (hasAdditional) {
            buildList {
                add("Alle" to uiState.aggregatedScorers)
                add(uiState.leagueName to uiState.scorers)
                uiState.additionalLeagues.forEach { league ->
                    if (league.scorers.isNotEmpty()) {
                        add(league.leagueName to league.scorers)
                    }
                }
            }
        } else {
            listOf(uiState.leagueName to uiState.scorers)
        }

        val activeScorers = scorerData.getOrNull(uiState.selectedScorerTab)?.second ?: uiState.scorers

        if (scorerData.any { it.second.isNotEmpty() }) {
            item(key = "scorer-header") {
                Text(
                    "Scorer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }

            // Scorer tabs (only if multiple)
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

            // Scorer header row
            item(key = "scorer-col-header") {
                ScorerHeaderRow()
            }

            items(activeScorers.take(20), key = { "scorer-${uiState.selectedScorerTab}-${it.playerId}" }) { scorer ->
                ScorerRow(scorer)
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
    teamId: Int,
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
                teamId = teamId,
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
                                teamId = teamId,
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
    teamId: Int,
    leagueName: String,
    onGameClick: (Int) -> Unit,
) {
    val isHome = game.homeTeamId == teamId
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
                isWin -> MaterialTheme.colorScheme.primary
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
                Text(
                    "Tabelle",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
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
                                onTeamClick(entry.tableEntry.teamId, leagueId)
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
private fun ScorerHeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Spieler", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Sp", Modifier.width(30.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("T", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("V", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Pkt", Modifier.width(34.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(32.dp)) // penalty column space
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

// ── Scorer Row ───────────────────────────────────────────────

@Composable
private fun ScorerRow(scorer: ScorerEntry) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${scorer.firstName} ${scorer.lastName}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
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
        // Penalties summary
        val totalPenalties = scorer.penalty2 + scorer.penalty2and2 + scorer.penalty10 +
            scorer.penaltyMsTech + scorer.penaltyMsFull
        if (totalPenalties > 0) {
            Text(
                "${totalPenalties}x",
                modifier = Modifier.width(32.dp),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Spacer(Modifier.width(32.dp))
        }
    }
}

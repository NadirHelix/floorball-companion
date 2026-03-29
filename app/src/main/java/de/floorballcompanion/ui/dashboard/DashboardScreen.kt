@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package de.floorballcompanion.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.floorballcompanion.data.local.entity.FavoriteEntity
import de.floorballcompanion.data.local.entity.TeamLeagueMapping
import de.floorballcompanion.data.remote.model.ScheduledGame
import de.floorballcompanion.data.remote.model.ScorerEntry
import de.floorballcompanion.data.remote.model.TableEntry
import de.floorballcompanion.data.repository.FloorballRepository
import de.floorballcompanion.ui.components.TeamLogo
import de.floorballcompanion.ui.team.TeamFavoriteColor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject

// ── Data classes ──────────────────────────────────────────────

data class TeamCardState(
    val favorite: FavoriteEntity,
    val nextGame: ScheduledGame? = null,
    val lastGame: ScheduledGame? = null,
    val upcomingGames: List<ScheduledGame> = emptyList(),
    val pastGames: List<ScheduledGame> = emptyList(),
    val leagues: List<TeamLeagueMapping> = emptyList(),
    val isLoading: Boolean = true,
)

data class LeagueCardState(
    val favorite: FavoriteEntity,
    val currentGameDay: List<ScheduledGame> = emptyList(),
    val table: List<TableEntry> = emptyList(),
    val scorers: List<ScorerEntry> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val hasLiveGame: Boolean get() = currentGameDay.any { it.gameStatus == "live" }
}

// ── ViewModel ─────────────────────────────────────────────────

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: FloorballRepository,
) : ViewModel() {

    val favoriteTeams: StateFlow<List<FavoriteEntity>> =
        repository.observeFavoriteTeams()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteLeagues: StateFlow<List<FavoriteEntity>> =
        repository.observeFavoriteLeagues()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _teamCards = MutableStateFlow<List<TeamCardState>>(emptyList())
    val teamCards = _teamCards.asStateFlow()

    private val _leagueCards = MutableStateFlow<List<LeagueCardState>>(emptyList())
    val leagueCards = _leagueCards.asStateFlow()

    private val _leagueTabSelection = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val leagueTabSelection = _leagueTabSelection.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    init {
        viewModelScope.launch {
            favoriteTeams.collect { favs ->
                _teamCards.value = favs.map { TeamCardState(favorite = it) }
                favs.forEachIndexed { index, fav ->
                    launch { loadTeamCard(index, fav) }
                }
            }
        }
        viewModelScope.launch {
            favoriteLeagues.collect { favs ->
                _leagueCards.value = favs.map { LeagueCardState(favorite = it) }
                favs.forEachIndexed { index, fav ->
                    launch { loadLeagueCard(index, fav) }
                }
            }
        }
        // Live polling
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                refreshLiveGames()
            }
        }
    }

    private suspend fun loadTeamCard(index: Int, fav: FavoriteEntity) {
        val leagueId = fav.leagueId ?: run {
            _teamCards.update { cards ->
                cards.toMutableList().also { if (index < it.size) it[index] = it[index].copy(isLoading = false) }
            }
            return
        }
        try {
            val schedule = repository.getSchedule(leagueId)
            val teamId = fav.externalId
            val teamGames = schedule.filter { it.homeTeamId == teamId || it.guestTeamId == teamId }
            val today = LocalDate.now()
            val pastGames = teamGames
                .filter { game -> game.result != null || parseGameDate(game.date)?.isBefore(today) == true }
                .sortedByDescending { it.date }
            val upcomingGames = teamGames
                .filter { game -> game.result == null && (parseGameDate(game.date)?.let { !it.isBefore(today) } != false) }
                .sortedBy { it.date }
            val leagues = repository.getLeaguesForTeam(teamId)
            _teamCards.update { cards ->
                cards.toMutableList().also {
                    if (index < it.size) {
                        it[index] = it[index].copy(
                            lastGame = pastGames.firstOrNull(),
                            nextGame = upcomingGames.firstOrNull(),
                            pastGames = pastGames,
                            upcomingGames = upcomingGames,
                            leagues = leagues,
                            isLoading = false,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            _teamCards.update { cards ->
                cards.toMutableList().also { if (index < it.size) it[index] = it[index].copy(isLoading = false) }
            }
        }
    }

    private suspend fun loadLeagueCard(index: Int, fav: FavoriteEntity) {
        try {
            coroutineScope {
                val gameDayDeferred = async { repository.getCurrentGameDay(fav.externalId) }
                val tableDeferred = async {
                    try { repository.refreshTable(fav.externalId) } catch (_: Exception) { emptyList() }
                }
                val scorerDeferred = async {
                    try { repository.getScorer(fav.externalId) } catch (e: Exception) { emptyList<ScorerEntry>() }
                }
                val gameDay = gameDayDeferred.await()
                val table = tableDeferred.await()
                val scorers = scorerDeferred.await()
                _leagueCards.update { cards ->
                    cards.toMutableList().also {
                        if (index < it.size) {
                            it[index] = it[index].copy(
                                currentGameDay = gameDay,
                                table = table,
                                scorers = scorers,
                                isLoading = false,
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            _leagueCards.update { cards ->
                cards.toMutableList().also {
                    if (index < it.size) it[index] = it[index].copy(isLoading = false, error = e.message)
                }
            }
        }
    }

    private fun refreshLiveGames() {
        viewModelScope.launch {
            _leagueCards.value.forEachIndexed { index, state ->
                if (state.hasLiveGame) {
                    launch {
                        try {
                            val gameDay = repository.getCurrentGameDay(state.favorite.externalId)
                            _leagueCards.update { cards ->
                                cards.toMutableList().also {
                                    if (index < it.size) it[index] = it[index].copy(currentGameDay = gameDay)
                                }
                            }
                        } catch (e: Exception) { /* ignore */ }
                    }
                }
            }
        }
    }

    fun selectLeagueTab(leagueId: Int, tab: Int) {
        _leagueTabSelection.update { it + (leagueId to tab) }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val leagues = favoriteLeagues.value
            leagues.forEachIndexed { index, fav ->
                launch { loadLeagueCard(index, fav) }
            }
            _isRefreshing.value = false
        }
    }

    fun removeFavorite(favorite: FavoriteEntity) {
        viewModelScope.launch {
            repository.removeFavorite(favorite.type, favorite.externalId)
        }
    }

    fun moveFavorite(type: String, list: List<FavoriteEntity>, fromIndex: Int, toIndex: Int) {
        if (toIndex < 0 || toIndex >= list.size) return
        viewModelScope.launch {
            val itemA = list[fromIndex]
            val itemB = list[toIndex]
            repository.updateFavoriteSortOrder(type, itemA.externalId, toIndex)
            repository.updateFavoriteSortOrder(type, itemB.externalId, fromIndex)
        }
    }
}

// ── Screen ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onTeamClick: (teamId: Int, leagueId: Int) -> Unit = { _, _ -> },
    onLeagueClick: (leagueId: Int) -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val teamCards by viewModel.teamCards.collectAsState()
    val leagueCards by viewModel.leagueCards.collectAsState()
    val leagueTabSelection by viewModel.leagueTabSelection.collectAsState()
    val favoriteTeams by viewModel.favoriteTeams.collectAsState()
    val favoriteLeagues by viewModel.favoriteLeagues.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    var showManageSheet by remember { mutableStateOf(false) }

    val hasFavorites = favoriteTeams.isNotEmpty() || favoriteLeagues.isNotEmpty()
    val anyLive = leagueCards.any { it.hasLiveGame }

    if (!hasFavorites) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.SportsHockey,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Willkommen!",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Gehe zum Tab \"Ligen\", um Verbände zu\ndurchsuchen und deine Lieblingsligen\noder Teams als Favoriten zu markieren.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Dashboard",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (anyLive) {
                        LiveBadge()
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren")
                        }
                    }
                    IconButton(onClick = { showManageSheet = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "Favoriten verwalten")
                    }
                }
            }

            itemsIndexed(teamCards, key = { _, s -> "team_${s.favorite.externalId}" }) { _, state ->
                TeamDashCard(
                    state = state,
                    onTeamClick = {
                        onTeamClick(state.favorite.externalId, state.favorite.leagueId ?: 0)
                    },
                    onLeagueClick = onLeagueClick,
                )
            }

            itemsIndexed(leagueCards, key = { _, s -> "league_${s.favorite.externalId}" }) { _, state ->
                val selectedTab = leagueTabSelection[state.favorite.externalId] ?: 0
                LeagueDashCard(
                    state = state,
                    selectedTab = selectedTab,
                    onTabSelected = { tab -> viewModel.selectLeagueTab(state.favorite.externalId, tab) },
                    onLeagueClick = { onLeagueClick(state.favorite.externalId) },
                    onGameClick = { /* no game navigation from dashboard */ },
                )
            }
        }
    }

    if (showManageSheet) {
        FavoritesManageSheet(
            favoriteTeams = favoriteTeams,
            favoriteLeagues = favoriteLeagues,
            onDismiss = { showManageSheet = false },
            onRemove = { viewModel.removeFavorite(it) },
            onMoveTeamUp = { index -> viewModel.moveFavorite("team", favoriteTeams, index, index - 1) },
            onMoveTeamDown = { index -> viewModel.moveFavorite("team", favoriteTeams, index, index + 1) },
            onMoveLeagueUp = { index -> viewModel.moveFavorite("league", favoriteLeagues, index, index - 1) },
            onMoveLeagueDown = { index -> viewModel.moveFavorite("league", favoriteLeagues, index, index + 1) },
        )
    }
}

// ── Live Badge ────────────────────────────────────────────────

@Composable
private fun LiveBadge() {
    Surface(
        color = MaterialTheme.colorScheme.error,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ── Team Dash Card ────────────────────────────────────────────

@Composable
private fun TeamDashCard(
    state: TeamCardState,
    onTeamClick: () -> Unit,
    onLeagueClick: (leagueId: Int) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onTeamClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TeamLogo(state.favorite.logoUrl, state.favorite.name, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.favorite.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TeamFavoriteColor,
                    )
                    state.favorite.leagueName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (state.isLoading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                val teamId = state.favorite.externalId

                // Next game(s) - expandable
                if (state.upcomingGames.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(6.dp))
                    DashExpandableGameSection(
                        title = "Nächstes Spiel",
                        games = state.upcomingGames,
                        teamId = teamId,
                    )
                }

                // Last game(s) - expandable
                if (state.pastGames.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(6.dp))
                    DashExpandableGameSection(
                        title = "Zuletzt gespielt",
                        games = state.pastGames,
                        teamId = teamId,
                    )
                }

                // Leagues
                val displayLeagues = if (state.leagues.isNotEmpty()) {
                    state.leagues
                } else {
                    // Fallback: show primary league from favorite
                    state.favorite.leagueId?.let { lid ->
                        listOf(
                            TeamLeagueMapping(
                                teamId = state.favorite.externalId,
                                leagueId = lid,
                                leagueName = state.favorite.leagueName ?: "",
                                gameOperationId = 0,
                                gameOperationName = state.favorite.gameOperationName ?: "",
                            )
                        )
                    } ?: emptyList()
                }

                if (displayLeagues.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        displayLeagues.forEach { league ->
                            AssistChip(
                                onClick = { onLeagueClick(league.leagueId) },
                                label = {
                                    Text(
                                        league.leagueName,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                },
                                modifier = Modifier.height(28.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashGameRow(game: ScheduledGame, teamId: Int) {
    val isHome = game.homeTeamId == teamId
    val opponentName = if (isHome) game.guestTeamName else game.homeTeamName
    val opponentLogo = if (isHome) game.guestTeamLogo else game.homeTeamLogo

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatDate(game.date),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            if (isHome) "H" else "A",
            style = MaterialTheme.typography.labelSmall,
            color = if (isHome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(16.dp),
        )
        TeamLogo(opponentLogo, opponentName, size = 18.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            opponentName,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        game.result?.let { result ->
            val homeGoals = result.homeGoals
            val guestGoals = result.guestGoals
            val (myGoals, opGoals) = if (isHome) homeGoals to guestGoals else guestGoals to homeGoals
            val won = myGoals > opGoals
            Text(
                "$homeGoals:$guestGoals",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (won) TeamFavoriteColor else MaterialTheme.colorScheme.error,
            )
        } ?: run {
            Text(
                game.time.take(5),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DashExpandableGameSection(
    title: String,
    games: List<ScheduledGame>,
    teamId: Int,
) {
    var expanded by remember { mutableStateOf(false) }

    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(2.dp))
    DashGameRow(game = games.first(), teamId = teamId)

    if (games.size > 1) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) "Weniger" else "Alle ${games.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 2.dp)) {
                games.drop(1).forEach { game ->
                    Spacer(Modifier.height(2.dp))
                    DashGameRow(game = game, teamId = teamId)
                }
            }
        }
    }
}

// ── League Dash Card ──────────────────────────────────────────

@Composable
private fun LeagueDashCard(
    state: LeagueCardState,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onLeagueClick: () -> Unit,
    onGameClick: (Int) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onLeagueClick)
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.favorite.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.favorite.gameOperationName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.hasLiveGame) {
                    LiveBadge()
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (state.error != null) {
                Text(
                    "Fehler beim Laden",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                // Compact chip row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("Spieltag", "Tabelle", "Scorer").forEachIndexed { index, title ->
                        FilterChip(
                            selected = selectedTab == index,
                            onClick = { onTabSelected(index) },
                            label = {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            modifier = Modifier.height(28.dp),
                        )
                    }
                }

                when (selectedTab) {
                    0 -> LeagueGameDayTab(state = state)
                    1 -> LeagueTableTab(state = state)
                    2 -> LeagueScorerTab(state = state)
                }
            }
        }
    }
}

@Composable
private fun LeagueGameDayTab(state: LeagueCardState) {
    if (state.currentGameDay.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Kein aktueller Spieltag",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            state.currentGameDay.forEach { game ->
                GameDayRow(game = game)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun GameDayRow(game: ScheduledGame) {
    val isLive = game.gameStatus == "live"
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatDate(game.date),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                game.homeTeamName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
                textAlign = TextAlign.End,
            )
            Spacer(Modifier.width(4.dp))
            TeamLogo(game.homeTeamLogo, game.homeTeamName, size = 16.dp)
        }
        Spacer(Modifier.width(4.dp))
        if (isLive) {
            Text(
                "LIVE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            game.result?.let { result ->
                Text(
                    "${result.homeGoals}:${result.guestGoals}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(44.dp),
                    textAlign = TextAlign.Center,
                )
            } ?: Text(
                game.time.take(5),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.width(4.dp))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeamLogo(game.guestTeamLogo, game.guestTeamName, size = 16.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                game.guestTeamName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LeagueTableTab(state: LeagueCardState) {
    if (state.table.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Keine Tabellendaten",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        DashMiniTable(entries = state.table)
    }
}

@Composable
private fun DashMiniTable(entries: List<TableEntry>) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#", Modifier.width(22.dp), style = dashHeaderStyle(), textAlign = TextAlign.Center)
            Spacer(Modifier.width(22.dp))
            Text("Team", Modifier.width(140.dp), style = dashHeaderStyle())
            Text("Sp", Modifier.width(28.dp), style = dashHeaderStyle(), textAlign = TextAlign.Center)
            Text("S", Modifier.width(24.dp), style = dashHeaderStyle(), textAlign = TextAlign.Center)
            Text("N", Modifier.width(24.dp), style = dashHeaderStyle(), textAlign = TextAlign.Center)
            Text("Pkt", Modifier.width(32.dp), style = dashHeaderStyle(), textAlign = TextAlign.Center)
        }
        HorizontalDivider()
        entries.forEach { entry ->
            Row(
                modifier = Modifier.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${entry.position}",
                    Modifier.width(22.dp),
                    style = dashCellStyle(),
                    textAlign = TextAlign.Center,
                )
                TeamLogo(entry.teamLogo, entry.teamName, size = 16.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    entry.teamName,
                    Modifier.width(140.dp),
                    style = dashCellStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${entry.games}", Modifier.width(28.dp), style = dashCellStyle(), textAlign = TextAlign.Center)
                Text("${entry.won}", Modifier.width(24.dp), style = dashCellStyle(), textAlign = TextAlign.Center)
                Text("${entry.lost}", Modifier.width(24.dp), style = dashCellStyle(), textAlign = TextAlign.Center)
                Text(
                    "${entry.points}",
                    Modifier.width(32.dp),
                    style = dashCellStyle(),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun LeagueScorerTab(state: LeagueCardState) {
    val teamLogoMap = remember(state.table) {
        state.table.associate { it.teamId to it.teamLogo }
    }
    if (state.scorers.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Keine Scorerdaten",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#", Modifier.width(22.dp), style = dashHeaderStyle(), textAlign = TextAlign.Center)
                Spacer(Modifier.width(20.dp)) // logo space
                Text("Spieler", Modifier.weight(1f), style = dashHeaderStyle())
                Text("T", Modifier.width(24.dp), style = dashHeaderStyle(), textAlign = TextAlign.Center)
                Text("A", Modifier.width(24.dp), style = dashHeaderStyle(), textAlign = TextAlign.Center)
                Text("Pkt", Modifier.width(30.dp), style = dashHeaderStyle(), textAlign = TextAlign.Center)
            }
            HorizontalDivider()
            state.scorers.take(5).forEach { scorer ->
                Row(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${scorer.position}",
                        Modifier.width(22.dp),
                        style = dashCellStyle(),
                        textAlign = TextAlign.Center,
                    )
                    TeamLogo(teamLogoMap[scorer.teamId], scorer.teamName, size = 16.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${scorer.firstName} ${scorer.lastName}",
                        Modifier.weight(1f),
                        style = dashCellStyle(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${scorer.goals}",
                        Modifier.width(24.dp),
                        style = dashCellStyle(),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "${scorer.assists}",
                        Modifier.width(24.dp),
                        style = dashCellStyle(),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "${scorer.goals + scorer.assists}",
                        Modifier.width(30.dp),
                        style = dashCellStyle(),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ── Favorites Manage Sheet ────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesManageSheet(
    favoriteTeams: List<FavoriteEntity>,
    favoriteLeagues: List<FavoriteEntity>,
    onDismiss: () -> Unit,
    onRemove: (FavoriteEntity) -> Unit,
    onMoveTeamUp: (Int) -> Unit,
    onMoveTeamDown: (Int) -> Unit,
    onMoveLeagueUp: (Int) -> Unit,
    onMoveLeagueDown: (Int) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Favoriten verwalten",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            if (favoriteTeams.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    Icon(
                        Icons.Default.Groups,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = TeamFavoriteColor,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Teams",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = TeamFavoriteColor,
                    )
                }
                favoriteTeams.forEachIndexed { index, fav ->
                    ManageRow(
                        favorite = fav,
                        canMoveUp = index > 0,
                        canMoveDown = index < favoriteTeams.size - 1,
                        onMoveUp = { onMoveTeamUp(index) },
                        onMoveDown = { onMoveTeamDown(index) },
                        onRemove = { onRemove(fav) },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            if (favoriteLeagues.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    Icon(
                        Icons.Default.EmojiEvents,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Ligen",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                favoriteLeagues.forEachIndexed { index, fav ->
                    ManageRow(
                        favorite = fav,
                        canMoveUp = index > 0,
                        canMoveDown = index < favoriteLeagues.size - 1,
                        onMoveUp = { onMoveLeagueUp(index) },
                        onMoveDown = { onMoveLeagueDown(index) },
                        onRemove = { onRemove(fav) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ManageRow(
    favorite: FavoriteEntity,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (favorite.type == "team") {
            TeamLogo(favorite.logoUrl, favorite.name, size = 28.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                favorite.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            favorite.leagueName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "Nach oben",
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Nach unten",
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Entfernen",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Favorit entfernen?") },
            text = { Text("\"${favorite.name}\" aus den Favoriten entfernen?") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove()
                    showDeleteDialog = false
                }) {
                    Text("Entfernen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Abbrechen") }
            },
        )
    }
}

// ── Style Helpers ─────────────────────────────────────────────

@Composable
private fun dashHeaderStyle() = MaterialTheme.typography.labelSmall.copy(
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun dashCellStyle() = MaterialTheme.typography.bodySmall

// ── Date Helpers ──────────────────────────────────────────────

private val dateParser = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val dateDisplay = DateTimeFormatter.ofPattern("dd.MM.")

private fun parseGameDate(dateStr: String): LocalDate? = try {
    LocalDate.parse(dateStr, dateParser)
} catch (e: DateTimeParseException) {
    null
}

private fun formatDate(dateStr: String): String =
    parseGameDate(dateStr)?.format(dateDisplay) ?: dateStr

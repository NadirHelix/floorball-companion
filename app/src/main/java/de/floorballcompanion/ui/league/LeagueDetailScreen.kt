package de.floorballcompanion.ui.league

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.floorballcompanion.data.local.entity.FavoriteEntity
import de.floorballcompanion.ui.components.TeamLogo
import de.floorballcompanion.data.remote.model.GameDayTitle
import de.floorballcompanion.data.remote.model.ScheduledGame
import de.floorballcompanion.data.remote.model.ScorerEntry
import de.floorballcompanion.data.remote.model.TableEntry
import de.floorballcompanion.data.repository.FloorballRepository
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

// ── ViewModel ────────────────────────────────────────────────

data class LeagueDetailUiState(
    val leagueName: String = "",
    val gameOperationSlug: String? = null,
    val gameOperationName: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val allGames: List<ScheduledGame> = emptyList(),
    val availableGameDays: List<Int> = emptyList(),
    val gameDayTitles: Map<Int, String> = emptyMap(),
    val currentGameDayIndex: Int = 0,
    val table: List<TableEntry> = emptyList(),
    val scorers: List<ScorerEntry> = emptyList(),
    val selectedTab: Int = 0,
) {
    val currentGameDayNumber: Int? get() = availableGameDays.getOrNull(currentGameDayIndex)
    val gamesForCurrentDay: List<ScheduledGame>
        get() = allGames.filter { it.gameDayNumber == currentGameDayNumber }
}

@HiltViewModel
class LeagueDetailViewModel @Inject constructor(
    private val repository: FloorballRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val leagueId: Int = savedStateHandle.get<Int>("leagueId") ?: 0

    private val _uiState = MutableStateFlow(LeagueDetailUiState())
    val uiState = _uiState.asStateFlow()

    val isFavorite: StateFlow<Boolean> =
        repository.isFavorite("league", leagueId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val scheduleDeferred = async { repository.getSchedule(leagueId) }
                val tableDeferred = async { repository.refreshTable(leagueId) }
                val scorerDeferred = async { repository.getScorer(leagueId) }
                val detailDeferred = async { repository.getLeagueDetail(leagueId) }

                val schedule = scheduleDeferred.await()
                val table = tableDeferred.await()
                val scorers = scorerDeferred.await()
                val detail = detailDeferred.await()

                val availableGameDays = schedule
                    .mapNotNull { it.gameDayNumber }
                    .distinct()
                    .sorted()

                val gameDayTitles = detail.gameDayTitles.associate { it.gameDayNumber to it.title }

                val currentDayNumber = determineCurrentGameDayNumber(schedule)
                val currentIndex = availableGameDays.indexOf(currentDayNumber).coerceAtLeast(0)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        leagueName = detail.name,
                        gameOperationSlug = detail.gameOperationSlug,
                        gameOperationName = detail.gameOperationName,
                        allGames = schedule,
                        availableGameDays = availableGameDays,
                        gameDayTitles = gameDayTitles,
                        currentGameDayIndex = currentIndex,
                        table = table,
                        scorers = scorers,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Laden fehlgeschlagen")
                }
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
    fun retry() = loadData()

    fun toggleFavorite() {
        viewModelScope.launch {
            if (isFavorite.value) {
                repository.removeFavorite("league", leagueId)
            } else {
                val state = _uiState.value
                val nextOrder = repository.getMaxFavoriteSortOrder("league") + 1
                repository.addFavorite(
                    FavoriteEntity(
                        type = "league",
                        externalId = leagueId,
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

// ── Hilfsfunktionen ──────────────────────────────────────────

private val isoDateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val deDotDateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun parseDate(dateStr: String): LocalDate? {
    for (fmt in listOf(isoDateFmt, deDotDateFmt)) {
        try { return LocalDate.parse(dateStr, fmt) } catch (_: DateTimeParseException) {}
    }
    return null
}

/**
 * Bestimmt den aktuell anzuzeigenden Spieltag:
 * Der erste Spieltag, dessen letztes Spiel weniger als 3 Tage her ist (bzw. noch aussteht).
 * Sind alle Spieltage abgeschlossen, wird der letzte zurückgegeben.
 */
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

// ── Screen ───────────────────────────────────────────────────

@Composable
fun LeagueDetailScreen(
    onBack: () -> Unit,
    viewModel: LeagueDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val isFavorite by viewModel.isFavorite.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Header mit Zurück-Button und Favoriten-Stern
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
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
                    0 -> GameDayTab(uiState, { viewModel.previousGameDay() }, { viewModel.nextGameDay() })
                    1 -> TableTab(uiState.table)
                    2 -> ScorerTab(uiState.scorers)
                }
            }
        }
    }
}

// ── Spieltag-Tab ─────────────────────────────────────────────

@Composable
private fun GameDayTab(
    uiState: LeagueDetailUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        val dayNumber = uiState.currentGameDayNumber
        val dayTitle = dayNumber?.let { uiState.gameDayTitles[it] ?: "$it. Spieltag" } ?: "Spieltag"

        // Navigation: Vorheriger / Spieltag-Titel / Nächster
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
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Nächster Spieltag")
            }
        }

        HorizontalDivider()

        if (uiState.gamesForCurrentDay.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Keine Spiele für diesen Spieltag", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(uiState.gamesForCurrentDay, key = { it.resolvedGameId }) { game ->
                    GameCard(game)
                }
            }
        }
    }
}

@Composable
private fun GameCard(game: ScheduledGame) {
    val dateDisplay = remember(game.date) {
        parseDate(game.date)?.format(deDotDateFmt) ?: game.date
    }
    val hasResult = game.homeGoals != null && game.guestGoals != null
    val isLive = game.gameStatus?.lowercase() in listOf("live", "1", "2", "3")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Home-Team mit Logo
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TeamLogo(
                        logoUrl = game.homeTeamLogo,
                        contentDescription = game.homeTeamName,
                        size = 28.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = game.homeTeamName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                    )
                }
                if (hasResult) {
                    Text(
                        text = "${game.homeGoals} : ${game.guestGoals}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                } else {
                    Text(
                        text = "–",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                // Gast-Team mit Logo
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = game.guestTeamName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    TeamLogo(
                        logoUrl = game.guestTeamLogo,
                        contentDescription = game.guestTeamName,
                        size = 28.dp,
                    )
                }
            }
        }
    }
}

// ── Tabelle-Tab ──────────────────────────────────────────────

@Composable
private fun TableTab(table: List<TableEntry>) {
    if (table.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Keine Tabellendaten vorhanden", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val hScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.horizontalScroll(hScroll)) {
            // Header
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TableHeader("#", 28.dp)
                Spacer(Modifier.width(24.dp)) // Platz für Logo
                TableHeader("Team", 170.dp, TextAlign.Start)
                TableHeader("Sp", 32.dp)
                TableHeader("S", 28.dp)
                TableHeader("SO", 28.dp)
                TableHeader("NO", 28.dp)
                TableHeader("N", 28.dp)
                TableHeader("Tore", 64.dp)
                TableHeader("Diff", 44.dp)
                TableHeader("Pkt", 40.dp)
            }
            HorizontalDivider()
            table.forEach { entry ->
                val diffText = if (entry.goalsDiff > 0) "+${entry.goalsDiff}" else "${entry.goalsDiff}"
                val diffColor = when {
                    entry.goalsDiff > 0 -> MaterialTheme.colorScheme.primary
                    entry.goalsDiff < 0 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Row(
                    modifier = Modifier.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TableCell("${entry.position}", 28.dp, fontWeight = FontWeight.Bold)
                    TeamLogo(
                        logoUrl = entry.teamLogo,
                        contentDescription = entry.teamName,
                        size = 20.dp,
                    )
                    TableCell(entry.teamName, 170.dp, textAlign = TextAlign.Start, fontWeight = FontWeight.Medium, maxLines = 1)
                    TableCell("${entry.games}", 32.dp)
                    TableCell("${entry.won}", 28.dp)
                    TableCell("${entry.wonOt}", 28.dp)
                    TableCell("${entry.lostOt}", 28.dp)
                    TableCell("${entry.lost}", 28.dp)
                    TableCell("${entry.goalsScored}:${entry.goalsReceived}", 64.dp)
                    TableCell(diffText, 44.dp, color = diffColor, fontWeight = FontWeight.Medium)
                    TableCell("${entry.points}", 40.dp, fontWeight = FontWeight.Bold)
                }
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

// ── Scorer-Tab ───────────────────────────────────────────────

@Composable
private fun ScorerTab(scorers: List<ScorerEntry>) {
    if (scorers.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Keine Scorerdaten vorhanden", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Text("Spieler / Team", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Sp", Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Text("T", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Text("V", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Text("Pkt", Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
        items(scorers, key = { it.playerId }) { scorer ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${scorer.position}",
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
                Text("${scorer.games}", Modifier.width(32.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
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
                    Modifier.width(36.dp),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

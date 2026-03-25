package de.floorballcompanion.ui.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsHockey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.floorballcompanion.data.local.entity.FavoriteEntity
import de.floorballcompanion.ui.components.TeamLogo
import de.floorballcompanion.data.remote.model.TableEntry
import de.floorballcompanion.data.repository.FloorballRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────

data class LeagueTableState(
    val favorite: FavoriteEntity,
    val table: List<TableEntry> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: FloorballRepository,
) : ViewModel() {

    private val _leagueTables = MutableStateFlow<List<LeagueTableState>>(emptyList())
    val leagueTables = _leagueTables.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    val favoriteLeagues: StateFlow<List<FavoriteEntity>> =
        repository.observeFavoriteLeagues()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Reagiere auf Favoriten-Änderungen
        viewModelScope.launch {
            favoriteLeagues.collect { favs ->
                if (favs.isNotEmpty()) {
                    loadTablesForFavorites(favs)
                } else {
                    _leagueTables.value = emptyList()
                }
            }
        }
    }

    private fun loadTablesForFavorites(favs: List<FavoriteEntity>) {
        viewModelScope.launch {
            // Initiale States setzen
            _leagueTables.value = favs.map { LeagueTableState(favorite = it) }

            // Parallel laden
            favs.forEachIndexed { index, fav ->
                launch {
                    try {
                        val table = repository.refreshTable(fav.externalId)
                        _leagueTables.update { current ->
                            current.toMutableList().also {
                                if (index < it.size) {
                                    it[index] = it[index].copy(
                                        table = table,
                                        isLoading = false,
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        _leagueTables.update { current ->
                            current.toMutableList().also {
                                if (index < it.size) {
                                    it[index] = it[index].copy(
                                        isLoading = false,
                                        error = e.message,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            loadTablesForFavorites(favoriteLeagues.value)
            _isRefreshing.value = false
        }
    }
}

// ── Screen ───────────────────────────────────────────────────

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val leagueTables by viewModel.leagueTables.collectAsState()
    val favoriteLeagues by viewModel.favoriteLeagues.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    if (favoriteLeagues.isEmpty()) {
        // Leerer Zustand
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
                    text = "Gehe zum Tab \"Ligen\", um Verbände zu\ndurchsuchen und deine Lieblingsligen\nals Favoriten zu markieren.",
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
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Dashboard",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.refresh() }) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren")
                        }
                    }
                }
            }

            items(leagueTables, key = { it.favorite.externalId }) { state ->
                LeagueTableCard(state)
            }
        }
    }
}

// ── Liga-Tabellen-Card ───────────────────────────────────────

@Composable
private fun LeagueTableCard(state: LeagueTableState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Text(
                text = state.favorite.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            state.favorite.gameOperationName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                state.error != null -> {
                    Text(
                        text = "Fehler: ${state.error}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.table.isEmpty() -> {
                    Text(
                        text = "Keine Tabellendaten vorhanden",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    MiniTable(state.table)
                }
            }
        }
    }
}

// ── Kompakte Tabelle ─────────────────────────────────────────

@Composable
private fun MiniTable(entries: List<TableEntry>) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
    ) {
        // Header-Zeile
        Row(
            modifier = Modifier.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("#", Modifier.width(24.dp), style = headerStyle(), textAlign = TextAlign.Center)
            Spacer(Modifier.width(24.dp)) // Platz für Logo
            Text("Team", Modifier.width(160.dp), style = headerStyle())
            Text("Sp", Modifier.width(32.dp), style = headerStyle(), textAlign = TextAlign.Center)
            Text("S", Modifier.width(28.dp), style = headerStyle(), textAlign = TextAlign.Center)
            Text("N", Modifier.width(28.dp), style = headerStyle(), textAlign = TextAlign.Center)
            Text("Tore", Modifier.width(56.dp), style = headerStyle(), textAlign = TextAlign.Center)
            Text("Diff", Modifier.width(40.dp), style = headerStyle(), textAlign = TextAlign.Center)
            Text("Pkt", Modifier.width(36.dp), style = headerStyle(), textAlign = TextAlign.Center)
        }

        HorizontalDivider()

        // Daten
        entries.forEach { entry ->
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${entry.position}",
                    Modifier.width(24.dp),
                    style = cellStyle(),
                    textAlign = TextAlign.Center,
                )
                TeamLogo(
                    logoUrl = entry.teamLogo,
                    contentDescription = entry.teamName,
                    size = 18.dp,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    entry.teamName,
                    Modifier.width(160.dp),
                    style = cellStyle(),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    "${entry.games}",
                    Modifier.width(32.dp),
                    style = cellStyle(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    "${entry.won}",
                    Modifier.width(28.dp),
                    style = cellStyle(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    "${entry.lost}",
                    Modifier.width(28.dp),
                    style = cellStyle(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    "${entry.goalsScored}:${entry.goalsReceived}",
                    Modifier.width(56.dp),
                    style = cellStyle(),
                    textAlign = TextAlign.Center,
                )
                val diffText = if (entry.goalsDiff > 0) "+${entry.goalsDiff}" else "${entry.goalsDiff}"
                val diffColor = when {
                    entry.goalsDiff > 0 -> MaterialTheme.colorScheme.primary
                    entry.goalsDiff < 0 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    diffText,
                    Modifier.width(40.dp),
                    style = cellStyle(),
                    textAlign = TextAlign.Center,
                    color = diffColor,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${entry.points}",
                    Modifier.width(36.dp),
                    style = cellStyle(),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun headerStyle() = MaterialTheme.typography.labelSmall.copy(
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun cellStyle() = MaterialTheme.typography.bodySmall
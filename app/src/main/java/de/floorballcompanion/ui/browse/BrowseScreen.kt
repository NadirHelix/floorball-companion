package de.floorballcompanion.ui.browse

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import de.floorballcompanion.data.local.entity.FavoriteEntity
import de.floorballcompanion.ui.components.resolveLogoUrl
import de.floorballcompanion.data.remote.model.GameOperation
import de.floorballcompanion.data.remote.model.LeaguePreview
import de.floorballcompanion.data.repository.FloorballRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repository: FloorballRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState = _uiState.asStateFlow()

    // Set von favorisierten Liga-IDs (reaktiv)
    private val _favoriteLeagueIds = MutableStateFlow<Set<Int>>(emptySet())
    val favoriteLeagueIds = _favoriteLeagueIds.asStateFlow()

    // Anzahl Favoriten pro Verbands-Slug
    private val _favoriteCountBySlug = MutableStateFlow<Map<String, Int>>(emptyMap())
    val favoriteCountBySlug = _favoriteCountBySlug.asStateFlow()

    init {
        loadOperations()
        observeFavorites()
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            repository.observeFavoriteLeagues().collect { favs ->
                _favoriteLeagueIds.value = favs.map { it.externalId }.toSet()
                _favoriteCountBySlug.value = favs
                    .mapNotNull { it.gameOperationSlug }
                    .groupingBy { it }
                    .eachCount()
            }
        }
    }

    private fun loadOperations() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val init = repository.getInit()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        gameOperations = init.gameOperations,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Laden fehlgeschlagen")
                }
            }
        }
    }

    fun loadLeaguesForOperation(operation: GameOperation) {
        // Toggle: wenn schon offen, zuklappen
        if (_uiState.value.expandedOperationId == operation.id) {
            _uiState.update { it.copy(expandedOperationId = null) }
            return
        }

        _uiState.update {
            it.copy(expandedOperationId = operation.id, leaguesLoading = true)
        }

        viewModelScope.launch {
            try {
                val leagues = repository.getLeaguesByOperation(operation.id)
                    .filter { l -> l.name.length > 2 }
                _uiState.update {
                    it.copy(
                        leaguesLoading = false,
                        leaguesForOperation = leagues.sortedBy { l -> l.name },
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(leaguesLoading = false, leaguesForOperation = emptyList())
                }
            }
        }
    }

    fun toggleLeagueFavorite(league: LeaguePreview) {
        viewModelScope.launch {
            val isFav = league.id in _favoriteLeagueIds.value
            if (isFav) {
                repository.removeFavorite("league", league.id)
            } else {
                val nextOrder = repository.getMaxFavoriteSortOrder("league") + 1
                repository.addFavorite(
                    FavoriteEntity(
                        type = "league",
                        externalId = league.id,
                        name = league.name,
                        gameOperationSlug = league.gameOperationSlug,
                        gameOperationName = league.gameOperationName,
                        sortOrder = nextOrder,
                    )
                )
            }
        }
    }

    fun retry() = loadOperations()
}

data class BrowseUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val gameOperations: List<GameOperation> = emptyList(),
    val expandedOperationId: Int? = null,
    val leaguesLoading: Boolean = false,
    val leaguesForOperation: List<LeaguePreview> = emptyList(),
)

// ── Screen ───────────────────────────────────────────────────

@Composable
fun BrowseScreen(
    onLeagueClick: (Int) -> Unit = {},
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val favoriteIds by viewModel.favoriteLeagueIds.collectAsState()
    val favCountBySlug by viewModel.favoriteCountBySlug.collectAsState()

    when {
        uiState.isLoading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        uiState.error != null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Fehler: ${uiState.error}", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.retry() }) { Text("Erneut versuchen") }
                }
            }
        }
        else -> {
            // Verbände mit Favoriten nach oben sortieren
            val sortedOperations = remember(uiState.gameOperations, favCountBySlug) {
                uiState.gameOperations.sortedByDescending { op ->
                    (favCountBySlug[op.path] ?: 0) > 0
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                item {
                    Text(
                        text = "Verbände & Ligen",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        text = "Tippe auf einen Verband um die Ligen zu sehen. Mit dem Stern markierst du Favoriten.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }

                items(sortedOperations, key = { it.id }) { operation ->
                    OperationCard(
                        operation = operation,
                        isExpanded = uiState.expandedOperationId == operation.id,
                        leaguesLoading = uiState.leaguesLoading && uiState.expandedOperationId == operation.id,
                        leagues = if (uiState.expandedOperationId == operation.id)
                            uiState.leaguesForOperation else emptyList(),
                        favoriteIds = favoriteIds,
                        favoriteCount = favCountBySlug[operation.path] ?: 0,
                        onToggleExpand = { viewModel.loadLeaguesForOperation(operation) },
                        onToggleFavorite = { viewModel.toggleLeagueFavorite(it) },
                        onLeagueClick = onLeagueClick,
                    )
                }
            }
        }
    }
}

// ── Verbands-Card mit aufklappbaren Ligen ────────────────────

@Composable
private fun OperationCard(
    operation: GameOperation,
    isExpanded: Boolean,
    leaguesLoading: Boolean,
    leagues: List<LeaguePreview>,
    favoriteIds: Set<Int>,
    favoriteCount: Int,
    onToggleExpand: () -> Unit,
    onToggleFavorite: (LeaguePreview) -> Unit,
    onLeagueClick: (Int) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isExpanded)
                MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Verbandslogo
            val logoUrl = resolveLogoUrl(operation.logoUrl)
            if (logoUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(logoUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = operation.name,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = operation.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = operation.shortName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (favoriteCount > 0) {
                        Text(
                            text = " ($favoriteCount ★)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Zuklappen" else "Aufklappen",
            )
        }

        // Ligen-Liste (aufklappbar)
        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                if (leaguesLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else if (leagues.isEmpty()) {
                    Text(
                        text = "Keine Ligen gefunden",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Ligen nach GF/KF gruppieren, Favoriten jeweils oben
                    val gfLeagues = remember(leagues, favoriteIds) {
                        leagues.filter { it.fieldSize == "GF" }
                            .sortedByDescending { it.id in favoriteIds }
                    }
                    val kfLeagues = remember(leagues, favoriteIds) {
                        leagues.filter { it.fieldSize != "GF" }
                            .sortedByDescending { it.id in favoriteIds }
                    }

                    if (gfLeagues.isNotEmpty() && kfLeagues.isNotEmpty()) {
                        // Beide Feldgrößen vorhanden → Tabs
                        var selectedFieldTab by remember { mutableIntStateOf(0) }
                        val tabs = listOf("Großfeld (${gfLeagues.size})", "Kleinfeld (${kfLeagues.size})")

                        TabRow(
                            selectedTabIndex = selectedFieldTab,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedFieldTab == index,
                                    onClick = { selectedFieldTab = index },
                                    text = { Text(title, style = MaterialTheme.typography.labelMedium) },
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        val visibleLeagues = if (selectedFieldTab == 0) gfLeagues else kfLeagues
                        visibleLeagues.forEach { league ->
                            LeagueRow(
                                league = league,
                                isFavorite = league.id in favoriteIds,
                                onToggleFavorite = { onToggleFavorite(league) },
                                onClick = { onLeagueClick(league.id) },
                            )
                        }
                    } else {
                        // Nur eine Feldgröße → keine Tabs nötig
                        val allSorted = if (gfLeagues.isNotEmpty()) gfLeagues else kfLeagues
                        allSorted.forEach { league ->
                            LeagueRow(
                                league = league,
                                isFavorite = league.id in favoriteIds,
                                onToggleFavorite = { onToggleFavorite(league) },
                                onClick = { onLeagueClick(league.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Einzelne Liga-Zeile ──────────────────────────────────────

@Composable
private fun LeagueRow(
    league: LeaguePreview,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Feldgröße-Badge
        Surface(
            shape = MaterialTheme.shapes.small,
            color = when (league.fieldSize) {
                "GF" -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.tertiaryContainer
            },
        ) {
            Text(
                text = league.fieldSize,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = when (league.fieldSize) {
                    "GF" -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onTertiaryContainer
                },
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = league.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (league.leagueType == "cup") {
                Text(
                    text = "Pokal / KO-Runde",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (isFavorite) "Favorit entfernen" else "Als Favorit markieren",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
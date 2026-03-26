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
import de.floorballcompanion.domain.LeagueGroupingService
import de.floorballcompanion.domain.model.LeagueGroup
import de.floorballcompanion.domain.model.LeaguePhase
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
    private val groupingService: LeagueGroupingService,
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
                val groups = groupingService.groupLeagues(leagues)
                _uiState.update {
                    it.copy(
                        leaguesLoading = false,
                        leagueGroupsForOperation = groups,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(leaguesLoading = false, leagueGroupsForOperation = emptyList())
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
    val leagueGroupsForOperation: List<LeagueGroup> = emptyList(),
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
                        leagueGroups = if (uiState.expandedOperationId == operation.id)
                            uiState.leagueGroupsForOperation else emptyList(),
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
    leagueGroups: List<LeagueGroup>,
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
                } else if (leagueGroups.isEmpty()) {
                    Text(
                        text = "Keine Ligen gefunden",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Alle Hauptligen aus den Gruppen flach sammeln (für GF/KF-Gruppierung)
                    val allMainLeagues = remember(leagueGroups) {
                        leagueGroups.map { it.mainLeague }
                    }
                    val gfGroups = remember(leagueGroups) {
                        leagueGroups.filter { it.mainLeague.fieldSize == "GF" }
                    }
                    val kfGroups = remember(leagueGroups) {
                        leagueGroups.filter { it.mainLeague.fieldSize != "GF" }
                    }

                    // Sortierung: Gruppen mit Favoriten (in Haupt- oder Unterliga) oben
                    fun List<LeagueGroup>.sortedByFavorites(): List<LeagueGroup> =
                        sortedByDescending { group ->
                            group.allLeagues.any { it.id in favoriteIds }
                        }

                    if (gfGroups.isNotEmpty() && kfGroups.isNotEmpty()) {
                        var selectedFieldTab by remember { mutableIntStateOf(0) }
                        val gfCount = gfGroups.sumOf { it.allLeagues.size }
                        val kfCount = kfGroups.sumOf { it.allLeagues.size }
                        val tabs = listOf("Großfeld ($gfCount)", "Kleinfeld ($kfCount)")

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

                        val visibleGroups = if (selectedFieldTab == 0)
                            gfGroups.sortedByFavorites() else kfGroups.sortedByFavorites()
                        visibleGroups.forEach { group ->
                            LeagueGroupRow(
                                group = group,
                                favoriteIds = favoriteIds,
                                onToggleFavorite = onToggleFavorite,
                                onLeagueClick = onLeagueClick,
                            )
                        }
                    } else {
                        val allGroups = (gfGroups.ifEmpty { kfGroups }).sortedByFavorites()
                        allGroups.forEach { group ->
                            LeagueGroupRow(
                                group = group,
                                favoriteIds = favoriteIds,
                                onToggleFavorite = onToggleFavorite,
                                onLeagueClick = onLeagueClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Liga-Gruppe (Hauptliga + aufklappbare Phasen) ────────────

@Composable
private fun LeagueGroupRow(
    group: LeagueGroup,
    favoriteIds: Set<Int>,
    onToggleFavorite: (LeaguePreview) -> Unit,
    onLeagueClick: (Int) -> Unit,
) {
    // Hauptliga
    LeagueRow(
        league = group.mainLeague,
        isFavorite = group.mainLeague.id in favoriteIds,
        hasRelated = group.hasPostSeason,
        onToggleFavorite = { onToggleFavorite(group.mainLeague) },
        onClick = { onLeagueClick(group.mainLeague.id) },
    )

    // Zugehörige Phasen (Playoffs, Playdowns, Relegation)
    if (group.hasPostSeason) {
        var phasesExpanded by remember { mutableStateOf(false) }

        // Aufklapp-Zeile
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { phasesExpanded = !phasesExpanded }
                .padding(start = 52.dp, end = 16.dp, top = 0.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (phasesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (phasesExpanded) "Zuklappen" else "Phasen anzeigen",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "${group.relatedLeagues.size} weitere Phase${if (group.relatedLeagues.size > 1) "n" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = phasesExpanded) {
            Column {
                val mainIsFavorite = group.mainLeague.id in favoriteIds
                group.relatedLeagues.forEach { (phase, league) ->
                    PhaseLeagueRow(
                        league = league,
                        phase = phase,
                        parentIsFavorite = mainIsFavorite,
                        onClick = { onLeagueClick(league.id) },
                    )
                }
            }
        }
    }
}

// ── Einzelne Liga-Zeile (Hauptliga) ─────────────────────────

@Composable
private fun LeagueRow(
    league: LeaguePreview,
    isFavorite: Boolean,
    hasRelated: Boolean = false,
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

// ── Phasen-Liga-Zeile (eingerückt, mit Phase-Badge) ─────────

@Composable
private fun PhaseLeagueRow(
    league: LeaguePreview,
    phase: LeaguePhase,
    parentIsFavorite: Boolean,
    onClick: () -> Unit,
) {
    val phaseLabel = when (phase) {
        LeaguePhase.PLAYOFF -> "Playoffs"
        LeaguePhase.PLAYDOWN -> "Playdowns"
        LeaguePhase.RELEGATION -> "Relegation"
        LeaguePhase.REGULAR -> ""
    }
    val phaseColor = when (phase) {
        LeaguePhase.PLAYOFF -> MaterialTheme.colorScheme.primary
        LeaguePhase.PLAYDOWN -> MaterialTheme.colorScheme.tertiary
        LeaguePhase.RELEGATION -> MaterialTheme.colorScheme.error
        LeaguePhase.REGULAR -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 52.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Phase-Badge
        Surface(
            shape = MaterialTheme.shapes.small,
            color = phaseColor.copy(alpha = 0.12f),
        ) {
            Text(
                text = phaseLabel,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = phaseColor,
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = league.name,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )

        // Favoriten-Status vom Parent erben (kein eigener Toggle)
        if (parentIsFavorite) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "Favorit (via Hauptliga)",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp).padding(end = 4.dp),
            )
        }
    }
}
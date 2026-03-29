package de.floorballcompanion.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SportsHockey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.floorballcompanion.ui.components.TeamLogo
import de.floorballcompanion.ui.team.TeamFavoriteColor
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.floorballcompanion.data.local.entity.FavoriteEntity
import de.floorballcompanion.data.repository.FloorballRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val repository: FloorballRepository,
) : ViewModel() {

    val favorites: StateFlow<List<FavoriteEntity>> =
        repository.observeFavorites()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removeFavorite(favorite: FavoriteEntity) {
        viewModelScope.launch {
            repository.removeFavorite(favorite.type, favorite.externalId)
        }
    }

    fun moveFavorite(type: String, list: List<FavoriteEntity>, fromIndex: Int, toIndex: Int) {
        if (toIndex < 0 || toIndex >= list.size) return
        viewModelScope.launch {
            // Swap sort orders
            val itemA = list[fromIndex]
            val itemB = list[toIndex]
            repository.updateFavoriteSortOrder(type, itemA.externalId, toIndex)
            repository.updateFavoriteSortOrder(type, itemB.externalId, fromIndex)
        }
    }
}

// ── Screen ───────────────────────────────────────────────────

@Composable
fun FavoritesScreen(
    onTeamClick: (teamId: Int, leagueId: Int) -> Unit = { _, _ -> },
    onLeagueClick: (leagueId: Int) -> Unit = {},
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val favorites by viewModel.favorites.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Favoriten",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp),
        )

        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.SportsHockey,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Noch keine Favoriten",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Gehe zu \"Ligen\" und markiere\nLigen oder Teams mit dem Stern.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        } else {
            val grouped = favorites.groupBy { it.type }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Teams BEFORE leagues (higher priority)
                grouped["team"]?.let { teams ->
                    item {
                        SectionHeader(
                            icon = Icons.Default.Groups,
                            title = "Teams (${teams.size})",
                            tint = TeamFavoriteColor,
                        )
                    }
                    itemsIndexed(teams, key = { _, fav -> "team_${fav.externalId}" }) { index, fav ->
                        FavoriteCard(
                            favorite = fav,
                            onClick = { onTeamClick(fav.externalId, fav.leagueId ?: 0) },
                            onRemove = { viewModel.removeFavorite(fav) },
                            canMoveUp = index > 0,
                            canMoveDown = index < teams.size - 1,
                            onMoveUp = { viewModel.moveFavorite("team", teams, index, index - 1) },
                            onMoveDown = { viewModel.moveFavorite("team", teams, index, index + 1) },
                        )
                    }
                }

                grouped["league"]?.let { leagues ->
                    item {
                        if (grouped.containsKey("team")) Spacer(Modifier.height(8.dp))
                        SectionHeader(
                            icon = Icons.Default.EmojiEvents,
                            title = "Ligen (${leagues.size})",
                        )
                    }
                    itemsIndexed(leagues, key = { _, fav -> "league_${fav.externalId}" }) { index, fav ->
                        FavoriteCard(
                            favorite = fav,
                            onClick = { onLeagueClick(fav.externalId) },
                            onRemove = { viewModel.removeFavorite(fav) },
                            canMoveUp = index > 0,
                            canMoveDown = index < leagues.size - 1,
                            onMoveUp = { viewModel.moveFavorite("league", leagues, index, index - 1) },
                            onMoveDown = { viewModel.moveFavorite("league", leagues, index, index + 1) },
                        )
                    }
                }
            }
        }
    }
}

// ── Section Header ───────────────────────────────────────────

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = tint,
        )
    }
}

// ── Einzelne Favoriten-Card ──────────────────────────────────

@Composable
private fun FavoriteCard(
    favorite: FavoriteEntity,
    onClick: () -> Unit = {},
    onRemove: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Logo for teams
            if (favorite.type == "team") {
                TeamLogo(favorite.logoUrl, favorite.name, size = 32.dp)
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = favorite.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                favorite.gameOperationName?.let { opName ->
                    Text(
                        text = opName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                favorite.leagueName?.let { lName ->
                    Text(
                        text = lName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Reihenfolge-Buttons
            Column {
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Nach oben",
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Nach unten",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Favorit entfernen",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
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
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Abbrechen")
                }
            },
        )
    }
}

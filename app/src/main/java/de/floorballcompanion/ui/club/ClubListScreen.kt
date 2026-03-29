package de.floorballcompanion.ui.club

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
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
import dagger.hilt.android.qualifiers.ApplicationContext
import de.floorballcompanion.data.local.entity.ClubEntity
import de.floorballcompanion.data.repository.FloorballRepository
import de.floorballcompanion.ui.components.TeamLogo
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────

@HiltViewModel
class ClubListViewModel @Inject constructor(
    private val repository: FloorballRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    companion object {
        private const val PREFS_NAME = "club_list_prefs"
        private const val KEY_DISCLAIMER_DISMISSED = "disclaimer_dismissed"
    }

    val clubs: StateFlow<List<ClubEntity>> =
        repository.observeAllClubs()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Logo-URLs von favorisierten Teams → für Clubs-mit-Favoriten-Erkennung
    val favoriteTeamLogoUrls: StateFlow<Set<String>> =
        repository.observeFavoriteTeams()
            .map { favs -> favs.mapNotNull { it.logoUrl }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _disclaimerDismissed = MutableStateFlow(
        prefs.getBoolean(KEY_DISCLAIMER_DISMISSED, false)
    )
    val disclaimerDismissed = _disclaimerDismissed.asStateFlow()

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    fun dismissDisclaimer() {
        prefs.edit().putBoolean(KEY_DISCLAIMER_DISMISSED, true).apply()
        _disclaimerDismissed.value = true
    }
}

// ── Screen ───────────────────────────────────────────────────

@Composable
fun ClubListScreen(
    onClubClick: (logoUrl: String) -> Unit = {},
    viewModel: ClubListViewModel = hiltViewModel(),
) {
    val clubs by viewModel.clubs.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val favoriteLogoUrls by viewModel.favoriteTeamLogoUrls.collectAsState()
    val disclaimerDismissed by viewModel.disclaimerDismissed.collectAsState()

    var searchVisible by remember { mutableStateOf(false) }

    // Clubs mit Favoriten vs. Rest
    val (favoriteClubs, otherClubs) = remember(clubs, favoriteLogoUrls) {
        clubs.partition { it.logoUrl in favoriteLogoUrls }
    }

    val filteredFavorite = remember(favoriteClubs, searchQuery) {
        if (searchQuery.isBlank()) favoriteClubs
        else favoriteClubs.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }
    val filteredOther = remember(otherClubs, searchQuery) {
        if (searchQuery.isBlank()) otherClubs
        else otherClubs.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }
    val filteredClubs = filteredFavorite + filteredOther

    Column(modifier = Modifier.fillMaxSize()) {
        // Header mit Suche
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Vereine",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                searchVisible = !searchVisible
                if (!searchVisible) viewModel.updateSearch("")
            }) {
                Icon(
                    imageVector = if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = if (searchVisible) "Suche schließen" else "Suchen",
                )
            }
        }

        // Such-Feld (nur wenn sichtbar)
        if (searchVisible) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearch(it) },
                placeholder = { Text("Verein suchen...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
            )
        }

        // Disclaimer (wegklickbar)
        if (!disclaimerDismissed) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Diese Liste wird automatisch aufgebaut, wenn du Ligen durchstöberst. " +
                            "Vereinszuordnungen basieren auf Team-Logos und können fehlerhaft sein.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.dismissDisclaimer() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Hinweis ausblenden",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }

        if (clubs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Noch keine Vereine entdeckt",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Durchstöbere Ligen im \"Ligen\"-Tab,\num Vereine automatisch zu erfassen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        } else {
            Text(
                "${filteredClubs.size} Vereine",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Vereine mit Favoriten oben
                if (filteredFavorite.isNotEmpty()) {
                    item(key = "fav-header") {
                        Text(
                            "Mit Favoriten",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(filteredFavorite, key = { "fav-${it.logoUrl}" }) { club ->
                        ClubCard(club = club, onClick = { onClubClick(club.logoUrl) })
                    }
                }

                // Trennlinie wenn beide Gruppen vorhanden
                if (filteredFavorite.isNotEmpty() && filteredOther.isNotEmpty()) {
                    item(key = "divider") {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    }
                }

                // Alle anderen Vereine
                items(filteredOther, key = { it.logoUrl }) { club ->
                    ClubCard(club = club, onClick = { onClubClick(club.logoUrl) })
                }
            }
        }
    }
}

// ── Club Card ────────────────────────────────────────────────

@Composable
private fun ClubCard(
    club: ClubEntity,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeamLogo(club.logoUrl, club.name, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                club.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

package de.floorballcompanion.ui.club

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.floorballcompanion.data.local.entity.ClubEntity
import de.floorballcompanion.data.local.entity.ClubWithTeams
import de.floorballcompanion.data.repository.FloorballRepository
import de.floorballcompanion.ui.components.TeamLogo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────

@HiltViewModel
class ClubListViewModel @Inject constructor(
    repository: FloorballRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    companion object {
        private const val PREFS_NAME = "club_list_prefs"
        private const val KEY_DISCLAIMER_DISMISSED = "disclaimer_dismissed"
    }

    // Alle Clubs inkl. Teams
    private val clubsWithTeams: StateFlow<List<ClubWithTeams>> =
        repository.observeClubsWithTeams()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Verbände (Namen aus gameOperationName)
    val availableFederations: StateFlow<List<String>> =
        repository.observeAllGameOperations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Favoriten wie gehabt
    val favoriteTeamLogoUrls: StateFlow<Set<String>> =
        repository.observeFavoriteTeams()
            .map { favs -> favs.mapNotNull { it.logoUrl }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // Suche + Verbandfilter
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _selectedFederation = MutableStateFlow<String?>(null)
    val selectedFederation = _selectedFederation.asStateFlow()

    fun selectFederation(federation: String?) {
        _selectedFederation.value = federation
    }

    // Gefilterte Clubs (nur ClubEntity für bestehendes UI)
    val clubs: StateFlow<List<ClubWithTeams>> =
        combine(clubsWithTeams, _searchQuery, _selectedFederation) { list, q, fed ->
            val qTrim = q.trim()
            val qLen = qTrim.length
            val qLower = qTrim.lowercase()
            val applyFed = fed?.isNotEmpty()?: false

            list.filter { cwt ->
                val matchesText =
                    if (qLower.isEmpty()) true
                    else if (qLen < 3) {
                        cwt.club.name.contains(qLower, ignoreCase = true)
                    } else {
                        cwt.club.name.contains(qLower, ignoreCase = true) ||
                                cwt.teams.any { it.teamName.contains(qLower, ignoreCase = true) }
                    }

                val matchesFederation =
                    if (!applyFed) true
                    else cwt.teams.any { it.gameOperationName == fed }

                matchesText && matchesFederation
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Disclaimer-Prefs wie gehabt
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _disclaimerDismissed = MutableStateFlow(
        prefs.getBoolean(KEY_DISCLAIMER_DISMISSED, false)
    )
    val disclaimerDismissed = _disclaimerDismissed.asStateFlow()

    fun updateSearch(query: String) { _searchQuery.value = query }

    fun dismissDisclaimer() {
        prefs.edit { putBoolean(KEY_DISCLAIMER_DISMISSED, true) }
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
    val federations by viewModel.availableFederations.collectAsState()
    val selectedFederation by viewModel.selectedFederation.collectAsState()

    var searchVisible by remember { mutableStateOf(searchQuery.isNotBlank() ||
            selectedFederation?.isNotEmpty()?:false) }

    val (favoriteClubs, otherClubs) = remember(clubs, favoriteLogoUrls) {
        clubs.partition { it.club.logoUrl in favoriteLogoUrls }
    }

    val filteredFavorite = remember(favoriteClubs, searchQuery) {
        if (searchQuery.isBlank()) favoriteClubs
        else favoriteClubs.filter { it.teams.any { it.teamName.contains(searchQuery, ignoreCase = true) } }
    }
    val filteredOther = remember(otherClubs, searchQuery) {
        if (searchQuery.isBlank()) otherClubs
        else otherClubs.filter { it.teams.any { it.teamName.contains(searchQuery, ignoreCase = true) }}
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
                if (!searchVisible) {
                    viewModel.updateSearch("")
                    viewModel.selectFederation(null)
                }
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
                placeholder = { Text("Verein oder Team suchen...") }, // kleiner UX-Hinweis
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
            )

            // Dropdown nur in diesem Block rendern
            CompactFederationDropdown(
                federations = federations,
                selected = selectedFederation,
                onSelect = { viewModel.selectFederation(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
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
                    items(filteredFavorite, key = { "fav-${it.club.logoUrl}" }) {
                        ClubCard(club = it.club, onClick = { onClubClick(it.club.logoUrl) })
                    }
                }

                // Trennlinie wenn beide Gruppen vorhanden
                if (filteredFavorite.isNotEmpty() && filteredOther.isNotEmpty()) {
                    item(key = "divider") {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    }
                }

                // Alle anderen Vereine
                items(filteredOther, key = { it.club.logoUrl }) {
                    ClubCard(club = it.club, onClick = { onClubClick(it.club.logoUrl) })
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactFederationDropdown(
    federations: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (federations.isNotEmpty()) expanded = !expanded },
        modifier = modifier.zIndex(1f)
    ) {
        AssistChip(
            onClick = { if (federations.isNotEmpty()) expanded = true },
            label = {
                Text(
                    text = selected ?: "Alle Verbände",
                    style = MaterialTheme.typography.labelSmall
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null
                )
            },
            enabled = federations.isNotEmpty(),
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Alle Verbände") },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            federations.forEach { fed ->
                DropdownMenuItem(
                    text = { Text(fed) },
                    onClick = {
                        onSelect(fed)
                        expanded = false
                    }
                )
            }
        }
    }
}
package de.floorballcompanion.ui.club

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.floorballcompanion.data.local.entity.ClubTeamEntity
import de.floorballcompanion.data.repository.FloorballRepository
import de.floorballcompanion.ui.components.TeamLogo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── UI State ─────────────────────────────────────────────────

data class ClubDetailUiState(
    val clubLogoUrl: String = "",
    val clubName: String = "",
    val teams: List<ClubTeamEntity> = emptyList(),
    val isLoading: Boolean = true,
)

// ── ViewModel ────────────────────────────────────────────────

@HiltViewModel
class ClubDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: FloorballRepository,
) : ViewModel() {

    private val clubLogoUrl: String = savedStateHandle["clubLogoUrl"] ?: ""

    private val _uiState = MutableStateFlow(ClubDetailUiState(clubLogoUrl = clubLogoUrl))
    val uiState = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            repository.observeTeamsForClub(clubLogoUrl).collect { teams ->
                val clubName = teams.firstOrNull()?.teamName ?: "Verein"
                _uiState.update {
                    it.copy(
                        clubName = clubName,
                        teams = teams,
                        isLoading = false,
                    )
                }
            }
        }
    }
}

// ── Screen ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClubDetailScreen(
    onBack: () -> Unit,
    onTeamClick: (teamId: Int, leagueId: Int) -> Unit = { _, _ -> },
    viewModel: ClubDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TeamLogo(uiState.clubLogoUrl, uiState.clubName, size = 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            uiState.clubName,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurueck")
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
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.teams.isEmpty()) {
                Text(
                    "Keine Teams gefunden",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ClubContent(uiState, onTeamClick)
            }
        }
    }
}

// ── Content ──────────────────────────────────────────────────

@Composable
private fun ClubContent(
    uiState: ClubDetailUiState,
    onTeamClick: (Int, Int) -> Unit,
) {
    // Group teams by gameOperationName
    val grouped = remember(uiState.teams) {
        uiState.teams.groupBy { it.gameOperationName }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        // Club header
        item(key = "header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TeamLogo(uiState.clubLogoUrl, uiState.clubName, size = 80.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    uiState.clubName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${uiState.teams.size} Teams in ${grouped.size} Verbänden",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        grouped.forEach { (operationName, teams) ->
            item(key = "section-$operationName") {
                Text(
                    operationName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(teams, key = { "team-${it.teamId}-${it.leagueId}" }) { team ->
                ClubTeamCard(
                    team = team,
                    logoUrl = uiState.clubLogoUrl,
                    onClick = { onTeamClick(team.teamId, team.leagueId) },
                )
            }
        }
    }
}

@Composable
private fun ClubTeamCard(
    team: ClubTeamEntity,
    logoUrl: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeamLogo(logoUrl, team.teamName, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    team.teamName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    team.leagueName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

package de.floorballcompanion.ui.game

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.floorballcompanion.R
import de.floorballcompanion.data.remote.model.*
import de.floorballcompanion.data.repository.FloorballRepository
import de.floorballcompanion.ui.components.TeamLogo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

// ── UI State ─────────────────────────────────────────────────

data class GameDetailUiState(
    val game: GameDetail? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

// ── ViewModel ────────────────────────────────────────────────

@HiltViewModel
class GameDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: FloorballRepository,
) : ViewModel() {

    private val gameId: Int = savedStateHandle["gameId"] ?: 0

    private val _uiState = MutableStateFlow(GameDetailUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadGame()
    }

    private fun loadGame() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val game = repository.getGameDetail(gameId)
                _uiState.update { it.copy(game = game, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Fehler beim Laden")
                }
            }
        }
    }

    fun retry() = loadGame()
}

// ── Screen ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    onBack: () -> Unit,
    viewModel: GameDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showInfoDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.placeholder_logo),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = androidx.compose.ui.graphics.Color.Unspecified,
                        )
                        Spacer(Modifier.width(8.dp))
                        val game = uiState.game
                        Text(
                            if (game != null) "${game.homeTeamName} vs ${game.guestTeamName}"
                            else "Spiel-Details",
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                    }
                },
                actions = {
                    if (uiState.game != null) {
                        IconButton(onClick = { showInfoDialog = true }) {
                            Icon(Icons.Default.Info, "Spielinfos")
                        }
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
                uiState.game != null -> {
                    GameContent(uiState.game!!)
                }
            }
        }
    }

    if (showInfoDialog && uiState.game != null) {
        GameInfoDialog(game = uiState.game!!, onDismiss = { showInfoDialog = false })
    }
}

// ── Main Content ─────────────────────────────────────────────

@Composable
private fun GameContent(game: GameDetail) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = buildList {
        add("Events")
        add("Kader")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Score-Header
        ScoreHeader(game)

        // Tabs
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                )
            }
        }

        // Tab-Content
        when (selectedTab) {
            0 -> EventsTab(game)
            1 -> RosterTab(game)
        }
    }
}

// ── Score-Header ─────────────────────────────────────────────

@Composable
private fun ScoreHeader(game: GameDetail) {
    val isLive = game.gameStatus.lowercase() in listOf("live", "1", "2", "3")

    Surface(
        color = if (isLive) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Liga + Spieltag
            val subtitle = buildString {
                game.leagueName?.let { append(it) }
                game.gameDay?.title?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Datum
            val dateDisplay = remember(game.date) {
                try {
                    LocalDate.parse(game.date)
                        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                } catch (_: Exception) { game.date }
            }
            Text(
                "$dateDisplay · ${game.startTime} Uhr",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // Teams + Score
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                // Home
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TeamLogo(game.homeTeamLogo, game.homeTeamName, size = 48.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        game.homeTeamName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }

                // Score
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    val result = game.result
                    if (result != null) {
                        Text(
                            "${result.homeGoals} : ${result.guestGoals}",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        val postfix = result.postfixShort
                        if (postfix.isNotEmpty()) {
                            Text(
                                postfix,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        // Drittelergebnisse
                        val periods = periodScoresText(result)
                        if (periods != null) {
                            Text(
                                "($periods)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (isLive) {
                        Text(
                            "LIVE",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            "–",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Guest
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TeamLogo(game.guestTeamLogo, game.guestTeamName, size = 48.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        game.guestTeamName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }

            // LIVE Badge
            if (isLive) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.error,
                ) {
                    Text(
                        "LIVE",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
            }

            // MVP — pro Team anzeigen
            val homeMvps = game.awards?.home?.filter { it.award == "mvp" && it.playerId != 0 } ?: emptyList()
            val guestMvps = game.awards?.guest?.filter { it.award == "mvp" && it.playerId != 0 } ?: emptyList()
            if (homeMvps.isNotEmpty() || guestMvps.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Home MVP
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (homeMvps.isNotEmpty()) {
                            Text(
                                "MVP",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            homeMvps.forEach { mvp ->
                                Text(
                                    "#${mvp.trikotNumber} ${mvp.playerFirstname} ${mvp.playerName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // Guest MVP
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (guestMvps.isNotEmpty()) {
                            Text(
                                "MVP",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            guestMvps.forEach { mvp ->
                                Text(
                                    "#${mvp.trikotNumber} ${mvp.playerFirstname} ${mvp.playerName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun periodScoresText(result: GameResult): String? {
    val home = result.homeGoalsPeriod
    val guest = result.guestGoalsPeriod
    if (home.isEmpty() || guest.isEmpty()) return null
    val pairs = home.zip(guest)
    // Trim trailing 0:0 when no overtime
    val trimmed = if (!result.overtime) {
        pairs.dropLastWhile { it.first == 0 && it.second == 0 }
    } else pairs
    if (trimmed.isEmpty()) return null
    return trimmed.joinToString(" ") { "${it.first}:${it.second}" }
}

// ── Events Tab ───────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventsTab(game: GameDetail) {
    val events = game.events.sortedBy { it.sortkey ?: "${it.period}-${it.time}" }
    val players = game.players

    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Keine Events vorhanden", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // Spieler-Lookup: Trikotnummer -> Name (pro Team)
    val homePlayers = remember(players) {
        players?.home?.associate { it.trikotNumber to "${it.playerFirstname} ${it.playerName}" } ?: emptyMap()
    }
    val guestPlayers = remember(players) {
        players?.guest?.associate { it.trikotNumber to "${it.playerFirstname} ${it.playerName}" } ?: emptyMap()
    }

    // Events gruppiert nach Drittel
    val groupedEvents = remember(events) {
        events.groupBy { it.period }
    }
    val periods = groupedEvents.keys.sorted()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        // Sticky Logos Header
        stickyHeader {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TeamLogo(game.homeTeamLogo, game.homeTeamName, size = 24.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        game.homeTeamName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        game.guestTeamName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                    TeamLogo(game.guestTeamLogo, game.guestTeamName, size = 24.dp)
                }
            }
        }

        periods.forEach { period ->
            val periodEvents = groupedEvents[period] ?: return@forEach
            val periodLabel = when (period) {
                1 -> "1. Drittel"
                2 -> "2. Drittel"
                3 -> "3. Drittel"
                4 -> "Verlängerung"
                5 -> "Penalty-Schießen"
                else -> "$period. Periode"
            }

            item(key = "period-$period") {
                Text(
                    periodLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            items(periodEvents, key = { "event-${it.eventId}" }) { event ->
                EventRow(
                    event = event,
                    homePlayers = homePlayers,
                    guestPlayers = guestPlayers,
                )
            }
        }
    }
}

@Composable
private fun EventRow(
    event: GameEvent,
    homePlayers: Map<Int, String>,
    guestPlayers: Map<Int, String>,
) {
    val isHome = event.eventTeam == "home"
    val playerLookup = if (isHome) homePlayers else guestPlayers

    val icon: String
    val primaryColor: androidx.compose.ui.graphics.Color
    val primaryText: String
    val secondaryText: String?

    when (event.eventType) {
        "goal" -> {
            icon = "\u26BD"  // ⚽
            primaryColor = MaterialTheme.colorScheme.primary
            val scorerName = event.number?.let { playerLookup[it] } ?: ""
            val scorerNum = event.number?.let { "#$it" } ?: ""
            primaryText = buildString {
                append(scorerNum)
                if (scorerName.isNotEmpty()) append(" $scorerName")
            }
            secondaryText = buildString {
                event.assist?.let { assistNum ->
                    if (assistNum > 0) {
                        val assistName = playerLookup[assistNum] ?: ""
                        append("Vorlage: #$assistNum")
                        if (assistName.isNotEmpty()) append(" $assistName")
                    }
                }
                event.goalTypeString?.let { gts ->
                    if (gts != "Tor") {
                        if (isNotEmpty()) append(" · ")
                        append(gts)
                    }
                }
            }.ifEmpty { null }
        }
        "penalty" -> {
            icon = "\uD83D\uDFE8"  // 🟨
            primaryColor = MaterialTheme.colorScheme.error
            val playerName = event.number?.let { playerLookup[it] } ?: ""
            val playerNum = event.number?.let { "#$it" } ?: ""
            primaryText = buildString {
                append(event.penaltyTypeString ?: "Strafe")
                append(" $playerNum")
                if (playerName.isNotEmpty()) append(" $playerName")
            }
            secondaryText = event.penaltyReasonString
        }
        "timeout" -> {
            icon = "\u23F1"  // ⏱
            primaryColor = MaterialTheme.colorScheme.onSurfaceVariant
            primaryText = "Auszeit"
            secondaryText = null
        }
        else -> {
            icon = "\u2022"  // •
            primaryColor = MaterialTheme.colorScheme.onSurfaceVariant
            primaryText = event.eventType
            secondaryText = null
        }
    }

    val score = if (event.homeGoals != null && event.guestGoals != null) {
        "${event.homeGoals}:${event.guestGoals}"
    } else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (isHome) {
            // Home event — content left, time+score center
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
                        Text(
                            primaryText,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = primaryColor,
                            textAlign = TextAlign.End,
                        )
                        if (secondaryText != null) {
                            Text(
                                secondaryText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(icon, fontSize = 14.sp)
                }
            }

            // Center: Time + Score
            Column(
                modifier = Modifier.width(56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    event.time,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (score != null) {
                    Text(
                        score,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Empty right side
            Spacer(Modifier.weight(1f))
        } else {
            // Empty left side
            Spacer(Modifier.weight(1f))

            // Center: Time + Score
            Column(
                modifier = Modifier.width(56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    event.time,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (score != null) {
                    Text(
                        score,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Guest event — content right
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            primaryText,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = primaryColor,
                        )
                        if (secondaryText != null) {
                            Text(
                                secondaryText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Roster Tab ───────────────────────────────────────────────

@Composable
private fun RosterTab(game: GameDetail) {
    val players = game.players
    if (players == null || (players.home.isEmpty() && players.guest.isEmpty())) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Kein Kader vorhanden", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var selectedTeam by remember { mutableIntStateOf(0) } // 0 = home, 1 = guest
    val teamNames = listOf(game.homeTeamName, game.guestTeamName)
    val roster = if (selectedTeam == 0) players.home else players.guest
    val startingSix = (if (selectedTeam == 0)
        game.startingPlayers?.home ?: emptyList()
    else
        game.startingPlayers?.guest ?: emptyList()
    ).filter { it.playerId != 0 }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTeam) {
            teamNames.forEachIndexed { index, name ->
                Tab(
                    selected = selectedTeam == index,
                    onClick = { selectedTeam = index },
                    text = { Text(name, maxLines = 1) },
                )
            }
        }

        var startingSixExpanded by remember { mutableStateOf(false) }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            // Starting Six (aufklappbar)
            if (startingSix.isNotEmpty()) {
                item(key = "starting-six-header-$selectedTeam") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable { startingSixExpanded = !startingSixExpanded }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Starting Six",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (startingSixExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (startingSixExpanded) "Zuklappen" else "Aufklappen",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                if (startingSixExpanded) {
                    val sorted = startingSix.sortedBy { positionOrder(it.position) }
                    itemsIndexed(sorted, key = { index, _ -> "start-$selectedTeam-$index" }) { _, player ->
                        Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)) {
                            PlayerRow(
                                number = player.trikotNumber,
                                name = "${player.playerFirstname} ${player.playerName}",
                                positionLabel = startingPositionLabel(player.position),
                                isGoalkeeper = player.position == "goal",
                                isCaptain = false,
                            )
                        }
                    }
                }
            }

            // Torhüter
            val goalkeepers = roster.filter { it.goalkeeper }
            val fieldPlayers = roster.filter { !it.goalkeeper }
                .sortedBy { it.trikotNumber }

            if (goalkeepers.isNotEmpty()) {
                item(key = "gk-header-$selectedTeam") {
                    Text(
                        "Torhüter",
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                itemsIndexed(goalkeepers, key = { index, _ -> "gk-$selectedTeam-$index" }) { _, player ->
                    PlayerRow(
                        number = player.trikotNumber,
                        name = "${player.playerFirstname} ${player.playerName}",
                        positionLabel = "TW",
                        isGoalkeeper = true,
                        isCaptain = player.captain,
                    )
                }
            }

            // Feldspieler
            item(key = "fp-header-$selectedTeam") {
                Text(
                    "Feldspieler",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            itemsIndexed(fieldPlayers, key = { index, _ -> "fp-$selectedTeam-$index" }) { _, player ->
                PlayerRow(
                    number = player.trikotNumber,
                    name = "${player.playerFirstname} ${player.playerName}",
                    positionLabel = null,
                    isGoalkeeper = false,
                    isCaptain = player.captain,
                )
            }
        }
    }
}

@Composable
private fun PlayerRow(
    number: Int,
    name: String,
    positionLabel: String?,
    isGoalkeeper: Boolean,
    isCaptain: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Trikotnummer
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (isGoalkeeper) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (isCaptain) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "C",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        if (positionLabel != null) {
            Text(
                positionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun positionOrder(pos: String) = when (pos) {
    "goal" -> 0
    "defender1", "defender2" -> 1
    "center" -> 2
    "forward1", "forward2" -> 3
    else -> 4
}

private fun startingPositionLabel(pos: String) = when (pos) {
    "goal" -> "TW"
    "defender1", "defender2" -> "VT"
    "center" -> "CE"
    "forward1", "forward2" -> "ST"
    else -> pos
}

// ── Info Bottom Sheet ────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameInfoDialog(game: GameDetail, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dateDisplay = remember(game.date) {
        try {
            LocalDate.parse(game.date)
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        } catch (_: Exception) { game.date }
    }

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Spielinfos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))

            InfoRow("Datum", "$dateDisplay · ${game.startTime} Uhr")
            game.leagueName?.let { InfoRow("Liga", it) }
            game.gameDay?.title?.let { InfoRow("Spieltag", it) }
            InfoRow("Spielnummer", game.gameNumber)
            game.arenaShort?.let { InfoRow("Halle", it) }
            game.arenaAddress?.let { address ->
                InfoRowClickable("Adresse", address) {
                    val mapUri = Uri.parse("geo:0,0?q=${Uri.encode(address)}")
                    context.startActivity(Intent(Intent.ACTION_VIEW, mapUri))
                }
            }
            game.nominatedReferees?.let { InfoRow("Schiedsrichter", it) }
            game.audience?.let { InfoRow("Zuschauer", "$it") }
            game.liveStreamLink?.let { link ->
                InfoRowClickable("Livestream", link) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                }
            }
            game.vodLink?.let { link ->
                InfoRowClickable("VOD", link) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun InfoRowClickable(label: String, value: String, onClick: () -> Unit) {
    Row {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(onClick = onClick),
            maxLines = 1,
        )
    }
}

package de.floorballcompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import de.floorballcompanion.ui.browse.BrowseScreen
import de.floorballcompanion.ui.dashboard.DashboardScreen
import de.floorballcompanion.ui.favorites.FavoritesScreen
import de.floorballcompanion.ui.game.GameDetailScreen
import de.floorballcompanion.ui.league.LeagueDetailScreen
import de.floorballcompanion.ui.club.ClubDetailScreen
import de.floorballcompanion.ui.club.ClubListScreen
import de.floorballcompanion.ui.team.TeamScreen
import java.net.URLEncoder
import java.net.URLDecoder
import de.floorballcompanion.ui.theme.FloorballCompanionTheme
import de.floorballcompanion.worker.LiveScoreWorker

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Hintergrund-Worker für Live-Scores starten
        LiveScoreWorker.enqueuePeriodicWork(this)

        setContent {
            FloorballCompanionTheme {
                MainApp()
            }
        }
    }
}

// ── Navigation ───────────────────────────────────────────────

sealed class Screen(val route: String, val label: String) {
    data object Dashboard : Screen("dashboard", "Dashboard")
    data object Browse : Screen("browse", "Ligen")
    data object Clubs : Screen("clubs", "Vereine")
    data object Favorites : Screen("favorites", "Favoriten")
}

val bottomNavItems = listOf(Screen.Dashboard, Screen.Browse, Screen.Clubs, Screen.Favorites)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isOnDetailScreen = currentDestination?.route?.let { route ->
        route.startsWith("league_detail/") ||
            route.startsWith("game_detail/") ||
            route.startsWith("team_detail/") ||
            route.startsWith("club_detail/")
    } == true

    Scaffold(
        topBar = {
            if (!isOnDetailScreen) {
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
                            Text("Floorball Companion")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        },
        contentWindowInsets = if (isOnDetailScreen) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            if (!isOnDetailScreen) {
                NavigationBar {
                    bottomNavItems.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == screen.route
                        } == true

                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = when (screen) {
                                        Screen.Dashboard -> Icons.Default.Home
                                        Screen.Browse -> Icons.Default.Search
                                        Screen.Clubs -> Icons.Default.Groups
                                        Screen.Favorites -> Icons.Default.Star
                                    },
                                    contentDescription = screen.label,
                                )
                            },
                            label = { Text(screen.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onTeamClick = { teamId, leagueId ->
                        navController.navigate("team_detail/$teamId/$leagueId")
                    },
                    onLeagueClick = { leagueId ->
                        navController.navigate("league_detail/$leagueId")
                    },
                )
            }
            composable(Screen.Browse.route) {
                BrowseScreen(
                    onLeagueClick = { leagueId ->
                        navController.navigate("league_detail/$leagueId")
                    },
                )
            }
            composable(Screen.Clubs.route) {
                ClubListScreen(
                    onClubClick = { logoUrl ->
                        navController.navigate("club_detail/${URLEncoder.encode(logoUrl, "UTF-8")}")
                    },
                )
            }
            composable(Screen.Favorites.route) {
                FavoritesScreen(
                    onTeamClick = { teamId, leagueId ->
                        navController.navigate("team_detail/$teamId/$leagueId")
                    },
                    onLeagueClick = { leagueId ->
                        navController.navigate("league_detail/$leagueId")
                    },
                )
            }
            composable(
                route = "league_detail/{leagueId}",
                arguments = listOf(navArgument("leagueId") { type = NavType.IntType }),
            ) {
                LeagueDetailScreen(
                    onBack = { navController.popBackStack() },
                    onGameClick = { gameId ->
                        navController.navigate("game_detail/$gameId")
                    },
                    onTeamClick = { teamId, leagueId ->
                        navController.navigate("team_detail/$teamId/$leagueId")
                    },
                )
            }
            composable(
                route = "game_detail/{gameId}",
                arguments = listOf(navArgument("gameId") { type = NavType.IntType }),
            ) {
                GameDetailScreen(
                    onBack = { navController.popBackStack() },
                    onTeamClick = { teamId, leagueId ->
                        navController.navigate("team_detail/$teamId/$leagueId")
                    },
                )
            }
            composable(
                route = "team_detail/{teamId}/{leagueId}",
                arguments = listOf(
                    navArgument("teamId") { type = NavType.IntType },
                    navArgument("leagueId") { type = NavType.IntType },
                ),
            ) {
                TeamScreen(
                    onBack = { navController.popBackStack() },
                    onGameClick = { gameId ->
                        navController.navigate("game_detail/$gameId")
                    },
                    onLeagueClick = { leagueId ->
                        navController.navigate("league_detail/$leagueId")
                    },
                    onTeamClick = { teamId, leagueId ->
                        navController.navigate("team_detail/$teamId/$leagueId")
                    },
                    onClubClick = { logoUrl ->
                        navController.navigate("club_detail/${URLEncoder.encode(logoUrl, "UTF-8")}")
                    },
                )
            }
            composable(
                route = "club_detail/{clubLogoUrl}",
                arguments = listOf(navArgument("clubLogoUrl") { type = NavType.StringType }),
            ) { backStackEntry ->
                val encodedUrl = backStackEntry.arguments?.getString("clubLogoUrl") ?: ""
                val decodedUrl = URLDecoder.decode(encodedUrl, "UTF-8")
                ClubDetailScreen(
                    onBack = { navController.popBackStack() },
                    onTeamClick = { teamId, leagueId ->
                        navController.navigate("team_detail/$teamId/$leagueId")
                    },
                )
            }
        }
    }
}
package de.floorballcompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import de.floorballcompanion.ui.league.LeagueDetailScreen
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
    data object Favorites : Screen("favorites", "Favoriten")
}

val bottomNavItems = listOf(Screen.Dashboard, Screen.Browse, Screen.Favorites)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isOnDetailScreen = currentDestination?.route?.startsWith("league_detail/") == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Floorball Companion") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
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
            composable(Screen.Dashboard.route) { DashboardScreen() }
            composable(Screen.Browse.route) {
                BrowseScreen(
                    onLeagueClick = { leagueId ->
                        navController.navigate("league_detail/$leagueId")
                    },
                )
            }
            composable(Screen.Favorites.route) { FavoritesScreen() }
            composable(
                route = "league_detail/{leagueId}",
                arguments = listOf(navArgument("leagueId") { type = NavType.IntType }),
            ) {
                LeagueDetailScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
package com.locus.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.locus.app.R
import com.locus.app.ui.audit.AuditJournalScreen
import com.locus.app.ui.chat.ChatScreen
import com.locus.app.ui.dashboard.DashboardScreen
import com.locus.app.ui.editor.EditorScreen
import com.locus.app.ui.grid.GridScreen
import com.locus.app.ui.models.ModelManagerScreen
import com.locus.app.ui.search.SearchScreen
import com.locus.app.ui.settings.DashboardSettingsScreen
import com.locus.app.ui.settings.PromptTemplatesScreen
import com.locus.app.ui.settings.SettingsScreen
import com.locus.app.ui.settings.UsageSummaryScreen
import com.locus.app.ui.trash.TrashScreen
import com.locus.app.ui.tree.TreeScreen

private data class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

private val topLevelDestinations =
    listOf(
        TopLevelDestination(
            route = LocusDestinations.GRID_ROUTE,
            labelRes = R.string.nav_grid,
            icon = Icons.Default.Home,
        ),
        TopLevelDestination(
            route = LocusDestinations.TREE_ROUTE,
            labelRes = R.string.nav_tree,
            icon = Icons.Default.Menu,
        ),
        TopLevelDestination(
            route = LocusDestinations.DASHBOARD_ROUTE,
            labelRes = R.string.nav_dashboard,
            icon = Icons.Default.Star,
        ),
        TopLevelDestination(
            route = LocusDestinations.SETTINGS_ROUTE,
            labelRes = R.string.nav_settings,
            icon = Icons.Default.Settings,
        ),
    )

@Composable
fun LocusNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = LocusDestinations.GRID_ROUTE,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val isTopLevel = topLevelDestinations.any { it.route == currentRoute }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (isTopLevel) {
                LocusBottomBar(
                    currentRoute = currentRoute,
                    onNavigateToDestination = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) { locusNavGraph(navController) }
    }
}

@Suppress("LongMethod")
private fun NavGraphBuilder.locusNavGraph(navController: NavHostController) {
    composable(LocusDestinations.GRID_ROUTE) {
        GridScreen(
            onNavigateToEditor = { noteId ->
                navController.navigate(LocusDestinations.editorRoute(noteId))
            },
            onNavigateToSearch = { navController.navigate(LocusDestinations.SEARCH_ROUTE) },
            onNavigateToChat = { navController.navigate(LocusDestinations.CHAT_ROUTE) },
        )
    }
    composable(LocusDestinations.TREE_ROUTE) {
        TreeScreen(
            onNavigateToEditor = { noteId ->
                navController.navigate(LocusDestinations.editorRoute(noteId))
            },
        )
    }
    composable(LocusDestinations.DASHBOARD_ROUTE) {
        DashboardScreen(
            onNavigateToEditor = { noteId ->
                navController.navigate(LocusDestinations.editorRoute(noteId))
            },
            onNavigateToCluster = { noteIds ->
                navController.navigate(LocusDestinations.searchRoute(noteIds))
            },
            onNavigateToSettings = {
                navController.navigate(LocusDestinations.DASHBOARD_SETTINGS_ROUTE)
            },
        )
    }
    composable(LocusDestinations.DASHBOARD_SETTINGS_ROUTE) {
        DashboardSettingsScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }
    composable(LocusDestinations.SETTINGS_ROUTE) {
        SettingsScreen(
            onNavigateToTrash = { navController.navigate(LocusDestinations.TRASH_ROUTE) },
            onNavigateToModelManager = {
                navController.navigate(LocusDestinations.MODEL_MANAGER_ROUTE)
            },
            onNavigateToUsageSummary = {
                navController.navigate(LocusDestinations.USAGE_SUMMARY_ROUTE)
            },
            onNavigateToAuditJournal = {
                navController.navigate(LocusDestinations.AUDIT_JOURNAL_ROUTE)
            },
            onNavigateToPromptTemplates = {
                navController.navigate(LocusDestinations.PROMPT_TEMPLATES_ROUTE)
            },
            onNavigateToDashboardSettings = {
                navController.navigate(LocusDestinations.DASHBOARD_SETTINGS_ROUTE)
            },
        )
    }
    composable(
        route = LocusDestinations.SEARCH_PATTERN,
        arguments =
            listOf(
                navArgument("noteIds") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) {
        SearchScreen(
            onNavigateToEditor = { noteId ->
                navController.navigate(LocusDestinations.editorRoute(noteId))
            },
            onNavigateBack = { navController.popBackStack() },
        )
    }
    composable(LocusDestinations.TRASH_ROUTE) {
        TrashScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }
    composable(
        route = LocusDestinations.EDITOR_ROUTE,
        arguments =
            listOf(
                navArgument(LocusDestinations.NOTE_ID_ARG) {
                    type = NavType.StringType
                },
            ),
    ) { backStackEntry ->
        val noteId = backStackEntry.arguments?.getString(LocusDestinations.NOTE_ID_ARG).orEmpty()
        EditorScreen(
            noteId = noteId,
            onNavigateBack = { navController.popBackStack() },
            onNavigateToNote = { targetId ->
                navController.navigate(LocusDestinations.editorRoute(targetId))
            },
        )
    }
    composable(LocusDestinations.CHAT_ROUTE) {
        ChatScreen(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToEditor = { noteId ->
                navController.navigate(LocusDestinations.editorRoute(noteId))
            },
            onNavigateToAuditJournal = {
                navController.navigate(LocusDestinations.AUDIT_JOURNAL_ROUTE)
            },
        )
    }
    composable(LocusDestinations.MODEL_MANAGER_ROUTE) {
        ModelManagerScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }
    composable(LocusDestinations.USAGE_SUMMARY_ROUTE) {
        UsageSummaryScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }
    composable(LocusDestinations.AUDIT_JOURNAL_ROUTE) {
        AuditJournalScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }
    composable(LocusDestinations.PROMPT_TEMPLATES_ROUTE) {
        PromptTemplatesScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }
}

@Composable
private fun LocusBottomBar(
    currentRoute: String?,
    onNavigateToDestination: (String) -> Unit,
) {
    NavigationBar {
        topLevelDestinations.forEach { destination ->
            val selected = currentRoute == destination.route
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = stringResource(destination.labelRes),
                    )
                },
                label = { Text(stringResource(destination.labelRes)) },
                selected = selected,
                onClick = {
                    if (currentRoute != destination.route) {
                        onNavigateToDestination(destination.route)
                    }
                },
            )
        }
    }
}

package com.stefan73.swipessh.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.stefan73.swipessh.session.SessionStatus
import com.stefan73.swipessh.ui.home.HomeRoute
import com.stefan73.swipessh.ui.home.HomeViewModel
import com.stefan73.swipessh.ui.terminal.TerminalRoute

object AppDestinations {
    const val HOME_ROUTE = "home"
    const val TERMINAL_ROUTE = "terminal"
}

/** Switches between the start screen and the terminal based on the shared session state. */
@Composable
fun SshTerminalApp(
    viewModel: HomeViewModel,
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.showConnectionScreen, uiState.sessionStatus) {
        when {
            uiState.showConnectionScreen || uiState.sessionStatus == SessionStatus.IDLE -> {
                if (navController.currentBackStackEntry?.destination?.route != AppDestinations.HOME_ROUTE) {
                    navController.popBackStack(AppDestinations.HOME_ROUTE, false)
                }
            }

            uiState.sessionStatus in setOf(
                SessionStatus.CONNECTING,
                SessionStatus.CONNECTED,
                SessionStatus.DISCONNECTING,
                SessionStatus.ERROR,
            ) -> {
                if (navController.currentBackStackEntry?.destination?.route != AppDestinations.TERMINAL_ROUTE) {
                    navController.navigate(AppDestinations.TERMINAL_ROUTE) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = AppDestinations.HOME_ROUTE,
    ) {
        composable(route = AppDestinations.HOME_ROUTE) {
            HomeRoute(viewModel = viewModel)
        }
        composable(route = AppDestinations.TERMINAL_ROUTE) {
            TerminalRoute(viewModel = viewModel)
        }
    }
}


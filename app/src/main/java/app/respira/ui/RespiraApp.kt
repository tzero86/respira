package app.respira.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import app.respira.feature.ambience.AmbienceScreen
import app.respira.feature.breathe.BreatheScreen
import app.respira.feature.home.HomeScreen
import app.respira.feature.meditation.MeditateScreen
import app.respira.ui.components.AuroraBackdrop
import app.respira.ui.components.MiniPlayer
import app.respira.ui.components.RespiraBottomBar

@Composable
fun RespiraApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showBar = route == Routes.Home || route == Routes.Meditate || route == Routes.Ambience
    val navRequest by AppNavigation.request.collectAsStateWithLifecycle()

    LaunchedEffect(navRequest) {
        val target = navRequest ?: return@LaunchedEffect
        navController.navigateTab(target)
        AppNavigation.consume()
    }

    AuroraBackdrop {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                NavHost(
                    navController = navController,
                    startDestination = Routes.Home,
                    enterTransition = {
                        fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 28 }
                    },
                    exitTransition = { fadeOut(tween(180)) },
                    popEnterTransition = { fadeIn(tween(240)) },
                    popExitTransition = {
                        fadeOut(tween(180)) + slideOutVertically(tween(260)) { it / 28 }
                    },
                ) {
                    composable(Routes.Home) {
                        HomeScreen(
                            onBreathe = { navController.navigateTab(Routes.Breathe) },
                            onMeditate = { navController.navigateTab(Routes.Meditate) },
                            onAmbience = { navController.navigateTab(Routes.Ambience) },
                        )
                    }
                    composable(Routes.Breathe) {
                        BreatheScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Routes.Meditate) {
                        MeditateScreen()
                    }
                    composable(Routes.Ambience) {
                        AmbienceScreen()
                    }
                }
            }

            MiniPlayer(
                modifier = if (showBar) Modifier else Modifier.navigationBarsPadding(),
            )

            AnimatedVisibility(visible = showBar) {
                RespiraBottomBar(
                    currentRoute = route,
                    onSelect = { navController.navigateTab(it) },
                )
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(Routes.Home) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

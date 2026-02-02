package com.example.rescueteam.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.rescueteam.screens.AssignmentScreen
import com.example.rescueteam.screens.ChatScreen

// (NEW) Defines the navigation routes
sealed class Screen(val route: String) {
    object AssignmentList : Screen("assignments")
    // Pass the victim's ID to the chat screen
    object Chat : Screen("chat/{victimId}") {
        fun createRoute(victimId: String) = "chat/$victimId"
    }
}

@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.AssignmentList.route) {

        // First screen: List of assigned victims
        composable(Screen.AssignmentList.route) {
            AssignmentScreen(
                onNavigateToChat = { victimId ->
                    navController.navigate(Screen.Chat.createRoute(victimId))
                }
            )
        }

        // Second screen: Chat with a specific victim
        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("victimId") { type = NavType.StringType })
        ) { backStackEntry ->
            val victimId = backStackEntry.arguments?.getString("victimId") ?: "unknown"
            ChatScreen(
                victimId = victimId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

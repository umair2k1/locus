package com.locus.app.flavor

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.locus.app.full.ui.SubscriptionLoginScreen
import com.locus.app.navigation.LocusDestinations

fun NavGraphBuilder.addFlavorDestinations(navController: NavHostController) {
    composable(LocusDestinations.SUBSCRIPTION_LOGIN_ROUTE) {
        SubscriptionLoginScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }
}

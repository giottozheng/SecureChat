package com.securechat.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.securechat.app.ui.screen.chat.ConversationListScreen
import com.securechat.app.ui.screen.contacts.ContactsScreen
import com.securechat.app.ui.screen.settings.SettingsScreen

/**
 * Central navigation graph for SecureChat.
 * Organized by bottom-nav tab groups.
 * NOTE: This NavHost is no longer used — SecureChatShell.kt handles navigation.
 */
@Composable
fun SecureChatNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = "main",
        modifier = modifier
    ) {
        // Main bottom-navigation section
        navigation(
            startDestination = "conversations",
            route = "main"
        ) {
            composable("conversations") {
                ConversationListScreen(
                    onConversationClick = { _ ->
                        // handled by SecureChatShell
                    },
                    onSendMessage = { _, _ ->
                        // TODO: Send encrypted message via WebSocket
                    }
                )
            }

            composable("contacts") {
                ContactsScreen()
            }

            composable("settings") {
                SettingsScreen(
                    onLogout = {
                        navController.navigate("login") {
                            popUpTo("main") { inclusive = true }
                        }
                    }
                )
            }
        }

        // Login screen (outside main nav graph)
        composable("login") {
            // Navigate back to conversations after successful login
            // Handled by the caller
        }
    }
}

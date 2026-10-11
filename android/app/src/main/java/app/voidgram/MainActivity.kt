package app.voidgram

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel

val VoidBg = Color(0xFF0E0B16)
val VoidSurface = Color(0xFF171325)
val VoidSurfaceHi = Color(0xFF221C35)
val VoidAccent = Color(0xFF9B7BFF)
val VoidMine = Color(0xFF4B3A8F)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = VoidAccent,
                    onPrimary = Color(0xFF1A1033),
                    background = VoidBg,
                    surface = VoidSurface,
                    surfaceVariant = VoidSurfaceHi,
                    onSurface = Color(0xFFEDE8FF),
                    onBackground = Color(0xFFEDE8FF),
                    onSurfaceVariant = Color(0xFFAFA6CC),
                    error = Color(0xFFFF6B7A),
                ),
            ) { Root() }
        }
    }
}

@Composable
fun Root(model: AppModel = viewModel()) {
    BackHandler(enabled = model.loggedIn && model.screen != Screen.Chats) { model.back() }

    Box(Modifier.fillMaxSize().background(VoidBg)) {
        when {
            model.booting -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            !model.loggedIn -> AuthScreen(model)
            else -> when (val s = model.screen) {
                Screen.Chats -> ChatListScreen(model)
                is Screen.Dialog -> DialogScreen(model, s.chatId)
                Screen.NewChat -> NewChatScreen(model)
                Screen.NewGroup -> NewGroupScreen(model)
                Screen.Profile -> ProfileScreen(model)
                is Screen.Info -> GroupInfoScreen(model, s.chatId)
            }
        }
    }

    model.error?.let { msg ->
        if (model.loggedIn) AlertDialog(
            onDismissRequest = { model.error = null },
            confirmButton = { TextButton(onClick = { model.error = null }) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}

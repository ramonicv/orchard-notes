package dev.rortega.orchardnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rortega.orchardnotes.auth.SessionState
import dev.rortega.orchardnotes.ui.signin.SignInScreen
import dev.rortega.orchardnotes.ui.signin.WelcomeScreen
import kotlinx.coroutines.launch

@Composable
fun OrchardRoot() {
    val container = appContainer()
    val session by container.sessionManager.state.collectAsStateWithLifecycle()
    var signingIn by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Leave the sign-in page as soon as a valid session appears.
    LaunchedEffect(session) {
        if (session is SessionState.SignedIn && !(session as SessionState.SignedIn).expired) signingIn = false
    }

    when (val state = session) {
        SessionState.Loading -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        SessionState.SignedOut ->
            if (signingIn) SignInScreen(onClose = { signingIn = false }) else WelcomeScreen(onSignIn = { signingIn = true })
        is SessionState.SignedIn ->
            if (signingIn) {
                SignInScreen(onClose = { signingIn = false })
            } else {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.safeDrawingPadding().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Signed in as ${state.account.fullName ?: state.account.appleId}")
                        if (state.expired) {
                            Button(onClick = { signingIn = true }) { Text("Session expired — sign in again") }
                        }
                        OutlinedButton(onClick = { scope.launch { container.sessionManager.signOut() } }) {
                            Text("Sign out")
                        }
                    }
                }
            }
    }
}

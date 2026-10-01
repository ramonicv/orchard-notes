package dev.rortega.orchardnotes.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rortega.orchardnotes.auth.SessionState
import dev.rortega.orchardnotes.ui.browse.NotesBrowser
import dev.rortega.orchardnotes.ui.signin.SignInScreen
import dev.rortega.orchardnotes.ui.signin.WelcomeScreen

@Composable
fun OrchardRoot() {
    val container = appContainer()
    val session by container.sessionManager.state.collectAsStateWithLifecycle()
    var signingIn by rememberSaveable { mutableStateOf(false) }

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
                NotesBrowser(session = state, onSignInAgain = { signingIn = true })
            }
    }
}

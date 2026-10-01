package dev.rortega.orchardnotes.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.rortega.orchardnotes.AppContainer
import dev.rortega.orchardnotes.OrchardApplication

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as OrchardApplication).container

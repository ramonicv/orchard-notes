package dev.rortega.orchardnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.rortega.orchardnotes.ui.OrchardRoot
import dev.rortega.orchardnotes.ui.theme.OrchardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OrchardTheme {
                OrchardRoot()
            }
        }
    }
}

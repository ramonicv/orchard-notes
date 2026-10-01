package dev.rortega.orchardnotes.screenshots

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.rortega.orchardnotes.MainActivity
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real app (debug demo data, no account) on the JVM and saves PNGs to
 * app/build/screenshots, for checking layouts without a device. Not an assertion test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AppScreenshots {
    @get:Rule val compose = createEmptyComposeRule()

    private fun save(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun launchDemo(): ActivityScenario<MainActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).putExtra("demo", true)
        return ActivityScenario.launch(intent)
    }

    @Before
    fun initWorkManager() {
        assumeTrue("Run with -Pscreenshots", System.getProperty("orchard.screenshots") == "true")
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
    }

    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phone() {
        launchDemo().use {
            settle()
            save("phone-1-notes-list")
            compose.onNodeWithContentDescription("Folders").performClick()
            settle()
            save("phone-2-folders")
            compose.onNodeWithText("Travel").performClick()
            settle()
            compose.onNodeWithText("Lisbon, April").performClick()
            settle()
            save("phone-3-note")
            compose.onNodeWithContentDescription("Edit").performClick()
            settle()
            save("phone-4-editing")
            compose.onNodeWithContentDescription("Back").performClick()
            settle()
            compose.onNodeWithContentDescription("Folders").performClick()
            settle()
            compose.onNodeWithText("All iCloud").performClick()
            settle()
            compose.onNodeWithText("Welcome to Orchard").performClick()
            settle()
            save("phone-5-welcome-note")
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun noteActions() {
        launchDemo().use {
            settle()
            compose.onNodeWithText("Groceries").performClick()
            settle()
            compose.onNodeWithContentDescription("More").performClick()
            settle()
            save("phone-8-note-menu")
            compose.onNodeWithText("Delete").performClick()
            settle()
            save("phone-9-deleted-snackbar")
            compose.onNodeWithContentDescription("Folders").performClick()
            settle()
            compose.onNodeWithText("Recently Deleted").performClick()
            settle()
            compose.onNodeWithText("Groceries").performClick()
            settle()
            save("phone-10-recently-deleted-note")
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun capturedNote() {
        launchDemo().use {
            settle()
            compose.onNodeWithText("Test Note").performScrollTo().performClick()
            settle()
            save("phone-6-captured-note")
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun phoneDark() {
        launchDemo().use {
            settle()
            compose.onNodeWithText("Groceries").performClick()
            settle()
            save("phone-7-dark-checklist")
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun tablet() {
        launchDemo().use {
            settle()
            compose.onNodeWithText("Welcome to Orchard").performClick()
            settle()
            save("tablet-1-three-panes")
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun welcome() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            settle()
            save("phone-0-welcome")
        }
    }
}

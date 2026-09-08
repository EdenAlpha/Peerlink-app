package com.peerlink.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.peerlink.app.core.AppState
import com.peerlink.app.core.PeerInfo
import com.peerlink.app.core.PeerSessionPhase
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.File

/** Real Compose rendering and interaction with a local, service-free fixture. */
class PeerLinkUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(active: Boolean = false, largeText: Boolean = false, connect: () -> Unit = {}, disconnect: () -> Unit = {}) {
        context.getSharedPreferences("peerlink_prefs", Context.MODE_PRIVATE).edit().putString("username", "Emmanuel").commit()
        AppState.isRunning.set(active)
        AppState.sessionPhase.set(if (active) PeerSessionPhase.ACTIVE else PeerSessionPhase.IDLE)
        AppState.sessionError.set(null)
        AppState.connectedPeerName = if (active) "Alex" else ""
        AppState.sessionStartedElapsedMs.set(if (active) android.os.SystemClock.elapsedRealtime() else 0)
        compose.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, if (largeText) 1.5f else 1f)) {
                PeerLinkTheme {
                    PeerLinkScreen(context, listOf(PeerInfo("Alex", "192.0.2.2")),
                        DiscoveryUiState(phase = DiscoveryPhase.PEERS_FOUND, message = "One player nearby", peerCount = 1),
                        PeerLinkActions(saveName = {}, connectToPeer = { connect() }, disconnect = disconnect))
                }
            }
        }
    }

    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "ui-checks").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val preview = Bitmap.createScaledBitmap(bitmap, 540, (bitmap.height * 540f / bitmap.width).toInt(), true)
        File(dir, "$name-preview.jpg").outputStream().use { preview.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    @After fun reset() {
        AppState.isRunning.set(false)
        AppState.sessionPhase.set(PeerSessionPhase.IDLE)
        AppState.connectedPeerName = ""
        AppState.sessionStartedElapsedMs.set(0)
    }

    @Test fun nearbyPlayerCanBeSelected() {
        var selected = 0
        show(connect = { selected++ })
        compose.onNodeWithText("Good games.", substring = true).assertIsDisplayed()
        screenshot("home")
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("Alex"))
        compose.onNodeWithText("Alex").performClick()
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithText("Activity", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Your activity").assertIsDisplayed()
        screenshot("activity")
    }

    @Test fun connectionActionsRequireConfirmationToDisconnect() {
        var disconnected = 0
        show(active = true, disconnect = { disconnected++ })
        compose.onNodeWithText("Open eFootball").assertIsDisplayed()
        screenshot("connected")
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("Disconnect"))
        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithText("End this connection?").assertIsDisplayed()
        compose.onNodeWithText("Keep playing").performClick()
        compose.runOnIdle { assertEquals(0, disconnected) }
    }

    @Test fun largeTextKeepsPlayerAndSettingsReachable() {
        show(largeText = true)
        screenshot("large-text")
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("Alex"))
        compose.onNodeWithText("Alex").assertIsDisplayed()
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Close panel").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Close panel").assertDoesNotExist()
    }
}

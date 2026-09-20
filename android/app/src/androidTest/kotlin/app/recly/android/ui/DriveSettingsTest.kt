package app.recly.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.recly.android.R
import app.recly.android.ui.theme.ReclyTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Exercises connection controls with callbacks that never touch a real Google account. */
class DriveSettingsTest {
    @get:Rule val ui = createComposeRule()
    private val main = mutableStateOf(MainUiState(loading = false, email = "test@example.test"))
    private var deleteRecordings: Boolean? = null
    private var recording = false
    private var holdDisconnect = false

    private fun show() {
        ui.setContent {
            ReclyTheme {
                SettingsScreen(
                    main = main.value, settings = SettingsUiState(),
                    onWifiOnly = {}, onLanguage = {}, onTheme = {}, onConsentReminder = {},
                    onSignIn = {},
                    onAskToDisconnect = {
                        main.value = main.value.copy(disconnect = DisconnectPrompt(3, recording))
                    },
                    onCancelDisconnect = { main.value = main.value.copy(disconnect = null) },
                    onDisconnect = {
                        deleteRecordings = it
                        if (holdDisconnect) {
                            main.value = main.value.copy(disconnect = null, disconnecting = true, busy = true)
                        }
                    }, onRevokeDebtSettled = {},
                    onExportWorkflows = {}, onImportWorkflows = {},
                    onCancelImport = {}, onConfirmImport = {},
                )
            }
        }
    }

    @Test fun oneDisconnectActionConfirmsAndKeepsRecordings() {
        show()
        ui.onNodeWithTag("drive-manage").assertDoesNotExist()
        ui.onNodeWithTag("signOut").assertDoesNotExist()
        ui.onNodeWithTag("drive-actions").assertDoesNotExist()
        ui.onNodeWithTag("disconnect").performClick()
        ui.onNodeWithTag("disconnect-confirm").assertIsEnabled()
        ui.runOnIdle { assertNull(deleteRecordings) }
        val cancel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.action_cancel)
        ui.onNodeWithText(cancel).performClick()
        ui.onNodeWithTag("disconnect-confirm").assertDoesNotExist()
        ui.runOnIdle { assertNull(deleteRecordings) }
        ui.onNodeWithTag("disconnect").performClick()
        ui.onNodeWithTag("disconnect-confirm").performClick()
        ui.runOnIdle { assertEquals(false, deleteRecordings) }
    }

    @Test fun pendingRevocationKeepsRetryVisibleAndPreventsLocalSignOut() {
        main.value = main.value.copy(disconnectPhase = DisconnectPhase.REVOKE_PENDING)
        show()
        ui.onNodeWithTag("signOut").assertDoesNotExist()
        ui.onNodeWithTag("drive-actions").assertDoesNotExist()
        ui.onNodeWithTag("disconnect").assertIsEnabled().performClick()
        ui.onNodeWithTag("disconnect-confirm").assertIsEnabled()
    }

    @Test fun activeRecordingStillBlocksRevocation() {
        recording = true
        show()
        ui.onNodeWithTag("disconnect").performClick()
        ui.onNodeWithTag("disconnect-confirm").assertIsNotEnabled()
        ui.runOnIdle { assertNull(deleteRecordings) }
    }

    @Test fun confirmingShowsProgressUntilCleanupEndsThenAllowsReconnection() {
        holdDisconnect = true
        show()
        ui.onNodeWithTag("disconnect").performClick()
        ui.onNodeWithTag("disconnect-confirm").performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val progress = context.getString(R.string.drive_disconnecting)
        val connect = context.getString(R.string.drive_connect)
        ui.onNodeWithTag("disconnect-confirm").assertDoesNotExist()
        ui.onNodeWithTag("disconnect").assertTextEquals(progress).assertIsNotEnabled()
        ui.runOnIdle {
            main.value = main.value.copy(email = null, disconnectPhase = DisconnectPhase.NONE)
        }
        ui.onNodeWithTag("disconnect").assertTextEquals(progress).assertIsNotEnabled()
        ui.onNodeWithText(connect).assertDoesNotExist()
        ui.runOnIdle { main.value = main.value.copy(disconnecting = false, busy = false) }
        ui.onNodeWithTag("disconnect").assertDoesNotExist()
        ui.onNodeWithText(connect).assertIsEnabled()
    }

    @Test fun cleanupFailureRestoresTheSameDisconnectButtonForRetry() {
        main.value = main.value.copy(email = null, disconnecting = true, busy = true,
            disconnectPhase = DisconnectPhase.REVOKED_CLEANUP_OWED)
        show()
        ui.onNodeWithTag("disconnect").assertIsNotEnabled()
        ui.runOnIdle { main.value = main.value.copy(disconnecting = false, busy = false) }
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.drive_disconnect)
        ui.onNodeWithTag("disconnect").assertTextEquals(label).assertIsEnabled().performClick()
        ui.onNodeWithTag("disconnect-confirm").assertIsEnabled()
    }
}

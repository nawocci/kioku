package eu.kanade.tachiyomi.sync

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class SyncStatusTest {

    // The engine refuses to cancel a session in any of these phases, and the UI
    // disables the pairing form; a state left out here would let the dialog
    // close a pairing that is still running.
    @Test
    fun `every phase of a pairing counts as busy`() {
        val pairing = listOf(
            SyncStatus.Connecting,
            SyncStatus.ApplyingSnapshot(10),
            SyncStatus.UploadingLibrary,
            SyncStatus.MergeConfirmation(serverEntries = 1, localEntries = 1),
            SyncStatus.AwaitingReview(emptyList()),
        )

        pairing.forEach { assertTrue(it.pairingBusy, "$it should be busy") }
    }

    @Test
    fun `a live or idle session is not a pairing`() {
        val idle = listOf(
            SyncStatus.Idle,
            SyncStatus.NotPaired,
            SyncStatus.Online(Instant.fromEpochMilliseconds(0)),
            SyncStatus.Error("boom"),
        )

        idle.forEach { assertFalse(it.pairingBusy, "$it should not be busy") }
    }
}

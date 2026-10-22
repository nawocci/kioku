package eu.kanade.tachiyomi.sync

import kotlin.time.Instant

/** What the sync settings screen shows. */
sealed interface SyncStatus {
    /** No session is running. */
    data object Idle : SyncStatus

    /** Not paired with a server. */
    data object NotPaired : SyncStatus

    /** Opening a session. */
    data object Connecting : SyncStatus

    /** A paired session is live: changes flow as they happen. */
    data class Online(val since: Instant) : SyncStatus

    /** The pairing snapshot is being applied. */
    data class ApplyingSnapshot(val records: Int) : SyncStatus

    /** The local library is being pushed up after pairing. */
    data object UploadingLibrary : SyncStatus

    /**
     * Pairing found data on both sides; the user must confirm the merge
     * before anything is applied.
     */
    data class MergeConfirmation(val serverEntries: Int, val localEntries: Int) : SyncStatus

    /**
     * Pairing found library entries on the server this device does not have;
     * answered through [SyncEngine.resolvePairing].
     */
    data class AwaitingReview(val entries: List<ChangeApplier.ReviewEntry>) : SyncStatus

    /** The last session failed. */
    data class Error(val message: String) : SyncStatus
}

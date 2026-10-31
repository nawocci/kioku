package xyz.altaf.kioku.sync

/**
 * The sync protocol version this client speaks. It must match the server's
 * exactly — the version is exchanged on the WebSocket handshake in both
 * directions, and a mismatch fails the session before any frame is
 * exchanged. It changes only when the wire format or the semantics of an
 * existing message change; see PROTOCOL.md in kioku-sync.
 */
const val PROTOCOL_VERSION = "2"

/** The header carrying the protocol version on the WebSocket handshake. */
const val PROTOCOL_HEADER = "X-Kioku-Sync-Protocol"

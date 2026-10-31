package eu.kanade.tachiyomi.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The pairing QR payload shown on the server dashboard:
 * {"v":1,"url":"https://sync.example.com","key":"kioku_…"}.
 *
 * The fields are validated after parsing so a random QR the camera happens to
 * read can never reach the pairing form.
 */
data class SyncQrPayload(val serverUrl: String, val apiKey: String) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val URL_REGEX = Regex("""^https?://[^\s/]+(:\d+)?/?$""")

        fun parse(text: String): SyncQrPayload? {
            val payload = runCatching { json.decodeFromString<QrJson>(text.trim()) }.getOrNull()
                ?: return null
            if (!URL_REGEX.matches(payload.url)) return null
            if (!payload.key.startsWith("kioku_")) return null
            return SyncQrPayload(payload.url.trimEnd('/'), payload.key)
        }
    }
}

/** The wire shape of the pairing QR; extra fields are ignored. */
@Serializable
private data class QrJson(val v: Int = 1, val url: String, val key: String)

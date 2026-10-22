package eu.kanade.tachiyomi.sync

/**
 * The pairing QR payload shown on the server dashboard:
 * {"v":1,"url":"https://sync.example.com","key":"kioku_…"}.
 *
 * The fields are validated on parse so a random QR the camera happens to
 * read can never reach the pairing form.
 */
data class SyncQrPayload(val serverUrl: String, val apiKey: String) {
    companion object {
        private val URL_REGEX = Regex("""^https?://[^\s/]+(:\d+)?/?$""")

        fun parse(text: String): SyncQrPayload? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
            val url = Regex(""""url"\s*:\s*"([^"]+)"""").find(trimmed)?.groupValues?.get(1)
                ?: return null
            val key = Regex(""""key"\s*:\s*"([^"]+)"""").find(trimmed)?.groupValues?.get(1)
                ?: return null
            if (!URL_REGEX.matches(url)) return null
            if (!key.startsWith("kioku_")) return null
            return SyncQrPayload(url.trimEnd('/'), key)
        }
    }
}

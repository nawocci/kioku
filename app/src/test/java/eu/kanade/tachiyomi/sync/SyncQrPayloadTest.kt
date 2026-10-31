package eu.kanade.tachiyomi.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SyncQrPayloadTest {

    @Test
    fun `reads the payload the dashboard renders`() {
        val payload = SyncQrPayload.parse("""{"v":1,"url":"https://sync.example.com","key":"kioku_abc123"}""")

        assertEquals(SyncQrPayload("https://sync.example.com", "kioku_abc123"), payload)
    }

    @Test
    fun `keeps a port and drops the trailing slash`() {
        val payload = SyncQrPayload.parse("""{"v":1,"url":"http://192.168.1.20:8756/","key":"kioku_abc"}""")

        assertEquals(SyncQrPayload("http://192.168.1.20:8756", "kioku_abc"), payload)
    }

    @Test
    fun `ignores fields it does not know`() {
        val payload = SyncQrPayload.parse(
            """{"url":"https://sync.example.com","key":"kioku_abc","v":2,"label":"Phone"}""",
        )

        assertEquals(SyncQrPayload("https://sync.example.com", "kioku_abc"), payload)
    }

    @Test
    fun `rejects anything that is not a pairing payload`() {
        val rejected = listOf(
            "",
            "hello",
            "{}",
            """{"url":"https://sync.example.com"}""",
            """{"key":"kioku_abc"}""",
            """{"url":"https://sync.example.com/path","key":"kioku_abc"}""",
            """{"url":"ftp://sync.example.com","key":"kioku_abc"}""",
            """{"url":"javascript:alert(1)","key":"kioku_abc"}""",
            """{"url":"https://sync.example.com","key":"nope_abc"}""",
            """{"url":"https://sync.example.com","key":""}""",
        )

        rejected.forEach { text ->
            assertNull(SyncQrPayload.parse(text), "accepted $text")
        }
    }
}

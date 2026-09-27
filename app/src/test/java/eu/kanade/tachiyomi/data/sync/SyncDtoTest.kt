package eu.kanade.tachiyomi.data.sync

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Pins the wire contract against the mihon-sync Go server (internal/syncapi/dto.go).
 * A field rename or an accidentally defaulted property that kotlinx then omits
 * would silently break sync, so assert the exact JSON shape.
 */
class SyncDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `manga dto serializes server field names`() {
        val encoded = json.encodeToString(
            SyncMangaDto.serializer(),
            SyncMangaDto(
                sourceId = 7,
                url = "/manga/one",
                title = "One",
                favorite = true,
                chapterFlags = 3,
                viewerFlags = 5,
                updateStrategy = "ALWAYS_UPDATE",
                notes = "n",
                dateAdded = 1_700_000_000_000,
                clientVersion = 9,
            ),
        )

        encoded shouldContain "\"source_id\":7"
        encoded shouldContain "\"url\":\"/manga/one\""
        encoded shouldContain "\"chapter_flags\":3"
        encoded shouldContain "\"viewer_flags\":5"
        encoded shouldContain "\"update_strategy\":\"ALWAYS_UPDATE\""
        encoded shouldContain "\"date_added\":1700000000000"
        encoded shouldContain "\"client_version\":9"
        encoded shouldContain "\"favorite\":true"
    }

    @Test
    fun `favorite and update_strategy are always emitted`() {
        // kotlinx omits default-valued properties; these two must not have defaults
        // or the server would decode them as false / "".
        val encoded = json.encodeToString(
            SyncMangaDto.serializer(),
            SyncMangaDto(sourceId = 1, url = "/m", favorite = false, updateStrategy = ""),
        )

        encoded shouldContain "\"favorite\":false"
        encoded shouldContain "\"update_strategy\":\"\""
    }

    @Test
    fun `push request serializes device_id and since`() {
        val encoded = json.encodeToString(
            SyncPushRequest.serializer(),
            SyncPushRequest(deviceId = "phone-uuid", since = 41, changes = SyncChangeSetDto()),
        )

        encoded shouldContain "\"device_id\":\"phone-uuid\""
        encoded shouldContain "\"since\":41"
        encoded shouldContain "\"changes\":"
    }

    @Test
    fun `decodes push response with piggybacked changes`() {
        val body = """
            {
              "rev": 42,
              "changes": {
                "mangas": [{"source_id": 7, "url": "/m", "title": "T", "favorite": true,
                            "update_strategy": "ALWAYS_UPDATE", "client_version": 5}],
                "chapters": [{"manga_source_id": 7, "manga_url": "/m", "url": "/c/1",
                              "read": true, "last_page_read": 12, "client_version": 3}],
                "categories": [{"name": "Reading", "order": 1}],
                "manga_categories": [{"manga_source_id": 7, "manga_url": "/m", "category": "Reading"}],
                "history": [{"manga_source_id": 7, "manga_url": "/m", "chapter_url": "/c/1",
                             "last_read": 1717000000, "read_duration": 300000}],
                "preferences": [{"key": "theme", "type": "string", "value": "dark"}],
                "extension_stores": [{"index_url": "https://x/repo.json", "name": "X"}]
              }
            }
        """.trimIndent()

        val response = json.decodeFromString(SyncPushResponse.serializer(), body)

        response.rev shouldBe 42L
        response.changes.mangas.single().sourceId shouldBe 7L
        response.changes.mangas.single().favorite shouldBe true
        response.changes.chapters.single().lastPageRead shouldBe 12L
        response.changes.categories.single().name shouldBe "Reading"
        response.changes.mangaCategories.single().category shouldBe "Reading"
        response.changes.history.single().readDuration shouldBe 300000L
        response.changes.preferences.single().key shouldBe "theme"
        (response.changes.preferences.single().value as JsonPrimitive).content shouldBe "dark"
        response.changes.extensionStores.single().indexUrl shouldBe "https://x/repo.json"
    }

    @Test
    fun `decodes tombstone and tombstone-only changeset`() {
        val body = """
            {
              "rev": 7,
              "changes": {
                "mangas": [{"source_id": 1, "url": "/gone", "favorite": false,
                            "update_strategy": "", "deleted": true}],
                "categories": [{"name": "Old", "deleted": true}]
              }
            }
        """.trimIndent()

        val response = json.decodeFromString(SyncPullResponse.serializer(), body)

        response.changes.mangas.single().deleted shouldBe true
        response.changes.categories.single().deleted shouldBe true
        response.changes.chapters shouldBe emptyList()
    }

    @Test
    fun `unknown server fields are ignored`() {
        // Forward compatibility: a newer server may add fields the client doesn't know.
        val body = """{"rev": 1, "changes": {"mangas": [], "new_entity": [1, 2]}}"""
        val response = json.decodeFromString(SyncPullResponse.serializer(), body)
        response.rev shouldBe 1L
    }

    @Test
    fun `preference value round trips as typed json`() {
        val dto = SyncPreferenceDto(
            key = "k",
            type = "int",
            value = JsonPrimitive(5),
        )
        val encoded = json.encodeToString(SyncPreferenceDto.serializer(), dto)
        val decoded = json.decodeFromString(SyncPreferenceDto.serializer(), encoded)

        encoded shouldContain "\"key\":\"k\""
        encoded shouldContain "\"type\":\"int\""
        encoded shouldContain "\"value\":5"
        decoded.value shouldBe JsonPrimitive(5)
    }
}

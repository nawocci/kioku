package mihon.domain.source.interactor

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/**
 * Regression guard for synced libraries showing blank covers/authors/status.
 *
 * A library restored from sync is inserted with `initialized = false` because the
 * sync server carries no metadata (author, status, cover, description, genre) — those
 * must be fetched from the source. The chapter-only update path (LibraryUpdateJob with
 * the default `auto_update_metadata = false`) must therefore NOT claim the details are
 * known, otherwise `MangaViewModel` sees `initialized = true` and skips the automatic
 * detail refresh on open, leaving the entry blank until a manual swipe-to-refresh.
 */
class UpdateMangaFromRemoteTest {

    private lateinit var sourceManager: SourceManager
    private lateinit var chapterRepository: ChapterRepository
    private lateinit var mangaRepository: MangaRepository
    private lateinit var syncChaptersWithSource: SyncChaptersWithSource
    private lateinit var coverCache: CoverCache
    private lateinit var libraryPreferences: LibraryPreferences
    private lateinit var downloadManager: DownloadManager

    private lateinit var updateMangaFromRemote: UpdateMangaFromRemote

    /** A manga as SyncMerger inserts it: no metadata yet, `initialized = false`. */
    private val syncedManga = Manga.create().copy(
        id = 42L,
        source = 1L,
        url = "/manga/one",
        title = "One",
        favorite = true,
        author = null,
        status = 0L,
        thumbnailUrl = null,
        initialized = false,
    )

    @BeforeEach
    fun beforeEach() {
        sourceManager = mockk()
        chapterRepository = mockk()
        mangaRepository = mockk()
        syncChaptersWithSource = mockk()
        coverCache = mockk(relaxed = true)
        libraryPreferences = mockk(relaxed = true)
        downloadManager = mockk(relaxed = true)

        val titlesPreference = mockk<Preference<Boolean>>()
        every { titlesPreference.get() } returns false
        every { libraryPreferences.updateMangaTitles } returns titlesPreference

        // A source that echoes the manga back when details aren't requested, like
        // CatalogueSource does when fetchDetails = false.
        val source = EchoSource(id = 1L)
        every { sourceManager.getOrStub(any()) } returns source

        coEvery { chapterRepository.getChapterByMangaId(any(), any()) } returns emptyList()
        coEvery { syncChaptersWithSource.await(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { mangaRepository.update(any()) } returns true
        coEvery { mangaRepository.getMangaById(any()) } returns syncedManga

        updateMangaFromRemote = UpdateMangaFromRemote(
            sourceManager = sourceManager,
            chapterRepository = chapterRepository,
            mangaRepository = mangaRepository,
            syncChaptersWithSource = syncChaptersWithSource,
            coverCache = coverCache,
            libraryPreferences = libraryPreferences,
            downloadManager = downloadManager,
        )
    }

    @Test
    fun `chapter-only update does not mark details as initialized`() = runTest {
        val captured = slot<MangaUpdate>()
        coEvery { mangaRepository.update(capture(captured)) } returns true

        updateMangaFromRemote(syncedManga, fetchDetails = false, fetchChapters = true)

        // null means "leave as-is", so `initialized` stays false and the app still
        // knows it must fetch details for this entry later.
        captured.captured.initialized.shouldBeNull()
    }

    @Test
    fun `detail fetch marks details as initialized`() = runTest {
        val captured = slot<MangaUpdate>()
        coEvery { mangaRepository.update(capture(captured)) } returns true

        updateMangaFromRemote(syncedManga, fetchDetails = true, fetchChapters = true)

        captured.captured.initialized shouldBe true
    }

    /** Minimal [Source] that returns the input manga unchanged when details aren't fetched. */
    private class EchoSource(override val id: Long) : Source {
        override val name: String = "Echo"
        override val supportsLatest: Boolean = false

        override suspend fun getPopularManga(page: Int): MangasPage = error("unused")
        override suspend fun getLatestUpdates(page: Int): MangasPage = error("unused")
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            error("unused")

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate = SMangaUpdate(manga, chapters)

        override suspend fun getPageList(chapter: SChapter): List<Page> = error("unused")
    }
}

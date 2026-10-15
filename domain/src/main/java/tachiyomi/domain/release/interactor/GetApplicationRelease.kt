package tachiyomi.domain.release.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

@Inject
class GetApplicationRelease(
    private val service: ReleaseService,
) {
    suspend fun await(arguments: Arguments): Result {
        val release = service.latest(arguments) ?: return Result.NoNewUpdate

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            arguments.isNightly,
            arguments.commitCount,
            arguments.versionName,
            release.version,
        )
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    private fun isNewVersion(
        isNightly: Boolean,
        commitCount: Int,
        versionName: String,
        versionTag: String,
    ): Boolean {
        // Removes prefixes like "r" or "v"
        val newVersion = versionTag.replace("[^\\d.]".toRegex(), "")
        return if (isNightly) {
            // Nightly builds are tagged as something like "r1234"; a plain release tag would
            // not parse as an Int, so treat an unparseable tag as "no update" instead of crashing.
            newVersion.toIntOrNull()?.let { it > commitCount } ?: false
        } else {
            // Release builds are tagged as something like "v0.1.2". Compare component by
            // component, padding the shorter version with zeroes so that a four-part Kioku
            // version ("0.20.4.1") compares correctly against a three-part one ("0.20.4").
            val oldVersion = versionName.replace("[^\\d.]".toRegex(), "")

            val newSemVer = newVersion.split(".").mapNotNull { it.toIntOrNull() }
            val oldSemVer = oldVersion.split(".").mapNotNull { it.toIntOrNull() }

            for (index in 0 until maxOf(newSemVer.size, oldSemVer.size)) {
                val new = newSemVer.getOrElse(index) { 0 }
                val old = oldSemVer.getOrElse(index) { 0 }
                if (new != old) {
                    return new > old
                }
            }

            false
        }
    }

    data class Arguments(
        val isFoss: Boolean,
        val isNightly: Boolean,
        val commitCount: Int,
        val versionName: String,
        val repository: String,
        val forceCheck: Boolean = false,
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }
}

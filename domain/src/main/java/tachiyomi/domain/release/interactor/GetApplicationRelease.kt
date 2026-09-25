package tachiyomi.domain.release.interactor

import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

class GetApplicationRelease(
    private val service: ReleaseService,
) {
    suspend fun await(arguments: Arguments): Result {
        val release = service.latest(arguments) ?: return Result.NoNewUpdate

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            arguments.versionName,
            release.version,
        )
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    private fun isNewVersion(
        versionName: String,
        versionTag: String,
    ): Boolean {
        // Removes prefixes like "v"
        val newVersion = versionTag.replace("[^\\d.]".toRegex(), "")
        val oldVersion = versionName.replace("[^\\d.]".toRegex(), "")

        val newSemVer = newVersion.split(".").map { it.toInt() }
        val oldSemVer = oldVersion.split(".").map { it.toInt() }

        oldSemVer.mapIndexed { index, i ->
            if (newSemVer[index] > i) {
                return true
            }
        }

        return false
    }

    data class Arguments(
        val isFoss: Boolean,
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

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
        // Supports both 3-part upstream tags (v0.20.4) and 4-part Kioku tags
        // (v0.20.4.1); missing parts count as 0 so the two shapes compare.
        val newParts = versionTag.splitVersionParts()
        val oldParts = versionName.splitVersionParts()
        val length = maxOf(newParts.size, oldParts.size)
        for (index in 0 until length) {
            val new = newParts.getOrElse(index) { 0 }
            val old = oldParts.getOrElse(index) { 0 }
            if (new != old) {
                return new > old
            }
        }

        return false
    }

    private fun String.splitVersionParts(): List<Int> {
        // Removes prefixes like "v" and suffixes like "f"
        return replace("[^\\d.]".toRegex(), "")
            .split(".")
            .map { it.toIntOrNull() ?: 0 }
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

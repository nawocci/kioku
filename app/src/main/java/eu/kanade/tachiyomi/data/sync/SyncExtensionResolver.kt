package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.flow.first
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SyncExtensionResolver(
    private val extensionManager: ExtensionManager = Injekt.get(),
) {

    /**
     * Finds available extensions matching any source IDs that are not currently installed.
     */
    suspend fun findMissingExtensions(sourceIds: Set<Long>): List<Extension.Available> {
        if (sourceIds.isEmpty()) return emptyList()

        val installedSourceIds = extensionManager.installedExtensionsFlow.first()
            .flatMap { it.sources }
            .map { it.id }
            .toSet()

        val missingSourceIds = sourceIds - installedSourceIds
        if (missingSourceIds.isEmpty()) return emptyList()

        val available = extensionManager.availableExtensionsFlow.first()
        val matchedExtensions = mutableMapOf<String, Extension.Available>()

        for (sourceId in missingSourceIds) {
            val ext = available.find { extension ->
                extension.sources.any { it.id == sourceId }
            }
            if (ext != null) {
                matchedExtensions[ext.pkgName] = ext
            }
        }

        return matchedExtensions.values.toList()
    }

    /**
     * Triggers batch download and installation for the given list of available extensions.
     */
    fun installExtensions(extensions: List<Extension.Available>) {
        for (extension in extensions) {
            extensionManager.installExtension(extension)
        }
    }
}

package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resolves and installs the extensions required by a synced library.
 *
 * A synced library can reference source IDs whose extensions aren't installed on
 * this device yet (e.g. a fresh install). This finds the matching available
 * extensions and batch-installs them.
 */
class SyncExtensionResolver(
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val updateExtensionStores: UpdateExtensionStores = Injekt.get(),
    private val extensionStoreRepository: ExtensionStoreRepository = Injekt.get(),
) {

    /**
     * Finds available extensions matching any source IDs that are not currently installed.
     */
    suspend fun findMissingExtensions(sourceIds: Set<Long>): List<Extension.Available> {
        if (sourceIds.isEmpty()) return emptyList()

        val installedSourceIds = extensionManager.installedExtensionsFlow.value
            .flatMap { it.sources }
            .map { it.id }
            .toSet()

        val missingSourceIds = sourceIds - installedSourceIds
        if (missingSourceIds.isEmpty()) return emptyList()

        // Refresh extension stores and fetch the available catalog.
        runCatching { updateExtensionStores() }
        val available = runCatching {
            extensionStoreRepository.fetchExtensions()
        }.getOrDefault(emptyList())

        val matchedExtensions = mutableMapOf<String, Extension.Available>()
        for (sourceId in missingSourceIds) {
            val ext = available.find { extension ->
                extension.sources.any { it.id == sourceId }
            }
            if (ext != null) {
                matchedExtensions[ext.pkgName] = ext
            } else {
                logcat(LogPriority.WARN) {
                    "SyncExtensionResolver: no extension found for sourceId=$sourceId in available stores"
                }
            }
        }

        return matchedExtensions.values.toList()
    }

    /**
     * Triggers batch download and installation for the given list of available extensions.
     */
    fun installExtensions(extensions: List<Extension.Available>, onFinished: () -> Unit = {}) {
        extensionManager.scope.launch {
            val total = extensions.size
            val finishedCounter = AtomicInteger(0)
            for (extension in extensions) {
                launch {
                    try {
                        extensionManager.installExtension(extension)
                            .takeWhile { installStep ->
                                installStep != InstallStep.Installed && installStep != InstallStep.Error
                            }
                            .collect()
                    } finally {
                        val count = finishedCounter.incrementAndGet()
                        if (count >= total) {
                            withContext(Dispatchers.Main) {
                                onFinished()
                            }
                        }
                    }
                }
            }
        }
    }
}

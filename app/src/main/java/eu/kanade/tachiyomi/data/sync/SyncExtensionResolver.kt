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

        logcat(LogPriority.INFO) { "SyncExtensionResolver: checking missing extensions for sourceIds=$sourceIds" }

        val installedSourceIds = extensionManager.installedExtensionsFlow.value
            .flatMap { it.sources }
            .map { it.id }
            .toSet()

        val missingSourceIds = sourceIds - installedSourceIds
        logcat(LogPriority.INFO) {
            "SyncExtensionResolver: missingSourceIds=$missingSourceIds, installedSourceIds=$installedSourceIds"
        }
        if (missingSourceIds.isEmpty()) return emptyList()

        // 1. Refresh extension stores and fetch available catalog
        runCatching {
            updateExtensionStores()
        }
        val available = runCatching {
            extensionStoreRepository.fetchExtensions()
        }.getOrDefault(emptyList())

        logcat(LogPriority.INFO) { "SyncExtensionResolver: fetched ${available.size} available extensions from stores" }

        val matchedExtensions = mutableMapOf<String, Extension.Available>()
        for (sourceId in missingSourceIds) {
            val ext = available.find { extension ->
                extension.sources.any { it.id == sourceId }
            }
            if (ext != null) {
                logcat(LogPriority.INFO) {
                    "SyncExtensionResolver: matched sourceId=$sourceId to extension ${ext.name} (${ext.pkgName})"
                }
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
        logcat(LogPriority.INFO) {
            "SyncExtensionResolver: installing ${extensions.size} extensions: ${extensions.map { it.name }}"
        }
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
                        logcat(LogPriority.INFO) {
                            "SyncExtensionResolver: extension ${extension.name} finished ($count/$total)"
                        }
                        if (count >= total) {
                            logcat(LogPriority.INFO) {
                                "SyncExtensionResolver: all extensions completed, calling onFinished"
                            }
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

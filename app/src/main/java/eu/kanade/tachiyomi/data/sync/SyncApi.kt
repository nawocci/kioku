package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.sync.service.SyncPreferences
import kotlin.time.Duration.Companion.seconds

/**
 * Client for the self-hosted mihon-sync server. Auth is a static API key sent
 * as a Bearer token — same interceptor pattern as the self-hosted trackers
 * (Kavita et al.).
 */
class SyncApi(
    private val preferences: SyncPreferences,
    networkHelper: NetworkHelper,
    private val json: Json,
) {

    private val client: OkHttpClient = networkHelper.client.newBuilder()
        // Tighter than NetworkHelper defaults so an unreachable server fails fast
        // (e.g. the "Test connection" button) instead of hanging ~30s.
        .connectTimeout(8.seconds)
        .readTimeout(20.seconds)
        .writeTimeout(30.seconds)
        .addInterceptor { chain ->
            val requestBuilder = chain.request().newBuilder()
                .header("Authorization", "Bearer ${preferences.syncApiKey.get()}")
            val deviceId = preferences.syncDeviceId.get()
            if (deviceId.isNotBlank()) {
                requestBuilder.header("X-Device-ID", deviceId)
            }
            chain.proceed(requestBuilder.build())
        }
        .build()

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && preferences.syncApiKey.get().isNotBlank()

    private val baseUrl: String
        get() = preferences.syncServerUrl.get().trim().trimEnd('/')

    suspend fun authCheck(): Boolean = withIOContext {
        if (!isConfigured) return@withIOContext false
        with(json) {
            client.newCall(GET("$baseUrl/api/v1/auth/check"))
                .awaitSuccess()
                .parseAs<SyncAuthCheckResponse>()
                .ok
        }
    }

    suspend fun push(since: Long, changes: SyncChangeSetDto): SyncPushResponse = withIOContext {
        val body = json.encodeToString(
            SyncPushRequest.serializer(),
            SyncPushRequest(
                deviceId = preferences.syncDeviceId.get(),
                since = since,
                changes = changes,
            ),
        ).toRequestBody(jsonMime)

        with(json) {
            client.newCall(POST("$baseUrl/api/v1/sync/push", body = body))
                .awaitSuccess()
                .parseAs<SyncPushResponse>()
        }
    }

    suspend fun pull(since: Long): SyncPullResponse = withIOContext {
        with(json) {
            client.newCall(GET("$baseUrl/api/v1/sync/pull?since=$since"))
                .awaitSuccess()
                .parseAs<SyncPullResponse>()
        }
    }

    companion object {
        private val jsonMime = "application/json; charset=utf-8".toMediaType()
    }
}

package com.acme.scantotally

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.acme.scantotally.data.RelayApi
import com.acme.scantotally.data.Repository
import com.acme.scantotally.feedback.Feedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore("scan-to-tally")

/**
 * Device provisioning.
 *
 * Company and godown are set ONCE, when the handset is enrolled. They are
 * deliberately not a per-session picker: making an operator choose the same
 * company and godown thirty times a shift is three taps of pure friction, and
 * one of those taps will eventually be wrong.
 */
class DeviceConfig(private val context: Context) {

    private val keyRelayUrl = stringPreferencesKey("relay_url")
    private val keyToken = stringPreferencesKey("device_token")
    private val keyGodown = stringPreferencesKey("godown")
    private val keyOperator = stringPreferencesKey("operator")

    val relayUrl: Flow<String> = context.dataStore.data.map { it[keyRelayUrl] ?: "" }
    val token: Flow<String> = context.dataStore.data.map { it[keyToken] ?: "" }
    val godown: Flow<String> = context.dataStore.data.map { it[keyGodown] ?: "Main Store" }
    val operator: Flow<String> = context.dataStore.data.map { it[keyOperator] ?: "" }

    suspend fun isProvisioned(): Boolean =
        relayUrl.first().isNotBlank() && token.first().isNotBlank()

    suspend fun save(url: String, token: String, godown: String, operator: String) {
        context.dataStore.edit {
            it[keyRelayUrl] = url.trim().trimEnd('/')
            it[keyToken] = token.trim()
            it[keyGodown] = godown.trim()
            it[keyOperator] = operator.trim()
        }
    }
}

class ScanToTallyApp : Application() {

    lateinit var config: DeviceConfig
        private set
    lateinit var feedback: Feedback
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedRepo: Repository? = null

    @Volatile
    private var cachedToken: String = ""

    override fun onCreate() {
        super.onCreate()
        config = DeviceConfig(this)
        feedback = Feedback(this)
    }

    /**
     * Builds a repository around the current relay settings.
     *
     * The API half may be null when the device has not been provisioned yet.
     * Everything local still works in that state, which is deliberate: an
     * unprovisioned handset can still be handled and demonstrated.
     */
    suspend fun repository(): Repository {
        val url = config.relayUrl.first()
        val token = config.token.first()
        cachedRepo?.let { if (token == cachedToken) return it }

        val api = if (url.isNotBlank() && token.isNotBlank()) RelayApi(url, token) else null
        return Repository(this, api).also {
            cachedRepo = it
            cachedToken = token
        }
    }

    fun invalidateRepository() {
        cachedRepo = null
    }

    /** Pulls master data in the background; failure is silent by design. */
    fun syncInBackground() {
        scope.launch {
            val repo = repository()
            runCatching { repo.syncMasters() }
            // And push anything the dock scanned while it was out of range.
            runCatching { repo.drainOutbox() }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        feedback.release()
    }
}

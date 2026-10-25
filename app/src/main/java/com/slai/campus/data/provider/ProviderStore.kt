package com.slai.campus.data.provider

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.domain.provider.ApiProvider
import com.slai.campus.domain.provider.ProviderPurpose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.providerDataStore: DataStore<Preferences> by preferencesDataStore(name = "api_providers")

/**
 * Persists the API provider list.
 *
 * Stored as a JSON array in its own DataStore file so a provider edit can never corrupt the session
 * store, and so the whole list can be exported/imported by copy-paste.
 */
@Singleton
class ProviderStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject constructor(context: Context) : this(context.providerDataStore)

    private val key = stringPreferencesKey("providers_json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    val rawJson: Flow<String> = dataStore.data.map { it[key].orEmpty() }

    val providers: Flow<List<ApiProvider>> = rawJson.map { decode(it) }

    suspend fun all(): List<ApiProvider> = decode(dataStore.data.first()[key].orEmpty())

    suspend fun enabledFor(system: SchoolSystem, purpose: ProviderPurpose): List<ApiProvider> =
        all().filter { it.enabled && it.system == system.key && it.purpose == purpose.key }

    suspend fun upsert(provider: ApiProvider) {
        update { providers ->
            val current = providers.toMutableList()
            val index = current.indexOfFirst { it.id == provider.id }
            if (index >= 0) current[index] = provider else current += provider
            current
        }
    }

    suspend fun delete(id: String) {
        update { providers -> providers.filterNot { it.id == id } }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        update { providers -> providers.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    suspend fun replaceAll(providers: List<ApiProvider>) = update { providers }

    private suspend fun update(transform: (List<ApiProvider>) -> List<ApiProvider>) {
        // DataStore serializes edit transactions. Reading outside this transaction loses another
        // edit when two callers add, delete or enable different providers at the same time.
        dataStore.edit { prefs ->
            val providers = transform(decode(prefs[key].orEmpty()))
            prefs[key] = json.encodeToString(ListSerializer(ApiProvider.serializer()), providers)
        }
    }

    private fun decode(text: String): List<ApiProvider> {
        if (text.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ApiProvider.serializer()), text)
        }.getOrElse {
            AppLog.w("provider decode failed: ${it.javaClass.simpleName}")
            emptyList()
        }
    }
}

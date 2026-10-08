package com.locus.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.locus.core.domain.dashboard.DashboardSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dashboardDataStore: DataStore<Preferences> by preferencesDataStore(name = "dashboard_preferences")

@Singleton
open class DashboardSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.dashboardDataStore)

    private val intervalHoursKey = intPreferencesKey("dashboard_interval_hours")
    private val heavyJobsConstrainedKey = booleanPreferencesKey("dashboard_heavy_jobs_constrained")
    private val digestEnabledKey = booleanPreferencesKey("dashboard_digest_enabled")
    private val clustersEnabledKey = booleanPreferencesKey("dashboard_clusters_enabled")
    private val actionItemsEnabledKey = booleanPreferencesKey("dashboard_action_items_enabled")
    private val remindersEnabledKey = booleanPreferencesKey("dashboard_reminders_enabled")

    open val settingsFlow: Flow<DashboardSettings> =
        dataStore.data.map { prefs ->
            DashboardSettings(
                intervalHours = prefs[intervalHoursKey] ?: DEFAULT_INTERVAL_HOURS,
                isHeavyJobsConstrained = prefs[heavyJobsConstrainedKey] ?: true,
                isDigestEnabled = prefs[digestEnabledKey] ?: true,
                isClustersEnabled = prefs[clustersEnabledKey] ?: true,
                isActionItemsEnabled = prefs[actionItemsEnabledKey] ?: true,
                isRemindersEnabled = prefs[remindersEnabledKey] ?: true,
            )
        }

    suspend fun setIntervalHours(hours: Int) {
        dataStore.edit { prefs -> prefs[intervalHoursKey] = hours }
    }

    suspend fun setHeavyJobsConstrained(constrained: Boolean) {
        dataStore.edit { prefs -> prefs[heavyJobsConstrainedKey] = constrained }
    }

    suspend fun setCardEnabled(
        cardType: String,
        enabled: Boolean,
    ) {
        dataStore.edit { prefs ->
            when (cardType.lowercase()) {
                "digest" -> prefs[digestEnabledKey] = enabled
                "clusters", "cluster" -> prefs[clustersEnabledKey] = enabled
                "action_items", "actions" -> prefs[actionItemsEnabledKey] = enabled
                "reminders", "reminder" -> prefs[remindersEnabledKey] = enabled
            }
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 24
    }
}

package com.ace77505.dex2oat.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ace77505.dex2oat.model.OutputLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private val locationKey = stringPreferencesKey("output_location")
    private val safUriKey = stringPreferencesKey("output_saf_uri")
    private val lastPackageKey = stringPreferencesKey("last_package_name")

    val outputLocation: Flow<OutputLocation> = context.dataStore.data.map { prefs ->
        val location = prefs[locationKey] ?: OutputLocation.LOCATION_APP
        if (location == OutputLocation.LOCATION_SAF) {
            val uri = prefs[safUriKey]
            if (uri.isNullOrBlank()) {
                OutputLocation.AppPrivate
            } else {
                OutputLocation.Saf(Uri.parse(uri))
            }
        } else {
            OutputLocation.AppPrivate
        }
    }

    val lastPackageName: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[lastPackageKey]
    }

    suspend fun setOutputLocation(location: OutputLocation) {
        context.dataStore.edit { prefs ->
            when (location) {
                OutputLocation.AppPrivate -> {
                    prefs[locationKey] = OutputLocation.LOCATION_APP
                }
                is OutputLocation.Saf -> {
                    prefs[locationKey] = OutputLocation.LOCATION_SAF
                    prefs[safUriKey] = location.uri.toString()
                }
            }
        }
    }

    suspend fun setSafUri(uri: Uri) {
        context.dataStore.edit { prefs ->
            prefs[locationKey] = OutputLocation.LOCATION_SAF
            prefs[safUriKey] = uri.toString()
        }
    }

    suspend fun setLastPackageName(packageName: String) {
        context.dataStore.edit { prefs ->
            prefs[lastPackageKey] = packageName
        }
    }
}

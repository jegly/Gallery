package com.gal.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

internal val Context.galDataStore: DataStore<Preferences> by preferencesDataStore(name = "gal_settings")

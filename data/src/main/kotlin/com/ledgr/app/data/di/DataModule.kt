package com.ledgr.app.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.ledgr.app.data.db.AccountDao
import com.ledgr.app.data.db.CategoryDao
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.data.db.LedgrDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("settings") })

    @Provides
    fun database(manager: DatabaseManager): LedgrDatabase = manager.requireDatabase()

    @Provides
    fun categoryDao(db: LedgrDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun accountDao(db: LedgrDatabase): AccountDao = db.accountDao()
}

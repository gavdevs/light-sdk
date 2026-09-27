package com.gav.lightvidious

import com.gav.lightvidious.data.AccountStore
import com.gav.lightvidious.data.HistoryDatabase
import com.gav.lightvidious.data.HistoryRepository
import com.gav.lightvidious.data.HistorySyncer
import com.gav.lightvidious.data.SettingsStore
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase

class LightvidiousServices private constructor(
    val settings: SettingsStore,
    val accounts: AccountStore,
    val history: HistoryRepository,
    val historySyncer: HistorySyncer,
) {
    companion object {
        @Volatile
        private var instance: LightvidiousServices? = null

        fun from(context: SealedLightContext): LightvidiousServices =
            instance ?: synchronized(this) {
                instance ?: run {
                    val history = HistoryRepository(
                        context.buildDatabase(HistoryDatabase::class.java, HistoryDatabase.NAME)
                            .historyDao(),
                    )
                    LightvidiousServices(
                        settings = SettingsStore(context.dataStore),
                        accounts = AccountStore(context.dataStore),
                        history = history,
                        historySyncer = HistorySyncer(history),
                    ).also { instance = it }
                }
            }
    }
}

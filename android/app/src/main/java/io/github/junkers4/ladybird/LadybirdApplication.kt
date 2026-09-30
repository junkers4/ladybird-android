package io.github.junkers4.ladybird

import android.app.Application
import io.github.junkers4.ladybird.core.settings.SettingsRepository
import io.github.junkers4.ladybird.engine.Engine
import io.github.junkers4.ladybird.settings.SharedPreferencesStorage

class LadybirdApplication : Application() {
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(SharedPreferencesStorage(this))
        Engine.start(this, settings.load())
    }
}

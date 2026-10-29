package de.amazonmonitor

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/** Setzt beim Start der App die gewählte Darstellung (Hell / Dunkel / wie das Handy). */
class MrFoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Store.nightMode(this))
    }
}

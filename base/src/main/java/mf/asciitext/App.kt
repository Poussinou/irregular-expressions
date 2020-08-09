package mf.asciitext

import android.app.Application
import mf.asciitext.fonts.AvailableFonts

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        /* Initialize fonts */
        AvailableFonts.init(applicationContext)
    }
}

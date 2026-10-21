package dev.speedcompiler.tv

import android.app.Application

class SpeedApp : Application() {
    val graph by lazy { AppGraph(this) }
}

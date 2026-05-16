package com.nzeus.nvg

import android.app.Application
import android.util.Log

class NvgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("NZEUS", "OPTIC-2 boot")
    }
}

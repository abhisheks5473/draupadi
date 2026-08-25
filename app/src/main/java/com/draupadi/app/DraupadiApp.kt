package com.draupadi.app

import android.app.Application
import com.draupadi.app.net.Cloud

class DraupadiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Cloud.init(this)
    }
}

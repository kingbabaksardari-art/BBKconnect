package com.vpnbridge.client

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LogBuffer.init()
    }
}

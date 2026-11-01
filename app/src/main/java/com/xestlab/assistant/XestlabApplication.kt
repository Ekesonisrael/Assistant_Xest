package com.xestlab.assistant

import android.app.Application
import com.xestlab.assistant.service.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class XestlabApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Re-arm every active reminder each time the app process starts
        CoroutineScope(Dispatchers.IO).launch {
            AlarmScheduler.scheduleAllActive(applicationContext)
        }
    }
}

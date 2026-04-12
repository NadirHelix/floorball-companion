package de.floorballcompanion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import de.floorballcompanion.worker.LiveScoreWorker
import javax.inject.Inject

@HiltAndroidApp
class FloorballApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    companion object {
        const val CHANNEL_LIVE = "live_scores"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        LiveScoreWorker.enqueuePeriodicWork(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            CHANNEL_LIVE,
            "Live-Ergebnisse",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Benachrichtigungen bei Toren deiner favorisierten Teams"
            enableVibration(true)
        }
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(channel)
    }
}
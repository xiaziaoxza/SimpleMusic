package com.simplemusic.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.simplemusic.app.player.PlaybackService

class SimpleMusicApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            PlaybackService.NOTIFICATION_CHANNEL_ID,
            "音乐播放",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "音乐播放控制通知"
            setSound(null, null)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        lateinit var instance: SimpleMusicApp
            private set
    }
}

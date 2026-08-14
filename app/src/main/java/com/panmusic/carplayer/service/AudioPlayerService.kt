package com.panmusic.carplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ServiceLifecycleDispatcher
import com.panmusic.carplayer.R
import com.panmusic.carplayer.model.MediaCommand
import com.panmusic.carplayer.repository.PlaybackRepository
import com.panmusic.carplayer.ui.PlayerActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 前台音乐播放服务 — 只负责前台 Service 生命周期 + 通知 + MediaSession
 *
 * 焦点管理、按键处理已拆分为独立的 AudioFocusManager / MediaKeyDispatcher / PlaybackController
 */
@AndroidEntryPoint
class AudioPlayerService : LifecycleService() {

    @Inject lateinit var mediaKeyDispatcher: MediaKeyDispatcher
    @Inject lateinit var playbackRepository: PlaybackRepository
    @Inject lateinit var playbackController: PlaybackController

    private val dispatcher = ServiceLifecycleDispatcher(this)

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "pan_music_playback"
        const val CHANNEL_NAME = "音乐播放"
    }

    // MediaSession 回调 → 统一走 MediaKeyDispatcher
    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = mediaKeyDispatcher.emit(MediaCommand.Play)
        override fun onPause() = mediaKeyDispatcher.emit(MediaCommand.Pause)
        override fun onSkipToNext() = mediaKeyDispatcher.emit(MediaCommand.Next)
        override fun onSkipToPrevious() = mediaKeyDispatcher.emit(MediaCommand.Previous)
    }

    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        dispatcher.onServicePreSuperOnCreate()
        createNotificationChannel()
        createMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        dispatcher.onServicePreSuperOnStart()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        mediaSession?.release()
        playbackRepository.release()
        dispatcher.onServicePreSuperOnDestroy()
        super.onDestroy()
    }

    // ========== 通知 ==========

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "音乐播放通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val song = playbackRepository.currentSong.value
        val title = song?.serverFilename ?: getString(R.string.player_unknown_song)

        val openIntent = Intent(this, PlayerActivity::class.java).let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string.app_name))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_media_previous,
                getString(R.string.notification_previous),
                createMediaActionIntent("previous")
            )
            .addAction(
                android.R.drawable.ic_media_play,
                getString(R.string.notification_play),
                createMediaActionIntent("play")
            )
            .addAction(
                android.R.drawable.ic_media_next,
                getString(R.string.notification_next),
                createMediaActionIntent("next")
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun createMediaActionIntent(action: String): PendingIntent {
        val intent = Intent(this, AudioPlayerService::class.java).apply {
            putExtra("media_action", action)
        }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ========== MediaSession ==========

    private fun createMediaSession() {
        mediaSession = MediaSessionCompat(this, "PanMusicCarPlayer").apply {
            setCallback(mediaSessionCallback)
            isActive = true
        }
    }
}

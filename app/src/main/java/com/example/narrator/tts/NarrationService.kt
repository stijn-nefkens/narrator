package com.example.narrator.tts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.narrator.MainActivity
import com.example.narrator.NarratorApp
import com.example.narrator.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NarrationService : Service() {

    private val narrator get() = (application as NarratorApp).container.narrator
    private lateinit var mediaSession: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectorJob: Job? = null
    private var startedForeground = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "NarrationSession").apply {
            setCallback(MediaCallback())
            isActive = true
        }
        collectorJob = scope.launch {
            narrator.state.collect(::onStateChanged)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> narrator.togglePlayPause()
            ACTION_SKIP_PREV_STEP -> narrator.skipStepPrev()
            ACTION_SKIP_NEXT_STEP -> narrator.skipStepNext()
        }
        onStateChanged(narrator.state.value)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        collectorJob?.cancel()
        scope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    // --- state sync ------------------------------------------------------

    private fun onStateChanged(state: NarratorState) {
        val loaded = state.loaded
        if (loaded == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            startedForeground = false
            stopSelf()
            return
        }
        mediaSession.setMetadata(buildMetadata(loaded))
        mediaSession.setPlaybackState(buildPlaybackState(state))

        val notification = buildNotification(state)
        if (state.isPlaying) {
            startForeground(NOTIFICATION_ID, notification)
            startedForeground = true
        } else {
            if (startedForeground) {
                stopForeground(STOP_FOREGROUND_DETACH)
                startedForeground = false
            }
            // POST_NOTIFICATIONS is runtime-requested in MainActivity. If the user denied
            // it (or never opened the app on Android 13+), notify() throws SecurityException.
            // Silently drop in that case — the foreground service path above doesn't need
            // permission, so playback still works without a notification.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildMetadata(loaded: LoadedBook): MediaMetadataCompat {
        val cover = loaded.coverPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        val chapterTitle = loaded.chapterTitles.firstOrNull().orEmpty()
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, loaded.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, loaded.author)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, chapterTitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, loaded.totalChunks.toLong())
        if (cover != null) builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
        return builder.build()
    }

    private fun buildPlaybackState(state: NarratorState): PlaybackStateCompat = PlaybackStateCompat.Builder()
        .setActions(
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO,
        )
        .setState(
            if (state.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
            state.position.globalChunk.toLong(),
            state.speed,
        )
        .build()

    private fun buildNotification(state: NarratorState): android.app.Notification {
        val loaded = state.loaded!!
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cover = loaded.coverPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(loaded.title)
            .setContentText(loaded.author)
            .setSmallIcon(R.drawable.ic_player)
            .setLargeIcon(cover)
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_step_prev,
                getString(R.string.player_prev_step),
                actionIntent(ACTION_SKIP_PREV_STEP),
            )
            .addAction(
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                actionIntent(ACTION_PLAY_PAUSE),
            )
            .addAction(
                R.drawable.ic_step_next,
                getString(R.string.player_next_step),
                actionIntent(ACTION_SKIP_NEXT_STEP),
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .build()
    }

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, NarrationService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_playback),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private inner class MediaCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (!narrator.state.value.isPlaying) narrator.togglePlayPause()
        }
        override fun onPause() {
            if (narrator.state.value.isPlaying) narrator.togglePlayPause()
        }
        override fun onSkipToNext() { narrator.skipStepNext() }
        override fun onSkipToPrevious() { narrator.skipStepPrev() }
        override fun onSeekTo(pos: Long) { narrator.seekToGlobalChunk(pos.toInt()) }
    }

    companion object {
        const val CHANNEL_ID = "narration_playback"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "com.example.narrator.PLAY_PAUSE"
        const val ACTION_SKIP_PREV_STEP = "com.example.narrator.SKIP_PREV_STEP"
        const val ACTION_SKIP_NEXT_STEP = "com.example.narrator.SKIP_NEXT_STEP"

        fun start(context: Context) {
            val intent = Intent(context, NarrationService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}

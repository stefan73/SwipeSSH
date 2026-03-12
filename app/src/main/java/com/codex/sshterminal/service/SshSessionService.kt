package com.codex.sshterminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.codex.sshterminal.AppLaunchActions
import com.codex.sshterminal.MainActivity
import com.codex.sshterminal.R
import com.codex.sshterminal.session.SessionRegistryState
import com.codex.sshterminal.session.SessionRepository
import com.codex.sshterminal.session.SessionSlotUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps the process alive for open SSH sessions and mirrors their state into a foreground notification. */
class SshSessionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binder = LocalBinder()
    private lateinit var sessionRepository: SessionRepository
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        sessionRepository = SessionRepository.getInstance(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(sessionRepository.snapshot.value))
        notificationJob = serviceScope.launch {
            sessionRepository.snapshot.collectLatest { state ->
                updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification(sessionRepository.snapshot.value)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        notificationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(state: SessionRegistryState) {
        if (!state.hasActiveSessions) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: SessionRegistryState): Notification {
        val activeSlot = state.activeSlot
        val activeCount = state.slots.count { it.isActive }
        val title = activeSlot?.session?.terminalTitle?.ifBlank { null }
            ?: if (activeCount > 1) "$activeCount active ${getString(R.string.app_name)} sessions" else "${getString(R.string.app_name)} session active"
        val text = buildSummaryText(state)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_terminal)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(buildOpenSlotIntent(activeSlot?.slotId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                state.slots.forEach { slot ->
                    addAction(
                        0,
                        if (slot.isActive) slot.slotId.toString() else "+",
                        if (slot.isActive) buildOpenSlotIntent(slot.slotId) else buildOpenConnectionScreenIntent(slot.slotId),
                    )
                }
            }
            .build()
    }

    private fun buildSummaryText(state: SessionRegistryState): String {
        return state.slots.joinToString(separator = "   ") { slot ->
            if (slot.isActive) {
                "${slot.slotId}: ${slot.session.terminalTitle}"
            } else {
                "${slot.slotId}: +"
            }
        }
    }

    private fun buildOpenSlotIntent(slotId: Int?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            slotId?.let { putExtra(AppLaunchActions.EXTRA_OPEN_SLOT_ID, it) }
        }
        return PendingIntent.getActivity(
            this,
            100 + (slotId ?: 0),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildOpenConnectionScreenIntent(slotId: Int): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppLaunchActions.EXTRA_OPEN_CONNECTION_SCREEN, true)
            putExtra(AppLaunchActions.EXTRA_PREFERRED_SLOT_ID, slotId)
        }
        return PendingIntent.getActivity(
            this,
            200 + slotId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "${getString(R.string.app_name)} sessions",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows active ${getString(R.string.app_name)} terminal sessions"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): SshSessionService = this@SshSessionService
    }

    companion object {
        private const val CHANNEL_ID = "ssh_terminal_sessions"
        private const val NOTIFICATION_ID = 1001

        /** Starts the foreground service before a connection begins so Android keeps the process alive. */
        fun ensureStarted(context: Context) {
            val intent = Intent(context, SshSessionService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}



package com.oberoi.tvblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper

/**
 * Always-on foreground service. Polls the dashboard, fires the 5 / 2 minute
 * warnings, and slams the lock screen back up the moment time runs out.
 */
class BlockerService : Service() {

    companion object {
        const val CHANNEL = "tvblocker"
        const val NOTIF_ID = 42
        const val POLL_MS = 7000L
        const val TICK_MS = 1000L

        fun start(ctx: Context) {
            val i = Intent(ctx, BlockerService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (e: Exception) {
                // Ignore: cannot start from background on some builds.
            }
        }
    }

    private lateinit var netThread: HandlerThread
    private lateinit var netHandler: Handler
    private val ui = Handler(Looper.getMainLooper())

    private var warned5 = false
    private var warned2 = false
    private var lastDeadline = 0L
    private var wasUnlocked = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        State.unlockUntilWall = Prefs.unlockUntilWall
        State.disabled = Prefs.disabled
        State.homePackage = Prefs.homePackage

        startForegroundSafe()

        netThread = HandlerThread("net").also { it.start() }
        netHandler = Handler(netThread.looper)
        netHandler.post(pollTask)
        ui.post(tickTask)

        WatchdogReceiver.schedule(this)
    }

    private fun startForegroundSafe() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "TV Blocker", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, LockActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else
            @Suppress("DEPRECATION") Notification.Builder(this)

        val n = b.setContentTitle("TV Blocker active")
            .setContentText("Protecting this TV")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // Some TV builds have no notification UI; the service still runs.
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            try {
                val r = Api.sync(
                    Prefs.serverUrl,
                    Prefs.deviceId,
                    Prefs.deviceName,
                    Prefs.enrollKey,
                    Launchers.json(this@BlockerService)
                )
                if (r != null) {
                    if (r.homePackage != State.homePackage) {
                        State.homePackage = r.homePackage
                        Prefs.homePackage = r.homePackage
                    }
                    if (r.disabled != State.disabled) {
                        State.disabled = r.disabled
                        Prefs.disabled = r.disabled
                    }
                    if (r.unlockSeconds > 0) {
                        val newDeadline = System.currentTimeMillis() + r.unlockSeconds * 1000L
                        // Only move the deadline if the server genuinely changed it.
                        if (Math.abs(newDeadline - State.unlockUntilWall) > 4000L) {
                            State.unlockUntilWall = newDeadline
                            Prefs.unlockUntilWall = newDeadline
                        }
                    } else if (State.unlockUntilWall != 0L && r.unlockSeconds == 0L) {
                        // Server says lock now.
                        State.unlockUntilWall = 0L
                        Prefs.unlockUntilWall = 0L
                    }
                }
            } catch (e: Exception) {
                // Network down: keep the last known state, expiry still applies.
            }
            netHandler.postDelayed(this, POLL_MS)
        }
    }

    private val tickTask = object : Runnable {
        override fun run() {
            val deadline = State.unlockUntilWall
            if (deadline != lastDeadline) {
                lastDeadline = deadline
                warned5 = false
                warned2 = false
            }

            val unlocked = State.isUnlocked()
            val remaining = State.remainingMs()

            if (unlocked && !State.disabled) {
                if (!warned5 && remaining in 1..(5 * 60 * 1000L)) {
                    warned5 = true
                    ReminderOverlay.show(this@BlockerService, "TV locks in 5 minutes")
                }
                if (!warned2 && remaining in 1..(2 * 60 * 1000L)) {
                    warned2 = true
                    ReminderOverlay.show(this@BlockerService, "TV locks in 2 minutes")
                }
            }

            // The moment time expires, take the screen back.
            if (wasUnlocked && !unlocked) {
                LockActivity.bringUp(this@BlockerService)
            }
            wasUnlocked = unlocked

            ui.postDelayed(this, TICK_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        try { netThread.quitSafely() } catch (e: Exception) { }
        // Ask to be restarted.
        WatchdogReceiver.schedule(this)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        start(this)
    }
}

package com.oberoi.tvblocker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/** Repeating alarm that resurrects the service if the system kills it. */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.oberoi.tvblocker.WATCHDOG"

        fun schedule(ctx: Context) {
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val i = Intent(ctx, WatchdogReceiver::class.java).setAction(ACTION)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
                val pi = PendingIntent.getBroadcast(ctx, 7, i, flags)
                am.setRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 60_000L,
                    5 * 60_000L,
                    pi
                )
            } catch (e: Exception) { }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Prefs.init(context)
        BlockerService.start(context)
    }
}

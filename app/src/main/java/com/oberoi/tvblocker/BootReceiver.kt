package com.oberoi.tvblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts everything back up when the TV powers on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Prefs.init(context)
        State.load()
        // The TV boots into its own home screen; the service puts the locked
        // banner up if it needs to.
        BlockerService.start(context)
    }
}

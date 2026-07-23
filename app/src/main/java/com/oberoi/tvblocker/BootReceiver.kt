package com.oberoi.tvblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts everything back up when the TV powers on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Prefs.init(context)
        State.unlockUntilWall = Prefs.unlockUntilWall
        State.disabled = Prefs.disabled
        State.homePackage = Prefs.homePackage
        BlockerService.start(context)
        if (!State.isUnlocked()) {
            LockActivity.bringUp(context)
        }
    }
}

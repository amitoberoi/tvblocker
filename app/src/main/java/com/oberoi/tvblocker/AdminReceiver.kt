package com.oberoi.tvblocker

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Being an active device admin means Android refuses to uninstall the app
 * until the admin is deactivated. No ADB or factory reset needed to enable it.
 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        BlockerService.start(context)
    }
}

package com.oberoi.tvblocker

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Watches which app comes to the foreground. When the TV is locked, anything
 * that is not on the allow list gets bounced straight back to the lock screen.
 */
class BlockerAccessibilityService : AccessibilityService() {

    private val alwaysAllowed = setOf(
        "com.android.systemui",
        "android",
        "com.google.android.inputmethod.latin",
        "com.android.inputmethod.latin"
    )

    private val settingsPackages = setOf(
        "com.android.tv.settings",
        "com.android.settings",
        "com.google.android.tvsettings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.settings.intelligence"
    )

    private var lastBounce = 0L
    private var launcherCache: Set<String> = emptySet()
    private var launcherCacheAt = 0L

    private fun launcherPackages(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - launcherCacheAt > 60_000L || launcherCache.isEmpty()) {
            launcherCache = Launchers.packages(this)
            launcherCacheAt = now
        }
        return launcherCache
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        BlockerService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        Prefs.init(this)

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg in alwaysAllowed) return

        // Launcher routing is enforced even while the blocker is switched off,
        // so only the launcher picked on the dashboard can ever be reached.
        val home = State.homePackage
        if (home.isNotEmpty() && pkg != home && pkg in launcherPackages()) {
            bounce()
            return
        }

        if (State.disabled) return

        // Settings is guarded even during an unlock window, otherwise the app
        // could simply be switched off during allowed time.
        if (pkg in settingsPackages && !State.settingsAllowed()) {
            bounce()
            return
        }

        if (!State.isUnlocked() && pkg !in settingsPackages) {
            bounce()
        }
    }

    private fun bounce() {
        val now = System.currentTimeMillis()
        if (now - lastBounce < 400L) return
        lastBounce = now
        LockActivity.bringUp(this)
    }

    override fun onInterrupt() { }
}

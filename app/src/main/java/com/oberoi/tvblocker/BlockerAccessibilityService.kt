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
        State.load()
        BlockerService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        Prefs.init(this)

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg in alwaysAllowed) return

        // The dashboard asked for the full-screen blocker: keep it on top.
        if (State.showLock) {
            LockActivity.bringUp(this, true)
            return
        }

        // Home screens: only the one chosen on the dashboard may run.
        val home = State.homePackage
        val launchers = launcherPackages()
        if (pkg in launchers) {
            if (home.isEmpty() || pkg == home) return
            Launchers.goHome(this)
            return
        }

        if (State.disabled) return

        // Settings handling. The parent PIN grants a 5-minute window
        // (settingsAllowed) that always lets Settings through.
        if (pkg in settingsPackages) {
            if (State.settingsAllowed()) return
            // Otherwise Settings is refused whenever it is blocked for this TV,
            // OR whenever the TV is locked. With block_settings on (the
            // default) an allowed window does not open Settings.
            if (State.blockSettings || !State.isUnlocked()) {
                refuse()
            }
            return
        }

        if (!State.isUnlocked()) {
            refuse()
        }
    }

    /**
     * Refuse an app while the TV is locked: say why, then return the TV to its
     * own home screen. The blocker screen itself is never shown here.
     */
    private fun refuse() {
        val now = System.currentTimeMillis()
        if (now - lastBounce < 800L) return
        lastBounce = now
        OverlayManager.flash(this, State.blockedText)
        Launchers.goHome(this)
    }

    override fun onInterrupt() { }
}

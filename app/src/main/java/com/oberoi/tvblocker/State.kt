package com.oberoi.tvblocker

/**
 * Shared in-memory state. The service, the accessibility guard and the parent
 * screen all live in the same process, so a plain object is enough.
 */
object State {
    /** Wall-clock millis until which apps are allowed. 0 = locked. */
    @Volatile var unlockUntilWall: Long = 0L

    /** Parent entered the PIN, so system Settings may be opened until this time. */
    @Volatile var settingsAllowedUntil: Long = 0L

    /** Master off switch. */
    @Volatile var disabled: Boolean = false

    /** Which launcher the TV should use. Other launchers are blocked. */
    @Volatile var homePackage: String = ""

    /** Wording shown on this TV, set per TV from the dashboard. */
    @Volatile var bannerText: String = "TV is Locked"
    @Volatile var blockedText: String = "TV is Locked. Please ask a parent to unlock it."

    /** Dashboard asked for the full-screen blocker. */
    @Volatile var showLock: Boolean = false

    /** Block system Settings even during an allowed window. On by default. */
    @Volatile var blockSettings: Boolean = true

    /**
     * Minutes the TV may go without reaching the server before it frees
     * itself. 0 means stay locked no matter what. This exists so a family is
     * never trapped by a server problem they cannot fix.
     */
    @Volatile var failOpenMinutes: Int = 30

    /** True while the TV has released itself because the server is silent. */
    @Volatile var failOpenActive: Boolean = false

    /** The app currently in the foreground, reported to the dashboard. */
    @Volatile var currentPkg: String = ""
    @Volatile var currentLabel: String = ""

    /** Parent cleared it; tell the server on the next poll. */
    @Volatile var ackShowLock: Boolean = false

    @Volatile var lastSyncOk: Long = 0L
    @Volatile var lastSyncError: String = ""

    fun isUnlocked(): Boolean =
        disabled || failOpenActive || System.currentTimeMillis() < unlockUntilWall

    fun remainingMs(): Long {
        val r = unlockUntilWall - System.currentTimeMillis()
        return if (r > 0) r else 0L
    }

    fun settingsAllowed(): Boolean =
        disabled || failOpenActive ||
            System.currentTimeMillis() < settingsAllowedUntil

    /** Restore everything that survives a reboot. */
    fun load() {
        unlockUntilWall = Prefs.unlockUntilWall
        disabled = Prefs.disabled
        homePackage = Prefs.homePackage
        showLock = Prefs.showLock
        blockSettings = Prefs.blockSettings
        failOpenMinutes = Prefs.failOpenMinutes
        if (Prefs.bannerText.isNotEmpty()) bannerText = Prefs.bannerText
        if (Prefs.blockedText.isNotEmpty()) blockedText = Prefs.blockedText
    }
}

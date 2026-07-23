package com.oberoi.tvblocker

/**
 * Shared in-memory state. The service, the accessibility guard and the lock
 * screen all live in the same process, so a plain object is enough.
 */
object State {
    /** Wall-clock millis until which apps are allowed. 0 = locked. */
    @Volatile var unlockUntilWall: Long = 0L

    /** Parent entered the PIN, so system Settings may be opened until this time. */
    @Volatile var settingsAllowedUntil: Long = 0L

    /** Master off switch, set locally with the PIN. */
    @Volatile var disabled: Boolean = false

    /** Which launcher the TV should use. Other launchers are blocked. */
    @Volatile var homePackage: String = ""

    @Volatile var lastSyncOk: Long = 0L
    @Volatile var lastSyncError: String = ""

    fun isUnlocked(): Boolean = disabled || System.currentTimeMillis() < unlockUntilWall

    fun remainingMs(): Long {
        val r = unlockUntilWall - System.currentTimeMillis()
        return if (r > 0) r else 0L
    }

    fun settingsAllowed(): Boolean =
        disabled || System.currentTimeMillis() < settingsAllowedUntil
}

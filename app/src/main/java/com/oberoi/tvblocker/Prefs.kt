package com.oberoi.tvblocker

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.UUID

object Prefs {
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) {
            sp = ctx.applicationContext.getSharedPreferences("tvblocker", Context.MODE_PRIVATE)
        }
    }

    private fun p(): SharedPreferences = sp!!

    var serverUrl: String
        get() = p().getString("server_url", "") ?: ""
        set(v) = p().edit().putString("server_url", v.trimEnd('/')).apply()

    var enrollKey: String
        get() = p().getString("enroll_key", "") ?: ""
        set(v) = p().edit().putString("enroll_key", v.trim()).apply()

    var deviceName: String
        get() = p().getString("device_name", "Living Room TV") ?: "Living Room TV"
        set(v) = p().edit().putString("device_name", v).apply()

    val deviceId: String
        get() {
            var id = p().getString("device_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString().substring(0, 12)
                p().edit().putString("device_id", id).apply()
            }
            return id
        }

    var setupDone: Boolean
        get() = p().getBoolean("setup_done", false)
        set(v) = p().edit().putBoolean("setup_done", v).apply()

    /** Wall-clock millis until which the TV stays unlocked. Survives reboot. */
    var unlockUntilWall: Long
        get() = p().getLong("unlock_until_wall", 0L)
        set(v) = p().edit().putLong("unlock_until_wall", v).apply()

    var disabled: Boolean
        get() = p().getBoolean("disabled", false)
        set(v) = p().edit().putBoolean("disabled", v).apply()

    /** Launcher package chosen on the dashboard. */
    var homePackage: String
        get() = p().getString("home_package", "") ?: ""
        set(v) = p().edit().putString("home_package", v).apply()

    var bannerText: String
        get() = p().getString("banner_text", "") ?: ""
        set(v) = p().edit().putString("banner_text", v).apply()

    var blockedText: String
        get() = p().getString("blocked_text", "") ?: ""
        set(v) = p().edit().putString("blocked_text", v).apply()

    var showLock: Boolean
        get() = p().getBoolean("show_lock", false)
        set(v) = p().edit().putBoolean("show_lock", v).apply()

    fun setPin(pin: String) {
        p().edit().putString("pin_hash", sha256(pin)).apply()
    }

    fun hasPin(): Boolean = !p().getString("pin_hash", null).isNullOrEmpty()

    fun checkPin(pin: String): Boolean {
        val stored = p().getString("pin_hash", null) ?: return false
        return stored == sha256(pin)
    }

    private fun sha256(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        val sb = StringBuilder()
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}

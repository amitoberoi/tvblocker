package com.oberoi.tvblocker

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object Api {

    data class SyncResult(
        val unlockSeconds: Long,   // seconds of unlock remaining, 0 = locked
        val disabled: Boolean,
        val homePackage: String,
        val bannerText: String,
        val blockedText: String,
        val showLock: Boolean,
        val message: String?
    )

    /**
     * Single round trip: reports this TV to the dashboard and returns how much
     * unlock time is left. Time is returned as a *duration*, not a timestamp,
     * so the TV clock being wrong never matters.
     */
    fun sync(
        base: String,
        deviceId: String,
        name: String,
        key: String,
        launchersJson: String = "[]",
        ackShowLock: Boolean = false
    ): SyncResult? {
        if (base.isEmpty()) return null
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$base/api/v1/sync")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")

            val payload = JSONObject()
                .put("device_id", deviceId)
                .put("name", name)
                .put("key", key)
            try {
                payload.put("launchers", JSONArray(launchersJson))
            } catch (e: Exception) {
                payload.put("launchers", JSONArray())
            }
            payload.put("ack_show_lock", ackShowLock)
            val body = payload.toString()

            val os: OutputStream = conn.outputStream
            os.write(body.toByteArray())
            os.flush()
            os.close()

            if (conn.responseCode != 200) {
                State.lastSyncError = "HTTP ${conn.responseCode}"
                return null
            }

            val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val j = JSONObject(text)
            State.lastSyncError = ""
            State.lastSyncOk = System.currentTimeMillis()
            return SyncResult(
                unlockSeconds = j.optLong("unlock_seconds", 0L),
                disabled = j.optBoolean("disabled", false),
                homePackage = j.optString("home_package", ""),
                bannerText = j.optString("banner_text", "TV is Locked"),
                blockedText = j.optString(
                    "blocked_text", "TV is Locked. Please ask a parent to unlock it."
                ),
                showLock = j.optBoolean("show_lock", false),
                message = if (j.isNull("message")) null else j.optString("message", null)
            )
        } catch (e: Exception) {
            State.lastSyncError = e.javaClass.simpleName
            return null
        } finally {
            conn?.disconnect()
        }
    }
}

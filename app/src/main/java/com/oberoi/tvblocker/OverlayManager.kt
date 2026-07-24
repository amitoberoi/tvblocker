package com.oberoi.tvblocker

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * All on-screen messaging. The TV shows its own home screen at all times, so
 * everything this app needs to say is drawn as an overlay on top of it:
 *  - a persistent red banner for as long as the TV is locked
 *  - a short centred message when a blocked app is opened
 *  - the 5 and 2 minute warnings
 */
object OverlayManager {

    private val ui = Handler(Looper.getMainLooper())
    private var banner: TextView? = null
    private var bannerText: String = ""
    private var lastFlash = 0L

    private fun canOverlay(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(ctx) else true

    private fun wm(ctx: Context): WindowManager =
        ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

    private fun makeView(ctx: Context, text: String, size: Float): TextView {
        val tv = TextView(ctx.applicationContext)
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.textSize = size
        tv.setPadding(48, 26, 48, 26)
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F0C0392B"))
        bg.cornerRadius = 24f
        tv.background = bg
        return tv
    }

    private fun params(gravity: Int, x: Int, y: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = gravity
        lp.x = x
        lp.y = y
        return lp
    }

    /** Stays on screen for the whole time the TV is locked. */
    fun showBanner(ctx: Context, text: String) {
        ui.post {
            if (!canOverlay(ctx)) return@post
            try {
                val existing = banner
                if (existing != null) {
                    if (text != bannerText) {
                        existing.text = text
                        bannerText = text
                    }
                    return@post
                }
                val tv = makeView(ctx, text, 22f)
                wm(ctx).addView(tv, params(Gravity.TOP or Gravity.END, 60, 60))
                banner = tv
                bannerText = text
            } catch (e: Exception) {
                banner = null
            }
        }
    }

    fun hideBanner(ctx: Context) {
        ui.post {
            val v = banner
            banner = null
            bannerText = ""
            if (v != null) {
                try { wm(ctx).removeView(v) } catch (e: Exception) { }
            }
        }
    }

    /** Short centred message. Rate limited so key mashing cannot stack them. */
    fun flash(ctx: Context, text: String, millis: Long = 5000L) {
        val now = System.currentTimeMillis()
        if (now - lastFlash < 1500L) return
        lastFlash = now
        ui.post {
            if (!canOverlay(ctx)) {
                try {
                    Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_LONG).show()
                } catch (e: Exception) { }
                return@post
            }
            try {
                val tv = makeView(ctx, text, 26f)
                wm(ctx).addView(tv, params(Gravity.CENTER, 0, 0))
                ui.postDelayed({
                    try { wm(ctx).removeView(tv) } catch (e: Exception) { }
                }, millis)
            } catch (e: Exception) {
                try {
                    Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_LONG).show()
                } catch (e2: Exception) { }
            }
        }
    }
}

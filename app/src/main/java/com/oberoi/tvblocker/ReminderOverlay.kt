package com.oberoi.tvblocker

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/** The 5 minute / 2 minute warning banner, drawn over whatever is playing. */
object ReminderOverlay {

    fun show(ctx: Context, text: String, millis: Long = 8000L) {
        val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(ctx) else true

        if (!canOverlay) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
            }
            return
        }

        Handler(Looper.getMainLooper()).post {
            try {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val tv = TextView(ctx)
                tv.text = text
                tv.setTextColor(Color.WHITE)
                tv.textSize = 26f
                tv.setPadding(48, 28, 48, 28)
                val bg = GradientDrawable()
                bg.setColor(Color.parseColor("#E6C0392B"))
                bg.cornerRadius = 24f
                tv.background = bg

                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    android.graphics.PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.END
                lp.x = 60
                lp.y = 60

                wm.addView(tv, lp)
                Handler(Looper.getMainLooper()).postDelayed({
                    try { wm.removeView(tv) } catch (e: Exception) { }
                }, millis)
            } catch (e: Exception) {
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}

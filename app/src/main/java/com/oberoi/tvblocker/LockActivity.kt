package com.oberoi.tvblocker

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * This activity is registered as HOME, so it is the screen the TV boots into
 * and the screen every HOME press returns to. When locked it shows a barrier.
 * When unlocked it turns into a small app launcher with a countdown.
 */
class LockActivity : Activity() {

    companion object {
        fun bringUp(ctx: Context) {
            try {
                val i = Intent(ctx, LockActivity::class.java)
                i.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                ctx.startActivity(i)
            } catch (e: Exception) { }
        }
    }

    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var appHolder: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var lastMode = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        State.unlockUntilWall = Prefs.unlockUntilWall
        State.disabled = Prefs.disabled
        if (State.homePackage.isEmpty()) State.homePackage = Prefs.homePackage

        // Blocker switched off from the dashboard: behave like a normal TV by
        // handing the screen straight to the stock launcher.
        if (handOffIfDisabled()) {
            BlockerService.start(this)
            return
        }

        buildUi()
        BlockerService.start(this)

        if (!Prefs.setupDone) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    private fun buildUi() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0F1B2E"))
        root.setPadding(64, 48, 64, 48)

        title = TextView(this)
        title.setTextColor(Color.WHITE)
        title.textSize = 44f
        title.gravity = Gravity.CENTER_HORIZONTAL

        subtitle = TextView(this)
        subtitle.setTextColor(Color.parseColor("#9DB2CC"))
        subtitle.textSize = 20f
        subtitle.gravity = Gravity.CENTER_HORIZONTAL
        subtitle.setPadding(0, 24, 0, 24)

        appHolder = LinearLayout(this)
        appHolder.orientation = LinearLayout.VERTICAL

        val scroll = ScrollView(this)
        scroll.addView(appHolder)

        root.addView(title)
        root.addView(subtitle)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        lastMode = -1
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (handOffIfDisabled()) return
            render()
            ui.postDelayed(this, 1000L)
        }
    }

    /** The launcher chosen on the dashboard, or the first one available. */
    private fun stockLauncher(): ComponentName? = Launchers.chosen(this)

    /**
     * When the dashboard has switched the blocker off, step out of the way.
     * Returns false if there is no other launcher, so the TV is never stranded.
     */
    private fun handOffIfDisabled(): Boolean {
        if (!State.disabled) return false
        val cn = stockLauncher() ?: return false
        return try {
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory(Intent.CATEGORY_HOME)
            i.component = cn
            i.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            startActivity(i)
            overridePendingTransition(0, 0)
            finish()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun render() {
        val unlocked = State.isUnlocked()
        val mode = if (unlocked) 1 else 0

        if (unlocked) {
            val secs = State.remainingMs() / 1000
            val m = secs / 60
            val s = secs % 60
            title.text = "TV Unlocked"
            subtitle.text = if (State.disabled)
                "Blocker disabled. Hold OK to re-enable."
            else
                String.format("Time remaining  %02d:%02d   —   pick an app below", m, s)
        } else {
            title.text = "TV Locked"
            val extra = if (State.lastSyncError.isNotEmpty())
                "\n(offline — hold OK for the parent PIN)" else ""
            subtitle.text = "Ask a parent to allow time.$extra"
        }

        if (mode != lastMode) {
            lastMode = mode
            appHolder.removeAllViews()
            if (unlocked) populateApps() else showLockedHint()
        }
    }

    private fun showLockedHint() {
        val t = TextView(this)
        t.setTextColor(Color.parseColor("#5E7A8B"))
        t.textSize = 18f
        t.gravity = Gravity.CENTER_HORIZONTAL
        t.text = "\nHold the OK button for 3 seconds to enter the parent PIN.\n\nDevice: " +
            Prefs.deviceName + "  (" + Prefs.deviceId + ")"
        appHolder.addView(t)
    }

    /** Because this app replaces HOME, it must offer a way to open apps. */
    private fun populateApps() {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN)
        main.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        var list = pm.queryIntentActivities(main, 0)
        if (list.isEmpty()) {
            val alt = Intent(Intent.ACTION_MAIN)
            alt.addCategory(Intent.CATEGORY_LAUNCHER)
            list = pm.queryIntentActivities(alt, 0)
        }

        val launcherPkgs = Launchers.packages(this)
        for (ri in list) {
            val pkg = ri.activityInfo.packageName ?: continue
            if (pkg == packageName) continue
            if (pkg in launcherPkgs) continue  // launchers are routed, not listed
            val b = Button(this)
            b.text = ri.loadLabel(pm)?.toString() ?: pkg
            b.textSize = 20f
            b.isFocusable = true
            b.setOnClickListener {
                val launch = pm.getLeanbackLaunchIntentForPackage(pkg)
                    ?: pm.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                } else {
                    Toast.makeText(this, "Cannot open this app", Toast.LENGTH_SHORT).show()
                }
            }
            appHolder.addView(b)
        }
    }

    // ---- Parent escape hatch: works completely offline ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.repeatCount == 0) event.startTracking()
            if (!State.isUnlocked()) return true
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            askPin()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            askPin()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onBackPressed() {
        // Swallow Back so the barrier cannot be dismissed.
        if (!State.isUnlocked()) return
        // When unlocked, Back on the launcher should also do nothing.
    }

    private fun askPin() {
        if (!Prefs.hasPin()) {
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        AlertDialog.Builder(this)
            .setTitle("Parent PIN")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (Prefs.checkPin(input.text.toString())) {
                    parentMenu()
                } else {
                    Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parentMenu() {
        val items = arrayOf(
            "Unlock 30 minutes (local)",
            "Unlock 60 minutes (local)",
            "Lock now",
            "Open system Settings for 5 minutes",
            "Open setup / change server or PIN",
            if (State.disabled) "Re-enable blocker" else "Disable blocker completely"
        )
        AlertDialog.Builder(this)
            .setTitle("Parent menu")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> grantLocal(30)
                    1 -> grantLocal(60)
                    2 -> {
                        State.unlockUntilWall = 0L
                        Prefs.unlockUntilWall = 0L
                        render()
                    }
                    3 -> {
                        State.settingsAllowedUntil = System.currentTimeMillis() + 5 * 60_000L
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                        } catch (e: Exception) {
                            Toast.makeText(this, "Settings not available", Toast.LENGTH_SHORT).show()
                        }
                    }
                    4 -> startActivity(Intent(this, SetupActivity::class.java))
                    5 -> {
                        State.disabled = !State.disabled
                        Prefs.disabled = State.disabled
                        if (!handOffIfDisabled()) {
                            lastMode = -1
                            render()
                        }
                    }
                }
            }
            .show()
    }

    private fun grantLocal(minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        State.unlockUntilWall = until
        Prefs.unlockUntilWall = until
        lastMode = -1
        render()
    }
}

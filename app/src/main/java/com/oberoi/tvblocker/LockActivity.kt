package com.oberoi.tvblocker

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * The parent screen. It is deliberately NOT the home screen: the TV shows its
 * own launcher at all times, and this appears only in two situations —
 *  1. a parent opens TV Blocker from the apps row, or
 *  2. the dashboard asks for the full-screen blocker, which stays until the
 *     parent PIN is entered.
 */
class LockActivity : Activity() {

    companion object {
        const val EXTRA_FORCED = "forced"

        fun bringUp(ctx: Context, forced: Boolean = false) {
            try {
                val i = Intent(ctx, LockActivity::class.java)
                i.putExtra(EXTRA_FORCED, forced)
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

    private var forced = false
    private var pinShowing = false
    private lateinit var title: TextView
    private lateinit var subtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        State.load()

        forced = intent?.getBooleanExtra(EXTRA_FORCED, false) == true || State.showLock

        buildUi()
        BlockerService.start(this)

        if (!Prefs.setupDone) {
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }

        // Opened on purpose from the apps row: go straight to the PIN prompt.
        if (!forced) askPin()
    }

    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        intent = newIntent
        forced = newIntent?.getBooleanExtra(EXTRA_FORCED, false) == true || State.showLock
        render()
        if (!forced && !pinShowing) askPin()
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.parseColor("#0F1B2E"))
        root.setPadding(64, 48, 64, 48)

        title = TextView(this)
        title.setTextColor(Color.parseColor("#FF6B6B"))
        title.textSize = 46f
        title.gravity = Gravity.CENTER

        subtitle = TextView(this)
        subtitle.setTextColor(Color.parseColor("#9DB2CC"))
        subtitle.textSize = 20f
        subtitle.gravity = Gravity.CENTER
        subtitle.setPadding(0, 28, 0, 0)

        root.addView(title)
        root.addView(subtitle)
        setContentView(root)
        render()
    }

    private fun render() {
        if (forced) {
            title.text = State.bannerText
            subtitle.text = "Hold OK for 3 seconds and enter the parent PIN to continue."
        } else {
            title.text = "TV Blocker"
            subtitle.text = "Parent access. Hold OK for 3 seconds if the PIN box closes."
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.repeatCount == 0) event.startTracking()
            return true
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
        // The forced screen may only be dismissed with the PIN.
        if (forced) return
        finish()
    }

    private fun askPin() {
        if (pinShowing) return
        if (!Prefs.hasPin()) {
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }
        pinShowing = true
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val dialog = AlertDialog.Builder(this)
            .setTitle("Parent PIN")
            .setView(input)
            .setCancelable(!forced)
            .setPositiveButton("OK") { _, _ ->
                pinShowing = false
                if (Prefs.checkPin(input.text.toString())) {
                    onPinAccepted()
                } else {
                    Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
                    if (!forced) finish()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                pinShowing = false
                if (!forced) finish()
            }
            .create()
        dialog.setOnCancelListener {
            pinShowing = false
            if (!forced) finish()
        }
        dialog.show()
    }

    private fun onPinAccepted() {
        if (forced) {
            // Clear the dashboard request and tell the server on the next poll.
            forced = false
            State.showLock = false
            Prefs.showLock = false
            State.ackShowLock = true
            render()
        }
        parentMenu()
    }

    private fun parentMenu() {
        val items = arrayOf(
            "Unlock 30 minutes (local)",
            "Unlock 60 minutes (local)",
            "Lock now",
            "Open system Settings for 5 minutes",
            "Open setup / change server or PIN",
            if (State.disabled) "Re-enable blocker" else "Disable blocker completely",
            "Close"
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle("Parent menu")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> grantLocal(30)
                    1 -> grantLocal(60)
                    2 -> {
                        State.unlockUntilWall = 0L
                        Prefs.unlockUntilWall = 0L
                        Launchers.goHome(this)
                        finish()
                    }
                    3 -> {
                        State.settingsAllowedUntil = System.currentTimeMillis() + 5 * 60_000L
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                        } catch (e: Exception) {
                            Toast.makeText(this, "Settings not available", Toast.LENGTH_SHORT).show()
                        }
                        finish()
                    }
                    4 -> {
                        startActivity(Intent(this, SetupActivity::class.java))
                        finish()
                    }
                    5 -> {
                        State.disabled = !State.disabled
                        Prefs.disabled = State.disabled
                        Launchers.goHome(this)
                        finish()
                    }
                    else -> finish()
                }
            }
            .create()
        dialog.setOnCancelListener { finish() }
        dialog.show()
    }

    private fun grantLocal(minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        State.unlockUntilWall = until
        Prefs.unlockUntilWall = until
        Launchers.goHome(this)
        finish()
    }
}

package com.oberoi.tvblocker

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * First-run wizard. Everything here is granted with on-screen clicks only.
 * No ADB, no factory reset.
 */
class SetupActivity : Activity() {

    private lateinit var urlBox: EditText
    private lateinit var keyBox: EditText
    private lateinit var nameBox: EditText
    private lateinit var pinBox: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.parseColor("#0F1B2E"))
        col.setPadding(64, 48, 64, 48)

        col.addView(header("TV Blocker setup"))
        col.addView(label("This TV's ID: " + Prefs.deviceId))

        col.addView(label("Dashboard URL (https://tv.yourdomain.com)"))
        urlBox = field(InputType.TYPE_TEXT_VARIATION_URI, Prefs.serverUrl)
        col.addView(urlBox)

        col.addView(label("Enrollment key (must match the server)"))
        keyBox = field(InputType.TYPE_CLASS_TEXT, Prefs.enrollKey)
        col.addView(keyBox)

        col.addView(label("Name for this TV"))
        nameBox = field(InputType.TYPE_CLASS_TEXT, Prefs.deviceName)
        col.addView(nameBox)

        col.addView(label("Parent PIN (leave blank to keep the current one)"))
        pinBox = field(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, ""
        )
        col.addView(pinBox)

        col.addView(button("1. Save settings") { save() })

        col.addView(button("2. Turn on the app guard (Accessibility)") {
            openOr(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        })

        col.addView(button("3. Allow on-screen warnings (overlay)") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    openOr(Settings.ACTION_SETTINGS)
                }
            }
        })

        col.addView(button("4. Block uninstall (Device admin)") { enableAdmin() })

        col.addView(button("5. Remove battery restrictions") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    openOr(Settings.ACTION_SETTINGS)
                }
            }
        })

        col.addView(button("6. Make this app the home screen") {
            Toast.makeText(
                this,
                "Press the HOME button on the remote, then choose TV Blocker and 'Always'.",
                Toast.LENGTH_LONG
            ).show()
        })

        col.addView(button("Finish") {
            save()
            Prefs.setupDone = true
            BlockerService.start(this)
            finish()
        })

        val scroll = ScrollView(this)
        scroll.addView(col)
        setContentView(scroll)
    }

    private fun header(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextColor(Color.WHITE)
        v.textSize = 34f
        v.setPadding(0, 0, 0, 24)
        return v
    }

    private fun label(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextColor(Color.parseColor("#9DB2CC"))
        v.textSize = 17f
        v.setPadding(0, 18, 0, 6)
        return v
    }

    private fun field(type: Int, value: String): EditText {
        val e = EditText(this)
        e.inputType = type
        e.setText(value)
        e.setTextColor(Color.WHITE)
        return e
    }

    private fun button(t: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.textSize = 18f
        b.setOnClickListener { action() }
        return b
    }

    private fun openOr(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: Exception) {
            Toast.makeText(this, "Open Settings manually", Toast.LENGTH_LONG).show()
        }
    }

    private fun enableAdmin() {
        try {
            val cn = ComponentName(this, AdminReceiver::class.java)
            val i = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            i.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
            i.putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.admin_description)
            )
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Device admin not available", Toast.LENGTH_LONG).show()
        }
    }

    private fun save() {
        Prefs.serverUrl = urlBox.text.toString().trim()
        Prefs.enrollKey = keyBox.text.toString().trim()
        Prefs.deviceName = nameBox.text.toString().trim().ifEmpty { "TV" }
        val pin = pinBox.text.toString().trim()
        if (pin.length >= 4) Prefs.setPin(pin)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        // Setup must never be the screen a child is left on.
        if (isFinishing) BlockerService.start(this)
    }
}

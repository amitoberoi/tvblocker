# TV Blocker

Remote-controlled kiosk lock for Android TV / Google TV, built for the
"no ADB, no factory reset" case. You grant minutes from a dashboard on your
DigitalOcean droplet; the TV unlocks, warns at 5 and 2 minutes, then locks itself.

## How the lock actually works

Because Device Owner mode needs a factory reset, this build uses the strongest
combination reachable through on-screen clicks alone:

| Layer | What it does | How it is enabled |
|---|---|---|
| **Launcher (HOME)** | The lock screen *is* the home screen. Boots into it, every HOME press returns to it. | Press HOME once, choose TV Blocker, "Always" |
| **Accessibility guard** | Bounces any app back to the lock screen while locked. | Settings → Accessibility → TV Blocker |
| **Device admin** | Android refuses to uninstall the app. | Setup screen, button 4 |
| **Settings guard** | System Settings is blocked unless a parent entered the PIN in the last 5 minutes. | automatic |
| **Foreground service + boot receiver + alarm watchdog** | Runs at boot, stays alive, ignores battery restrictions. | Setup screen, button 5 |

**Offline escape hatch:** on the lock screen, hold **OK for 3 seconds** and enter
the parent PIN. Works with no internet and no server. From there you can unlock
locally, open Settings, or disable the blocker entirely. You cannot get locked
out of your own TV.

## Part 1 — Get the APK (no Android Studio needed)

1. Create a **new GitHub repository** (private is fine).
2. Upload the contents of this folder to it (drag-and-drop in the GitHub web UI
   works — keep the folder structure).
3. Go to the **Actions** tab and let the *Build APK* workflow run (~3–5 min).
4. Open the finished run → **Artifacts** → download `tvblocker-apk`.
5. Unzip it. You now have `app-debug.apk`, signed and installable.

If the build fails, open the failed step, copy the compiler error, and give it to
Claude Code together with this repo — that error loop is exactly how the rough
edges get fixed.

## Part 2 — Deploy the server on your droplet

```bash
cd /opt && git clone <your-repo> tvblocker && cd tvblocker/backend
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt

export TVBLOCKER_ENROLL_KEY="$(openssl rand -hex 16)"   # save this
export TVBLOCKER_ADMIN_PASS="choose-a-strong-password"
.venv/bin/uvicorn main:app --host 127.0.0.1 --port 8789
```

Make it permanent with systemd (`/etc/systemd/system/tvblocker.service`):

```ini
[Unit]
Description=TV Blocker
After=network.target

[Service]
WorkingDirectory=/opt/tvblocker/backend
Environment=TVBLOCKER_ENROLL_KEY=your-key-here
Environment=TVBLOCKER_ADMIN_PASS=your-password-here
ExecStart=/opt/tvblocker/backend/.venv/bin/uvicorn main:app --host 127.0.0.1 --port 8789
Restart=always

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload && systemctl enable --now tvblocker
```

**Put it behind HTTPS.** With Caddy this is two lines in `/etc/caddy/Caddyfile`:

```
tv.yourdomain.com {
    reverse_proxy 127.0.0.1:8789
}
```

HTTPS matters here: over plain HTTP the enrollment key travels in clear text and
anyone on the network could grant themselves unlimited TV time.

## Part 3 — Install on each TV (no ADB)

1. On the TV: **Settings → Apps → Security & restrictions → Unknown sources** → allow
   your file-transfer app.
2. Get the APK onto the TV using **Downloader** (AFTVnews) from the Play Store, or
   **Send Files to TV** from your phone. Both are plain app installs.
3. Install the APK, open **TV Blocker**, and work down the setup screen:
   - Dashboard URL (`https://tv.yourdomain.com`), enrollment key, TV name, parent PIN
   - Buttons 2–5 grant accessibility, overlay, device admin and battery exemption
   - Press **HOME** on the remote and choose **TV Blocker → Always**
4. The TV appears on your dashboard within about 7 seconds.

Repeat per TV. Each gets its own ID; one dashboard controls them all.

## Part 4 — Test before you trust it

- Grant 6 minutes, confirm the countdown, watch the 5- and 2-minute warnings fire.
- Let it expire and confirm the lock screen takes the screen back.
- Press Extend on the dashboard mid-session.
- **Reboot the TV** and confirm it comes back locked.
- Unplug the router and confirm the PIN escape hatch still works.

## Known limits (be honest with yourself about these)

- A factory reset from the TV's own recovery menu removes everything. No app
  installed without Device Owner can survive that.
- Switching HDMI input to a console or another stick bypasses this entirely.
- Google TV's built-in **kids profile lock** is a good free layer to stack on top.

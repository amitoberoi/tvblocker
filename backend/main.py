"""
TV Blocker control server.

Runs on the DigitalOcean droplet next to your other FastAPI services.
The TVs poll /api/v1/sync every few seconds; you grant, extend or revoke
time from the dashboard at /.

Environment:
  TVBLOCKER_ENROLL_KEY   shared secret the TV app sends   (required)
  TVBLOCKER_ADMIN_PASS   dashboard password               (required)
  TVBLOCKER_DB           sqlite path (default tvblocker.db)
"""

import base64
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
from contextlib import closing

from fastapi import FastAPI, Form, HTTPException, Request, Response
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

ENROLL_KEY = os.environ.get("TVBLOCKER_ENROLL_KEY", "")
ADMIN_PASS = os.environ.get("TVBLOCKER_ADMIN_PASS", "")
DB_PATH = os.environ.get("TVBLOCKER_DB", "tvblocker.db")

if not ENROLL_KEY or not ADMIN_PASS:
    raise SystemExit("Set TVBLOCKER_ENROLL_KEY and TVBLOCKER_ADMIN_PASS")

app = FastAPI(title="TV Blocker")

# Sessions are HMAC-signed cookies rather than server memory, so restarting
# the service no longer logs you out. Changing the admin password (or setting
# TVBLOCKER_SECRET) invalidates every existing session immediately.
_SECRET = hashlib.sha256(
    (os.environ.get("TVBLOCKER_SECRET", "") + ADMIN_PASS).encode()
).digest()
SESSION_DAYS = 30


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def _unb64(txt: str) -> bytes:
    return base64.urlsafe_b64decode(txt + "=" * (-len(txt) % 4))


def _sign(expiry: int) -> str:
    msg = str(expiry).encode()
    sig = hmac.new(_SECRET, msg, hashlib.sha256).digest()
    return _b64(msg) + "." + _b64(sig)


def _verify(token: str) -> bool:
    try:
        raw, sig = token.split(".", 1)
        msg = _unb64(raw)
        want = _unb64(sig)
        good = hmac.new(_SECRET, msg, hashlib.sha256).digest()
        if not hmac.compare_digest(good, want):
            return False
        return int(msg.decode()) > time.time()
    except Exception:
        return False


def db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    with closing(db()) as conn:
        conn.execute(
            """CREATE TABLE IF NOT EXISTS devices (
                   device_id    TEXT PRIMARY KEY,
                   name         TEXT NOT NULL,
                   unlock_until REAL NOT NULL DEFAULT 0,
                   disabled     INTEGER NOT NULL DEFAULT 0,
                   last_seen    REAL NOT NULL DEFAULT 0
               )"""
        )
        # Safe migration for databases created before launcher routing existed.
        for ddl in (
            "ALTER TABLE devices ADD COLUMN launchers TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE devices ADD COLUMN home_package TEXT NOT NULL DEFAULT ''",
        ):
            try:
                conn.execute(ddl)
            except sqlite3.OperationalError:
                pass  # column already present

        conn.execute(
            """CREATE TABLE IF NOT EXISTS events (
                   id        INTEGER PRIMARY KEY AUTOINCREMENT,
                   ts        REAL,
                   device_id TEXT,
                   action    TEXT,
                   detail    TEXT
               )"""
        )
        conn.commit()


init_db()


def log(device_id, action, detail=""):
    with closing(db()) as conn:
        conn.execute(
            "INSERT INTO events (ts, device_id, action, detail) VALUES (?,?,?,?)",
            (time.time(), device_id, action, detail),
        )
        conn.commit()


# ---------------- TV endpoint ----------------

@app.post("/api/v1/sync")
async def sync(request: Request):
    body = await request.json()
    if not secrets.compare_digest(str(body.get("key", "")), ENROLL_KEY):
        raise HTTPException(status_code=403, detail="bad key")

    device_id = str(body.get("device_id", ""))[:64]
    name = str(body.get("name", "TV"))[:64]
    if not device_id:
        raise HTTPException(status_code=400, detail="device_id required")

    # The TV reports which launchers it has installed so the dashboard can
    # offer them as choices.
    raw_launchers = body.get("launchers")
    launchers_json = ""
    if isinstance(raw_launchers, list):
        clean = [
            {"pkg": str(x.get("pkg", ""))[:128], "label": str(x.get("label", ""))[:64]}
            for x in raw_launchers
            if isinstance(x, dict) and x.get("pkg")
        ]
        launchers_json = json.dumps(clean[:10])

    now = time.time()
    with closing(db()) as conn:
        row = conn.execute(
            "SELECT * FROM devices WHERE device_id=?", (device_id,)
        ).fetchone()
        if row is None:
            conn.execute(
                "INSERT INTO devices (device_id, name, unlock_until, disabled,"
                " last_seen, launchers, home_package) VALUES (?,?,0,0,?,?,'')",
                (device_id, name, now, launchers_json),
            )
            conn.commit()
            unlock_until, disabled, home_package = 0.0, 0, ""
        else:
            unlock_until = row["unlock_until"]
            disabled = row["disabled"]
            home_package = row["home_package"] or ""
            if launchers_json:
                conn.execute(
                    "UPDATE devices SET last_seen=?, name=?, launchers=?"
                    " WHERE device_id=?",
                    (now, name, launchers_json, device_id),
                )
            else:
                conn.execute(
                    "UPDATE devices SET last_seen=?, name=? WHERE device_id=?",
                    (now, name, device_id),
                )
            conn.commit()

    remaining = max(0, int(unlock_until - now))
    return JSONResponse(
        {
            "unlock_seconds": remaining,
            "disabled": bool(disabled),
            "home_package": home_package,
            "message": None,
        }
    )


# ---------------- Dashboard ----------------

def authed(request: Request) -> bool:
    return _verify(request.cookies.get("tvb_session", ""))


LOGIN_HTML = """<!doctype html><html><head><meta charset=utf-8>
<title>TV Blocker</title><meta name=viewport content="width=device-width,initial-scale=1">
<style>body{font-family:system-ui;background:#0F1B2E;color:#eee;display:flex;
height:100vh;align-items:center;justify-content:center}
form{background:#16263c;padding:32px;border-radius:12px}
input,button{font-size:16px;padding:10px;width:100%;box-sizing:border-box;margin-top:8px;
border-radius:8px;border:1px solid #2E7DD1;background:#0F1B2E;color:#fff}
button{background:#2E7DD1;cursor:pointer}</style></head><body>
<form method=post action=/login><h2>TV Blocker</h2>
<input type=password name=password placeholder=Password autofocus>
<button>Sign in</button></form></body></html>"""


@app.get("/", response_class=HTMLResponse)
def dashboard(request: Request):
    if not authed(request):
        return HTMLResponse(LOGIN_HTML)

    now = time.time()
    with closing(db()) as conn:
        devices = conn.execute("SELECT * FROM devices ORDER BY name").fetchall()
        events = conn.execute(
            "SELECT * FROM events ORDER BY id DESC LIMIT 15"
        ).fetchall()

    rows = []
    for d in devices:
        remaining = int(max(0, d["unlock_until"] - now))
        online = (now - d["last_seen"]) < 30
        if d["disabled"]:
            status = "<span class=off>BLOCKER OFF — normal TV</span>"
        elif remaining > 0:
            status = (
                f"<span class=ok>UNLOCKED {remaining // 60}m {remaining % 60}s</span>"
            )
        else:
            status = "<span class=lock>LOCKED</span>"

        if d["disabled"]:
            toggle_label, toggle_cls = "Resume blocking", "warn"
        else:
            toggle_label, toggle_cls = "Unblock permanently", "off"

        try:
            launchers = json.loads(d["launchers"] or "[]")
        except Exception:
            launchers = []
        chosen = d["home_package"] or ""
        if launchers:
            opts = "".join(
                f'<option value="{l["pkg"]}"'
                f'{" selected" if l["pkg"] == chosen else ""}>{l["label"]}</option>'
                for l in launchers
            )
            picker = (
                f'<form method=post action=/home class=row>'
                f'<input type=hidden name=device_id value="{d["device_id"]}">'
                f'<select name=home_package>{opts}</select>'
                f'<button class=alt>Set home</button></form>'
            )
        else:
            picker = '<div class=id>No launcher list reported yet</div>'

        rows.append(
            f"""<div class=card>
<div class=hdr><b>{d['name']}</b>
<span class="dot {'on' if online else 'off'}"></span></div>
<div class=st>{status}</div>
<form method=post action=/grant class=row>
  <input type=hidden name=device_id value="{d['device_id']}">
  <input type=number name=minutes value=30 min=1 max=600>
  <button>Grant</button>
</form>
<form method=post action=/extend class=row>
  <input type=hidden name=device_id value="{d['device_id']}">
  <input type=number name=minutes value=15 min=1 max=600>
  <button class=alt>Extend</button>
</form>
<form method=post action=/lock class=row>
  <input type=hidden name=device_id value="{d['device_id']}">
  <button class=danger>Lock now</button>
</form>
<form method=post action=/toggle class=row>
  <input type=hidden name=device_id value="{d['device_id']}">
  <button class={toggle_cls}>{toggle_label}</button>
</form>
{picker}
<div class=id>{d['device_id']}</div>
</div>"""
        )

    ev = "".join(
        f"<li>{time.strftime('%d %b %H:%M', time.localtime(e['ts']))} — "
        f"{e['action']} {e['detail']}</li>"
        for e in events
    )

    return HTMLResponse(
        f"""<!doctype html><html><head><meta charset=utf-8><title>TV Blocker</title>
<meta name=viewport content="width=device-width,initial-scale=1">
<meta http-equiv=refresh content=10>
<style>
body{{font-family:system-ui;background:#0F1B2E;color:#e8eef6;margin:0;padding:24px}}
h1{{font-size:22px}}
.grid{{display:flex;flex-wrap:wrap;gap:16px}}
.card{{background:#16263c;border-radius:14px;padding:18px;width:280px}}
.hdr{{display:flex;justify-content:space-between;align-items:center}}
.dot{{width:10px;height:10px;border-radius:50%;display:inline-block}}
.on{{background:#3ddc84}} .off{{background:#777}}
.st{{margin:10px 0;font-size:15px}}
.ok{{color:#3ddc84}} .lock{{color:#ff6b6b}}
.row{{display:flex;gap:8px;margin-top:8px}}
input[type=number]{{width:80px}}
select{{flex:1;padding:9px;border-radius:8px;border:1px solid #2E7DD1;background:#0F1B2E;color:#fff;font-size:14px}}
input,button{{padding:9px;border-radius:8px;border:1px solid #2E7DD1;
background:#0F1B2E;color:#fff;font-size:15px}}
button{{background:#2E7DD1;cursor:pointer;flex:1}}
button.alt{{background:#3a6ea5}} button.danger{{background:#a53a3a;border-color:#a53a3a}}
button.off{{background:#4a4a52;border-color:#4a4a52}}
button.warn{{background:#8a6d1f;border-color:#8a6d1f}}
.off{{color:#c9a227}}
.id{{color:#63799a;font-size:11px;margin-top:10px}}
ul{{color:#9DB2CC;font-size:13px;line-height:1.7}}
</style></head><body>
<h1>TV Blocker</h1>
<div class=grid>{''.join(rows) or '<p>No TVs have checked in yet.</p>'}</div>
<h3>Recent activity</h3><ul>{ev}</ul>
</body></html>"""
    )


@app.post("/login")
def login(response: Response, password: str = Form(...)):
    if not secrets.compare_digest(password, ADMIN_PASS):
        return HTMLResponse(LOGIN_HTML, status_code=401)
    expiry = int(time.time()) + SESSION_DAYS * 24 * 3600
    r = RedirectResponse("/", status_code=303)
    r.set_cookie(
        "tvb_session",
        _sign(expiry),
        httponly=True,
        samesite="lax",
        secure=True,
        max_age=SESSION_DAYS * 24 * 3600,
    )
    return r


def _require(request: Request):
    if not authed(request):
        raise HTTPException(status_code=403, detail="not signed in")


@app.post("/grant")
def grant(request: Request, device_id: str = Form(...), minutes: int = Form(...)):
    _require(request)
    until = time.time() + minutes * 60
    with closing(db()) as conn:
        conn.execute(
            "UPDATE devices SET unlock_until=? WHERE device_id=?", (until, device_id)
        )
        conn.commit()
    log(device_id, "GRANT", f"{minutes} min")
    return RedirectResponse("/", status_code=303)


@app.post("/extend")
def extend(request: Request, device_id: str = Form(...), minutes: int = Form(...)):
    _require(request)
    now = time.time()
    with closing(db()) as conn:
        row = conn.execute(
            "SELECT unlock_until FROM devices WHERE device_id=?", (device_id,)
        ).fetchone()
        base = max(now, row["unlock_until"] if row else now)
        conn.execute(
            "UPDATE devices SET unlock_until=? WHERE device_id=?",
            (base + minutes * 60, device_id),
        )
        conn.commit()
    log(device_id, "EXTEND", f"+{minutes} min")
    return RedirectResponse("/", status_code=303)


@app.post("/home")
def set_home(request: Request, device_id: str = Form(...), home_package: str = Form(...)):
    """Choose which of the TV's launchers is used when blocking is switched off."""
    _require(request)
    with closing(db()) as conn:
        conn.execute(
            "UPDATE devices SET home_package=? WHERE device_id=?",
            (home_package.strip()[:128], device_id),
        )
        conn.commit()
    log(device_id, "HOME", home_package)
    return RedirectResponse("/", status_code=303)


@app.post("/toggle")
def toggle(request: Request, device_id: str = Form(...)):
    """Flip the blocker off entirely (TV behaves normally) or back on."""
    _require(request)
    with closing(db()) as conn:
        row = conn.execute(
            "SELECT disabled FROM devices WHERE device_id=?", (device_id,)
        ).fetchone()
        turning_off = not (row and row["disabled"])
        if turning_off:
            conn.execute(
                "UPDATE devices SET disabled=1 WHERE device_id=?", (device_id,)
            )
        else:
            # Resuming protection also locks immediately, so there is no gap.
            conn.execute(
                "UPDATE devices SET disabled=0, unlock_until=0 WHERE device_id=?",
                (device_id,),
            )
        conn.commit()
    log(
        device_id,
        "BLOCKER",
        "switched OFF (normal TV)" if turning_off else "switched ON (locked)",
    )
    return RedirectResponse("/", status_code=303)


@app.post("/lock")
def lock(request: Request, device_id: str = Form(...)):
    _require(request)
    with closing(db()) as conn:
        conn.execute("UPDATE devices SET unlock_until=0 WHERE device_id=?", (device_id,))
        conn.commit()
    log(device_id, "LOCK", "immediate")
    return RedirectResponse("/", status_code=303)

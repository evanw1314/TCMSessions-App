import base64
import socket
from io import BytesIO

import qrcode
from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse

router = APIRouter(tags=["pairing"])


def _lan_ip() -> str:
    """Best-effort LAN-facing IP for a server bound to 0.0.0.0, which has no
    single "own" address. A UDP "connect" never sends a packet — it just asks
    the OS to pick the outbound interface for that destination, whose address
    we then read back. Falls back to loopback if there's no route out (e.g.
    an isolated dev machine), which at least keeps /pair from crashing."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


@router.get("/pair", response_class=HTMLResponse)
def pair(request: Request):
    # The Host header a client used to reach us doesn't help here (a phone on
    # the LAN and a desktop opening this page as localhost:8000 would see
    # different values) — the practitioner needs the LAN-reachable address
    # regardless of how they got to this page, so we detect it directly and
    # keep only the port from the incoming request.
    port = request.url.port or 8000
    address = f"http://{_lan_ip()}:{port}"

    img = qrcode.make(address)
    buf = BytesIO()
    img.save(buf, format="PNG")
    qr_b64 = base64.b64encode(buf.getvalue()).decode("ascii")

    return f"""<!doctype html>
<html>
<head>
  <meta charset="utf-8" />
  <title>Pair TCM Sessions app</title>
  <style>
    body {{ font-family: sans-serif; text-align: center; padding: 40px; }}
    img {{ width: 280px; height: 280px; }}
    code {{ font-size: 1.1em; }}
  </style>
</head>
<body>
  <h1>Scan to pair the TCM Sessions app</h1>
  <img src="data:image/png;base64,{qr_b64}" alt="QR code for {address}" />
  <p>Server address: <code>{address}</code></p>
  <p>Open the app, tap "Scan QR" on the login screen, and point the camera at this code.</p>
</body>
</html>"""

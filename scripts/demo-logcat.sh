#!/usr/bin/env bash
#
# Emit a scripted, realistic Android log on a connected device, to exercise the Logcat tab.
#
# Everything the panel is built around, in one run: JSON request and response bodies, headers
# carrying credentials (so the AI context has something to redact), a run of identical lines (so
# the collapse rule has something to collapse), system lines that name the app (so "Related"
# scope has something to admit), a full crash with a stack trace, and an ANR report.
#
# Each block is emitted by ONE remote `log` process reading stdin, so every line in a block
# shares a PID — which is what lets the details pane reassemble a stack trace.
#
# These lines come from the `log` command, not from an app, so they carry the shell's PID: they
# are visible under the Related and All scopes, not under App. To demo App scope, drop
# scripts/demo/LogcatDemo.kt into a sample app and call it — same scenario, from the app process.
#
# Usage:
#   scripts/demo-logcat.sh [--package com.example.app] [--serial emulator-5554] [--fast]
#
# The logs are synthetic: no app is installed, started or touched, and nothing is written to the
# device beyond its own ring buffer. `adb logcat -c` clears them.

set -euo pipefail

PACKAGE="com.example.myapplicationccc"
SERIAL=""
PAUSE=0.9

while [ $# -gt 0 ]; do
  case "$1" in
    --package) PACKAGE="$2"; shift 2 ;;
    --serial)  SERIAL="$2";  shift 2 ;;
    --fast)    PAUSE=0;      shift   ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

command -v adb >/dev/null || { echo "adb is not on PATH" >&2; exit 1; }

ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "no device: run 'adb devices'" >&2; exit 1; }

# One `log` process per block, reading stdin, so every line of a block shares a PID — which is
# what lets the details pane reassemble a stack trace out of one-line-per-frame records.
#
# Android's `log` emits one empty record at EOF whatever you feed it, so each block is followed
# by a blank row. That is the device's behaviour, not the panel's; `adb logcat -c` clears it.
emit() {
  local priority="$1" tag="$2"
  "${ADB[@]}" shell "log -p $priority -t '$tag'"
  [ "$PAUSE" = 0 ] || sleep "$PAUSE"
}

say() { printf '  %s\n' "$1"; }

echo "Writing a demo log to the device as '$PACKAGE'."

# ---------------------------------------------------------------- startup
say "startup (Related scope: system lines that name the app)"

emit i ActivityManager <<EOF
Start proc 3189:$PACKAGE/u0a188 for activity {$PACKAGE/.MainActivity}
EOF

emit i ActivityTaskManager <<EOF
START u0 {act=android.intent.action.MAIN cmp=$PACKAGE/.MainActivity} from uid 2000
EOF

emit i "$PACKAGE" <<'EOF'
App initialized in 412 ms
EOF

emit d MainActivity <<'EOF'
Screen opened: Offers
EOF

# ---------------------------------------------------------------- network + JSON
say "network with JSON bodies and credentials (Network filter, redaction)"

emit d ApiClient <<'EOF'
--> POST https://api.example.com/v1/auth/token
Content-Type: application/json
X-Api-Key: EXAMPLE-NOT-A-REAL-KEY
{"grant_type":"refresh_token","refresh_token":"EXAMPLE-NOT-A-REAL-TOKEN","device_id":"emu-5554"}
--> END POST (112-byte body)
EOF

emit d ApiClient <<'EOF'
<-- 200 OK https://api.example.com/v1/auth/token (287ms)
Set-Cookie: session=EXAMPLE-NOT-A-REAL-SESSION; Path=/; HttpOnly; Secure
Content-Type: application/json
{
  "access_token": "eyJhbGciOiJub25lIn0.eyJub3RlIjoiZXhhbXBsZS1vbmx5In0.not-a-real-signature",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "offers.read profile.read"
}
<-- END HTTP (241-byte body)
EOF

emit d OkHttp <<'EOF'
--> GET https://api.example.com/v1/offers?city=berlin&limit=12
Authorization: Bearer eyJhbGciOiJub25lIn0.eyJub3RlIjoiZXhhbXBsZS1vbmx5In0.not-a-real-signature
Accept: application/json
--> END GET
EOF

emit i OkHttp <<'EOF'
<-- 200 OK https://api.example.com/v1/offers?city=berlin&limit=12 (432ms, 3.4 kB)
EOF

emit d ApiClient <<'EOF'
Response body:
{
  "city": "berlin",
  "count": 12,
  "offers": [
    { "id": "of_8812", "title": "Weekend city break", "price": { "amount": 149.00, "currency": "EUR" }, "available": true },
    { "id": "of_8813", "title": "Museum pass",        "price": { "amount":  29.50, "currency": "EUR" }, "available": true },
    { "id": "of_8814", "title": "Airport transfer",   "price": { "amount":  19.00, "currency": "EUR" }, "available": false }
  ],
  "page": { "next": null, "total": 12 }
}
EOF

emit i OfferRepository <<'EOF'
Offers loaded: 12 results in 1.2s (cache miss)
EOF

emit i ConnectivityService <<'EOF'
NetworkAgentInfo [WIFI () - 101] validation passed
EOF

# ---------------------------------------------------------------- noise worth collapsing
say "a repeated warning (collapse rule) and a dropped-frames report"

emit w ImageLoader <<'EOF'
Bitmap allocation exceeded the soft limit, evicting the LRU entry
Bitmap allocation exceeded the soft limit, evicting the LRU entry
Bitmap allocation exceeded the soft limit, evicting the LRU entry
Bitmap allocation exceeded the soft limit, evicting the LRU entry
Bitmap allocation exceeded the soft limit, evicting the LRU entry
Bitmap allocation exceeded the soft limit, evicting the LRU entry
Bitmap allocation exceeded the soft limit, evicting the LRU entry
Bitmap allocation exceeded the soft limit, evicting the LRU entry
EOF

emit i Choreographer <<'EOF'
Skipped 47 frames!  The application may be doing too much work on its main thread.
EOF

emit w CheckoutViewModel <<'EOF'
Price recalculation took 318 ms on the main thread
EOF

# ---------------------------------------------------------------- the crash
say "a crash with a full stack trace (Crashes filter, details pane)"

emit e AndroidRuntime <<EOF
FATAL EXCEPTION: main
Process: $PACKAGE, PID: 3189
java.lang.IllegalStateException: Required value was null
	at com.example.myapplication.ui.OffersViewModel.loadOffers(OffersViewModel.kt:42)
	at com.example.myapplication.ui.OffersViewModel\$loadOffers\$1.invokeSuspend(OffersViewModel.kt:31)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:104)
	at android.os.Handler.handleCallback(Handler.java:958)
	at android.os.Looper.loop(Looper.java:257)
	at android.app.ActivityThread.main(ActivityThread.java:8218)
Caused by: java.io.IOException: Unexpected end of stream on https://api.example.com/v1/offers
	at okhttp3.internal.http1.Http1ExchangeCodec.readResponseHeaders(Http1ExchangeCodec.kt:206)
	at okhttp3.internal.connection.Exchange.readResponseHeaders(Exchange.kt:110)
	... 16 more
EOF

emit i ActivityManager <<EOF
Process $PACKAGE (pid 3189) has died: fg  TOP
EOF

# ---------------------------------------------------------------- the ANR
say "an ANR report (ANRs filter)"

emit e ActivityManager <<EOF
ANR in $PACKAGE (${PACKAGE}/.CheckoutActivity)
PID: 3401
Reason: Input dispatching timed out (Application does not respond, 5003ms)
Load: 12.4 / 9.8 / 7.1
CPU usage from 0ms to 5003ms later:
  92% 3401/$PACKAGE: 88% user + 4% kernel
EOF

echo
echo "Done. In the Logcat tab, try:"
echo "  App + All logs      — everything above, one process at a time"
echo "  Related + Crashes   — the FATAL EXCEPTION, with its frames"
echo "  App + Network       — the OkHttp/ApiClient traffic and the JSON bodies"
echo "  All + ANRs          — the ANR report"
echo "  Select the crash line, then read the stack trace in the details pane"
echo "  Click any line of the JSON body — the details pane shows the whole block"
echo "  Drag across several lines: the log is an editor, so text selects like a terminal"

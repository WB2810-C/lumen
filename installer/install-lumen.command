#!/bin/bash
# Lumen – put it on your TV (Mac). Double-click this file.
# If macOS says it can't check the file: right-click it → Open → Open.
cd "$(dirname "$0")" || exit 1
PKG=com.wolfram.lumen
ADB="./platform-tools/adb"
MODE=install
[ "$1" = "restore" ] && MODE=restore

finish() { [ -n "$TARGET" ] && "$ADB" disconnect "$TARGET" >/dev/null 2>&1; echo; read -r -p "  Press Enter to close. " _; exit "${1:-0}"; }
yes_no() { local a; while true; do read -r -p "  $1 [y/n] " a; case "$a" in [Yy]*) return 0;; [Nn]*) return 1;; esac; done; }

echo
echo "  =========================================="
if [ "$MODE" = restore ]; then echo "    Back to the Google TV home screen"; else echo "    Lumen  -  put it on your TV"; fi
echo "  =========================================="
echo

if [ "$MODE" = install ] && [ ! -f Lumen.apk ]; then
  echo "  Lumen.apk is not next to this file. Unzip Lumen-installer.zip first, then open that folder."
  finish 1
fi

if [ ! -x "$ADB" ]; then
  echo "  Downloading Google's small adb tool (about 7 MB, no Android Studio)..."
  curl -fsSL -o platform-tools.zip https://dl.google.com/android/repository/platform-tools-latest-darwin.zip \
    && unzip -qo platform-tools.zip && rm -f platform-tools.zip
  [ -x "$ADB" ] || { echo "  Couldn't download adb. Check the internet connection and try again."; finish 1; }
fi
"$ADB" start-server >/dev/null 2>&1

cat <<'EOF'

  On the TV, once:
    1. Settings > System > About > click "Android TV OS build" 7 times
       until it says you are a developer
    2. Settings > System > Developer options > turn on "USB debugging"
    3. Settings > Network & Internet > your Wi-Fi > write down the IP address

  The Mac and the TV must be on the same Wi-Fi.

EOF

# Waits up to 90 seconds until the TV is connected and allowed.
wait_device() {
  local i
  for i in $(seq 1 45); do
    [ "$("$ADB" -s "$TARGET" get-state 2>/dev/null)" = "device" ] && return 0
    [ "$i" = 3 ] && echo "  Waiting for OK on the TV..."
    sleep 2
    "$ADB" connect "$TARGET" >/dev/null 2>&1
  done
  return 1
}

TVIP=""
while [ -z "$TVIP" ]; do read -r -p "  TV's IP address (like 192.168.1.50): " TVIP; TVIP="${TVIP// /}"; done
case "$TVIP" in *:*) TARGET="$TVIP";; *) TARGET="$TVIP:5555";; esac

echo
echo "  Connecting... Look at the TV: if it asks \"Allow USB debugging?\","
echo "  tick \"Always allow from this computer\" and choose OK."
OUT="$("$ADB" connect "$TARGET" 2>&1)"; echo "  $OUT"
if echo "$OUT" | grep -qiE "connected to|authenticate"; then
  wait_device || { echo; echo "  The TV didn't allow the connection. Run this again and choose OK on the TV."; finish 1; }
else
  echo
  echo "  The TV didn't answer on the usual port. Use Wireless debugging instead:"
  echo "    On the TV: Settings > System > Developer options > Wireless debugging > turn it on,"
  echo "    then choose \"Pair device with pairing code\"."
  echo
  read -r -p "  IP address and port shown under the code (like 192.168.1.50:37123): " PAIRADDR
  read -r -p "  The 6-digit code: " PAIRCODE
  "$ADB" pair "$PAIRADDR" "$PAIRCODE"
  echo
  echo "  Now close the code on the TV. The Wireless debugging screen shows"
  echo "  IP address & Port - that port is a different one (like 192.168.1.50:41234)."
  read -r -p "  That IP address and port: " TARGET
  "$ADB" connect "$TARGET"
  wait_device || { echo; echo "  Couldn't connect. Check: same Wi-Fi, debugging on, the right IP address."; finish 1; }
fi
echo; echo "  Connected to the TV."

if [ "$MODE" = restore ]; then
  echo "  Switching the Google TV home screen back on..."
  "$ADB" -s "$TARGET" shell pm enable --user 0 com.google.android.apps.tv.launcherx >/dev/null 2>&1
  "$ADB" -s "$TARGET" shell pm enable --user 0 com.google.android.tungsten.setupwraith >/dev/null 2>&1
  "$ADB" -s "$TARGET" shell cmd package set-home-activity --user 0 \
    com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity >/dev/null 2>&1
  echo
  if yes_no "Also remove Lumen from the TV?"; then "$ADB" -s "$TARGET" uninstall "$PKG"; fi
  "$ADB" -s "$TARGET" shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  echo; echo "  Done. Press Home on the remote: the Google TV home screen is back."
  finish 0
fi

echo
echo "  Installing Lumen... this takes a moment."
echo "  If the TV shows a Play Protect message, choose \"Install anyway\"."
OUT="$("$ADB" -s "$TARGET" install -r Lumen.apk 2>&1)"; echo "$OUT"
if ! echo "$OUT" | grep -q "Success"; then
  if echo "$OUT" | grep -qE "UPDATE_INCOMPATIBLE|SIGNATURES"; then
    echo "  An older Lumen made with a different key is on the TV. Replacing it..."
    "$ADB" -s "$TARGET" uninstall "$PKG" >/dev/null 2>&1
    OUT="$("$ADB" -s "$TARGET" install Lumen.apk 2>&1)"; echo "$OUT"
  fi
  echo "$OUT" | grep -q "Success" || { echo; echo "  The install didn't work - the reason is just above. Send a screenshot to Claude."; finish 1; }
fi

echo
echo "  Switching on the microphone and Now Playing for Lumen..."
"$ADB" -s "$TARGET" shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1
"$ADB" -s "$TARGET" shell cmd notification allow_listener "$PKG/$PKG.MediaListener" >/dev/null 2>&1
echo "  Opening Lumen on the TV..."
"$ADB" -s "$TARGET" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
echo
if ! yes_no "Do you see Lumen on the TV now?"; then
  echo
  echo "  Then Lumen didn't start properly. Your normal home screen stays as it is."
  echo "  Saving what the TV reported in lumen-crash.txt - send that file to Claude."
  { "$ADB" -s "$TARGET" logcat -d -b crash; "$ADB" -s "$TARGET" logcat -d -t 400 '*:E'; } > lumen-crash.txt 2>&1
  finish 1
fi

echo
echo "  Make Lumen the home screen? Then the Home button on the remote opens Lumen."
echo "  You can undo it any time with \"Back to Google TV.command\"."
if yes_no "Make Lumen the home screen?"; then
  "$ADB" -s "$TARGET" shell cmd package set-home-activity --user 0 "$PKG/.MainActivity" >/dev/null 2>&1
  "$ADB" -s "$TARGET" shell pm disable-user --user 0 com.google.android.apps.tv.launcherx >/dev/null 2>&1
  "$ADB" -s "$TARGET" shell pm disable-user --user 0 com.google.android.tungsten.setupwraith >/dev/null 2>&1
  "$ADB" -s "$TARGET" shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  echo; echo "  Done! Press Home on the remote and Lumen opens."
  echo "  To update Lumen later, just run this again with the new Lumen.apk."
else
  echo; echo "  Done! Lumen is in the TV's app list. Your normal home screen stays."
fi
finish 0

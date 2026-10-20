---
name: tv-deploy
description: Use when building and installing the launcher on the living-room TV, verifying it on the device, or checking Home owner, accessibility services and notification listener.
---

# Deploy the launcher to the TV

`TV_HOST` comes from `backup/README.md`; `T="tools/venv/bin/python tools/tv.py"`.

1. Check first: `nc -vz -G 3 $TV_HOST 5555`, and that our launcher is in front
   (`$T 'dumpsys activity activities | grep topResumedActivity'`). Never interrupt another app or a Moonlight stream.
2. Build and install (`backup/` lives in the main checkout; call it by that path from a worktree):
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
     INPUT_KEY_SERVICE=1 HOME_KEY_SERVICE=1 bash launcher/build-and-install.sh
   ```
   Android turns the key services off on every reinstall, so pass the flags each time.
3. Verify:
   ```sh
   $T 'cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME'
   $T 'settings get secure enabled_accessibility_services'
   $T 'settings get secure enabled_notification_listeners'
   ```
4. Screenshots: `$T 'input keyevent KEYCODE_DPAD_DOWN; sleep 1; screencap -p /sdcard/s.png'`, then
   `$T --pull /sdcard/s.png $TMPDIR/s.png`, and look at it. Keep them out of the repo unless they go in `docs/fork/`.
5. Remote buttons and accessibility services need a human press (adb keys never reach them); ask the user.

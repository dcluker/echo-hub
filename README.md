# Echo Hub

Launcher app for an Amazon Echo Show 5 running LineageOS 18.1 (`checkers`, unlocked with amonet). It is a WebView shell
around the MyFlix `/echo` page (browse, filters, search, cast to a TV, now playing), plus a Library | Room toggle that opens
a Home Assistant dashboard and a silent doorbell camera popup.

The page and all server endpoints live in the **MyFlix** repo (`static/echo.html`, `js/echo.js`, `js/echowake.js`,
`/api/cast*`, `/api/echo/*`). This repo is only the Android shell.

## What the app does

- Sets itself as the home screen (HOME + DEFAULT intent filter), fullscreen immersive, screen kept on.
- Pairs once with a short code (same flow as the MyFlix TV app) and renews its Cloudflare Access cookie in the background.
- Grants the page the microphone (`RECORD_AUDIO`) for voice search and wake words. First use asks; or grant over adb:
  `adb shell pm grant <package> android.permission.RECORD_AUDIO`.
- JS bridge `window.EchoHub` (only the MyFlix host can reach it): `openDashboard()` and `showDoorbell()`.
- Only the MyFlix host is allowed to load; everything else is blocked. The Home Assistant view is a separate WebView
  limited to the configured Home Assistant host, destroyed when you leave it (the Echo has about 1 GB of RAM).

## Build

```sh
cd android
cp local.properties.example local.properties     # hostnames, optional Home Assistant URLs (gitignored)
./build_apk.sh            # signed release -> dist/EchoHub.apk (keystore password from the macOS Keychain)
./build_apk.sh test       # debug build "Echo Hub Test" (side by side) -> dist/EchoHub-test.apk
```

`ECHOHUB_DASH_URL` / `ECHOHUB_DOORBELL_URL` are optional. Leave them empty and the Room toggle and doorbell popup do nothing.
`local.properties`, `keystore/` and `dist/` are not committed.

## Install on the Echo (wireless adb)

Developer options -> Wireless debugging -> "Pair device with pairing code". Each pairing code is single use and the port changes,
so run `adb pair <ip>:<port> <code>` right away, then find the connect port with `adb mdns services` and
`adb connect <ip>:<port>`; `adb install -r dist/EchoHub.apk`. After an install or reboot the connect port changes again.

## Boot animation

`boot-animation/myflix-bootanimation.zip` is the MyFlix intro (960x480, 24 fps, silent) in Android `bootanimation.zip` format
(`desc.txt` + `part0` frames, stored not compressed). It replaces `/system/media/bootanimation.zip`, which is read-only:
flash it from TWRP or remount `/system` with root. Back up the original first. The first "Amazon" logo comes from the
bootloader `logo` partition and is not touched by this.

## Notes from the build

- Do not connect the Echo to its stock Amazon Wi-Fi flow before unlocking: an update can patch the exploit.
- Debug notifications chime at full volume; `adb shell cmd notification set_dnd alarms` silences them.

# Android app

## What it is

A WebView that shows a Mo server, plus a small Bluetooth bridge so the page can send print jobs to the printer. It works two ways, and you choose on first launch:

- Connect to a Mo server: the app shows your server (on a computer or NAS) and prints what it sends.
- Everything on this phone: the app runs the Mo server itself. It includes Python and Mo's own server code (the same `app/` and `scripts/` folders as the rest of this repo), downloads the cards you pick to the phone, and works with no server or internet after that.

The app's own logic is in [`MainActivity.kt`](app/src/main/java/app/momobile/MainActivity.kt) (about 750 lines) and, for the phone-only mode, [`mo_local.py`](app/src/main/python/mo_local.py) (about 60).

The server turns cards into printer bytes. The app's only job is to write those bytes to the printer over Bluetooth.

## Permissions

| Permission | Why |
| --- | --- |
| `BLUETOOTH_CONNECT` (Android 12+) | To connect to the paired printer. Android shows this as "Nearby devices". |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (Android 11 and older only) | The older equivalent of the above. |
| `INTERNET` | To load the Mo page from your server, and in phone-only mode to download cards. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK` | Only used if you tick "Keep downloading when I leave the app" (phone-only mode): lets the card download keep going while you use other apps, and keeps the phone from sleeping until it's done. Android grants these at install and no app can switch them off itself, so the app simply doesn't use them unless you tick the box. |
| `POST_NOTIFICATIONS` (Android 13+) | Only asked for if you tick "Show download progress in my notifications". You can tick "Turn the notification permission off again when the download is done" and the app hands it back to Android afterwards. |

The APK also lists `app.momobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. That one is added automatically by Google's AndroidX Core library and only lets the app talk to itself. It doesn't grant anything.

No location, contacts, camera, microphone, storage or phone permissions. It doesn't scan for Bluetooth devices either. It only uses printers you've already paired in Android's settings.

## What it stores

In the app's private settings:

- The server address you typed in, or that you chose the phone-only mode
- Which paired printer you picked (its Bluetooth address)
- Your background-download choices

In phone-only mode, also the cards you downloaded: up to about 1 GB if you download everything (tokens alone are about 75 MB). They go in the app's private storage, or in the app's own folder on an SD card if you pick that (no storage permission needed; Android gives every app its own folder there). Settings shows how much space they use, and can move or delete them.

Android backup is turned off, so none of it leaves your phone. Uninstalling the app deletes all of it, including the SD card folder.

## What it connects to

- Your Mo server, at the address you entered. The app only opens pages from that server; any link to another site opens in your normal browser instead, so the Bluetooth bridge is never handed to another website. (Mo's page itself doesn't load anything from other sites.)
- Your printer, over Bluetooth.
- In phone-only mode, only while downloading cards: [MTGJSON](https://mtgjson.com) for the card list and [Scryfall](https://scryfall.com) for the card images, the same sources as a Mo server uses. The built-in server only listens on the phone itself (`127.0.0.1`), so nothing else on your network can reach it.

That's it. No analytics, no crash reporting, no ads, no accounts, no update checks, and nothing is ever sent to me.

Plain `http://` is allowed because most people run Mo on their home network or over a VPN without a certificate. If you put your server behind HTTPS, use the `https://` address.

## Connect links

A server's setup page has a "Connect" button that opens a link like `momobile://connect?server=http://192.168.1.20:8420`. Any web page could make a link like that, so the app never switches on its own: it shows the address and asks first. Cancel and nothing changes.

## What the web page can do through the bridge

The page talks to the app through these functions, and only the page from your server gets them:

| Function | Does |
| --- | --- |
| `printBytes(data)` | Sends printer bytes to the paired printer |
| `status()` | Says whether the printer is connected, and its name |
| `choosePrinter()` | Opens the list of paired devices so you can pick the printer |
| `keepScreenOn(on)` | Keeps the screen on while cards download in phone-only mode |
| `openSettings()` | Opens the app's settings screen (the gear in the title bar) |
| `backgroundOptions()`, `setBackgroundOption(name, on)`, `downloadStarted()` | The background-download checkboxes on the setup page (phone-only mode) |

The settings gear in the title bar of Mo's first window is added by the app itself: when a page from your server finishes loading, the app runs a short script that puts the button there (`SETTINGS_BUTTON_JS` in `MainActivity.kt`), so it works with any Mo server. It calls `openSettings()`, which only opens the app's own settings screen.

The app's own welcome and settings screens are bundled in the app (`assets/`) and use two more, `MoWelcome` and `MoSettings`, which only work while one of those screens is showing.

## Libraries

Google's standard AndroidX libraries (`core-ktx`, `appcompat`, `activity-ktx`) and the Kotlin standard library. For the phone-only mode, [Chaquopy](https://chaquo.com/chaquopy/) (open source, MIT) provides Python 3.12, plus the same Python packages a Mo server uses: Flask, Werkzeug, Pillow, requests, ijson, segno and pyserial. No ads or tracking SDKs. That's why the app is about 30 MB, and why it needs a 64-bit phone.

## Verifying a download

Every release lists the SHA-256 of `MoMobile.apk`. Releases are signed with this certificate:

```
SHA-256: 54:ec:7e:ec:1a:3d:09:8d:cd:c0:af:0c:81:02:8a:82:e0:d6:1c:b2:cc:96:0b:6e:a9:db:4b:f0:cf:e6:3f:c7
```

To check an APK yourself (needs the Android SDK build tools):

```
apksigner verify --print-certs MoMobile.apk
```

Android also checks this on its own: an update only installs over the app if it's signed with the same certificate.

## Building it yourself

You don't have to trust my APK. Open this `android/` folder in Android Studio and press Run, or with JDK 17, the Android SDK and Python 3.12 installed:

```
./gradlew assembleDebug
```

Your build is signed with your own debug key, so to switch between mine and yours you'll need to uninstall first.

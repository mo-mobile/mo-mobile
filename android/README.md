# The Android app

## What it is

The app shows the Mo page and sends whatever you print to your Bluetooth printer. You pick one of two ways to use it the first time you open it:

- Connect to a Mo server. The app shows the Mo running on your computer or NAS and prints what it sends.
- Everything on this phone. The app runs Mo by itself. It has Python and Mo's server code built in (the same `app/` and `scripts/` folders as the rest of this repo), downloads the cards you pick, and after that works without a server or internet.

Mo makes the printer data. All the app does is send it to the printer over Bluetooth.

Most of the app is in [`MainActivity.kt`](app/src/main/java/app/momobile/MainActivity.kt). The phone-only part also uses [`mo_local.py`](app/src/main/python/mo_local.py) (about 60 lines) and [`DownloadService.kt`](app/src/main/java/app/momobile/DownloadService.kt) for downloading in the background.

## Permissions

| Permission | Why |
| --- | --- |
| `BLUETOOTH_CONNECT` (Android 12+) | To connect to your paired printer. Android calls this "Nearby devices". |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (Android 11 and older) | The older version of the one above. |
| `INTERNET` | To load Mo from your server, or to download cards in the phone-only mode. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK` | Only used if you tick "Keep downloading when I leave the app". They let the download keep going while you're in other apps and keep the phone from sleeping until it's done. Android gives these to the app when you install it and there's no way for an app to turn them off, so the app just doesn't use them unless you tick the box. |
| `POST_NOTIFICATIONS` (Android 13+) | Only asked for if you tick "Show download progress in my notifications". If you also tick "Turn the notification permission off again when the download is done", the app gives it back once it's finished. |

The app also lists `app.momobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Google's AndroidX library adds that one by itself. It only lets the app talk to itself and doesn't give it access to anything.

It doesn't ask for location, contacts, camera, microphone, storage or phone. It doesn't scan for Bluetooth devices either. It only uses printers you've already paired in Android's settings.

## What it saves

In the app's own settings:

- Your server address, or that you picked the phone-only mode
- Which paired printer you picked
- Your background download choices

In the phone-only mode it also saves the cards you download. That's up to about 1 GB if you get everything, and about 75 MB for just tokens. They go in the app's private storage, or in the app's own folder on your SD card if you pick that. Android gives every app its own folder on the SD card, so that doesn't need a storage permission. You can see how much space they take in Settings, and move or delete them there.

Android backup is off, so none of this leaves your phone. Uninstalling the app deletes all of it, including the folder on the SD card.

## What it connects to

- Your Mo server, at the address you gave it. The app only opens pages from that server. Links to other sites open in your normal browser, so no other website ever gets access to your printer. Mo itself doesn't load anything from other sites.
- Your printer, over Bluetooth.
- In the phone-only mode, only while downloading cards: [MTGJSON](https://mtgjson.com) for the card list and [Scryfall](https://scryfall.com) for the images, the same places a Mo server gets them from. If you download Top 8 decks it also gets the deck lists from [mtgtop8.com](https://www.mtgtop8.com). Downloading a missing card from Deck Print gets just that card from Scryfall. The Mo server inside the app only listens on the phone itself (`127.0.0.1`), so nothing else on your network can reach it.

There's no analytics, crash reporting, ads, accounts or update checks, and nothing ever gets sent to me.

The app allows plain `http://` because most people run Mo at home or over a VPN without a certificate. If your server has HTTPS, use the `https://` address.

## Connect links

A server's phone page has a Connect button that opens a link like `momobile://connect?server=http://192.168.1.20:8420`. Any website could make a link like that, so the app never switches by itself. It shows you the address and asks first. If you hit Cancel nothing changes.

## What the page can do in the app

The Mo page talks to the app through these, and only pages from your server get them:

| Function | What it does |
| --- | --- |
| `printBytes(data)` | Sends printer data to your printer |
| `status()` | Says if the printer is connected and what it's called |
| `choosePrinter()` | Opens the list of paired devices so you can pick the printer |
| `keepScreenOn(on)` | Keeps the screen on while cards download in the phone-only mode |
| `openSettings()` | Opens the app's settings (the gear in the title bar) |
| `backgroundOptions()`, `setBackgroundOption(name, on)`, `downloadStarted()` | The background download checkboxes on the setup page in the phone-only mode |

The gear in Mo's title bars, the back button on the setup page, and the dots under the windows you can swipe are added by the app, not by Mo. When a page from your server loads, the app runs a short script that adds them (`SETTINGS_BUTTON_JS` in `MainActivity.kt`), so they work with any Mo server. The gear only opens the app's own settings.

The welcome and settings screens are part of the app (in `assets/`). They use two more, `MoWelcome` and `MoSettings`, which only work while one of those screens is open.

## Libraries

Google's standard AndroidX libraries (`core-ktx`, `appcompat`, `activity-ktx`) and Kotlin. For the phone-only mode, [Chaquopy](https://chaquo.com/chaquopy/) (open source, MIT) adds Python 3.12 and the same Python packages a Mo server uses: Flask, Werkzeug, Pillow, requests, ijson, segno, pyserial and Beautiful Soup. No ad or tracking libraries. Python is why the app is about 30 MB and why it needs a 64-bit phone.

## Checking your download

Every release has the SHA-256 of `MoMobile.apk` so you can check your file matches. My releases are all signed with this certificate:

```
SHA-256: 54:ec:7e:ec:1a:3d:09:8d:cd:c0:af:0c:81:02:8a:82:e0:d6:1c:b2:cc:96:0b:6e:a9:db:4b:f0:cf:e6:3f:c7
```

To check it yourself (you need the Android SDK build tools):

```
apksigner verify --print-certs MoMobile.apk
```

Android checks this too. An update only installs over the app if it's signed with the same certificate.

## Building it yourself

You don't have to trust my APK. Open this `android/` folder in Android Studio and hit Run. Or, with JDK 17, the Android SDK and Python 3.12 installed:

```
./gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`. If you want it to open straight to your server, add `-PmoServerUrl=http://192.168.1.20:8420` with your own address.

Your build is signed with your own key, so you'll have to uninstall mine before installing yours, and the other way around.

To use another printer whose name the app doesn't recognize, add its name to `NAME_HINTS` in `MainActivity.kt`. To change the name or icon, edit `app_name` in `res/values/strings.xml`, or in Android Studio right-click `app` → New → Image Asset.

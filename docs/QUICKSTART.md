# Quick Start Guide

Two ways to run it:

- Just your phone. The app downloads the cards and does everything itself, even offline.
- Phone plus a computer. The computer keeps the cards and your phone prints from it.

## What you need

- An [iDPRT S1 printer](https://www.amazon.com/dp/B0D2H86KSN). Other printers would need their own driver.
- An Android phone, Android 8 or newer, 64-bit. iPhones won't work.
- For the computer way, a Windows, Mac or Linux computer on the same Wi-Fi.

All the cards together are about 1 GB. Tokens alone are about 75 MB. I haven't tested the Mac steps since I don't have one, so open an issue if something's off.

## Install the app

1. Download `MoMobile.apk` from the [latest release](https://github.com/mo-mobile/mo-mobile/releases/latest) on your phone and open it. Allow installs from your browser when Android asks. What the app can access is in [android/README.md](../android/README.md).
2. Turn the printer on and pair it in your phone's Bluetooth settings. It shows up as `S1-` and four characters.

   <img src="images/quickstart/06-pair-printer.png" alt="The S1 printer in Bluetooth settings" width="320">

3. Open Mo Mobile. Tap Allow when it asks about nearby devices, that's how it talks to the printer.

   <img src="images/quickstart/welcome.png" alt="The Mo Mobile welcome screen" width="300">

## Just your phone

Pick Everything on this phone, choose your cards and tap Download. Use Wi-Fi. Tokens take a couple minutes and everything takes about half an hour. Mo opens when it's done.

<img src="images/quickstart/card-picker.png" alt="Choosing which cards to download" width="320">

## Phone plus a computer

1. Install [Docker Desktop](https://www.docker.com/products/docker-desktop/) (Windows or Mac) and open it once. On Linux, run `curl -fsSL https://get.docker.com | sh`.
2. Start the server:
   - Windows: download `MoMobile-setup.bat` from the [latest release](https://github.com/mo-mobile/mo-mobile/releases/latest) and double-click it. If Windows warns you, click More info and then Run anyway. Click Yes when it asks about the firewall.
   - Mac or Linux: run `curl -fsSL https://raw.githubusercontent.com/mo-mobile/mo-mobile/main/setup/setup.sh | sh`
3. The setup page opens in your browser. Pick your cards, then scan the QR code with your phone and tap "Connect the app to this server".

<img src="images/quickstart/setup-page.png" alt="The setup page with download progress and a QR code" width="320"> <img src="images/quickstart/phone-page.png" alt="The phone setup page" width="320">

Run the setup again any time to update. It keeps your cards.

## Print something

Scroll down to Token Print, type `gob`, pick a Goblin and hit Print. For Momir, tap a number and hit Print Creature.

<img src="images/quickstart/09a-token-search.png" alt="Token search" width="260"> <img src="images/quickstart/09b-momir.png" alt="Momir number pad" width="260">

The gear in the Momir Vig title bar opens Settings, where you can add or delete cards, pick a printer, or switch servers. Swipe the top window for Stonehewer and Jhoira, and Deck Print for Top 8.

## Tips

- The S1 turns off after 15 minutes. You can make it 4 hours in the printer's app, HerePrint, under Settings → Standby time. Close HerePrint after, since only one app can connect to the printer at a time.
- Away from home your phone can't reach your computer. I use [Tailscale](https://tailscale.com) for that.
- The setup page is always at `http://<your computer's address>:8420/setup`.

## Troubleshooting

- Phone can't open the QR code page: make sure it's on the same Wi-Fi, not mobile data. Otherwise it's the computer's firewall. On Windows, run the setup again and click Yes, and make sure your Wi-Fi is set to Private.
- Setup says Docker isn't running: open Docker Desktop, wait for it to start, and run the setup again.
- Download stopped: start it again. It keeps what it already has.
- Nothing prints: make sure HerePrint or a computer isn't connected to the printer, and that it's charged.

To build the server from source instead, see [PHONE_PRINTING.md](PHONE_PRINTING.md).

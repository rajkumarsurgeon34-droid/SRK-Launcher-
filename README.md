# SRK Launcher

An iOS-style home screen for Android, part of the SRK family by ShazVerse Technologies.

## Features
- **Home Screen + Dock** – squircle icons, swipeable pages with page dots, frosted 4-app Dock, red notification badges
- **Edit mode** – long-press empty space: icons jiggle, drag to rearrange (drag to screen edge to change page, drop on Dock), tap ⊖ to remove
- **App Library** – swipe past the last page: auto-sorted folders (Suggestions, Recently Added, Social, Productivity, Education, Finance…), search and A–Z list
- **Search (Spotlight)** – swipe down in the middle of the Home Screen
- **Widgets** – Weather (free Open-Meteo, no key), analog Clock, Calendar (next event), Battery; Today view to the left of page 1
- **Control Centre** – swipe down from the top-right: Airplane, Mobile data, Wi-Fi, Bluetooth, media player, rotation lock, Do Not Disturb, brightness & volume sliders, Screen Mirroring, torch, timer, calculator, camera
- Swipe down from the top-left opens Android's notifications
- App shortcuts in the long-press menu (when set as default Home app)

## Install
Download `SRK-Launcher.apk` from **Releases**, install it, then choose **SRK Launcher** as your Home app.

Optional permissions (Settings button in edit mode): notification access (badges + media), modify system settings (brightness/rotation), Do Not Disturb access, location (weather), calendar.

## Build
Every push to `main` builds a signed APK with GitHub Actions and publishes it to Releases.

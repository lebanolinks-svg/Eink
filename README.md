# InkPhone

An Android app that makes a normal phone look and move like an e-ink device, to make it calmer to use and easier to put down. Everything on the phone keeps working as before.

## What it does

| Feature | Effect | Needs |
|---|---|---|
| **Master grey** | The whole phone in black and white. A ◐ button on the home screen turns it on and off. | Two switches in Android's Accessibility settings (guided), or one tap after the debug permission |
| **E-ink launcher** | Paper home screen with an app grid that turns pages like an e-reader. Icons as **Doodle** (each app's real icon redrawn as a pen sketch), **Grey** or **As is**. Long-press an app to pin, hide, limit or open its info. | Set InkPhone as the home app |
| **Build your home screen** | Switch each block on or off: clock (serif or handwritten), date, screen time, most used apps, search, grey button, app names. Choose 3 to 6 icons per row. | — |
| **App limits** | Minutes per day for any app. When they run out the app shows a "time's up" page with a way home and a 5-minute extension. | Usage access; "display over other apps" to lock from inside the app |
| **Widgets from other apps** | Put any widget (weather, calendar, music) on the paper home screen, shown in black and white if you like. Resize, reorder or remove them from the menu. | Android asks once to let InkPhone show widgets |
| **Widgets** | "Screen time" and "Most used apps" widgets for any launcher. | Usage access |
| **Paper wash** | Optional paper-grey film with grain over every app, a refresh flash on screen changes, and the display's slowest refresh rate. Off by default. | "Display over other apps" and usage access |

Android refuses taps on sign-in, payment, install and permission screens while any app draws over them. The paper wash therefore removes itself whenever Google sign-in, the Play Store, Settings, or an app that looks like a bank, wallet or authenticator is in front, and its notification has a pause button for anything it misses.

**Pricing.** Free to install. Everything is open for a 7-day trial; after that the home screen and Master grey stay free, and a one-time in-app purchase (`inkphone_unlock`) unlocks doodle and grey icons, app limits, widgets and the paper wash. Debug builds are always unlocked.

The app uses no accessibility service, reads no screen content and has no servers. The internet permission comes from the Google Play Billing library; InkPhone's own code makes no network calls.

**Settings that outlive the app.** Greyscale and "remove animations" are Android system settings. Uninstalling InkPhone does not revert them; the Advanced card has a "put my phone back" section with the steps. Removing animations is optional and kept in Advanced because it also freezes loading spinners in every app.

## Install

1. Copy `app-debug.apk` to the phone and open it (allow "install unknown apps" when asked).
2. Open **InkPhone**. It shows four cards; each works on its own:
   - **Master grey** — every app in black and white. Three guided taps in Android's colour settings.
   - **E-ink launcher** — the paper home screen, its icon look and its blocks.
   - **App limits** — daily minutes per app.
   - **Advanced** — the paper wash, screen time, and one-tap control for technical users.

One-tap Grey phone needs a one-time permission from a computer with USB debugging on:

```
adb shell pm grant app.inkphone android.permission.WRITE_SECURE_SETTINGS
```

## Limits

- Android does not let one app change how another app draws, so scrolling inside other apps is not slowed to true e-ink frame rates. The motion effect comes from removed animations, the refresh flash and the lowest refresh rate the panel offers (usually 60 Hz; lower on LTPO screens).
- The slow refresh-rate request is honoured by most phones but not all.
- Doodle icons apply on the InkPhone home screen. Icons drawn by other apps (recents, share sheets) stay as they are, in grey once greyscale is on.

## Build

Requires JDK 17+ and the Android SDK (platform 36).

```
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest  # doodle tests, and renders the menu to app/build/screens/settings.png
```

## Google Play

Listing text, declarations, privacy policy and graphics are in `docs/play-store/`.

```
./gradlew bundleRelease -PuploadKeystore=/path/upload.jks -PuploadStorePassword=... -PuploadKeyAlias=upload -PuploadKeyPassword=...
```

produces `app/build/outputs/bundle/release/app-release.aab`, signed with the upload key. The upload key is never stored in this repository.

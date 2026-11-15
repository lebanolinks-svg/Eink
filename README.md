# InkPhone

An Android app that makes a normal phone look and move like an e-ink device, to make it calmer to use and easier to put down. Everything on the phone keeps working as before.

## What it does

| Layer | Effect | Needs |
|---|---|---|
| **Home screen** | Paper-coloured launcher with a serif clock, today's screen time, and an app grid that turns pages like an e-reader instead of scrolling. Icons shown as **Doodle** (each app's real icon redrawn as a pen sketch), **Ink** (high-contrast grey) or **Original**. Long-press an app to pin, hide or open its info. | Set InkPhone as the home app |
| **Paper screen layer** | Over every app: a warm paper tint with grain, a black/white "page refresh" flash when the screen changes, and a request for the display's slowest refresh rate. | "Display over other apps" permission (flash also needs usage access) |
| **Greyscale + no animations** | The whole phone in black and white, with window and transition animations removed. | Two switches in Android's Accessibility settings; the app guides you there and ticks each when done |
| **Screen time** | "On the phone today: 1h 20m" on the home screen. | Usage access |

While the layer is on, Android shows a small permanent "Paper screen layer is on" notification; that is what keeps the layer alive.

The screen layer is a see-through overlay that never receives touches. The app uses no accessibility service, reads no screen content and has no internet permission.

## Install

1. Copy `app-debug.apk` to the phone and open it (allow "install unknown apps" when asked).
2. Open **InkPhone** and follow the four sections top to bottom.
3. If Android greys out "display over other apps" for a sideloaded app: **App info → ⋮ → Allow restricted settings**, then try again.
4. For greyscale and animations, section 3 of the app opens the right Android settings screen and shows a tick once each is on. No computer needed.

Technical users can instead let InkPhone flip both switches itself by granting a one-time permission from a computer with USB debugging on:

```
adb shell pm grant app.inkphone android.permission.WRITE_SECURE_SETTINGS
```

## Limits

- Android does not let one app change how another app draws, so scrolling inside other apps is not slowed to true e-ink frame rates. The motion effect comes from removed animations, the refresh flash and the lowest refresh rate the panel offers (usually 60 Hz; lower on LTPO screens).
- The slow refresh-rate request is honoured by most phones but not all.
- Doodle icons apply on the InkPhone home screen. Icons drawn by other apps (recents, share sheets) stay as they are, in grey once greyscale is on.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest  # doodle renderer tests; writes app/build/doodle-preview.ppm
```

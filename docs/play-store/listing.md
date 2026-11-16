# Google Play listing for InkPhone

Text to paste into Play Console. Character limits are Google's.

## App name (max 30)

InkPhone: E-Ink Look & Limits

## Short description (max 80)

Turn your phone grey, calm and paper-like. Hand-drawn icons, app time limits.

## Full description (max 4000)

Your phone is built to hold your attention. InkPhone makes it quieter.

InkPhone gives any Android phone the calm look of an e-ink reader: a paper-coloured home screen, hand-drawn icons, black-and-white apps, and firm limits on the apps that eat your day. Everything on your phone keeps working. It simply stops shouting.

MASTER GREY
Colour is what makes feeds hard to put down. InkPhone guides you through switching the whole phone to black and white in three taps, and gives you a button on the home screen to get back to it.

E-INK HOME SCREEN
• A paper page instead of a wall of colour
• Every app icon redrawn as a pen sketch, or shown in grey, or left as it is
• Apps turn pages like an e-reader instead of scrolling without end
• Pin the apps you need, hide the ones that tempt you

BUILD IT YOUR WAY
• Choose what the home screen shows: clock, date, screen time, most used apps, search
• 3 to 6 icons per row, with or without names
• Add widgets from your other apps, shown in black and white to match

APP LIMITS
Give any app a number of minutes per day. When they run out, InkPhone covers the app with a "time's up" page until tomorrow.

SCREEN TIME AT A GLANCE
See how long you have been on the phone today, and which apps took the time, on the home screen or as widgets on any launcher.

PRIVATE BY DESIGN
InkPhone has no internet permission. It cannot send anything off your phone. No account, no ads, no tracking.

GOOD TO KNOW
• Android does not allow any app to switch the system greyscale setting by itself. InkPhone takes you straight to the right screen; you flip the switch once.
• App limits and screen time need Android's "usage access" permission. Locking an app from the inside also needs "display over other apps".
• InkPhone changes how your phone looks and is used. It does not change the screen hardware.

## Category

Tools (alternative: Productivity)

## Tags

Digital wellbeing, launcher, screen time, minimalist

## Contact details

Email: [CONTACT EMAIL]
Website: [OPTIONAL]
Privacy policy URL: [URL WHERE privacy-policy.md IS HOSTED]

---

# Play Console declarations

## Data safety

- Does your app collect or share any of the required user data types? **No**
- Is all user data encrypted in transit? Not applicable (no data leaves the device)
- Do you provide a way for users to request data deletion? Not applicable (no data collected)

## Ads

Contains ads: **No**

## Content rating questionnaire

Category: Utility, Productivity, Communication or Other. No violence, sexuality, language, controlled substances, gambling or user interaction. Expected rating: Everyone / PEGI 3.

## Target audience

18 and over (recommended: keeps the app out of the Families programme and its extra requirements).

## Foreground service declaration (special use)

Google asks for a description and a short video.

Description to paste:

> InkPhone runs a foreground service of type "special use" for two user-enabled features. (1) App limits: the user sets a daily time limit for an app; the service watches which app is in the foreground and shows a full-screen "time's up" page over that app when the limit is reached. (2) Paper wash: an optional translucent paper-coloured overlay over the screen. Both must keep running while the user is in other apps, and stop as soon as the user turns the features off. A persistent notification is shown while the service runs.

Video to record (about 30 seconds): open InkPhone menu → App limits → add a 5-minute limit to an app → open that app → show the "time's up" page and the ongoing notification.

## Permissions likely to be questioned in review

| Permission | Justification to give |
|---|---|
| PACKAGE_USAGE_STATS | Core feature: screen-time display, most-used apps, and enforcing daily app limits the user sets. Data stays on the device. |
| SYSTEM_ALERT_WINDOW | Shows the "time's up" page over an app whose user-set limit has run out, and the optional paper wash. |
| RECEIVE_BOOT_COMPLETED | Restarts app limits after a reboot so limits cannot be bypassed by restarting the phone. |
| WRITE_SECURE_SETTINGS | Cannot be granted through Play; only works if the user grants it over ADB, to let the app toggle greyscale. |

In-app disclosure: before sending the user to grant usage access, the app already explains what it is used for. Reviewers check for this.

## Graphics in this folder

- `icon-512.png`: app icon, 512 × 512
- `feature-graphic.png`: 1024 × 500
- Screenshots: take at least 2 (ideally 4 to 6) on a real phone: home screen with doodle icons, the menu, the "time's up" page, a grey app. The renders from the build are not suitable because they contain no real apps.

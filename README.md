# Isle — a dynamic-island overlay for Android

A floating pill at the top of your screen (like iPhone's Dynamic Island) with:

| Feature | How it works |
|---|---|
| **Reel / Shorts counter** | Accessibility service detects swipes inside Instagram Reels and YouTube Shorts, counts them live, tracks minutes spent, and pops reminders every N reels / N minutes (messages escalate the longer you scroll). Tap **+ / −** in the panel to correct a miscount. |
| **Focus timer (Pomodoro)** | Start / pause / reset / skip. Auto-starts the break, counts completed sessions per day, shows your focus goal. |
| **Music controls** | Track title + prev / play-pause / next for whatever app is playing (Spotify, YT Music, etc.). |
| **Health reminders** | 20-20-20 eye break, water, stretch — toggle each from the panel, set intervals in the app. |
| **Misc** | Clock, battery %, flashlight toggle, drag anywhere (position is remembered), tap outside to collapse. |

## Build & install

1. Open the `Isle` folder in **Android Studio** (Koala or newer) and let Gradle sync. Accept any "update AGP / Gradle" prompt.
2. Plug in your phone (USB debugging on) and press **Run**, or use *Build → Build APK(s)*.
3. Open **Isle** and grant, in order:
   - **Display over other apps**
   - **Accessibility service** (for the reel counter)
   - **Notification access** (for track title / music control)
4. Tap **Start island**. Drag it over your camera hole if you like.

> **Switches greyed out?** Android 13+ blocks accessibility / notification access for sideloaded apps.
> Tap the "Switch greyed out?" card in the app → ⋮ menu → **Allow restricted settings**, then retry.

## Reel detection may need tuning

Instagram and YouTube rename internal view IDs between updates, and I could not test against the live apps.
Detection rules live in `ReelConfig.kt`. If the counter doesn't move:

```
adb logcat -s IsleReels
```

Scroll some reels. When no rule matches, the log prints candidate view IDs
(anything containing clip / reel / short / pager / viewer). Paste the right one into `ReelConfig.VIEW_IDS`.

To add another app, add its package name and view IDs to `ReelConfig`.

## Privacy

The accessibility service only checks which app is in front and whether a reels/shorts player is on screen.
It does not read, store or send any text, messages or content. There is no networking code and no internet permission.

## Project layout

```
app/src/main/java/com/example/isle/
  OverlayService.kt            the pill UI, timer, reminders, music, torch
  ReelAccessibilityService.kt  detects reels screens + counts swipes
  ReelConfig.kt                packages + view IDs to recognise (tune here)
  Store.kt                     shared state, settings, daily stats
  MainActivity.kt              setup + settings screen
  MediaListener.kt             empty NotificationListener (unlocks media sessions)
```

## Ideas for next steps

- Start automatically on boot
- Hard "lock" screen after a reel limit (overlay that blocks the app for a few minutes)
- Weekly stats chart
- Smooth expand animation by switching the island to a fixed-size window

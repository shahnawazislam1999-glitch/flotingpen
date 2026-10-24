# FloatingPen

Native Kotlin Android app. The smart pen writes on top of every app; your fingers keep using the apps.
Made for the Xiaomi Pad 6 + Xiaomi Smart Pen (2nd gen).

## Build the APK on GitHub

1. Push this whole folder to the `main` branch of a GitHub repository.
2. Actions tab > **Build APK** (also runs on every push). Download the artifact **flotingPen-debug-apk**.

## First run

1. Open the app, tap **Accessibility service**, enable FloatingPen
   (if greyed out: Settings > Apps > FloatingPen > ⋮ > Allow restricted settings).
2. Allow **Notifications** (for the Turn off button).
3. Switch **Writing pad** on (or use the Quick Settings tile). A banner says "Pen layer on".

## Use

* Pen = writes. Finger = normal work in your apps (open, close, scroll).
* Pen side button (Page Up / Page Down) = clear the ink. Notification buttons: **Clear**, **Turn off**.
* Power button (screen off) = the pad turns off (can be disabled in the app).

## How it works

* **Pen capture mode** (default, Android 14+): the service asks Android for the pen's events
  (`setMotionEventSources`). Android then gives pen events only to the service, never to the app below,
  and the overlay window is never touchable, so finger touches are never affected.
* **Window mode** (fallback; automatic on Android 13 and older): the layer catches touches, the pen
  writes, finger taps / swipes are re-played onto the app underneath.
* The first time the pen is seen a banner shows its tool type and source. Use **Pen test** in the app
  (with the pad off) to see exactly what Android reports for the pen.
* Ink lives only in memory; clearing frees it.

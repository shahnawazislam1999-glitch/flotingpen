# FloatingPen

Native Kotlin Android app that puts a transparent writing layer over every app. Write with the
Xiaomi smart pen; your fingers keep using the apps underneath. Ported from the writing pad in
`mock-Scrolling-3.html` (same Nirmal / Classic / Pencil pen feel, scrub erase, lasso erase,
two-finger floating canvas, annotation pen).

## Build the APK on GitHub

1. Create a new GitHub repository and push this whole folder to the `main` branch.
2. Open the **Actions** tab, run **Build APK** (it also runs on every push).
3. When it finishes, download the artifact **flotingPen-debug-apk** and install the `.apk`.
   (Push a tag like `v1.0.0` to also get a GitHub Release with the APK attached.)

No Gradle wrapper is needed: the workflow installs Gradle 8.7, JDK 17 and the Android SDK itself.
Opening the folder in Android Studio also works (it creates the wrapper for you).

## First run

1. Open the app and tap **Grant all permissions**, one screen at a time:
   accessibility service, display over other apps, storage / directory access, notifications.
2. If Android greys out the accessibility switch: **Settings > Apps > FloatingPen > ⋮ > Allow restricted settings**, then enable it again.
3. Turn on **Writing pad** in the app, or use the **Writing pad** tile in Quick Settings
   (drag it into the tile panel once). No button is ever drawn over the screen.

## Gestures (while the pen is active)

| Action | What happens |
| --- | --- |
| Pen | Write |
| Finger scribble | Erase (only the part you scrub over) |
| One-finger tap | Switch red / blue |
| One-finger hold 3 s | Annotation pen on / off (glows, fades after 1 s) |
| Two-finger tap | Clear everything instantly (ink, annotations, white box) |
| Two fingers drag | Float the canvas in any direction |
| Two fingers hold 3 s | White writing box. Handles: blue = rotate, amber = resize, grey = move. Hold again to hide |
| Pen lower button | Lasso erase: circle the ink, it is erased at once |
| Pen upper button | Slide the canvas back to its normal position |

When the pen has been idle for the "gesture window" (default 4 s, adjustable) the overlay lets
touches through again, so fingers scroll and tap the apps below normally.

## Modes (Settings in the app)

* **Smart pen mode** (default): only the pen writes.
* **Finger mode**: fingers and pen both write. Apps below cannot be touched while the pad is on.
* **Compatibility mode**: for Android 13 and older, or if pen strokes do not appear. The overlay always
  catches touches and finger taps/swipes are re-played onto the app below (scrolling is applied when
  the finger lifts).

## How it works

* Overlay: an accessibility overlay window (`TYPE_ACCESSIBILITY_OVERLAY`). The service does not read any screen content.
* Pen buttons: the Xiaomi pen sends Page Up / Page Down (also Up/Down/Left/Right) keys; the service intercepts them while the pad is on.
* Android 14+: stylus events are observed system-wide, and the window only becomes touchable while the pen is near.
* Memory: strokes are packed float arrays; one screen-size bitmap cache exists only while ink exists.
  Clearing recycles it immediately. Ink is never written to storage.
* Permissions declared: overlay, vibrate, notifications, read/write external storage, all-files access, media images.

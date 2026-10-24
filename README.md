# FloatingPen v2

Native Kotlin Android app. The smart pen writes on top of every app; your fingers keep using the apps.
Made for the Xiaomi Pad 6 + Xiaomi Smart Pen (2nd gen).

## Build the APK on GitHub

1. Create a GitHub repository and push this whole folder to its `main` branch.
2. In the repo: **Settings > Actions > General > Workflow permissions > Read and write permissions** (the workflow itself already asks for `write-all`).
3. **Actions** tab > *Build APK (v2)* (runs on every push, or press *Run workflow*). Download the artifact **FloatingPen-v2-apk**
   (`FloatingPen-v2-debug.apk`). Push a tag like `v2` to also get a GitHub Release with the APK attached.

## First run

1. Open the app, tap **Accessibility service**, enable FloatingPen
   (if greyed out: Settings > Apps > FloatingPen > ⋮ > Allow restricted settings).
2. Allow **Notifications**.
3. Switch **Writing pad** on (or use the Quick Settings tile / the bubble menu).

## Pen modes (home screen, bubble menu, overlay)

Nirmal Pen (smooth, no lag) - Classic Pen - Pencil (graphite grain) - Annotation Pen (glow, fades after 1 s).
Size, opacity and colour are shared by the home screen, the bubble menu and the overlay.
The home screen has a practice pad that uses the same engine.

## Smart pen buttons (writing pad on)

| Button | Action |
|---|---|
| Down | Writing -> **stroke eraser** (pen touching a stroke erases it). Press again -> writing |
| Up, 1 press | **Lasso**: circle strokes, they are erased when you lift the pen |
| Up, 2 quick presses | **Clear screen**, back to writing |
| Down while in lasso | Back to writing at once |
| Nib held still 4 s | Red <-> blue |
| Quick pen tap, no writing | Clicks the app below (switch off in Advanced if you want dots) |

A small ERASER / LASSO label shows at the top while those tools are active.

## Floating bubble

A tiny dot (5 % visible by default) floats over the apps. Finger-tap it: pen type, write / erase / lasso, colour, size,
opacity, undo, **Erase screen** (one tap), pen layer on/off. Drag it to move. Change its visibility in the app
(it also shows brightly for ~2.5 s whenever the service starts, so you can see where it is).

## How it works

* **Pen capture mode** (default, Android 14+): the service asks Android for the pen's events (`setMotionEventSources`).
  Pen events go only to the service, the overlay window is never touchable, so finger touches are never affected.
* **Window mode** (fallback; automatic on Android 13 and older): the layer catches touches, the pen writes,
  finger taps / swipes are re-played onto the app underneath.
* Strokes are vectors: the stroke being written is drawn straight on the GPU canvas, the finished stroke is baked
  into one bitmap when the pen lifts. Input is stabilised with pressure/speed width and soft start/end tapers
  (same maths as the HTML reference).
* Ink lives only in memory; clearing frees it.

# AR Touchpad

An Android app that turns your phone's touchscreen into a trackpad for controlling **Android desktop mode** on [Viture XR Pro](https://www.viture.com/) glasses (or any USB-C DisplayPort display running Android's extended desktop).

## How it works

The glasses connect via USB-C DisplayPort and appear as a second display in Android's desktop mode. The app runs an **Accessibility Service** that draws a cursor on that display and performs everything you do on the phone as touch gestures there. Nothing else is needed: **no ADB, no Wireless/USB Debugging, no Shizuku, no root, no Developer Options** — so apps that refuse to run while debugging is enabled (banking apps, etc.) keep working.

- **Single-finger drag** — moves the cursor
- **Tap** — tap/click at the cursor
- **Double tap** — double tap at the cursor
- **Long press (600 ms)** — long press at the cursor (context menu, the touch equivalent of a right click)
- **Long press, then drag** — drag with the finger held (text selection; the selection is copied to the clipboard on release)
- **Two-finger drag** — scroll
- **Two-finger pinch** — pinch zoom
- **⌨ Keys** — type on the phone, insert the text into the focused field on the glasses
- **Bottom nav bar** — Back / Home / Recents

## Requirements

| Requirement | Details |
|---|---|
| Android | 14+ (minSdk 34) |
| Accessibility Service | Must be enabled in Settings → Accessibility → AR Touchpad |
| Display | Glasses connected in **Desktop / Extended** mode (not Mirror) |

Tested on **Pixel 10 + Viture XR Pro** running Android 16.

## Setup

### 1. Install AR Touchpad
Build and install the debug APK, or download a release:

```bash
git clone https://github.com/pgratz1/AR-Touchpad.git
cd AR-Touchpad
./gradlew installDebug
```

### 2. Enable the Accessibility Service
Open the app and tap **Enable Service**, or go to **Settings → Accessibility → AR Touchpad** and turn it on.

### 3. Connect the glasses
Plug in the Viture XR Pro. Pull down the notification shade and switch to **Desktop / Extended** mode (not Mirror). The app's settings panel lists all detected displays — the glasses should appear as a second display, and a cursor appears on them.

## Architecture

```
app/
└── src/main/java/…/
    ├── TouchpadAccessibilityService.kt — The input backend: cursor overlay on the glasses
    │                                     display, gesture dispatch, text insertion, nav actions
    ├── TouchpadViewModel.kt            — State, display detection, smoothing, gesture dispatch
    ├── MainActivity.kt                 — Single-activity host
    └── ui/TouchpadScreen.kt            — Compose UI: touch surface, status bar, keyboard proxy
```

**Cursor:** a `TYPE_ACCESSIBILITY_OVERLAY` window is attached to the external display through a display-specific window context; the service draws the pointer there and hides it after a few seconds of inactivity.

**Gestures:** every action is a `dispatchGesture()` call targeted at the external display with `GestureDescription.Builder.setDisplayId()`. Scrolls, pinches and long-press drags are continuous strokes chained with `StrokeDescription.continueStroke()`, so the content on the glasses follows the phone fingers in real time.

**Text:** the phone-side keyboard strip accumulates text; on send it is inserted into the input-focused editable node of the glasses display (paste first, `ACTION_SET_TEXT` as fallback) followed by `ACTION_IME_ENTER`.

**Navigation:** Back / Home / Recents use `performGlobalAction()`.

### Trade-offs versus a real mouse

Earlier versions created a hardware-like mouse through `/dev/uinput`, which required Shizuku and therefore Wireless Debugging. That is gone. The accessibility route cannot inject raw key or mouse events, so:

- "right click" is a touch long press (works wherever an app offers a long-press context menu);
- there is no Ctrl+scroll / keyboard shortcut injection; zoom is a touch pinch instead;
- there is no DeX-style direct typing (that needed a privileged window-manager call); the keyboard strip is the typing path.

## Building from source

Requires Android Studio (for the SDK). No NDK is needed any more.

```bash
# Debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

## License

Copyright 2026 Paul Gratz

Licensed under the [Apache License, Version 2.0](LICENSE).

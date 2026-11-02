# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: AR Touchpad

An Android app that turns a phone's touchscreen into a trackpad for Android desktop mode on Viture XR Pro glasses (or any USB-C DisplayPort display). Tested on Pixel 10 + Viture XR Pro running Android 16.

- **Package:** `com.pgratz.artouchpad`
- **Min SDK:** 34 (Android 14)
- **Language:** Kotlin only (no native code)
- **UI:** Jetpack Compose + Material3

The app deliberately depends on **nothing privileged**: no ADB / Wireless Debugging, no Shizuku, no root, no hidden APIs. Everything goes through the public `AccessibilityService` API. Keep it that way — enabling debugging on the phone breaks banking apps for the user, which is why the earlier uinput/Shizuku backend was removed.

## Build Commands

Run from the project root (where `gradlew` lives):

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (requires signing keys in local.properties)
./gradlew assembleRelease

# Install debug APK on connected device
./gradlew installDebug

# Lint
./gradlew lint

# Clean
./gradlew clean
```

There are no unit or instrumented tests (`src/test` and `src/androidTest` don't exist); verification is manual on device.

## Setup Requirements

- Android Studio (provides SDK and Gradle); no NDK needed
- `local.properties` at project root with `sdk.dir=<path to Android SDK>`
- For release builds, add signing keys to `local.properties`: `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

Runtime on device:
- Accessibility Service enabled in Settings → Accessibility → AR Touchpad
- Glasses connected in Desktop / Extended mode

## Architecture

Single process. The accessibility service is the only input backend.

```
app/src/main/
  java/…/
    TouchpadAccessibilityService.kt  — Cursor overlay on the external display, gesture dispatch,
                                        text insertion, global nav actions, text-focus detection
    TouchpadViewModel.kt             — State, display detection, smoothing filter, delegates to the service
    MainActivity.kt                  — Single-activity host
    ui/TouchpadScreen.kt             — Compose UI: status bar, touch surface, keyboard proxy, nav bar
  res/xml/accessibility_service_config.xml — canPerformGestures, canRetrieveWindowContent, window flags
```

### Data flow

1. `TouchpadSurface` (Compose) intercepts raw pointer events and classifies them into gestures (tap, double tap, long press, long-press drag, two-finger scroll or pinch — the two-finger mode is locked per contact).
2. Gesture callbacks hit `TouchpadViewModel`, which applies velocity-adaptive exponential smoothing to cursor movement and maintains `TouchpadState` via `StateFlow`.
3. `TouchpadViewModel` calls `TouchpadAccessibilityService.instance` directly (same process, main thread).
4. The service moves the overlay cursor and dispatches gestures on the target display.

### Key design constraints

**Cursor overlay:** `attachOverlay()` builds a window context for the external display (`createDisplayContext(display).createWindowContext(TYPE_ACCESSIBILITY_OVERLAY, null)`) and adds a full-screen, `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` view that draws the pointer. Not-touchable is essential so dispatched gestures pass through. The overlay is re-attached whenever the target display changes and the cursor hides after 4 s idle.

**Gesture targeting:** every `GestureDescription` sets `setDisplayId(targetDisplayId)` (public since API 30). Coordinates are display pixels of that display.

**Continuous gestures:** `DragSession` holds one or two synthetic fingers down and chains `StrokeDescription.continueStroke()` segments (`DRAG_SEGMENT_MS` each). Only one continuation is in flight at a time; movement arriving meanwhile is coalesced into the next segment. `end()` finishes with a short stationary stroke (`LIFT_HOLD_MS`) and `willContinue=false`, so lifting does not fling. A scroll re-plants its finger at the cursor once it has travelled 35 % of the display's shorter side so it never runs off the edge. A pinch is two fingers centred on the cursor, horizontal, spanning `pinchSpan`.

**Long press / selection:** when the phone-side long press fires (600 ms), `beginPress()` touches down at the cursor and holds `PRESS_HOLD_MS` (> the system 400 ms long-press timeout). Releasing without moving ends the press → long press on the glasses (context menu). Moving first drags the held finger → selection/drag; release then runs `ACTION_COPY` on the focused node.

**Text input:** a field focused on the glasses (`TYPE_VIEW_FOCUSED` with `window.displayId != DEFAULT_DISPLAY`) opens the phone-side `EditText` strip. On send, `typeText()` finds the input-focused editable node among `windowsOnAllDisplays[targetDisplayId]`, tries `ACTION_PASTE` (honours caret) then `ACTION_SET_TEXT` (append), restores the previous clipboard, and `pressImeEnter()` fires `ACTION_IME_ENTER`. There is no key injection of any kind.

**Navigation:** Back/Home/Recents are `performGlobalAction()`; they act on the focused display.

**Settings persistence:** `sensitivity` (default 0.5), `scrollSpeed`, and `naturalScroll` live in SharedPreferences `touchpad_prefs`, loaded into the initial `TouchpadState` and written in the ViewModel setters. With natural scroll off, the glasses finger moves opposite to the phone fingers (traditional trackpad); on, it follows them.

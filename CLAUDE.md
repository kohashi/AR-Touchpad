# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: AR Touchpad

An Android app that turns a phone's touchscreen into a trackpad for Android desktop mode on Viture XR Pro glasses (or any USB-C DisplayPort display). Tested on Pixel 10 + Viture XR Pro running Android 16.

- **Package:** `com.pgratz.artouchpad`
- **Min SDK:** 34 (Android 14), target arm64-v8a only
- **Language:** Kotlin + C++ (JNI)
- **UI:** Jetpack Compose + Material3

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

The NDK CMake build compiles `libartouchpad.so` automatically alongside the Kotlin build.

There are no unit or instrumented tests (`src/test` and `src/androidTest` don't exist); verification is manual on device.

## Setup Requirements

- Android Studio (provides SDK, NDK, Gradle)
- NDK r25+ (installed via Android Studio SDK Manager)
- `local.properties` at project root with `sdk.dir=/home/pgratz/Android/Sdk`
- For release builds, add signing keys to `local.properties`: `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

Runtime on device:
- Accessibility Service enabled in Settings → Accessibility → AR Touchpad (required — enough on its own to run the touchpad)
- [Shizuku](https://shizuku.rikka.app/) running (optional upgrade: grants shell uid = `input` group = `/dev/uinput` access for the full-fidelity uinput backend)

## Architecture

The app has two processes: the normal app process and a Shizuku UserService process (shell uid).

```
app/src/main/
  aidl/…/IMouseService.aidl         — Binder interface between app and Shizuku service
  cpp/uinput_jni.cpp                 — JNI: opens /dev/uinput, calls ioctl, writes input_events
  java/…/
    MouseService.kt                  — Shizuku UserService (shell uid): owns the uinput fd
    ShizukuMouseController.kt        — App-side: binds/unbinds MouseService, rate-limits IPC to ~60 Hz
    TouchpadViewModel.kt             — State, display detection, backend selection, gesture dispatch, smoothing filter
    UinputNative.kt                  — Kotlin object declaring external JNI functions
    TouchpadAccessibilityService.kt  — Accessibility backend: dispatchGesture injection + software
                                       cursor overlay; also nav bar actions and external text focus
    ui/TouchpadScreen.kt             — Compose UI: status bar, touch surface, keyboard proxy, nav bar
```

### Input backends

`TouchpadViewModel.refresh()` picks an `InputBackend` each time state changes: `SHIZUKU` when the
Shizuku UserService is bound (best fidelity), else `ACCESSIBILITY` when the accessibility service
is enabled, else `NONE`. Every input operation (`moveCursor`, clicks, scroll, pinch, select drag,
`pressKey`, `typeText`) routes through this selection, so the touchpad works with only the
accessibility permission — no wireless debugging.

The accessibility backend (`TouchpadAccessibilityService`):
- Injects taps/long-presses/drags with `dispatchGesture`, targeted at the glasses via
  `GestureDescription.Builder.setDisplayId` (public API since 30).
- Draws a software cursor as a `TYPE_ACCESSIBILITY_OVERLAY` window created through a window
  context on the external display (no SYSTEM_ALERT_WINDOW needed). Position updates are
  coalesced to ~120 Hz.
- Serializes all gestures through a queue: `dispatchGesture` cancels any in-flight gesture, and
  continued strokes (`StrokeDescription.willContinue`) — used for two-finger scrolling
  (content-drag with edge re-anchoring) and long-press select drags — die if an unrelated
  gesture is dispatched while the synthetic finger is down.
- Scroll sign/scale: ViewModel converts wheel-style deltas to content-drag pixels
  (`A11Y_SCROLL_PX_PER_UNIT`, negated — see comment in `performScroll`).
- Text is injected with `ACTION_SET_TEXT` (append) + `ACTION_IME_ENTER` on the input-focused
  node (searched via `windowsOnAllDisplays` on the glasses display first); select-drag
  auto-copy uses `ACTION_COPY`. Nav keys map to `performGlobalAction`.
- No shell access → `setDisplayImePolicy` is unavailable; `dexKeyboardActive` is forced false
  and the keyboard proxy strip is the text path.

### Data flow

1. `TouchpadSurface` (Compose) intercepts raw pointer events and classifies them into gestures (tap, drag, two-finger scroll/pinch, long-press-drag for text select).
2. Gesture callbacks hit `TouchpadViewModel`, which applies velocity-adaptive exponential smoothing to cursor movement and maintains `TouchpadState` via `StateFlow`.
3. `TouchpadViewModel` delegates to `ShizukuMouseController`, which rate-limits IPC to ~60 Hz (accumulating deltas between frames) and calls through to `IMouseService`.
4. `MouseService` (shell uid) either writes `input_event` structs to `/dev/uinput` (for cursor/scroll/button) or uses `InputManagerGlobal.injectInputEvent` reflection (for key events and Ctrl+scroll zoom).

### Key design constraints

**JNI for uinput:** Android 16 removed the generic `Os.ioctl(fd, req, value)` variant. The JNI bridge in `uinput_jni.cpp` is the only reliable way to call `ioctl` with an arbitrary value argument for `UI_SET_EVBIT`, `UI_SET_RELBIT`, etc.

**Reflection for key injection:** `InputManagerGlobal.injectInputEvent` and `InputEvent.setDisplayId` are hidden APIs. They're accessed via reflection, gated by HiddenApiBypass library, and lint suppression is set in `build.gradle.kts` (`BlockedPrivateApi`).

**Shizuku UserService versioning:** `userServiceArgs.version(17)` in `ShizukuMouseController` is bumped whenever the `IMouseService.aidl` interface changes (adding methods). AIDL method IDs are explicit integers — new methods must append to avoid breaking existing bindings. `destroy() = 16777114` is Shizuku's reserved destroy transaction ID; never renumber it.

**Cursor-to-display association:** `MouseService.setDisplay()` pins the uinput device to the target display via `InputManagerGlobal.setInputDeviceDisplayAssociation` (reflection, with a fallback through the raw `IInputManager` binder in the `mIm` field). Without this, Android may route the virtual mouse to the phone's display. The device is found by scanning `InputDevice`s for the name `"AR Touchpad Mouse"` after a 400 ms registration delay.

**Two-finger gesture disambiguation:** `TouchpadSurface` compares `|dSpan|` vs `|dx| + |dy|` each frame to decide pinch vs. scroll. Pinch threshold crossing triggers `Ctrl+scroll` MotionEvents (200 px per detent) for content-level zoom in Chrome/WebView.

**Text selection flow:** Long-press (600 ms, haptic) → drag moves cursor with `BTN_LEFT` held → release calls `mouseUp()` + `pressKeyWithCtrl(KEYCODE_C)` to auto-copy. A second finger during select drag cancels selection (`onSelectEnd()`).

**Text input (two paths):** Primary is DeX-style direct typing: `MouseService.setImePolicy` calls `IWindowManager.setDisplayImePolicy(displayId, FALLBACK)` via reflection (shell uid holds the required `INTERNAL_SYSTEM_WINDOW` permission; there is no `wm` shell subcommand for this), so a field focused on the glasses shows Gboard on the phone while the InputConnection stays with the field — every keystroke lands directly. Applied in `TouchpadViewModel.applyImePolicy` when the external display is detected, gated by the persisted `dexKeyboardEnabled` preference, restored to `local` in `onCleared()`. `dexKeyboardActive` in state reflects whether the call actually worked on this build. Fallback (when the command is unsupported or the toggle is off): the keyboard proxy — an editable-field focus on the glasses (detected via `AccessibilityEvent.TYPE_VIEW_FOCUSED` with `window.displayId != DEFAULT_DISPLAY`) shows a phone-side `EditText` strip; text accumulates on the phone, then is injected via `typeText` + `pressKey(ENTER)` after a 200 ms IME teardown delay, with a delayed BACK press dismissing the glasses-side IME.

**Settings persistence:** `sensitivity` (default 0.5), `scrollSpeed`, `naturalScroll`, and `dexKeyboardEnabled` live in SharedPreferences `touchpad_prefs`, loaded into the initial `TouchpadState` and written in the ViewModel setters.

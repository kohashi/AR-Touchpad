# AR Touchpad

An Android app that turns your phone's touchscreen into a trackpad for controlling **Android desktop mode** on [Viture XR Pro](https://www.viture.com/) glasses (or any USB-C DisplayPort display running Android's extended desktop).

## How it works

The glasses connect via USB-C DisplayPort and appear as a second display in Android's desktop mode. The app drives that display through one of two backends, picked automatically:

- **Accessibility backend (default, zero extra setup)** — with only the Accessibility Service enabled, the app synthesizes touch gestures on the glasses display (`dispatchGesture` targeted at the external display) and draws its own software cursor as an accessibility overlay. No Shizuku, no wireless debugging.
- **Shizuku backend (optional upgrade)** — when [Shizuku](https://shizuku.rikka.app/) is running, the app creates a virtual mouse device using the Linux `uinput` subsystem, so Android sees a real hardware mouse and shows a proper system cursor, with real wheel scrolling and key injection (including the DeX-style phone keyboard).

- **Single-finger drag** — moves the cursor
- **Tap** — left click
- **Double tap** — double click
- **Long press (600 ms)** — right click
- **Two-finger drag** — scroll
- **Bottom nav bar** — Back / Home / Recents / Screenshot (via Accessibility Service)

## Requirements

| Requirement | Details |
|---|---|
| Android | 14+ (minSdk 34) |
| Architecture | arm64-v8a |
| Accessibility Service | Must be enabled in Settings (drives the touchpad and nav buttons) |
| [Shizuku](https://shizuku.rikka.app/) | *Optional* — running via Wireless Debugging or root, for full mouse fidelity |

Tested on **Pixel 10 + Viture XR Pro** running Android 16.

## Setup

### 1. (Optional) Enable Shizuku
The app works without Shizuku — the Accessibility Service alone is enough. For the highest-fidelity mode (real OS cursor, wheel scrolling, DeX-style keyboard), follow the [Shizuku guide](https://shizuku.rikka.app/guide/setup/) to start Shizuku via Wireless Debugging (no root required):

1. Enable **Developer Options** → **Wireless Debugging**
2. Run `adb tcpip 5555` and `adb connect <phone-ip>`
3. Start Shizuku from its app

### 2. Install AR Touchpad
Build and install the debug APK, or download a release:

```bash
git clone https://github.com/pgratz1/AR-Touchpad.git
cd AR-Touchpad
./gradlew installDebug
```

### 3. Grant permissions
- Enable the **AR Touchpad** Accessibility Service in **Settings → Accessibility** (required)
- If using Shizuku: open the app → tap **Grant Shizuku** when prompted (optional)

### 4. Connect the glasses
Plug in the Viture XR Pro. Pull down the notification shade and switch to **Desktop / Extended** mode (not Mirror). The app's status bar shows all detected displays — the glasses should appear as a second display.

## Architecture

```
app/
├── src/main/
│   ├── aidl/          IMouseService.aidl — Binder interface to Shizuku service
│   ├── cpp/           uinput_jni.cpp — JNI: open /dev/uinput, ioctl, write events
│   └── java/…/
│       ├── MouseService.kt              — Shizuku UserService (runs as shell uid)
│       ├── ShizukuMouseController.kt    — Binds/unbinds the Shizuku service
│       ├── TouchpadViewModel.kt         — State, display detection, gesture dispatch
│       ├── UinputNative.kt             — Kotlin wrapper for the JNI library
│       ├── TouchpadAccessibilityService.kt — Global nav actions (Back/Home/etc.)
│       └── ui/TouchpadScreen.kt        — Compose UI: touch surface + status bar
```

**Why JNI for uinput?**  
Android 16 removed the generic `Os.ioctl(FileDescriptor, int/long, long)` method from `android.system.Os`, leaving only specialised variants. A small native library is the only reliable way to call `ioctl` with an arbitrary integer value — required for `UI_SET_EVBIT`, `UI_SET_RELBIT`, etc.

**Why Shizuku?**  
Creating a `uinput` device requires the `input` group (gid 1004). Shizuku grants shell uid (2000), which is in that group. No root needed.

## Building from source

Requires Android Studio (for the SDK) and NDK r25+.

```bash
# Debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

The NDK CMake build compiles `libartouchpad.so` for `arm64-v8a` automatically.

## License

Copyright 2026 Paul Gratz

Licensed under the [Apache License, Version 2.0](LICENSE).

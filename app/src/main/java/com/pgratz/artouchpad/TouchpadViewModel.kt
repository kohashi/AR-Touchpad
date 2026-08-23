// Copyright 2026 Paul Gratz
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.pgratz.artouchpad

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import android.accessibilityservice.AccessibilityService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TouchMode { IDLE, CURSOR, SCROLL, SELECT }

// Which input-injection backend is driving the glasses display.
//   SHIZUKU       — uinput virtual mouse via the shell-uid MouseService (needs Shizuku running,
//                   which requires wireless debugging or ADB to start). Full fidelity: real OS
//                   cursor, wheel scrolling, key injection.
//   ACCESSIBILITY — AccessibilityService.dispatchGesture + a software cursor overlay. Needs
//                   only the accessibility permission — no wireless debugging. Slightly lower
//                   fidelity (synthesized touches instead of a real mouse).
enum class InputBackend { NONE, SHIZUKU, ACCESSIBILITY }

// WindowManager DISPLAY_IME_POLICY_* values, passed to IWindowManager.setDisplayImePolicy.
private const val IME_POLICY_LOCAL = 0     // IME on the display that owns the focused field
private const val IME_POLICY_FALLBACK = 1  // IME on the default display (phone) — DeX-style

// Accessibility backend: touchpad finger pixels → content-drag pixels on the glasses.
// The Shizuku path converts 20 finger px to one wheel detent (~60–100 content px), so ~3×
// keeps perceived scroll speed comparable between backends at the same scrollSpeed setting.
private const val A11Y_SCROLL_PX_PER_UNIT = 3f

data class DisplayInfo(val id: Int, val name: String, val width: Int, val height: Int)

data class TouchpadState(
    val isServiceEnabled: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val shizukuPermission: Boolean = false,
    val shizukuConnected: Boolean = false,
    val backend: InputBackend = InputBackend.NONE,
    val mouseReady: Boolean = false,
    val allDisplays: List<DisplayInfo> = emptyList(),
    val targetDisplay: DisplayInfo? = null,
    val cursorX: Float = 0f,
    val cursorY: Float = 0f,
    val sensitivity: Float = 0.5f,
    val scrollSpeed: Float = 0.8f,
    val naturalScroll: Boolean = false,
    val showSettings: Boolean = false,
    val touchMode: TouchMode = TouchMode.IDLE,
    val showKeyboard: Boolean = false,
    // User preference: show the keyboard on the phone (IME fallback policy) instead of
    // on the glasses. dexKeyboardActive reflects whether the policy actually took effect
    // (false when unsupported on this build, or when running on the accessibility backend,
    // which has no shell access to set IME policies).
    val dexKeyboardEnabled: Boolean = true,
    val dexKeyboardActive: Boolean = false,
) {
    val externalDisplayConnected get() = targetDisplay != null
    val displayWidth get() = targetDisplay?.width ?: 1920
    val displayHeight get() = targetDisplay?.height ?: 1080
}

class TouchpadViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("touchpad_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(TouchpadState(
        sensitivity = prefs.getFloat("sensitivity", 0.5f),
        scrollSpeed = prefs.getFloat("scroll_speed", 0.8f),
        naturalScroll = prefs.getBoolean("natural_scroll", false),
        dexKeyboardEnabled = prefs.getBoolean("dex_keyboard", true),
    ))
    val state: StateFlow<TouchpadState> = _state.asStateFlow()

    private val displayManager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val mouse = ShizukuMouseController()

    // The accessibility service instance, when the user has enabled it in Settings.
    private val a11y get() = TouchpadAccessibilityService.instance
    private val useA11y get() = _state.value.backend == InputBackend.ACCESSIBILITY

    private var pinchAccum = 0f
    private var smoothDx = 0f
    private var smoothDy = 0f

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    init {
        mouse.init(::refresh)
        displayManager.registerDisplayListener(displayListener, null)
        refresh()
        mouse.bind()
        TouchpadAccessibilityService.onServiceStateChanged = { refresh() }
        TouchpadAccessibilityService.onExternalTextFocus = {
            // With the fallback IME policy active, Android shows the phone-side keyboard on
            // its own and keystrokes flow directly to the field on the glasses — nothing to do
            // (a BACK press here would dismiss that keyboard).
            if (!_state.value.dexKeyboardActive && !_state.value.showKeyboard) {
                _state.update { it.copy(showKeyboard = true) }
                // Dismiss the glasses-side IME that Android auto-showed.
                // BACK is consumed by the IME (dismisses it) and never reaches the app,
                // so Chrome's text field stays focused and ready for our injected text.
                viewModelScope.launch {
                    delay(400)
                    pressKey(KeyEvent.KEYCODE_BACK)
                }
            }
        }
    }

    // Enumerates all displays via DisplayManager; picks the first non-default display as the
    // target (glasses). Updates state with display list, cursor center, and all status flags,
    // and selects the active backend: Shizuku when its service is bound (best fidelity),
    // otherwise the accessibility service when enabled.
    fun refresh() {
        val allDisplays = displayManager.displays.map { d ->
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getMetrics(m)
            DisplayInfo(d.displayId, d.name ?: "Display ${d.displayId}", m.widthPixels, m.heightPixels)
        }

        // Pick the external display: prefer any non-default display,
        // fall back to presentation category.
        val external = allDisplays.firstOrNull { it.id != Display.DEFAULT_DISPLAY }
            ?: run {
                displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                    .firstOrNull()
                    ?.let { d ->
                        val m = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        d.getMetrics(m)
                        DisplayInfo(d.displayId, d.name ?: "Presentation", m.widthPixels, m.heightPixels)
                    }
            }

        val shizukuReady = mouse.hasPermission() && mouse.isConnected
        val service = TouchpadAccessibilityService.instance
        val backend = when {
            shizukuReady -> InputBackend.SHIZUKU
            service != null -> InputBackend.ACCESSIBILITY
            else -> InputBackend.NONE
        }

        if (external != null && shizukuReady) {
            mouse.setDisplay(external.id, external.width, external.height)
            applyImePolicy(external.id)
        }
        if (backend != InputBackend.SHIZUKU) {
            // No shell access → the DeX-style IME policy can't be applied on this backend.
            appliedImePolicy = null
            _state.update { it.copy(dexKeyboardActive = false) }
        }

        // The software cursor is only needed when the accessibility backend does the
        // injecting (Shizuku's uinput mouse gets a real OS pointer).
        service?.setPointerEnabled(backend == InputBackend.ACCESSIBILITY && external != null)
        if (external != null) service?.moveCursorTo(external.width / 2f, external.height / 2f)

        _state.update {
            it.copy(
                isServiceEnabled = service != null,
                shizukuAvailable = mouse.hasShizuku(),
                shizukuPermission = mouse.hasPermission(),
                shizukuConnected = mouse.isConnected,
                backend = backend,
                mouseReady = backend != InputBackend.NONE,
                allDisplays = allDisplays,
                targetDisplay = external,
                cursorX = if (external != null) external.width / 2f else it.cursorX,
                cursorY = if (external != null) external.height / 2f else it.cursorY,
            )
        }
    }

    // Opens the Shizuku permission dialog so the user can grant shell-uid access.
    fun requestShizukuPermission() = mouse.requestPermission()

    // Tracks the (displayId, policy) last applied successfully, so refresh() calls
    // (which fire on every display event) don't re-exec the wm command needlessly.
    private var appliedImePolicy: Pair<Int, Int>? = null

    // Applies the IME policy matching the dexKeyboardEnabled preference to the external
    // display: fallback → keyboard appears on the phone, local → on the glasses.
    // Runs on IO because MouseService execs a `wm` subprocess. dexKeyboardActive is set
    // only when the fallback policy actually took effect on this build.
    private fun applyImePolicy(displayId: Int) {
        val policy = if (_state.value.dexKeyboardEnabled) IME_POLICY_FALLBACK else IME_POLICY_LOCAL
        if (appliedImePolicy == displayId to policy) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = mouse.setImePolicy(displayId, policy)
            appliedImePolicy = if (ok) displayId to policy else null
            _state.update { it.copy(dexKeyboardActive = ok && policy == IME_POLICY_FALLBACK) }
        }
    }

    // Input: raw pixel deltas from the touch event.
    // Applies velocity-adaptive exponential smoothing (heavy for slow/fine moves,
    // light for fast moves) before scaling by sensitivity. This suppresses finger
    // tremor on precise movements without adding noticeable lag on fast sweeps.
    fun moveCursor(rawDx: Float, rawDy: Float) {
        val speed = kotlin.math.sqrt(rawDx * rawDx + rawDy * rawDy)
        val alpha = (speed / 8f).coerceIn(0.30f, 0.85f)
        smoothDx = alpha * rawDx + (1f - alpha) * smoothDx
        smoothDy = alpha * rawDy + (1f - alpha) * smoothDy

        val sens = _state.value.sensitivity
        val dx = smoothDx * sens
        val dy = smoothDy * sens
        if (dx == 0f && dy == 0f) return

        val w = _state.value.displayWidth.toFloat()
        val h = _state.value.displayHeight.toFloat()
        val newX = (_state.value.cursorX + dx).coerceIn(0f, w - 1f)
        val newY = (_state.value.cursorY + dy).coerceIn(0f, h - 1f)

        if (useA11y) a11y?.moveCursorTo(newX, newY) else mouse.moveMouse(dx, dy)

        _state.update {
            it.copy(cursorX = newX, cursorY = newY, touchMode = TouchMode.CURSOR)
        }
    }

    // Updates touchMode in state, which drives the cursor/scroll indicator shown in the status bar.
    // Resets the smoothing filter on IDLE so the decaying tail doesn't bleed into the next touch.
    // On the accessibility backend, leaving SCROLL lifts the synthetic scrolling finger.
    fun setTouchMode(mode: TouchMode) {
        if (mode == TouchMode.IDLE) { smoothDx = 0f; smoothDy = 0f }
        if (mode != TouchMode.SCROLL && useA11y) a11y?.endScroll()
        _state.update { it.copy(touchMode = mode) }
    }

    // Delegate clicks to the active backend at the current tracked cursor position.
    fun performClick() =
        if (useA11y) a11y?.performClick() ?: Unit
        else mouse.click(_state.value.cursorX, _state.value.cursorY)

    fun performDoubleClick() =
        if (useA11y) a11y?.performDoubleClick() ?: Unit
        else mouse.doubleClick(_state.value.cursorX, _state.value.cursorY)

    fun performRightClick() =
        if (useA11y) a11y?.performRightClick() ?: Unit
        else mouse.rightClick(_state.value.cursorX, _state.value.cursorY)

    // Starts a selection drag: Shizuku presses BTN_LEFT without releasing; the accessibility
    // backend synthesizes a long-press-and-hold whose finger follows subsequent cursor moves.
    fun startSelectDrag() =
        if (useA11y) a11y?.beginSelectDrag() ?: Unit else mouse.mouseDown()

    // Ends the selection drag and auto-copies the selection: Shizuku releases BTN_LEFT and
    // injects Ctrl+C; the accessibility backend lifts the finger and fires ACTION_COPY on
    // the focused node.
    fun endSelectDrag() {
        if (useA11y) {
            a11y?.endSelectDrag(andCopy = true)
        } else {
            mouse.mouseUp()
            mouse.pressKeyWithCtrl(KeyEvent.KEYCODE_C)
        }
    }

    // Applies scrollSpeed multiplier and natural-scroll direction inversion, then forwards
    // the adjusted delta to the backend. Sign chain (kept identical between backends): with
    // naturalScroll off, finger down → adjusted dy < 0 → wheel detent + (Shizuku) → content
    // moves down ≡ synthetic finger drags down (accessibility), hence the negation.
    fun performScroll(dx: Float, dy: Float) {
        val speed = _state.value.scrollSpeed
        val dir = if (_state.value.naturalScroll) 1f else -1f
        val adjX = dx * speed * dir
        val adjY = dy * speed * dir
        if (useA11y) {
            a11y?.scrollBy(-adjX * A11Y_SCROLL_PX_PER_UNIT, -adjY * A11Y_SCROLL_PX_PER_UNIT)
        } else {
            mouse.scroll(adjX, adjY)
        }
        _state.update { it.copy(touchMode = TouchMode.SCROLL) }
    }

    // Forwards a key press to the active backend. Shizuku injects a real KeyEvent on the
    // glasses display; the accessibility backend maps nav keys to global actions and Enter
    // to ACTION_IME_ENTER on the focused field (other keycodes have no a11y equivalent).
    fun pressKey(keyCode: Int) {
        if (useA11y) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK ->
                    a11y?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                KeyEvent.KEYCODE_HOME ->
                    a11y?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                KeyEvent.KEYCODE_APP_SWITCH ->
                    a11y?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER ->
                    a11y?.pressEnter()
            }
        } else {
            mouse.pressKey(keyCode)
        }
    }

    // Injects text into the focused field on the glasses via the active backend.
    fun typeText(text: String) {
        if (useA11y) a11y?.typeText(text) else mouse.typeText(text)
    }

    // Input: dDist — span change in pixels this frame (positive = spreading = zoom in).
    // Accumulates until 200 px threshold to avoid jitter; each 200 px = one zoom step.
    // Shizuku injects Ctrl+scroll (content zoom in Chrome/WebView); the accessibility
    // backend synthesizes a real two-finger pinch, which works in any zoomable app.
    fun pinchZoom(dDist: Float) {
        pinchAccum += dDist
        val detents = (pinchAccum / 200f).toInt()
        if (detents != 0) {
            pinchAccum -= detents * 200f
            if (useA11y) a11y?.pinchBy(detents.toFloat()) else mouse.ctrlScroll(detents.toFloat())
        }
    }
    // Toggles showKeyboard in state, which shows or hides the KeyboardProxy strip in the UI.
    fun toggleKeyboard() = _state.update { it.copy(showKeyboard = !it.showKeyboard) }

    // Input: text accumulated in the phone keyboard proxy.
    // Dismisses the phone keyboard first (to avoid IME session conflicts), waits 200 ms for
    // the IME to tear down, then injects the text followed by Enter to the glasses display.
    fun sendKeyboardText(text: String) {
        if (text.isEmpty()) { toggleKeyboard(); return }
        toggleKeyboard()
        viewModelScope.launch {
            delay(200)
            typeText(text)
            pressKey(KeyEvent.KEYCODE_ENTER)
        }
    }

    // Delegates an AccessibilityService global action (e.g. GLOBAL_ACTION_BACK) to the service instance.
    fun performGlobalAction(action: Int) =
        TouchpadAccessibilityService.instance?.performGlobalAction(action)

    // Settings state updaters — each writes one field into TouchpadState and persists it
    // so tuned values survive app restarts.
    fun setSensitivity(v: Float) {
        prefs.edit().putFloat("sensitivity", v).apply()
        _state.update { it.copy(sensitivity = v) }
    }
    fun setScrollSpeed(v: Float) {
        prefs.edit().putFloat("scroll_speed", v).apply()
        _state.update { it.copy(scrollSpeed = v) }
    }
    fun setNaturalScroll(v: Boolean) {
        prefs.edit().putBoolean("natural_scroll", v).apply()
        _state.update { it.copy(naturalScroll = v) }
    }
    fun setDexKeyboard(v: Boolean) {
        prefs.edit().putBoolean("dex_keyboard", v).apply()
        _state.update { it.copy(dexKeyboardEnabled = v) }
        if (_state.value.backend == InputBackend.SHIZUKU) {
            _state.value.targetDisplay?.let { applyImePolicy(it.id) }
        }
    }
    fun toggleSettings() = _state.update { it.copy(showSettings = !it.showSettings) }

    // Cleans up the accessibility callbacks, display listener, cursor overlay, and mouse
    // service when the ViewModel is destroyed (e.g. app process ends).
    override fun onCleared() {
        TouchpadAccessibilityService.onExternalTextFocus = null
        TouchpadAccessibilityService.onServiceStateChanged = null
        TouchpadAccessibilityService.instance?.setPointerEnabled(false)
        displayManager.unregisterDisplayListener(displayListener)
        // Restore the glasses-side IME before the service goes away. Synchronous call:
        // viewModelScope is already cancelled by the time onCleared runs.
        if (appliedImePolicy?.second == IME_POLICY_FALLBACK) {
            _state.value.targetDisplay?.let { mouse.setImePolicy(it.id, IME_POLICY_LOCAL) }
        }
        mouse.destroy()
    }
}

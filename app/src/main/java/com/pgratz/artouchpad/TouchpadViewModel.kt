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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TouchMode { IDLE, CURSOR, SCROLL, PINCH, SELECT }

data class DisplayInfo(val id: Int, val name: String, val width: Int, val height: Int)

data class TouchpadState(
    val isServiceEnabled: Boolean = false,
    val allDisplays: List<DisplayInfo> = emptyList(),
    val targetDisplay: DisplayInfo? = null,
    val sensitivity: Float = 0.5f,
    val scrollSpeed: Float = 0.8f,
    val naturalScroll: Boolean = false,
    val showSettings: Boolean = false,
    val touchMode: TouchMode = TouchMode.IDLE,
    val showKeyboard: Boolean = false,
) {
    val externalDisplayConnected get() = targetDisplay != null
    // The touchpad works as soon as the accessibility service runs and the glasses are
    // connected as a second display. No ADB, Shizuku or developer options involved.
    val inputReady get() = isServiceEnabled && targetDisplay != null
}

class TouchpadViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("touchpad_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(TouchpadState(
        sensitivity = prefs.getFloat("sensitivity", 0.5f),
        scrollSpeed = prefs.getFloat("scroll_speed", 0.8f),
        naturalScroll = prefs.getBoolean("natural_scroll", false),
    ))
    val state: StateFlow<TouchpadState> = _state.asStateFlow()

    private val displayManager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var smoothDx = 0f
    private var smoothDy = 0f

    private val service get() = TouchpadAccessibilityService.instance

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    init {
        displayManager.registerDisplayListener(displayListener, null)
        TouchpadAccessibilityService.onStateChanged = ::refresh
        TouchpadAccessibilityService.onExternalTextFocus = {
            // A text field on the glasses took focus: offer the phone-side keyboard strip.
            // Focusing its EditText pulls the IME over to the phone display on its own.
            if (!_state.value.showKeyboard) _state.update { it.copy(showKeyboard = true) }
        }
        refresh()
    }

    // Enumerates all displays; picks the first non-default display as the target (glasses).
    fun refresh() {
        val allDisplays = displayManager.displays.map { d ->
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getMetrics(m)
            DisplayInfo(d.displayId, d.name ?: "Display ${d.displayId}", m.widthPixels, m.heightPixels)
        }

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

        _state.update {
            it.copy(
                isServiceEnabled = service != null,
                allDisplays = allDisplays,
                targetDisplay = external,
            )
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
        service?.moveCursor(dx, dy)
    }

    // Updates touchMode in state, which drives the mode indicator in the status bar.
    // Resets the smoothing filter on IDLE so the decaying tail doesn't bleed into the next touch.
    fun setTouchMode(mode: TouchMode) {
        if (mode == TouchMode.IDLE) { smoothDx = 0f; smoothDy = 0f }
        _state.update { it.copy(touchMode = mode) }
    }

    fun performClick() { service?.performClick() }
    fun performDoubleClick() { service?.performDoubleClick() }

    // Phone-side long press fired: put a finger down on the glasses and hold it.
    fun startLongPress() { service?.beginPress() }

    // Long press released. If the finger moved meanwhile it was a selection drag, so the
    // selection is copied to the clipboard; otherwise the release completes a plain long
    // press, which desktop mode treats like a right click (context menu).
    fun endLongPress(selected: Boolean) { service?.endPress(copySelection = selected) }

    // Two-finger drag → one synthetic finger dragging on the glasses. Natural scroll moves
    // the glasses finger with the phone fingers (content follows); otherwise it is inverted
    // so dragging down scrolls the page down, like a traditional trackpad.
    fun performScroll(dx: Float, dy: Float) {
        val speed = _state.value.scrollSpeed
        val dir = if (_state.value.naturalScroll) 1f else -1f
        service?.scrollBy(dx * speed * dir, dy * speed * dir)
    }

    // Input: span change in pixels this frame (positive = spreading = zoom in).
    // Mirrored as a two-finger pinch on the glasses.
    fun pinchZoom(dDist: Float) { service?.pinchBy(dDist) }

    // Both fingers lifted (or one of them): release whatever two-finger gesture is active.
    fun endTwoFinger() {
        service?.endScroll()
        service?.endPinch()
    }

    fun performGlobalAction(action: Int) { service?.globalAction(action) }

    // Toggles showKeyboard in state, which shows or hides the KeyboardProxy strip in the UI.
    fun toggleKeyboard() = _state.update { it.copy(showKeyboard = !it.showKeyboard) }

    // Input: text accumulated in the phone keyboard proxy. Hides the strip, gives the IME a
    // moment to move back, then inserts the text into the focused field on the glasses and
    // triggers its IME action (the accessibility equivalent of Enter).
    fun sendKeyboardText(text: String) {
        toggleKeyboard()
        if (text.isEmpty()) return
        viewModelScope.launch {
            delay(150)
            val svc = service ?: return@launch
            if (svc.typeText(text)) {
                delay(80)
                svc.pressImeEnter()
            }
        }
    }

    // Settings state updaters — each writes one field into TouchpadState and persists it.
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
    fun toggleSettings() = _state.update { it.copy(showSettings = !it.showSettings) }

    override fun onCleared() {
        TouchpadAccessibilityService.onExternalTextFocus = null
        TouchpadAccessibilityService.onStateChanged = null
        displayManager.unregisterDisplayListener(displayListener)
    }
}

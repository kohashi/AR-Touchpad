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

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.hypot

// Accessibility-based input backend. Besides detecting external text focus and serving
// global nav actions (its original jobs), this service can now drive the glasses display
// entirely on its own — no Shizuku / wireless debugging required:
//
//   - taps / double-taps / long-presses / drags are synthesized with dispatchGesture(),
//     targeted at the external display via GestureDescription.Builder.setDisplayId (API 30+)
//   - a software cursor is drawn as a TYPE_ACCESSIBILITY_OVERLAY window on the external
//     display (no SYSTEM_ALERT_WINDOW permission needed for accessibility services)
//   - scrolling and text-selection dragging use continued strokes
//     (StrokeDescription.willContinue) so one synthetic finger stays down across frames
//   - text is injected with ACTION_SET_TEXT / ACTION_IME_ENTER on the focused editable node
//
// All public methods must be called on the main thread (they are — the ViewModel and the
// Compose pointerInput loop both run there). dispatchGesture is asynchronous, so gestures
// are serialized through a small queue: continued strokes break if an unrelated gesture is
// dispatched while a synthetic finger is held down.
class TouchpadAccessibilityService : AccessibilityService() {

    companion object {
        var instance: TouchpadAccessibilityService? = null
            private set

        // Invoked when an editable view on the external display gains focus.
        var onExternalTextFocus: (() -> Unit)? = null

        // Invoked when the service connects/disconnects so the ViewModel can re-pick a backend.
        var onServiceStateChanged: (() -> Unit)? = null

        private const val TAG = "TouchpadA11y"

        private const val TAP_MS = 50L
        private const val LONG_PRESS_MS = 650L
        private const val DOUBLE_GAP_MS = 100L
        private const val CLICK_VIBRATION_MS = 15L
        private const val LONG_VIBRATION_MS = 40L

        // Continued-stroke sessions: the initial touch-down hold before movement, and the
        // stationary release segment (short + stationary → no fling velocity).
        private const val SESSION_DOWN_MS = 20L
        private const val SESSION_END_MS = 30L

        // Pinch gesture geometry: base half-span of the two fingers and how far each
        // detent (from TouchpadViewModel.pinchZoom's 200 px accumulator) spreads them.
        private const val PINCH_MS = 160L
        private const val PINCH_HALF_SPAN = 110f
        private const val PINCH_PX_PER_DETENT = 45f

        // Keep synthetic fingers this far away from display edges (system gesture zones).
        private const val EDGE_MARGIN = 48f
    }

    private lateinit var displayManager: DisplayManager
    private lateinit var vibrator: Vibrator
    private val handler = Handler(Looper.getMainLooper())

    // ---- cursor / display state -------------------------------------------------------

    var cursorX = 540f
        private set
    var cursorY = 960f
        private set
    private var displayWidth = 1920
    private var displayHeight = 1080
    private var displayId = Display.DEFAULT_DISPLAY

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = detectExternalDisplay()
        override fun onDisplayRemoved(displayId: Int) = detectExternalDisplay()
        override fun onDisplayChanged(displayId: Int) = detectExternalDisplay()
    }

    // Called by Android when the service is bound and ready. Initializes DisplayManager and
    // Vibrator, registers the display listener, and detects the external display immediately.
    override fun onServiceConnected() {
        instance = this
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        displayManager.registerDisplayListener(displayListener, handler)
        detectExternalDisplay()
        onServiceStateChanged?.invoke()
    }

    // Finds the first non-default display (the glasses); falls back to the phone's window
    // bounds if none is found. Updates dimensions, recenters the cursor, and re-attaches
    // the cursor overlay to the (possibly new) display.
    private fun detectExternalDisplay() {
        val external = displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (external != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            external.getMetrics(metrics)
            displayWidth = metrics.widthPixels
            displayHeight = metrics.heightPixels
            displayId = external.displayId
        } else {
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            displayWidth = bounds.width()
            displayHeight = bounds.height()
            displayId = Display.DEFAULT_DISPLAY
        }
        cursorX = displayWidth / 2f
        cursorY = displayHeight / 2f
        resetSessions()
        reattachOverlay()
    }

    // ---- software cursor overlay ------------------------------------------------------

    private var pointerRequested = false
    private var overlayView: View? = null
    private var overlayWm: WindowManager? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayDisplayId = Display.INVALID_DISPLAY
    private var overlayMovePending = false
    private val overlayMover = Runnable {
        overlayMovePending = false
        val v = overlayView ?: return@Runnable
        val lp = overlayParams ?: return@Runnable
        lp.x = cursorX.toInt()
        lp.y = cursorY.toInt()
        runCatching { overlayWm?.updateViewLayout(v, lp) }
    }

    // Enables/disables the software cursor. The ViewModel turns it on only while the
    // accessibility backend is active (with Shizuku the OS draws a real pointer).
    fun setPointerEnabled(enabled: Boolean) {
        pointerRequested = enabled
        reattachOverlay()
    }

    // (Re)creates the cursor overlay window on the current external display, or removes it
    // when disabled / no external display. TYPE_ACCESSIBILITY_OVERLAY windows are permitted
    // to accessibility services without SYSTEM_ALERT_WINDOW; a window context created for
    // the external display places the window there.
    private fun reattachOverlay() {
        val wantedDisplay = if (pointerRequested && displayId != Display.DEFAULT_DISPLAY) displayId
                            else Display.INVALID_DISPLAY
        if (overlayDisplayId == wantedDisplay && (overlayView != null) == (wantedDisplay != Display.INVALID_DISPLAY)) {
            return
        }
        removeOverlay()
        if (wantedDisplay == Display.INVALID_DISPLAY) return
        val display = displayManager.getDisplay(wantedDisplay) ?: return
        try {
            val ctx = createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val wm = ctx.getSystemService(WindowManager::class.java) ?: return
            val density = ctx.resources.displayMetrics.density
            val view = CursorView(ctx)
            val lp = WindowManager.LayoutParams(
                (16 * density).toInt(),
                (24 * density).toInt(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = cursorX.toInt()
                y = cursorY.toInt()
            }
            wm.addView(view, lp)
            overlayView = view
            overlayWm = wm
            overlayParams = lp
            overlayDisplayId = wantedDisplay
        } catch (e: Exception) {
            Log.e(TAG, "cursor overlay failed", e)
        }
    }

    private fun removeOverlay() {
        overlayView?.let { v -> runCatching { overlayWm?.removeView(v) } }
        overlayView = null
        overlayWm = null
        overlayParams = null
        overlayDisplayId = Display.INVALID_DISPLAY
    }

    // Coalesces overlay position updates to ~120 Hz so 120 Hz touch input doesn't flood
    // WindowManager with one binder transaction per touch event.
    private fun scheduleOverlayMove() {
        if (overlayMovePending) return
        overlayMovePending = true
        handler.postDelayed(overlayMover, 8)
    }

    // Simple arrow pointer drawn in white with a dark outline so it reads on any background.
    private class CursorView(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * ctx.resources.displayMetrics.density
        }
        private val path = Path()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            // Classic pointer shape in a unit box, scaled to the view. Hotspot = top-left.
            path.reset()
            path.moveTo(0.05f * w, 0.02f * h)
            path.lineTo(0.05f * w, 0.72f * h)
            path.lineTo(0.28f * w, 0.55f * h)
            path.lineTo(0.48f * w, 0.94f * h)
            path.lineTo(0.65f * w, 0.86f * h)
            path.lineTo(0.45f * w, 0.48f * h)
            path.lineTo(0.72f * w, 0.46f * h)
            path.close()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawPath(path, fill)
            canvas.drawPath(path, outline)
        }
    }

    // ---- serialized gesture dispatch --------------------------------------------------

    private val gestureQueue = ArrayDeque<GestureDescription>()
    private var gestureBusy = false

    private val gestureCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            gestureBusy = false
            if (copyAfterDrag && !dragActive && dragStroke == null) {
                copyAfterDrag = false
                // Give the UI a beat to finalize the selection before copying it.
                handler.postDelayed({ copySelection() }, 150)
            }
            pump()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            // The system lifted our synthetic finger — any continued stroke is dead.
            gestureBusy = false
            resetSessions()
            pump()
        }
    }

    private fun enqueue(gesture: GestureDescription) {
        gestureQueue.addLast(gesture)
        pump()
    }

    // Dispatches the next queued gesture, or — when the queue is drained — the next
    // continuation segment of an active drag/scroll session.
    private fun pump() {
        if (gestureBusy) return
        while (true) {
            val g = gestureQueue.removeFirstOrNull() ?: nextSessionSegment() ?: return
            if (dispatchGesture(g, gestureCallback, null)) {
                gestureBusy = true
                return
            }
            Log.w(TAG, "dispatchGesture refused")
            resetSessions()
        }
    }

    private fun point(x: Float, y: Float) = Path().apply { moveTo(x, y) }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) = Path().apply {
        moveTo(x1, y1)
        if (x1 != x2 || y1 != y2) lineTo(x2, y2)
    }

    // Assembles a GestureDescription from strokes, targeted at the external display.
    private fun gesture(vararg strokes: StrokeDescription): GestureDescription {
        val builder = GestureDescription.Builder()
        strokes.forEach { builder.addStroke(it) }
        if (displayId != Display.DEFAULT_DISPLAY) builder.setDisplayId(displayId)
        return builder.build()
    }

    // Segment duration scaled to distance (≈3 px/ms) so slow precise drags stay smooth and
    // long jumps don't lag behind the finger.
    private fun segmentMs(dist: Float) = (dist / 3f).toLong().coerceIn(16L, 100L)

    private fun clampX(x: Float) = x.coerceIn(EDGE_MARGIN, displayWidth - EDGE_MARGIN)
    private fun clampY(y: Float) = y.coerceIn(EDGE_MARGIN, displayHeight - EDGE_MARGIN)

    // ---- cursor movement ---------------------------------------------------------------

    // Moves the software cursor to an absolute position (the ViewModel owns the position and
    // pushes it here). While a select-drag is active the held synthetic finger follows via
    // continued strokes generated in nextSessionSegment().
    fun moveCursorTo(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, (displayWidth - 1).toFloat())
        cursorY = y.coerceIn(0f, (displayHeight - 1).toFloat())
        scheduleOverlayMove()
        if (dragActive) pump()
    }

    // ---- clicks -------------------------------------------------------------------------

    // Dispatches a TAP_MS-duration tap gesture at the current cursor position and vibrates briefly.
    fun performClick() {
        if (sessionActive()) return
        enqueue(gesture(StrokeDescription(point(cursorX, cursorY), 0, TAP_MS)))
        vibrate(CLICK_VIBRATION_MS)
    }

    // Dispatches two tap strokes separated by DOUBLE_GAP_MS in a single GestureDescription
    // so the system recognizes them as a double-tap rather than two independent taps.
    fun performDoubleClick() {
        if (sessionActive()) return
        val s1 = StrokeDescription(point(cursorX, cursorY), 0, TAP_MS)
        val s2 = StrokeDescription(point(cursorX, cursorY), TAP_MS + DOUBLE_GAP_MS, TAP_MS)
        enqueue(gesture(s1, s2))
        vibrate(CLICK_VIBRATION_MS)
    }

    // Dispatches a LONG_PRESS_MS-duration stationary press at the cursor position.
    // Touch UIs map long-press to the context menu — the touch equivalent of right-click.
    fun performRightClick() {
        if (sessionActive()) return
        enqueue(gesture(StrokeDescription(point(cursorX, cursorY), 0, LONG_PRESS_MS)))
        vibrate(LONG_VIBRATION_MS)
    }

    // ---- select drag (long-press + drag via continued strokes) -------------------------

    private var dragActive = false
    private var dragEndRequested = false
    private var dragStroke: StrokeDescription? = null
    private var dragX = 0f
    private var dragY = 0f
    private var copyAfterDrag = false

    private fun sessionActive() = dragActive || scrollActive

    // Starts a long-press-hold at the cursor: the synthetic finger goes down, holds for
    // LONG_PRESS_MS (starting text selection), and stays down (willContinue) so subsequent
    // moveCursorTo calls extend the selection.
    fun beginSelectDrag() {
        if (sessionActive()) return
        dragActive = true
        dragEndRequested = false
        copyAfterDrag = false
        dragX = cursorX
        dragY = cursorY
        val stroke = StrokeDescription(point(dragX, dragY), 0, LONG_PRESS_MS, true)
        dragStroke = stroke
        enqueue(gesture(stroke))
        vibrate(LONG_VIBRATION_MS)
    }

    // Lifts the synthetic finger, ending the selection. When andCopy is set, the focused
    // node's selection is copied to the clipboard once the release lands.
    fun endSelectDrag(andCopy: Boolean = true) {
        if (!dragActive) return
        dragEndRequested = true
        copyAfterDrag = andCopy
        pump()
    }

    // ---- two-finger scroll (content drag via continued strokes) ------------------------

    private var scrollActive = false
    private var scrollEndRequested = false
    private var scrollStroke: StrokeDescription? = null
    private var scrollFingerX = 0f
    private var scrollFingerY = 0f
    private var scrollPendingX = 0f
    private var scrollPendingY = 0f

    // Accumulates content-drag deltas in external-display pixels. A synthetic finger drags
    // the content under the cursor; when it runs into the display edge the session lifts and
    // re-anchors near the center, carrying the remaining delta.
    fun scrollBy(dx: Float, dy: Float) {
        if (dragActive) return
        scrollPendingX += dx
        scrollPendingY += dy
        if (!scrollActive) {
            scrollActive = true
            scrollEndRequested = false
            scrollFingerX = clampX(cursorX)
            scrollFingerY = clampY(cursorY)
            val stroke = StrokeDescription(point(scrollFingerX, scrollFingerY), 0, SESSION_DOWN_MS, true)
            scrollStroke = stroke
            enqueue(gesture(stroke))
        } else {
            pump()
        }
    }

    // Ends the scroll session (called when both fingers lift on the touchpad). Remaining
    // sub-pixel deltas are discarded; the finger releases stationary so nothing flings.
    fun endScroll() {
        if (!scrollActive) return
        scrollEndRequested = true
        scrollPendingX = 0f
        scrollPendingY = 0f
        pump()
    }

    // ---- pinch zoom ---------------------------------------------------------------------

    // Dispatches a two-finger pinch centered on the cursor. detents > 0 spreads (zoom in),
    // < 0 contracts (zoom out). Uses real touch pinch, which works in any zoomable app.
    fun pinchBy(detents: Float) {
        if (detents == 0f) return
        if (sessionActive()) {
            // Can't pinch while a synthetic finger is held down; wind the scroll down first.
            if (scrollActive) endScroll()
            return
        }
        // Keep the pinch's widest extent on-screen; center it if the display is too narrow
        // (coerceIn throws when min > max).
        val margin = PINCH_HALF_SPAN + PINCH_PX_PER_DETENT * 4 + EDGE_MARGIN
        val cx = if (displayWidth > 2 * margin) cursorX.coerceIn(margin, displayWidth - margin)
                 else displayWidth / 2f
        val cy = clampY(cursorY)
        val delta = (detents * PINCH_PX_PER_DETENT).coerceIn(-PINCH_HALF_SPAN + 20f, PINCH_PX_PER_DETENT * 4)
        val startHalf = PINCH_HALF_SPAN
        val endHalf = (startHalf + delta).coerceAtLeast(20f)
        val left = StrokeDescription(line(cx - startHalf, cy, cx - endHalf, cy), 0, PINCH_MS)
        val right = StrokeDescription(line(cx + startHalf, cy, cx + endHalf, cy), 0, PINCH_MS)
        enqueue(gesture(left, right))
    }

    // ---- session continuation ----------------------------------------------------------

    private fun resetSessions() {
        dragActive = false
        dragEndRequested = false
        dragStroke = null
        scrollActive = false
        scrollEndRequested = false
        scrollStroke = null
        scrollPendingX = 0f
        scrollPendingY = 0f
    }

    // Produces the next continued-stroke segment for the active drag or scroll session,
    // or null when there is nothing to do. Called by pump() with the queue drained.
    private fun nextSessionSegment(): GestureDescription? {
        // Select drag: follow the cursor, then lift when requested.
        dragStroke?.let { prev ->
            if (cursorX != dragX || cursorY != dragY) {
                val dist = hypot(cursorX - dragX, cursorY - dragY)
                val seg = prev.continueStroke(line(dragX, dragY, cursorX, cursorY), 0, segmentMs(dist), true)
                dragX = cursorX
                dragY = cursorY
                dragStroke = seg
                return gesture(seg)
            }
            if (dragEndRequested) {
                val seg = prev.continueStroke(point(dragX, dragY), 0, SESSION_END_MS, false)
                dragStroke = null
                dragActive = false
                dragEndRequested = false
                return gesture(seg)
            }
            return null
        }

        // Scroll: drain pending content-drag delta, re-anchoring at the edge.
        if (scrollActive) {
            val prev = scrollStroke
            if (prev == null) {
                // Re-anchor after an edge lift: place the finger back near the center,
                // biased against the pending direction to maximize travel.
                if (abs(scrollPendingX) < 1f && abs(scrollPendingY) < 1f) {
                    scrollActive = false
                    scrollEndRequested = false
                    return null
                }
                scrollFingerX = clampX(displayWidth / 2f - scrollPendingX.coerceIn(-displayWidth / 4f, displayWidth / 4f))
                scrollFingerY = clampY(displayHeight / 2f - scrollPendingY.coerceIn(-displayHeight / 4f, displayHeight / 4f))
                val stroke = StrokeDescription(point(scrollFingerX, scrollFingerY), 0, SESSION_DOWN_MS, true)
                scrollStroke = stroke
                return gesture(stroke)
            }
            if (abs(scrollPendingX) >= 1f || abs(scrollPendingY) >= 1f) {
                val tx = clampX(scrollFingerX + scrollPendingX)
                val ty = clampY(scrollFingerY + scrollPendingY)
                val movedX = tx - scrollFingerX
                val movedY = ty - scrollFingerY
                if (abs(movedX) < 1f && abs(movedY) < 1f) {
                    // Pinned against the edge: lift, then re-anchor on the next segment.
                    val seg = prev.continueStroke(point(scrollFingerX, scrollFingerY), 0, SESSION_END_MS, false)
                    scrollStroke = null
                    return gesture(seg)
                }
                scrollPendingX -= movedX
                scrollPendingY -= movedY
                val seg = prev.continueStroke(
                    line(scrollFingerX, scrollFingerY, tx, ty), 0, segmentMs(hypot(movedX, movedY)), true)
                scrollFingerX = tx
                scrollFingerY = ty
                scrollStroke = seg
                return gesture(seg)
            }
            if (scrollEndRequested) {
                val seg = prev.continueStroke(point(scrollFingerX, scrollFingerY), 0, SESSION_END_MS, false)
                scrollStroke = null
                scrollActive = false
                scrollEndRequested = false
                return gesture(seg)
            }
        }
        return null
    }

    // ---- text injection (keyboard proxy backend) ----------------------------------------

    // Finds the input-focused node, preferring windows on the external display.
    private fun focusedInputNode(): AccessibilityNodeInfo? {
        if (displayId != Display.DEFAULT_DISPLAY) {
            windowsOnAllDisplays.get(displayId)?.forEach { w ->
                w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
            }
        }
        return findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    // Appends text to the focused editable field via ACTION_SET_TEXT. Returns false when no
    // editable field has input focus (the caller can surface that to the user).
    fun typeText(text: String): Boolean {
        val node = focusedInputNode() ?: return false
        if (!node.isEditable) return false
        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // Presses the IME action (Enter/Send/Search) on the focused editable field.
    fun pressEnter(): Boolean {
        val node = focusedInputNode() ?: return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    // Copies the focused node's current selection to the clipboard (post-select-drag).
    fun copySelection(): Boolean {
        val node = focusedInputNode() ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_COPY)
    }

    // ---- misc ---------------------------------------------------------------------------

    // Triggers a single haptic pulse of the given duration.
    private fun vibrate(ms: Long) {
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // Receives all accessibility events; filters for TYPE_VIEW_FOCUSED on an editable,
    // visible view whose window is on a non-default display (i.e. the glasses). When matched,
    // fires onExternalTextFocus so the ViewModel can show the phone keyboard proxy.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) return
        val source = event.source ?: return
        if (!source.isEditable || !source.isVisibleToUser) return
        val displayId = source.window?.displayId ?: Display.DEFAULT_DISPLAY
        if (displayId != Display.DEFAULT_DISPLAY) {
            onExternalTextFocus?.invoke()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        removeOverlay()
        handler.removeCallbacksAndMessages(null)
        if (::displayManager.isInitialized) {
            displayManager.unregisterDisplayListener(displayListener)
        }
        onServiceStateChanged?.invoke()
        super.onDestroy()
    }

}

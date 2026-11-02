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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
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

/**
 * The only input backend of the app. Runs inside the normal app process with no
 * ADB, Shizuku, root or developer-options dependency.
 *
 * Everything the touchpad does on the glasses display goes through public
 * AccessibilityService APIs:
 *  - a software cursor drawn in a TYPE_ACCESSIBILITY_OVERLAY window attached to the
 *    external display (Android 11+ lets accessibility services open windows on any display);
 *  - taps, long presses, drags, two-finger scrolls and pinches dispatched with
 *    dispatchGesture() targeted at the external display via GestureDescription.Builder.setDisplayId();
 *  - text entry through AccessibilityNodeInfo actions (paste / set text / IME enter) on the
 *    focused editable node of the external display;
 *  - Back / Home / Recents through performGlobalAction().
 *
 * All public methods must be called on the main thread (Compose pointer input already is).
 */
class TouchpadAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchpadA11y"

        var instance: TouchpadAccessibilityService? = null
            private set

        // Invoked when an editable view on the external display gains focus.
        var onExternalTextFocus: (() -> Unit)? = null

        // Invoked when the service connects/disconnects or its target display changes.
        var onStateChanged: (() -> Unit)? = null

        private const val TAP_MS = 60L
        private const val DOUBLE_GAP_MS = 90L
        // Hold time for the glasses-side touch after the phone-side long press fired.
        // Must exceed ViewConfiguration.getLongPressTimeout() (400 ms) so that the glasses
        // register a long press (context menu / word selection) before any drag starts.
        private const val PRESS_HOLD_MS = 500L
        // Duration of each continuation segment of a drag. Shorter = lower latency,
        // more binder traffic; 40 ms keeps a drag smooth at touch rates.
        private const val DRAG_SEGMENT_MS = 40L
        // Stationary hold before lifting a drag so the velocity tracker sees ~0 velocity
        // and the content does not fling when a two-finger scroll ends.
        private const val LIFT_HOLD_MS = 70L
        // Initial finger spacing of a synthetic pinch and its bounds.
        private const val PINCH_START_SPAN = 320f
        private const val PINCH_MIN_SPAN = 60f
        private const val CLICK_VIBRATION_MS = 15L
        private const val CURSOR_HIDE_DELAY_MS = 4000L
    }

    private enum class SessionKind { PRESS, SCROLL, PINCH }

    private lateinit var displayManager: DisplayManager
    private lateinit var vibrator: Vibrator
    private val mainHandler = Handler(Looper.getMainLooper())

    var targetDisplayId = Display.DEFAULT_DISPLAY
        private set
    var displayWidth = 1920
        private set
    var displayHeight = 1080
        private set
    val hasExternalDisplay get() = targetDisplayId != Display.DEFAULT_DISPLAY

    var cursorX = 960f
        private set
    var cursorY = 540f
        private set

    private var overlayView: CursorOverlayView? = null
    private var overlayWm: WindowManager? = null
    private var overlayDisplayId = Display.INVALID_DISPLAY

    private var session: DragSession? = null
    private var sessionKind: SessionKind? = null
    // Where the current scroll drag touched down and how far it has travelled; used to
    // lift and re-touch before the synthetic finger runs off the edge of the display.
    private var scrollOriginX = 0f
    private var scrollOriginY = 0f
    private var scrollFingerX = 0f
    private var scrollFingerY = 0f
    private var pinchSpan = PINCH_START_SPAN

    // Last editable node focused on the external display; fallback for typeText when the
    // window tree lookup finds no input-focused node (e.g. some WebView builds).
    private var lastExternalEditable: AccessibilityNodeInfo? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = detectExternalDisplay()
        override fun onDisplayRemoved(displayId: Int) = detectExternalDisplay()
        override fun onDisplayChanged(displayId: Int) = detectExternalDisplay()
    }

    private val hideCursorRunnable = Runnable { overlayView?.setCursorVisible(false) }

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        instance = this
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        displayManager.registerDisplayListener(displayListener, mainHandler)
        detectExternalDisplay()
        onStateChanged?.invoke()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        endSession()
        detachOverlay()
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        mainHandler.removeCallbacksAndMessages(null)
        onStateChanged?.invoke()
    }

    // Finds the first non-default display (the glasses). Falls back to the phone's own
    // display so the service still has valid bounds while nothing is connected.
    // Recenters the cursor and (re)attaches the cursor overlay when the target changes.
    private fun detectExternalDisplay() {
        val external = displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        val newId: Int
        if (external != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            external.getMetrics(metrics)
            displayWidth = metrics.widthPixels
            displayHeight = metrics.heightPixels
            newId = external.displayId
        } else {
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            displayWidth = bounds.width()
            displayHeight = bounds.height()
            newId = Display.DEFAULT_DISPLAY
        }

        val changed = newId != targetDisplayId
        targetDisplayId = newId
        if (changed) {
            endSession()
            cursorX = displayWidth / 2f
            cursorY = displayHeight / 2f
        }
        if (external != null) attachOverlay(external) else detachOverlay()
        overlayView?.setCursor(cursorX, cursorY)
        if (changed) onStateChanged?.invoke()
    }

    // ---------------------------------------------------------------- cursor overlay

    // Opens a full-screen, non-touchable accessibility overlay on the given display and
    // draws the cursor in it. Non-touchable so dispatched gestures pass straight through.
    private fun attachOverlay(display: Display) {
        if (overlayView != null && overlayDisplayId == display.displayId) return
        detachOverlay()
        runCatching {
            val ctx = createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val wm = ctx.getSystemService(WindowManager::class.java)
            val view = CursorOverlayView(ctx)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "AR Touchpad Cursor"
            }
            wm.addView(view, lp)
            overlayView = view
            overlayWm = wm
            overlayDisplayId = display.displayId
            Log.i(TAG, "cursor overlay attached to display ${display.displayId}")
        }.onFailure { Log.w(TAG, "attachOverlay failed: $it") }
    }

    private fun detachOverlay() {
        val view = overlayView ?: return
        runCatching { overlayWm?.removeViewImmediate(view) }
        overlayView = null
        overlayWm = null
        overlayDisplayId = Display.INVALID_DISPLAY
    }

    private fun showCursor() {
        overlayView?.setCursorVisible(true)
        mainHandler.removeCallbacks(hideCursorRunnable)
        mainHandler.postDelayed(hideCursorRunnable, CURSOR_HIDE_DELAY_MS)
    }

    // ---------------------------------------------------------------- pointer

    // Moves the software cursor by (dx, dy) display pixels, clamped to the display.
    // While a press session is active the glasses-side finger follows the cursor, which
    // turns a long-press-then-drag into a selection / drag on the glasses.
    fun moveCursor(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, (displayWidth - 1).toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, (displayHeight - 1).toFloat())
        overlayView?.setCursor(cursorX, cursorY)
        showCursor()
        if (sessionKind == SessionKind.PRESS) session?.moveTo(listOf(PointF(cursorX, cursorY)))
    }

    fun performClick() {
        endSession()
        dispatchGesture(buildGesture(stroke(cursorX, cursorY, cursorX, cursorY, 0, TAP_MS)), null, null)
        vibrate(CLICK_VIBRATION_MS)
        showCursor()
    }

    // Two taps in one GestureDescription so the system sees them as a double tap.
    fun performDoubleClick() {
        endSession()
        val x = cursorX
        val y = cursorY
        val s1 = stroke(x, y, x, y, 0, TAP_MS)
        val s2 = stroke(x, y, x, y, TAP_MS + DOUBLE_GAP_MS, TAP_MS)
        dispatchGesture(buildGesture(s1, s2), null, null)
        vibrate(CLICK_VIBRATION_MS)
        showCursor()
    }

    // Touches down at the cursor and holds. Called when the phone-side long press fires.
    // Releasing without moving yields a long press on the glasses (context menu, i.e. the
    // touch equivalent of a right click); moving first drags with the finger held down
    // (text selection in editable content, window/item drag elsewhere).
    fun beginPress() {
        if (sessionKind == SessionKind.PRESS) return
        endSession()
        sessionKind = SessionKind.PRESS
        session = DragSession(listOf(PointF(cursorX, cursorY)), PRESS_HOLD_MS)
        showCursor()
    }

    // Lifts the held finger. With copySelection the focused node's selection is copied
    // to the clipboard, mirroring the old Ctrl+C after a selection drag.
    fun endPress(copySelection: Boolean) {
        if (sessionKind != SessionKind.PRESS) return
        endSession()
        if (copySelection) {
            mainHandler.postDelayed({
                focusedNodeOnTarget()?.let { node ->
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_COPY)
                    Log.d(TAG, "ACTION_COPY -> $ok")
                }
            }, 150)
        }
    }

    // Drags one synthetic finger on the glasses by (dx, dy). The touch starts at the
    // cursor and is re-planted at the cursor whenever it has travelled far enough to
    // risk leaving the display, so long scrolls keep going.
    fun scrollBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        if (sessionKind != SessionKind.SCROLL) {
            endSession()
            startScrollSession()
        }
        var nx = (scrollFingerX + dx).coerceIn(0f, (displayWidth - 1).toFloat())
        var ny = (scrollFingerY + dy).coerceIn(0f, (displayHeight - 1).toFloat())
        val limit = minOf(displayWidth, displayHeight) * 0.35f
        val travelled = Math.hypot((nx - scrollOriginX).toDouble(), (ny - scrollOriginY).toDouble())
        if (travelled > limit) {
            endSession()
            startScrollSession()
            nx = (scrollFingerX + dx).coerceIn(0f, (displayWidth - 1).toFloat())
            ny = (scrollFingerY + dy).coerceIn(0f, (displayHeight - 1).toFloat())
        }
        scrollFingerX = nx
        scrollFingerY = ny
        session?.moveTo(listOf(PointF(nx, ny)))
    }

    private fun startScrollSession() {
        sessionKind = SessionKind.SCROLL
        scrollOriginX = cursorX
        scrollOriginY = cursorY
        scrollFingerX = cursorX
        scrollFingerY = cursorY
        session = DragSession(listOf(PointF(cursorX, cursorY)), 10L)
    }

    fun endScroll() {
        if (sessionKind == SessionKind.SCROLL) endSession()
    }

    // Two synthetic fingers centred on the cursor, moving apart (dSpan > 0) or together.
    fun pinchBy(dSpan: Float) {
        if (dSpan == 0f) return
        if (sessionKind != SessionKind.PINCH) {
            endSession()
            sessionKind = SessionKind.PINCH
            pinchSpan = PINCH_START_SPAN.coerceAtMost(maxPinchSpan())
            session = DragSession(pinchPoints(pinchSpan), 10L)
        }
        pinchSpan = (pinchSpan + dSpan).coerceIn(PINCH_MIN_SPAN, maxPinchSpan())
        session?.moveTo(pinchPoints(pinchSpan))
    }

    fun endPinch() {
        if (sessionKind == SessionKind.PINCH) endSession()
    }

    private fun maxPinchSpan(): Float {
        val room = minOf(cursorX, displayWidth - 1 - cursorX) * 2f - 4f
        return room.coerceAtLeast(PINCH_MIN_SPAN)
    }

    private fun pinchPoints(span: Float): List<PointF> {
        val half = span / 2f
        val y = cursorY.coerceIn(1f, (displayHeight - 2).toFloat())
        return listOf(PointF(cursorX - half, y), PointF(cursorX + half, y))
    }

    private fun endSession() {
        session?.end()
        session = null
        sessionKind = null
    }

    // ---------------------------------------------------------------- text & navigation

    // Inserts text into the editable node focused on the glasses display. Paste is tried
    // first because it honours the caret position; if the node rejects it the text is
    // appended with ACTION_SET_TEXT. The user's previous clipboard is restored afterwards.
    fun typeText(text: String): Boolean {
        if (text.isEmpty()) return true
        val node = focusedEditableOnTarget() ?: run {
            Log.w(TAG, "typeText: no focused editable on display $targetDisplayId")
            return false
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        val previous = runCatching { clipboard.primaryClip }.getOrNull()
        var ok = false
        runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("AR Touchpad", text))
            ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        if (!ok) {
            val existing = node.text?.toString().orEmpty()
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
            }
            ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        if (previous != null) {
            mainHandler.postDelayed({ runCatching { clipboard.setPrimaryClip(previous) } }, 600)
        }
        Log.d(TAG, "typeText(${text.length} chars) -> $ok")
        return ok
    }

    // Triggers the focused field's IME action (Go / Search / Send / newline) — the
    // accessibility equivalent of pressing Enter on a keyboard.
    fun pressImeEnter(): Boolean {
        val node = focusedEditableOnTarget() ?: return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    // Back / Home / Recents. These act on the currently focused display, which is the
    // glasses as soon as a dispatched gesture has landed there.
    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    private fun focusedEditableOnTarget(): AccessibilityNodeInfo? {
        focusedNodeOnTarget()?.takeIf { it.isEditable }?.let { return it }
        val cached = lastExternalEditable ?: return null
        return if (runCatching { cached.refresh() }.getOrDefault(false) && cached.isEditable) cached else null
    }

    // Input-focused node among the windows shown on the target display.
    private fun focusedNodeOnTarget(): AccessibilityNodeInfo? {
        val wins = runCatching { windowsOnAllDisplays.get(targetDisplayId) }.getOrNull().orEmpty()
        for (w in wins) {
            val root = w.root ?: continue
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        }
        return findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.window?.displayId == targetDisplayId }
    }

    // Filters for TYPE_VIEW_FOCUSED on an editable, visible view whose window is on the
    // external display and tells the UI to offer the phone-side keyboard proxy.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) return
        val source = event.source ?: return
        if (!source.isEditable || !source.isVisibleToUser) return
        val displayId = source.window?.displayId ?: Display.DEFAULT_DISPLAY
        if (displayId != Display.DEFAULT_DISPLAY) {
            lastExternalEditable = source
            onExternalTextFocus?.invoke()
        }
    }

    // ---------------------------------------------------------------- gesture plumbing

    private fun pointPath(p: PointF) = Path().apply { moveTo(p.x, p.y) }

    private fun linePath(from: PointF, to: PointF) = Path().apply {
        moveTo(from.x, from.y)
        if (from.x != to.x || from.y != to.y) lineTo(to.x, to.y)
    }

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, startMs: Long, durationMs: Long) =
        GestureDescription.StrokeDescription(linePath(PointF(x1, y1), PointF(x2, y2)), startMs, durationMs)

    private fun buildGesture(vararg strokes: GestureDescription.StrokeDescription): GestureDescription =
        buildGesture(strokes.toList())

    private fun buildGesture(strokes: List<GestureDescription.StrokeDescription>): GestureDescription {
        val builder = GestureDescription.Builder()
        strokes.forEach { builder.addStroke(it) }
        builder.setDisplayId(targetDisplayId)
        return builder.build()
    }

    private fun vibrate(ms: Long) {
        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /**
     * One or more synthetic fingers held down on the target display, moved by chaining
     * StrokeDescription.continueStroke() segments. Only one continuation gesture is in
     * flight at a time; movement requested meanwhile is coalesced into the next segment.
     */
    private inner class DragSession(start: List<PointF>, holdMs: Long) {
        private var strokes: List<GestureDescription.StrokeDescription> =
            start.map { GestureDescription.StrokeDescription(pointPath(it), 0, holdMs, true) }
        private var current: List<PointF> = start
        private var pending: List<PointF>? = null
        private var inFlight = false
        private var endRequested = false
        private var dead = false

        init { dispatch() }

        fun moveTo(points: List<PointF>) {
            if (dead || endRequested) return
            pending = points
            if (!inFlight) flush()
        }

        fun end() {
            if (dead) return
            endRequested = true
            if (!inFlight) lift()
        }

        private fun flush() {
            val target = pending ?: return
            pending = null
            if (target == current) return
            strokes = strokes.mapIndexed { i, s ->
                s.continueStroke(linePath(current[i], target[i]), 0, DRAG_SEGMENT_MS, true)
            }
            current = target
            dispatch()
        }

        private fun lift() {
            dead = true
            strokes = strokes.mapIndexed { i, s -> s.continueStroke(pointPath(current[i]), 0, LIFT_HOLD_MS, false) }
            dispatchGesture(buildGesture(strokes), null, null)
        }

        private fun dispatch() {
            inFlight = true
            val ok = dispatchGesture(buildGesture(strokes), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    inFlight = false
                    if (dead) return
                    if (endRequested) lift() else if (pending != null) flush()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    // The system cancels our gesture when a real touch lands on that display
                    // or when another gesture pre-empts it; the finger is already up.
                    inFlight = false
                    dead = true
                    Log.d(TAG, "drag session cancelled")
                }
            }, null)
            if (!ok) {
                inFlight = false
                dead = true
                Log.w(TAG, "dispatchGesture refused")
            }
        }
    }

    /** Draws a classic arrow pointer at the cursor position; hides itself when idle. */
    private class CursorOverlayView(ctx: Context) : View(ctx) {
        private var cx = 0f
        private var cy = 0f
        private var visible = true
        private val scale = resources.displayMetrics.density * 1.4f
        private val arrow = Path().apply {
            moveTo(0f, 0f); lineTo(0f, 17f); lineTo(4.5f, 13f); lineTo(7.5f, 20f)
            lineTo(10f, 19f); lineTo(7f, 12f); lineTo(12.5f, 12f); close()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.2f; strokeJoin = Paint.Join.ROUND
        }

        fun setCursor(x: Float, y: Float) {
            cx = x; cy = y
            invalidate()
        }

        fun setCursorVisible(show: Boolean) {
            if (visible == show) return
            visible = show
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (!visible) return
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(scale, scale)
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, outline)
            canvas.restore()
        }
    }
}

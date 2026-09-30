package io.github.junkers4.ladybird.browser

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.Choreographer
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.junkers4.ladybird.core.engine.KeyEventType
import io.github.junkers4.ladybird.core.engine.KeyMapping
import io.github.junkers4.ladybird.core.engine.LadybirdKeyCode
import io.github.junkers4.ladybird.core.input.GestureMath
import io.github.junkers4.ladybird.core.input.InputPrivacy
import io.github.junkers4.ladybird.engine.NativeEngine

/**
 * One tab's page. Frames are copied into this SurfaceView's window by the native bridge (ARCH-003);
 * touch, keys and IME input are translated to Ladybird events (UI-010, UI-011).
 */
@SuppressLint("ViewConstructor")
class WebContentView(
    context: Context,
    val viewId: Long,
    private val isPrivateTab: Boolean,
    private val incognitoKeyboard: () -> Boolean,
) : SurfaceView(context), SurfaceHolder.Callback {
    private val density = resources.displayMetrics.density
    private var imeEnabled = false
    private var momentum: Choreographer.FrameCallback? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // --- Surface --------------------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        NativeEngine.nativeSetSurface(viewId, holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NativeEngine.nativeSetViewport(viewId, width, height, density)
        NativeEngine.nativeSetSurface(viewId, holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Blocks until the engine thread stopped drawing into the surface (ARCH-002).
        NativeEngine.nativeSetSurface(viewId, null)
    }

    // --- Touch ----------------------------------------------------------------------------------

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            stopMomentum()
            requestFocus()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            send(GestureMath.tap(e.x, e.y))
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            send(GestureMath.tap(e.x, e.y, clickCount = 2))
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            send(GestureMath.longPress(e.x, e.y))
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val wheel = GestureMath.drag(e2.x, e2.y, distanceX, distanceY, density)
            NativeEngine.nativeScroll(viewId, wheel.x, wheel.y, wheel.deltaX, wheel.deltaY, wheel.phase)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            startMomentum(e2.x, e2.y, GestureMath.flingDeltas(velocityX, velocityY, density))
            return true
        }
    })

    private val scaleGestures = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            NativeEngine.nativePinch(viewId, detector.focusX, detector.focusY, GestureMath.pinchScaleDelta(detector.scaleFactor))
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestures.onTouchEvent(event)
        if (!scaleGestures.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (momentum == null) {
                val end = GestureMath.dragEnd(event.x, event.y)
                NativeEngine.nativeScroll(viewId, end.x, end.y, 0f, 0f, end.phase)
            }
        }
        return true
    }

    private fun send(events: List<GestureMath.PointerEvent>) {
        for (event in events)
            NativeEngine.nativeMouseEvent(viewId, event.type, event.x, event.y, event.button, event.buttons, 0, event.clickCount)
    }

    private fun startMomentum(x: Float, y: Float, deltas: List<Pair<Float, Float>>) {
        stopMomentum()
        if (deltas.isEmpty()) return
        var index = 0
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (index >= deltas.size) {
                    NativeEngine.nativeScroll(viewId, x, y, 0f, 0f, io.github.junkers4.ladybird.core.engine.ScrollPhase.ENDED)
                    momentum = null
                    return
                }
                val (dx, dy) = deltas[index++]
                NativeEngine.nativeScroll(viewId, x, y, dx, dy, io.github.junkers4.ladybird.core.engine.ScrollPhase.MOMENTUM)
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        momentum = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stopMomentum() {
        momentum?.let { Choreographer.getInstance().removeFrameCallback(it) }
        momentum = null
    }

    // --- Keys and IME ---------------------------------------------------------------------------

    fun setInputMethodEnabled(enabled: Boolean) {
        if (imeEnabled == enabled) return
        imeEnabled = enabled
        val manager = context.getSystemService(InputMethodManager::class.java) ?: return
        manager.restartInput(this)
        if (enabled) {
            requestFocus()
            manager.showSoftInput(this, 0)
        } else {
            manager.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    override fun onCheckIsTextEditor() = imeEnabled

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = if (imeEnabled) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_NULL
        outAttrs.imeOptions = InputPrivacy.imeOptions(EditorInfo.IME_FLAG_NO_FULLSCREEN, isPrivateTab, incognitoKeyboard())
        return EngineInputConnection()
    }

    private inner class EngineInputConnection : BaseInputConnection(this@WebContentView, false) {
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            NativeEngine.nativeImeCommit(viewId, text.toString())
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            NativeEngine.nativeImeSetComposing(viewId, text.toString())
            return true
        }

        override fun finishComposingText(): Boolean {
            NativeEngine.nativeImeFinishComposing(viewId)
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { sendEditingKey(LadybirdKeyCode.code("Backspace")) }
            repeat(afterLength) { sendEditingKey(LadybirdKeyCode.code("Delete")) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            dispatchToEngine(event)
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            sendEditingKey(LadybirdKeyCode.code("Return"), '\n'.code)
            return true
        }
    }

    private fun sendEditingKey(keyCode: Int, codePoint: Int = 0) {
        NativeEngine.nativeKeyEvent(viewId, KeyEventType.DOWN, keyCode, 0, codePoint, false)
        NativeEngine.nativeKeyEvent(viewId, KeyEventType.UP, keyCode, 0, codePoint, false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = dispatchToEngine(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = dispatchToEngine(event) || super.onKeyUp(keyCode, event)

    private fun dispatchToEngine(event: KeyEvent): Boolean {
        val ladybirdKey = KeyMapping.keyCode(event.keyCode)
        val codePoint = event.getUnicodeChar(event.metaState).takeIf { it > 0 } ?: 0
        if (ladybirdKey == 0 && codePoint == 0) return false
        val type = if (event.action == KeyEvent.ACTION_UP) KeyEventType.UP else KeyEventType.DOWN
        val modifiers = KeyMapping.modifiers(event.metaState, isKeypad = event.keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_EQUALS)
        NativeEngine.nativeKeyEvent(viewId, type, ladybirdKey, modifiers, codePoint, event.repeatCount > 0)
        return true
    }
}

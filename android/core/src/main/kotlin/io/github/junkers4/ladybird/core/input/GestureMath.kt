package io.github.junkers4.ladybird.core.input

import io.github.junkers4.ladybird.core.engine.MouseButton
import io.github.junkers4.ladybird.core.engine.MouseEventType
import io.github.junkers4.ladybird.core.engine.ScrollPhase
import kotlin.math.abs
import kotlin.math.exp

/**
 * Translates touch gestures into the mouse/wheel/pinch events Ladybird understands (requirement UI-010).
 * Coordinates are physical pixels; wheel deltas are CSS pixels (physical / density), positive when
 * the content should move up (scroll down), like Ladybird's Qt port.
 */
object GestureMath {
    data class PointerEvent(val type: Int, val x: Float, val y: Float, val button: Int, val buttons: Int, val clickCount: Int)

    data class WheelEvent(val x: Float, val y: Float, val deltaX: Float, val deltaY: Float, val phase: Int)

    /** A tap is a primary-button press and release at the same point. */
    fun tap(x: Float, y: Float, clickCount: Int = 1) = listOf(
        PointerEvent(MouseEventType.MOVE, x, y, MouseButton.NONE, MouseButton.NONE, 0),
        PointerEvent(MouseEventType.DOWN, x, y, MouseButton.PRIMARY, MouseButton.PRIMARY, clickCount),
        PointerEvent(MouseEventType.UP, x, y, MouseButton.PRIMARY, MouseButton.NONE, clickCount),
    )

    /** A long press opens the context menu, like a secondary-button click. */
    fun longPress(x: Float, y: Float) = listOf(
        PointerEvent(MouseEventType.MOVE, x, y, MouseButton.NONE, MouseButton.NONE, 0),
        PointerEvent(MouseEventType.DOWN, x, y, MouseButton.SECONDARY, MouseButton.SECONDARY, 1),
        PointerEvent(MouseEventType.UP, x, y, MouseButton.SECONDARY, MouseButton.NONE, 1),
    )

    /** GestureDetector.onScroll gives the distance moved since the last event (previous - current). */
    fun drag(x: Float, y: Float, distanceX: Float, distanceY: Float, density: Float) =
        WheelEvent(x, y, distanceX / density, distanceY / density, ScrollPhase.ONGOING)

    fun dragEnd(x: Float, y: Float) = WheelEvent(x, y, 0f, 0f, ScrollPhase.ENDED)

    /**
     * Momentum after a fling: exponentially decaying per-frame deltas until the speed drops below
     * [minimumSpeed] px/s. Velocities are physical px/s as reported by Android (positive when the
     * finger moves down/right, i.e. the content should scroll up/left).
     */
    fun flingDeltas(
        velocityX: Float,
        velocityY: Float,
        density: Float,
        frameSeconds: Float = 1f / 60f,
        friction: Float = 4f,
        minimumSpeed: Float = 30f,
        maximumFrames: Int = 240,
    ): List<Pair<Float, Float>> {
        val deltas = mutableListOf<Pair<Float, Float>>()
        var vx = velocityX
        var vy = velocityY
        val decay = exp(-friction * frameSeconds)
        while ((abs(vx) >= minimumSpeed || abs(vy) >= minimumSpeed) && deltas.size < maximumFrames) {
            deltas += (-vx * frameSeconds / density) to (-vy * frameSeconds / density)
            vx *= decay
            vy *= decay
        }
        return deltas
    }

    /** ScaleGestureDetector reports a factor relative to the previous event; Ladybird wants the delta. */
    fun pinchScaleDelta(scaleFactor: Float): Float = scaleFactor - 1f
}

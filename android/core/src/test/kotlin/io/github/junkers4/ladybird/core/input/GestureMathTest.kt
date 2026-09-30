package io.github.junkers4.ladybird.core.input

import io.github.junkers4.ladybird.core.Requirement
import io.github.junkers4.ladybird.core.engine.MouseButton
import io.github.junkers4.ladybird.core.engine.MouseEventType
import io.github.junkers4.ladybird.core.engine.ScrollPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

@Requirement("UI-010")
class GestureMathTest {
    @Test
    fun tapIsAPrimaryClick() {
        val events = GestureMath.tap(10f, 20f)
        assertEquals(listOf(MouseEventType.MOVE, MouseEventType.DOWN, MouseEventType.UP), events.map { it.type })
        assertEquals(MouseButton.PRIMARY, events[1].button)
        assertEquals(MouseButton.NONE, events[2].buttons)
    }

    @Test
    @Requirement("UI-006")
    fun longPressIsASecondaryClick() {
        assertEquals(MouseButton.SECONDARY, GestureMath.longPress(1f, 2f)[1].button)
    }

    @Test
    fun dragProducesCssPixelDeltas() {
        val wheel = GestureMath.drag(0f, 0f, distanceX = 0f, distanceY = 30f, density = 3f)
        assertEquals(10f, wheel.deltaY)
        assertEquals(ScrollPhase.ONGOING, wheel.phase)
        assertEquals(ScrollPhase.ENDED, GestureMath.dragEnd(0f, 0f).phase)
    }

    @Test
    fun flingDecays() {
        val deltas = GestureMath.flingDeltas(velocityX = 0f, velocityY = -3000f, density = 2f)
        assertTrue(deltas.size in 10..240)
        assertTrue(deltas.first().second > 0) // finger moved up -> content scrolls down
        assertTrue(deltas.zipWithNext().all { (a, b) -> abs(b.second) < abs(a.second) })
        assertEquals(emptyList<Pair<Float, Float>>(), GestureMath.flingDeltas(5f, 5f, 1f))
    }

    @Test
    fun pinch() {
        assertEquals(0.25f, GestureMath.pinchScaleDelta(1.25f), 1e-6f)
    }
}

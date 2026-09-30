package io.github.junkers4.ladybird.core.engine

import io.github.junkers4.ladybird.core.Requirement
import io.github.junkers4.ladybird.core.repo.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Requirement("ARCH-005", "UI-011")
class KeyMappingContractTest {
    private val upstream: Map<String, Int> by lazy {
        val header = Repo.text("ladybird/Libraries/LibCompositing/KeyCode.h")
        Regex("__ENUMERATE_KEY_CODE\\(\\s*(\\w+)\\s*,\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*,\\s*(0x[0-9A-Fa-f]+)\\s*\\)")
            .findAll(header)
            .associate { it.groupValues[1] to it.groupValues[2].removePrefix("0x").toInt(16) }
    }

    @Test
    fun everyKotlinKeyCodeMatchesUpstream() {
        assertTrue("could not parse KeyCode.h", upstream.size > 100)
        for ((name, value) in LadybirdKeyCode.VALUES)
            assertEquals("Key_$name", upstream[name], value)
    }

    @Test
    fun everyMappedAndroidKeyHasALadybirdCode() {
        for ((androidKeyCode, name) in KeyMapping.ANDROID_TO_LADYBIRD)
            assertTrue("Android key $androidKeyCode -> $name", KeyMapping.keyCode(androidKeyCode) != 0)
    }

    @Test
    fun commonKeys() {
        assertEquals(0x41, KeyMapping.keyCode(29)) // KEYCODE_A
        assertEquals(0x5A, KeyMapping.keyCode(54)) // KEYCODE_Z
        assertEquals(0x30, KeyMapping.keyCode(7)) // KEYCODE_0
        assertEquals(0x0D, KeyMapping.keyCode(66)) // KEYCODE_ENTER
        assertEquals(0x08, KeyMapping.keyCode(67)) // KEYCODE_DEL
        assertEquals(0x2E, KeyMapping.keyCode(112)) // KEYCODE_FORWARD_DEL
        assertEquals(0x7B, KeyMapping.keyCode(142)) // KEYCODE_F12
        assertEquals(0, KeyMapping.keyCode(4)) // KEYCODE_BACK is handled by the app
    }

    @Test
    fun modifiers() {
        assertEquals(LadybirdKeyModifier.SHIFT, KeyMapping.modifiers(0x1))
        assertEquals(LadybirdKeyModifier.CTRL or LadybirdKeyModifier.ALT, KeyMapping.modifiers(0x1000 or 0x2))
        assertEquals(LadybirdKeyModifier.SUPER or LadybirdKeyModifier.KEYPAD, KeyMapping.modifiers(0x10000, isKeypad = true))
        assertEquals(LadybirdKeyModifier.NONE, KeyMapping.modifiers(0))
        assertTrue(KeyMapping.isEditingKey(67))
    }
}

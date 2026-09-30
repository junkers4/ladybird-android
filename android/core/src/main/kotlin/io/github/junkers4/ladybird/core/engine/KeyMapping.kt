package io.github.junkers4.ladybird.core.engine

/**
 * Ladybird's Compositing::KeyCode values (requirement ARCH-005). KeyMappingContractTest checks every
 * entry against ladybird/Libraries/LibCompositing/KeyCode.h.
 */
object LadybirdKeyCode {
    val VALUES: Map<String, Int> = linkedMapOf(
        "Backspace" to 0x08, "Tab" to 0x09, "Return" to 0x0D, "LeftShift" to 0x10, "RightShift" to 0xB0,
        "LeftControl" to 0x11, "RightControl" to 0xB1, "LeftAlt" to 0x12, "RightAlt" to 0xB2, "PauseBreak" to 0x13,
        "CapsLock" to 0x14, "Escape" to 0x1B, "Space" to 0x20, "PageUp" to 0x21, "PageDown" to 0x22, "End" to 0x23,
        "Home" to 0x24, "Left" to 0x25, "Up" to 0x26, "Right" to 0x27, "Down" to 0x28, "SysRq" to 0x2C,
        "Delete" to 0x2E, "Plus" to 0x6A, "Minus" to 0x6B, "Slash" to 0x6C, "Comma" to 0x6D, "Period" to 0x6E,
        "F1" to 0x70, "F2" to 0x71, "F3" to 0x72, "F4" to 0x73, "F5" to 0x74, "F6" to 0x75, "F7" to 0x76,
        "F8" to 0x77, "F9" to 0x78, "F10" to 0x79, "F11" to 0x7A, "F12" to 0x7B, "Apostrophe" to 0x7D,
        "Insert" to 0x7E, "Semicolon" to 0x7F, "Equal" to 0x81, "LeftBracket" to 0x84, "RightBracket" to 0x85,
        "Backslash" to 0x86, "Backtick" to 0x8C, "NumLock" to 0x90, "ScrollLock" to 0x91, "LeftSuper" to 0x92,
        "RightSuper" to 0xAC, "BrowserSearch" to 0x93, "BrowserFavorites" to 0x94, "BrowserHome" to 0x95,
        "PreviousTrack" to 0x96, "BrowserBack" to 0x97, "BrowserForward" to 0x98, "BrowserRefresh" to 0x99,
        "BrowserStop" to 0x9A, "VolumeDown" to 0x9B, "VolumeUp" to 0x9C, "NextTrack" to 0x9F, "Mute" to 0xA6,
        "PlayPause" to 0xAA, "Menu" to 0xAB, "AtSign" to 0x62, "Hashtag" to 0x63, "Asterisk" to 0x68,
    ).apply {
        for (digit in 0..9) put(digit.toString(), 0x30 + digit)
        for (letter in 'A'..'Z') put(letter.toString(), 0x41 + (letter - 'A'))
    }

    fun code(name: String): Int = VALUES[name] ?: error("Unknown Ladybird key code $name")
}

/** Ladybird's Compositing::KeyModifier bits. */
object LadybirdKeyModifier {
    const val NONE = 0
    const val ALT = 1 shl 0
    const val CTRL = 1 shl 1
    const val SHIFT = 1 shl 2
    const val SUPER = 1 shl 3
    const val KEYPAD = 1 shl 4
}

/**
 * Translates Android key events (android.view.KeyEvent key codes and meta state, passed as plain
 * ints so this stays platform independent) to Ladybird key codes and modifiers.
 */
object KeyMapping {
    // android.view.KeyEvent.KEYCODE_* values.
    private const val KEYCODE_0 = 7
    private const val KEYCODE_A = 29

    val ANDROID_TO_LADYBIRD: Map<Int, String> = buildMap {
        for (digit in 0..9) put(KEYCODE_0 + digit, digit.toString())
        for (letter in 0 until 26) put(KEYCODE_A + letter, ('A' + letter).toString())
        put(17, "Asterisk") // KEYCODE_STAR
        put(18, "Hashtag") // KEYCODE_POUND
        put(19, "Up") // KEYCODE_DPAD_UP
        put(20, "Down")
        put(21, "Left")
        put(22, "Right")
        put(24, "VolumeUp")
        put(25, "VolumeDown")
        put(55, "Comma")
        put(56, "Period")
        put(57, "LeftAlt")
        put(58, "RightAlt")
        put(59, "LeftShift")
        put(60, "RightShift")
        put(61, "Tab")
        put(62, "Space")
        put(66, "Return") // KEYCODE_ENTER
        put(67, "Backspace") // KEYCODE_DEL
        put(68, "Backtick") // KEYCODE_GRAVE
        put(69, "Minus")
        put(70, "Equal")
        put(71, "LeftBracket")
        put(72, "RightBracket")
        put(73, "Backslash")
        put(74, "Semicolon")
        put(75, "Apostrophe")
        put(76, "Slash")
        put(77, "AtSign")
        put(81, "Plus")
        put(84, "BrowserSearch") // KEYCODE_SEARCH
        put(85, "PlayPause") // KEYCODE_MEDIA_PLAY_PAUSE
        put(86, "BrowserStop") // KEYCODE_MEDIA_STOP
        put(87, "NextTrack")
        put(88, "PreviousTrack")
        put(92, "PageUp")
        put(93, "PageDown")
        put(111, "Escape")
        put(112, "Delete") // KEYCODE_FORWARD_DEL
        put(113, "LeftControl")
        put(114, "RightControl")
        put(115, "CapsLock")
        put(116, "ScrollLock")
        put(117, "LeftSuper") // KEYCODE_META_LEFT
        put(118, "RightSuper")
        put(120, "SysRq")
        put(121, "PauseBreak") // KEYCODE_BREAK
        put(122, "Home") // KEYCODE_MOVE_HOME
        put(123, "End") // KEYCODE_MOVE_END
        put(124, "Insert")
        put(125, "BrowserForward") // KEYCODE_FORWARD
        for (function in 0 until 12) put(131 + function, "F${function + 1}")
        put(143, "NumLock")
        put(160, "Return") // KEYCODE_NUMPAD_ENTER
        put(164, "Mute") // KEYCODE_VOLUME_MUTE
        put(174, "BrowserFavorites") // KEYCODE_BOOKMARK
        put(285, "BrowserRefresh") // KEYCODE_REFRESH
    }

    // android.view.KeyEvent.META_* bits.
    private const val META_SHIFT_ON = 0x1
    private const val META_ALT_ON = 0x2
    private const val META_CTRL_ON = 0x1000
    private const val META_META_ON = 0x10000

    /** Returns the Ladybird key code for an Android key code, or 0 (Key_Invalid) when there is none. */
    fun keyCode(androidKeyCode: Int): Int = ANDROID_TO_LADYBIRD[androidKeyCode]?.let(LadybirdKeyCode::code) ?: 0

    fun modifiers(androidMetaState: Int, isKeypad: Boolean = false): Int {
        var modifiers = LadybirdKeyModifier.NONE
        if (androidMetaState and META_SHIFT_ON != 0) modifiers = modifiers or LadybirdKeyModifier.SHIFT
        if (androidMetaState and META_ALT_ON != 0) modifiers = modifiers or LadybirdKeyModifier.ALT
        if (androidMetaState and META_CTRL_ON != 0) modifiers = modifiers or LadybirdKeyModifier.CTRL
        if (androidMetaState and META_META_ON != 0) modifiers = modifiers or LadybirdKeyModifier.SUPER
        if (isKeypad) modifiers = modifiers or LadybirdKeyModifier.KEYPAD
        return modifiers
    }

    /** Key presses that edit text but that IMEs usually send as key events rather than text. */
    fun isEditingKey(androidKeyCode: Int): Boolean = androidKeyCode in setOf(61, 66, 67, 112, 19, 20, 21, 22, 92, 93, 122, 123, 160)
}

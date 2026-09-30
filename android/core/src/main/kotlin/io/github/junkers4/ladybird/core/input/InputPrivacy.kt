package io.github.junkers4.ladybird.core.input

/** Keyboard privacy (requirements SEC-013, SEC-014). */
object InputPrivacy {
    /** android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING */
    const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000

    fun useIncognitoKeyboard(isPrivateTab: Boolean, incognitoKeyboardSetting: Boolean) = isPrivateTab || incognitoKeyboardSetting

    fun imeOptions(baseOptions: Int, isPrivateTab: Boolean, incognitoKeyboardSetting: Boolean): Int =
        if (useIncognitoKeyboard(isPrivateTab, incognitoKeyboardSetting)) baseOptions or IME_FLAG_NO_PERSONALIZED_LEARNING else baseOptions

    /** FLAG_SECURE (no screenshots, blank recents thumbnail). */
    fun secureWindow(isPrivateTab: Boolean, screenshotProtectionSetting: Boolean) = isPrivateTab || screenshotProtectionSetting
}

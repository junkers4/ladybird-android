package io.github.junkers4.ladybird.core.engine

import io.github.junkers4.ladybird.core.Requirement
import io.github.junkers4.ladybird.core.repo.Repo
import org.junit.Assert.assertEquals
import org.junit.Test

/** Keeps the Kotlin side of the JNI protocol in sync with the native side. */
@Requirement("ARCH-004")
class BridgeProtocolContractTest {
    private val header = Repo.text("native/src/BridgeProtocol.h")

    /** Parses `enum Name : int { PrefixFooBar = 1, ... }` into FOO_BAR -> 1. */
    private fun nativeEnum(name: String, prefix: String): Map<String, Int> {
        val body = Regex("enum $name : int \\{(.*?)\\};", RegexOption.DOT_MATCHES_ALL).find(header)?.groupValues?.get(1)
            ?: error("enum $name not found")
        return Regex("(\\w+) = (\\d+),").findAll(body).associate { match ->
            val constant = match.groupValues[1].removePrefix(prefix)
            constant.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase() to match.groupValues[2].toInt()
        }
    }

    private fun kotlinConstants(holder: Any): Map<String, Int> =
        holder::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }

    @Test
    fun viewEvents() = assertEquals(nativeEnum("ViewEvent", "ViewEvent"), kotlinConstants(ViewEvent))

    @Test
    fun engineEvents() = assertEquals(nativeEnum("EngineEvent", "EngineEvent"), kotlinConstants(EngineEvent))

    @Test
    fun mouseEventTypes() = assertEquals(nativeEnum("MouseEventType", "MouseEvent"), kotlinConstants(MouseEventType))

    @Test
    fun mouseButtons() = assertEquals(nativeEnum("MouseButton", "MouseButton"), kotlinConstants(MouseButton))

    @Test
    fun scrollPhases() = assertEquals(nativeEnum("ScrollPhase", "ScrollPhase"), kotlinConstants(ScrollPhase))

    @Test
    fun dialogKinds() = assertEquals(nativeEnum("DialogKind", "DialogKind"), kotlinConstants(DialogKind))

    @Test
    fun keyEventTypes() = assertEquals(nativeEnum("KeyEventType", "KeyEvent"), kotlinConstants(KeyEventType))

    @Test
    fun jniRegistrationTableMatches() {
        val source = Repo.text("native/src/NativeEngineJNI.cpp")
        val registered = Regex("NATIVE_METHOD\\(\"(\\w+)\", \"([^\"]+)\"").findAll(source).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(NativeMethods.SIGNATURES, registered)
    }

    @Test
    fun callbackSignaturesMatch() {
        val source = Repo.text("native/src/JNIBridge.cpp")
        assert(source.contains("\"io/github/junkers4/ladybird/engine/NativeCallbacks\""))
        assert(source.contains("\"onViewEvent\", \"(JILjava/lang/String;Ljava/lang/String;JJ)V\""))
        assert(source.contains("\"onEngineEvent\", \"(ILjava/lang/String;)V\""))
        assert(source.contains("\"getClipboardText\", \"()Ljava/lang/String;\""))
        assert(source.contains("\"setClipboardText\", \"(Ljava/lang/String;)V\""))
    }

    @Test
    fun applicationMenusAndActionsExistNatively() {
        val application = Repo.text("native/src/AndroidApplication.cpp")
        for (menu in ApplicationMenus.ALL)
            assert(application.contains("menu_name == \"$menu\"sv")) { "menu $menu is not handled natively" }
        val jni = Repo.text("native/src/NativeEngineJNI.cpp")
        for (action in ApplicationActions.ALL)
            assert(jni.contains("action_name == \"$action\"sv")) { "action $action is not handled natively" }
    }
}

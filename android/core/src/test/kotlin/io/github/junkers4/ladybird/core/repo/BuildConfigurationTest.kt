package io.github.junkers4.ladybird.core.repo

import io.github.junkers4.ladybird.core.Requirement
import io.github.junkers4.ladybird.core.engine.NativeMethods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigurationTest {
    private val gradle = Repo.text("android/app/build.gradle.kts")

    @Test
    @Requirement("SEC-001")
    fun onlyAllowlistedDependencies() {
        val dependencies = Regex("^\\s*(implementation|api|runtimeOnly|compileOnly)\\((.*)\\)", RegexOption.MULTILINE)
            .findAll(gradle).map { it.groupValues[2] }.toList()
        val allowed = setOf(
            "project(\":core\")",
            "\"androidx.core:core-ktx",
            "\"androidx.appcompat:appcompat",
            "\"androidx.activity:activity-ktx",
            "\"androidx.recyclerview:recyclerview",
            "\"androidx.preference:preference-ktx",
            "\"com.google.android.material:material",
        )
        for (dependency in dependencies)
            assertTrue("dependency not on the allowlist: $dependency", allowed.any { dependency.startsWith(it) })
        val forbidden = listOf("firebase", "play-services", "gms", "crashlytics", "analytics", "sentry", "bugsnag", "admob", "appcenter")
        val source = Repo.root.resolve("android/app/src").walk().filter { it.isFile }.joinToString("\n") { it.readText() }
        for (word in forbidden) {
            assertTrue("$word in dependencies", dependencies.none { it.contains(word, ignoreCase = true) })
            assertTrue("$word in app sources", !source.contains("com.google.$word") && !source.contains("import $word"))
        }
    }

    @Test
    @Requirement("BLD-008")
    fun releaseBuildsAreMinifiedAndKeepTheJniSurface() {
        assertTrue(gradle.contains("isMinifyEnabled = true"))
        assertTrue(gradle.contains("isShrinkResources = true"))
        assertTrue(gradle.contains("useLegacyPackaging = true"))
        val rules = Repo.text("android/app/proguard-rules.pro")
        assertTrue(rules.contains("-keep class io.github.junkers4.ladybird.engine.NativeEngine { native <methods>; }"))
        for (callback in listOf("onViewEvent", "onEngineEvent", "getClipboardText", "setClipboardText"))
            assertTrue(callback, rules.contains(callback))
    }

    @Test
    @Requirement("ARCH-004")
    fun kotlinDeclaresEveryNativeMethod() {
        val source = Repo.text("android/app/src/main/java/io/github/junkers4/ladybird/engine/NativeEngine.kt")
        val declared = Regex("external fun (\\w+)\\(").findAll(source).map { it.groupValues[1] }.toSet()
        assertEquals(NativeMethods.SIGNATURES.keys, declared)
    }
}

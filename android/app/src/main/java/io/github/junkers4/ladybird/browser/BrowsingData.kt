package io.github.junkers4.ladybird.browser

import android.content.Context
import io.github.junkers4.ladybird.engine.EngineInstaller
import java.io.File

/** Clear-on-exit (SEC-015): removes the engine's profile (history, cookies, storage, cache), not its resources. */
object BrowsingData {
    fun clear(context: Context) {
        val layout = EngineInstaller(context).layout
        for (directory in listOf(layout.data, layout.cache, layout.downloads, layout.tmp)) File(directory).deleteRecursively()
        File(layout.config).listFiles()?.filter { it.name != ".lagom" }?.forEach { it.deleteRecursively() }
        // The engine keeps its databases open; end the process so nothing is written back.
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

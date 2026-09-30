package io.github.junkers4.ladybird.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Called from the engine thread (native/src/JNIBridge.cpp). Events are forwarded to the UI thread;
 * clipboard access is synchronous because LibWebView expects a value back.
 */
object NativeCallbacks {
    interface Listener {
        fun onViewEvent(viewId: Long, event: Int, text: String?, extra: String?, i1: Long, i2: Long)
        fun onEngineEvent(event: Int, text: String?)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile var listener: Listener? = null
    @Volatile var appContext: Context? = null

    @JvmStatic
    fun onViewEvent(viewId: Long, event: Int, text: String?, extra: String?, i1: Long, i2: Long) {
        mainHandler.post { listener?.onViewEvent(viewId, event, text, extra, i1, i2) }
    }

    @JvmStatic
    fun onEngineEvent(event: Int, text: String?) {
        mainHandler.post { listener?.onEngineEvent(event, text) }
    }

    @JvmStatic
    fun getClipboardText(): String? {
        val context = appContext ?: return null
        val result = AtomicReference<String?>()
        val done = CountDownLatch(1)
        mainHandler.post {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            result.set(clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString())
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        return result.get()
    }

    @JvmStatic
    fun setClipboardText(text: String?) {
        val context = appContext ?: return
        mainHandler.post {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Ladybird", text ?: ""))
        }
    }
}

package io.github.junkers4.ladybird.engine

import android.content.Context
import android.util.Log
import io.github.junkers4.ladybird.BuildConfig
import io.github.junkers4.ladybird.core.engine.EngineEvent
import io.github.junkers4.ladybird.core.privacy.EnginePolicy
import io.github.junkers4.ladybird.core.settings.BrowserSettings
import java.util.Locale

/**
 * Owns the engine's lifetime: installs resources, starts LibWebView's browser process on its thread and
 * pushes the privacy policy to it. Observers get engine/view events on the UI thread.
 */
object Engine : NativeCallbacks.Listener {
    enum class State { NOT_STARTED, STARTING, READY, UNAVAILABLE, FAILED }

    interface Observer {
        fun onEngineStateChanged(state: State, message: String?) {}
        fun onEngineEvent(event: Int, text: String?) {}
        fun onViewEvent(viewId: Long, event: Int, text: String?, extra: String?, i1: Long, i2: Long) {}
    }

    var state = State.NOT_STARTED
        private set
    private var failureMessage: String? = null
    private val observers = mutableListOf<Observer>()
    private var pendingPolicy: String? = null

    fun addObserver(observer: Observer) {
        observers += observer
        observer.onEngineStateChanged(state, failureMessage)
    }

    fun removeObserver(observer: Observer) {
        observers -= observer
    }

    fun start(context: Context, settings: BrowserSettings) {
        if (state != State.NOT_STARTED) return
        if (!BuildConfig.HAS_ENGINE || !NativeEngine.load()) {
            setState(State.UNAVAILABLE, null)
            return
        }
        setState(State.STARTING, null)
        NativeCallbacks.appContext = context.applicationContext
        NativeCallbacks.listener = this

        val installer = EngineInstaller(context.applicationContext)
        Thread({
            try {
                installer.install()
                installer.applyEnvironment()
                val started = NativeEngine.nativeStartEngine(installer.layout.root, installer.layout.resources, installer.layout.engineArguments().toTypedArray())
                if (!started) NativeCallbacks.onEngineEvent(EngineEvent.FAILED, "Unable to start the engine thread")
            } catch (error: Throwable) {
                Log.e("LadybirdEngine", "Engine installation failed", error)
                NativeCallbacks.onEngineEvent(EngineEvent.FAILED, error.message ?: error.toString())
            }
        }, "LadybirdInstaller").start()
        applyPolicy(settings)
    }

    /** Requirement SEC-006/SEC-007/ADB-001...: the engine always runs with the current policy. */
    fun applyPolicy(settings: BrowserSettings) {
        val json = EnginePolicy.toJson(settings, Locale.getDefault().language)
        if (state == State.READY) NativeEngine.nativeApplyPolicy(json) else pendingPolicy = json
    }

    val isReady get() = state == State.READY

    override fun onEngineEvent(event: Int, text: String?) {
        when (event) {
            EngineEvent.READY -> {
                setState(State.READY, null)
                pendingPolicy?.let { NativeEngine.nativeApplyPolicy(it) }
                pendingPolicy = null
            }
            EngineEvent.FAILED -> setState(State.FAILED, text)
        }
        observers.toList().forEach { it.onEngineEvent(event, text) }
    }

    override fun onViewEvent(viewId: Long, event: Int, text: String?, extra: String?, i1: Long, i2: Long) {
        observers.toList().forEach { it.onViewEvent(viewId, event, text, extra, i1, i2) }
    }

    private fun setState(newState: State, message: String?) {
        state = newState
        failureMessage = message
        observers.toList().forEach { it.onEngineStateChanged(newState, message) }
    }
}

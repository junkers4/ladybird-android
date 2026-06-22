package org.serenityos.ladybird

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Orchestrates the local privacy daemons behind the network compartments.
 *
 * The browser routes through a daemon by writing its proxy spec to
 * `filesDir/network_proxy`, which the RequestServer process reads at startup
 * (see RequestServerService.cpp). This controller owns the *other* half:
 * generating each daemon's config, starting/stopping it, tracking its bootstrap
 * state, and only publishing the proxy once the daemon is actually ready.
 *
 *  - Tor   → SOCKS5 on 127.0.0.1:9050 (config: torrc)
 *  - I2P   → HTTP   on 127.0.0.1:4444 (config: i2pd.conf)
 *
 * The daemons themselves are launched through [DaemonLauncher]. Until a native
 * Tor / i2pd library is bundled, [launcher] is a [MissingDaemonLauncher] that
 * reports the daemon unavailable, so the app stays fully usable in Normal mode
 * and degrades gracefully (with a clear message) if Tor/I2P is selected.
 */
class NetworkController(
    private val context: Context,
    // One launcher per proxied mode. Tor uses Guardian Project's bundled tor;
    // I2P stays on the missing-daemon launcher until i2pd is added.
    private val launchers: Map<NetworkMode, DaemonLauncher> = mapOf(
        NetworkMode.Tor to TorLauncher(context),
        NetworkMode.I2P to I2pLauncher(context),
    ),
    // I2P benefits from staying connected long-term, so when this is true we
    // don't tear its launcher down on switch-away (only on shutdown).
    private val keepI2pRunning: () -> Boolean = { true },
) {
    enum class State { Direct, Starting, Ready, Unavailable, Failed }

    private val configDir: File get() = File(context.filesDir, "net").apply { mkdirs() }
    private val proxyFile: File get() = File(context.filesDir, "network_proxy")

    private var current: NetworkMode = NetworkMode.Normal
    private var activeLauncher: DaemonLauncher? = null

    /**
     * Switch to [mode]. [onState] is invoked as the daemon progresses
     * (Starting → Ready/Failed/Unavailable), so the UI can show status and
     * recolor. The proxy file is written to the daemon's endpoint only once the
     * daemon reports ready; for Normal it is cleared immediately.
     */
    fun switchTo(mode: NetworkMode, onProgress: (Int) -> Unit = {}, onState: (State) -> Unit) {
        val previous = current
        current = mode
        // Tear down the previous mode's daemon — but keep I2P alive across
        // switches when the user asked to (it stays integrated in the network).
        val keepPrev = previous == NetworkMode.I2P && keepI2pRunning()
        if (!keepPrev) activeLauncher?.stopAll()
        activeLauncher = null

        if (mode == NetworkMode.Normal) {
            writeProxy("")
            onState(State.Direct)
            return
        }

        val launcher = launchers[mode]
        if (launcher == null) {
            writeProxy("")
            onState(State.Unavailable)
            return
        }
        activeLauncher = launcher

        // Apply the compartment's proxy up front — before the daemon has even
        // finished bootstrapping — so no request can leak out directly while it
        // starts. Requests now target the loopback proxy port (which simply isn't
        // answering yet); the exact spec is re-confirmed once it reports Ready.
        writeProxy(mode.proxySpec)

        onState(State.Starting)
        try {
            writeConfig(mode)
            startLauncher(launcher, mode, onProgress, onState)
        } catch (t: Throwable) {
            // A daemon must never be able to crash the browser; fall back to direct.
            Log.e(TAG, "starting ${mode.displayName} failed", t)
            writeProxy("")
            onState(State.Failed)
        }
    }

    private fun startLauncher(
        launcher: DaemonLauncher,
        mode: NetworkMode,
        onProgress: (Int) -> Unit,
        onState: (State) -> Unit,
    ) {
        launcher.start(mode, configDir, onProgress = { pct ->
            // Drop a late progress tick if the user already switched away.
            if (current == mode) onProgress(pct)
        }) { result ->
            // Ignore a late callback if the user switched again meanwhile.
            if (current != mode) return@start
            when (result) {
                is DaemonLauncher.Result.Ready -> {
                    writeProxy(result.proxySpec)
                    onState(State.Ready)
                }
                DaemonLauncher.Result.Unavailable -> {
                    writeProxy("")
                    onState(State.Unavailable)
                }
                DaemonLauncher.Result.Failed -> {
                    writeProxy("")
                    onState(State.Failed)
                }
            }
        }
    }

    fun shutdown() {
        activeLauncher?.stopAll()
        activeLauncher = null
    }

    private fun writeProxy(spec: String) {
        runCatching { proxyFile.writeText(spec) }
            .onFailure { Log.w(TAG, "could not write proxy file", it) }
    }

    /** Emit the daemon's config file so a freshly-started daemon binds the
     *  expected local proxy port and keeps its state under our files dir. */
    private fun writeConfig(mode: NetworkMode) {
        when (mode) {
            NetworkMode.Tor -> File(configDir, "torrc").writeText(
                """
                SocksPort ${mode.proxyPort}
                DataDirectory ${File(configDir, "tor-data").absolutePath}
                ClientOnly 1
                AvoidDiskWrites 1
                """.trimIndent()
            )
            NetworkMode.I2P -> File(configDir, "i2pd.conf").writeText(
                """
                [httpproxy]
                enabled = true
                address = 127.0.0.1
                port = ${mode.proxyPort}
                [general]
                datadir = ${File(configDir, "i2pd-data").absolutePath}
                """.trimIndent()
            )
            NetworkMode.Normal -> {}
        }
    }

    companion object {
        private const val TAG = "LadybirdNet"
    }
}

/** Pluggable backend that actually runs a daemon. The real implementation
 *  starts a bundled native Tor / i2pd and reports bootstrap progress. */
interface DaemonLauncher {
    sealed class Result {
        /** Daemon is up; route through [proxySpec] (e.g. "socks5://127.0.0.1:9050"). */
        data class Ready(val proxySpec: String) : Result()
        object Unavailable : Result()
        object Failed : Result()
    }
    fun start(
        mode: NetworkMode,
        configDir: File,
        onProgress: (Int) -> Unit = {},
        onResult: (Result) -> Unit,
    )
    fun stopAll()
}

/** Used for a mode whose daemon isn't bundled yet (currently I2P): reports the
 *  daemon missing so the UI can tell the user, and routing falls back to direct. */
class MissingDaemonLauncher : DaemonLauncher {
    override fun start(
        mode: NetworkMode,
        configDir: File,
        onProgress: (Int) -> Unit,
        onResult: (DaemonLauncher.Result) -> Unit,
    ) {
        Log.i("LadybirdNet", "${mode.displayName} daemon not bundled yet; staying direct")
        onResult(DaemonLauncher.Result.Unavailable)
    }

    override fun stopAll() {}
}

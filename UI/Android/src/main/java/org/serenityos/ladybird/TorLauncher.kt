package org.serenityos.ladybird

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.torproject.jni.TorService
import java.io.File

/**
 * Runs Tor via Guardian Project's bundled [TorService] (ships libtor.so for
 * arm64). Starting the service boots tor with a local SOCKS proxy; we listen
 * for its status broadcast and, once it reports ON, read the actual SOCKS port
 * and hand back "socks5://127.0.0.1:<port>" so all traffic routes over Tor.
 *
 * Only used for [NetworkMode.Tor]; [NetworkController] keeps the I2P slot on a
 * separate launcher.
 */
class TorLauncher(private val context: Context) : DaemonLauncher {

    private var receiver: BroadcastReceiver? = null
    private var binder: TorService.LocalBinder? = null
    private var connection: ServiceConnection? = null
    private var pending: ((DaemonLauncher.Result) -> Unit)? = null
    private var progress: ((Int) -> Unit)? = null
    @Volatile private var polling = false
    @Volatile private var torControlUp = false
    private var poller: Thread? = null

    override fun start(
        mode: NetworkMode,
        configDir: File,
        onProgress: (Int) -> Unit,
        onResult: (DaemonLauncher.Result) -> Unit,
    ) {
        if (mode != NetworkMode.Tor) {
            onResult(DaemonLauncher.Result.Unavailable)
            return
        }
        pending = onResult
        progress = onProgress
        torControlUp = false

        // Bind so we can read the chosen SOCKS port once tor is up.
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service as? TorService.LocalBinder
            }
            override fun onServiceDisconnected(name: ComponentName?) { binder = null }
        }
        connection = conn

        // Listen for tor status. TorService broadcasts ON/STARTING/OFF.
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                // Never let a status callback throw on the main thread (it would
                // crash the app); any failure just falls back to a direct route.
                try {
                    when (intent?.getStringExtra(TorService.EXTRA_STATUS)) {
                        TorService.STATUS_ON -> {
                            // The control port is up, but that does NOT mean tor has
                            // finished building a circuit — on a slow/roaming network
                            // it can sit at a low bootstrap % for a long time. So we
                            // do NOT report ready here; the poller reports ready only
                            // once bootstrap actually reaches 100% (real working
                            // circuit), which also keeps the logo filling honest.
                            Log.i(TAG, "Tor control up; waiting for full bootstrap…")
                            torControlUp = true
                            startBootstrapPolling()
                        }
                        TorService.STATUS_OFF -> Log.i(TAG, "Tor stopped")
                        TorService.STATUS_STARTING -> {
                            Log.i(TAG, "Tor bootstrapping…")
                            startBootstrapPolling()
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Tor status handling failed", t)
                    finish(DaemonLauncher.Result.Failed)
                }
            }
        }
        receiver = rcv
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(rcv, IntentFilter(TorService.ACTION_STATUS))

        try {
            val intent = Intent(context, TorService::class.java).apply { action = TorService.ACTION_START }
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            context.startService(intent)
            // Start polling right away (not only on the STATUS_STARTING broadcast,
            // which can be missed) so the logo's progress always moves rather than
            // sitting at 0%.
            startBootstrapPolling()
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start TorService", t)
            finish(DaemonLauncher.Result.Failed)
        }
    }

    /**
     * Tor reports its bootstrap as a percentage on the control port. TorService
     * owns an authenticated [net.freehaven.tor.control.TorControlConnection]; we
     * poll `status/bootstrap-phase` on it (read-only, so it never disturbs the
     * service's own event handling) and report 0→100 so the UI can fill a
     * progress bar and fade the onion logo from grey to full colour.
     */
    private fun startBootstrapPolling() {
        if (polling) return
        polling = true
        val startedAt = System.currentTimeMillis()
        poller = Thread {
            var last = -1
            var nullReads = 0
            while (polling) {
                // Real bootstrap % from the control port when it's readable.
                val real = runCatching {
                    binder?.service?.torControlConnection
                        ?.getInfo("status/bootstrap-phase")
                        ?.let { PROGRESS_RE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                }.getOrNull()
                nullReads = if (real == null) nullReads + 1 else 0
                // A gentle synthetic creep (caps at 90%) so the logo always shows
                // some movement even before the real % is readable; the real value
                // overrides it upward.
                val synthetic = ((System.currentTimeMillis() - startedAt) / 400L).toInt().coerceIn(0, 90)
                val pct = maxOf(real ?: 0, synthetic)
                if (pct != last) {
                    last = pct
                    progress?.invoke(pct)
                }
                // Ready only when tor reports a fully built circuit (100%)…
                if ((real ?: -1) >= 100) { reportReady(); break }
                // …or, if the control port is up but its bootstrap phase simply
                // can't be read after a while, trust STATUS_ON as a fallback.
                if (torControlUp && nullReads >= NULL_READS_BEFORE_READY) { reportReady(); break }
                // …or, if the control port is up but bootstrap has stalled below
                // 100% for too long (common on roaming/restrictive networks where
                // a full circuit never completes), stop waiting and route through
                // the SOCKS proxy anyway rather than leaving the user stuck on the
                // loading screen forever. Tor keeps finishing the remaining legs in
                // the background and serves the stream once it's actually requested.
                if (torControlUp && System.currentTimeMillis() - startedAt >= MAX_BOOTSTRAP_MS) {
                    Log.i(TAG, "Tor bootstrap stalled at ${real ?: 0}%; proceeding via SOCKS anyway")
                    reportReady(); break
                }
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun reportReady() {
        val port = binder?.service?.socksPort?.takeIf { it > 0 } ?: NetworkMode.Tor.proxyPort
        Log.i(TAG, "Tor ready on SOCKS $port")
        progress?.invoke(100)
        finish(DaemonLauncher.Result.Ready("socks5://127.0.0.1:$port"))
    }

    private fun stopBootstrapPolling() {
        polling = false
        poller?.interrupt()
        poller = null
    }

    private fun finish(result: DaemonLauncher.Result) {
        stopBootstrapPolling()
        pending?.invoke(result)
        pending = null
    }

    override fun stopAll() {
        stopBootstrapPolling()
        receiver?.let {
            runCatching { LocalBroadcastManager.getInstance(context).unregisterReceiver(it) }
        }
        receiver = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        binder = null
        runCatching {
            context.startService(
                Intent(context, TorService::class.java).apply { action = TorService.ACTION_STOP }
            )
        }
    }

    companion object {
        private const val TAG = "LadybirdNet"
        private const val POLL_MS = 400L
        // ~10s of unreadable bootstrap phase after the control port is up before
        // we trust STATUS_ON and report ready anyway.
        private const val NULL_READS_BEFORE_READY = 25
        // Hard cap on how long we wait for a full bootstrap once tor's control
        // port is up. Past this, we route via SOCKS even if stuck below 100%.
        private const val MAX_BOOTSTRAP_MS = 60_000L
        private val PROGRESS_RE = Regex("PROGRESS=(\\d+)")
    }
}

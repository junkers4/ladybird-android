package org.serenityos.ladybird

import android.content.Context
import android.util.Log
import org.purplei2p.i2pd.I2PD_JNI
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Routes through I2P using an **embedded** i2pd daemon (PurpleI2P), bundled as
 * src/main/jniLibs/arm64-v8a/libi2pd.so. Unlike the old bridge to the external
 * I2P Android app, this needs no other app installed: we run the router inside
 * our own process and it exposes an HTTP proxy on 127.0.0.1:4444 that the
 * RequestServer process reaches over loopback (all our processes share one
 * network namespace, exactly like the bundled Tor).
 *
 * First run: the daemon needs its reseed certificates in the data dir, so we
 * unpack res/raw/i2pd_assets.zip (certificates/ + addressbook/) there, write a
 * minimal i2pd.conf enabling the HTTP proxy, and drop the `assets.ready` marker
 * the native start() waits for. Then setDataDir + startDaemon.
 */
class I2pLauncher(private val context: Context) : DaemonLauncher {

    override fun start(
        mode: NetworkMode,
        configDir: File,
        onProgress: (Int) -> Unit,
        onResult: (DaemonLauncher.Result) -> Unit,
    ) {
        if (mode != NetworkMode.I2P) {
            onResult(DaemonLauncher.Result.Unavailable)
            return
        }

        // The daemon is a native singleton; never start it twice in one process.
        synchronized(lock) {
            if (started) {
                onProgress(100)
                onResult(DaemonLauncher.Result.Ready(PROXY))
                return
            }
        }

        Thread {
            try {
                val dataDir = File(context.filesDir, "i2pd").apply { mkdirs() }
                prepareDataDir(dataDir)
                onProgress(8)

                synchronized(lock) {
                    // Re-check under the lock: a racing switch may have started it.
                    if (!started) {
                        if (!libLoaded) { I2PD_JNI.loadLibraries(); libLoaded = true }
                        I2PD_JNI.setDataDir(dataDir.absolutePath)
                        val res = I2PD_JNI.startDaemon()
                        if (!res.equals("ok", ignoreCase = true)) {
                            Log.e(TAG, "i2pd start failed: $res")
                            onResult(DaemonLauncher.Result.Failed)
                            return@Thread
                        }
                        I2PD_JNI.startAcceptingTunnels()
                        started = true
                    }
                }
                Log.i(TAG, "i2pd started; warming up on $PROXY")
                onProgress(20)

                // 1) Wait for the HTTP proxy acceptor to start listening (ramp 20→55).
                var pct = 20
                var waited = 0L
                while (!I2PD_JNI.getHTTPProxyState() && waited < PROXY_WAIT_MS) {
                    pct = (pct + 3).coerceAtMost(55)
                    onProgress(pct)
                    Thread.sleep(POLL_MS)
                    waited += POLL_MS
                }
                if (!I2PD_JNI.getHTTPProxyState()) {
                    Log.e(TAG, "i2pd HTTP proxy never came up")
                    onResult(DaemonLauncher.Result.Failed)
                    return@Thread
                }

                // 2) The proxy is up; I2P still needs time to build client tunnels.
                // Smoothly ramp the logo 55→100 over a fixed warm-up so it never
                // sticks, then report ready. (Routing a request needs tunnels, which
                // is inherent to I2P — the first page load may still take a moment.)
                val warmStart = System.currentTimeMillis()
                while (true) {
                    val frac = (System.currentTimeMillis() - warmStart).toFloat() / WARMUP_MS
                    pct = (55 + frac * 45).toInt().coerceIn(55, 100)
                    onProgress(pct)
                    if (pct >= 100) break
                    Thread.sleep(POLL_MS)
                }
                Log.i(TAG, "i2pd warmed up; ready on $PROXY")
                onProgress(100)
                onResult(DaemonLauncher.Result.Ready(PROXY))
            } catch (t: Throwable) {
                // A daemon must never crash the browser; fall back to direct.
                Log.e(TAG, "i2pd launch failed", t)
                onResult(DaemonLauncher.Result.Failed)
            }
        }.apply { isDaemon = true; name = "i2pd-launch" }.start()
    }

    /** Unpack reseed certificates + write config + drop the assets.ready marker
     *  the native start() blocks on. Idempotent: re-runnable on every start. */
    private fun prepareDataDir(dataDir: File) {
        if (!File(dataDir, "certificates").isDirectory) {
            context.resources.openRawResource(R.raw.i2pd_assets).use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val out = File(dataDir, entry.name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
        }
        File(dataDir, "i2pd.conf").writeText(buildConf())
        File(dataDir, "assets.ready").writeText("")
    }

    /** Base config plus the addressbook section, which the user can toggle. */
    private fun buildConf(): String {
        val addressbook = if (AppSettings(context).i2pAddressbookSubscriptions)
            ADDRESSBOOK_ON else ADDRESSBOOK_OFF
        return I2PD_CONF + "\n\n" + addressbook + "\n"
    }

    /** Embedded daemon stays in-process; only torn down on full shutdown. */
    override fun stopAll() {
        synchronized(lock) {
            if (!started) return
            runCatching { I2PD_JNI.stopAcceptingTunnels() }
            runCatching { I2PD_JNI.stopDaemon() }
            started = false
        }
    }

    companion object {
        private const val TAG = "LadybirdNet"
        private const val PROXY = "http://127.0.0.1:4444"
        private const val POLL_MS = 500L
        // How long to wait for the HTTP proxy to start listening, then how long to
        // warm up (let tunnels build) while ramping the logo to 100%.
        private const val PROXY_WAIT_MS = 20_000L
        private const val WARMUP_MS = 35_000L
        private val lock = Any()
        @Volatile private var libLoaded = false
        @Volatile private var started = false

        // Minimal client config: HTTP proxy on the port NetworkMode.I2P expects,
        // no transit (keep it light on mobile), no web console / extra acceptors.
        private val I2PD_CONF = """
            ipv4 = true
            ipv6 = false
            notransit = true
            floodfill = false

            [httpproxy]
            enabled = true
            address = 127.0.0.1
            port = 4444
            # Route clearnet (non-.i2p) requests through a public I2P outproxy so
            # normal sites also load in the I2P compartment; .i2p eepsites are
            # served directly. Without this only eepsites would work.
            outproxy = http://exit.stormycloud.i2p

            [socksproxy]
            enabled = false

            [sam]
            enabled = false

            [i2cp]
            enabled = false

            [http]
            enabled = false

            [reseed]
            verify = true
        """.trimIndent()

        // Addressbook subscriptions: hosts.txt lists published inside I2P that map
        // .i2p names to destinations, fetched over I2P from a b32 address (which
        // needs no addressbook lookup itself), so eepsites resolve without a jump
        // service. The b32 is the canonical I2P name registry (reg.i2p).
        private val ADDRESSBOOK_ON = """
            [addressbook]
            defaulturl = http://shx5vqsw7usdaunyzr2qmes2fq37oumybpudrd4jjj4e4vk4uusa.b32.i2p/hosts.txt
            subscriptions = http://shx5vqsw7usdaunyzr2qmes2fq37oumybpudrd4jjj4e4vk4uusa.b32.i2p/hosts.txt
        """.trimIndent()

        // No subscriptions: rely only on the bundled addressbook + jump services.
        private val ADDRESSBOOK_OFF = """
            [addressbook]
            subscriptions =
        """.trimIndent()
    }
}

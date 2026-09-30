package io.github.junkers4.ladybird.engine

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import io.github.junkers4.ladybird.core.engine.HelperProcesses
import io.github.junkers4.ladybird.core.resources.ResourceInstallPlan
import io.github.junkers4.ladybird.core.security.CertificateBundle
import java.io.File
import java.security.KeyStore
import java.util.zip.ZipInputStream

/**
 * Prepares the on-disk layout LibWebView expects (see ResourceInstallPlan) and the process environment:
 * resources extracted once per app version (ARCH-008), helper executables exposed through libexec/
 * symlinks (ARCH-001) and a trust store with system CAs only (SEC-009).
 */
class EngineInstaller(private val context: Context) {
    val layout = ResourceInstallPlan.Layout(File(context.filesDir, "ladybird").absolutePath)

    fun install() {
        for (directory in listOf(layout.bin, layout.libexec, layout.resources, layout.config, layout.data, layout.cache, layout.downloads, layout.tmp))
            File(directory).mkdirs()

        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
        val stampFile = File(layout.root, ResourceInstallPlan.STAMP_FILE)
        val stamp = ResourceInstallPlan.stamp(versionCode, packageInfo.lastUpdateTime)
        if (ResourceInstallPlan.needsInstall(stampFile.takeIf { it.exists() }?.readText(), versionCode, packageInfo.lastUpdateTime)) {
            File(layout.resources).deleteRecursively()
            File(layout.resources).mkdirs()
            extractResources()
            stampFile.writeText(stamp)
        }

        linkHelpers()
        symlink(layout.resources, layout.lagomLink)
        writeCertificateBundle()
    }

    fun environment(): Map<String, String> = layout.environment(context.applicationInfo.nativeLibraryDir)

    fun applyEnvironment() {
        for ((name, value) in environment()) Os.setenv(name, value, true)
    }

    private fun extractResources() {
        val target = File(layout.resources).canonicalFile
        context.assets.open("engine/ladybird-resources.zip").use { stream ->
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val file = File(target, entry.name).canonicalFile
                    // Zip-slip protection.
                    if (!file.path.startsWith(target.path + File.separator)) continue
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
    }

    private fun linkHelpers() {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        for (helper in HelperProcesses.ALL) {
            val binary = File(nativeDir, HelperProcesses.libraryFileName(helper))
            if (!binary.exists()) {
                Log.e(TAG, "Missing helper executable $binary")
                continue
            }
            symlink(binary.absolutePath, layout.helperLink(helper))
        }
    }

    private fun symlink(target: String, link: String) {
        val linkFile = File(link)
        runCatching { if (Os.readlink(link) == target) return }
        linkFile.delete()
        runCatching { Os.symlink(target, link) }.onFailure { Log.e(TAG, "symlink $link -> $target failed", it) }
    }

    private fun writeCertificateBundle() {
        val anchors = linkedMapOf<String, ByteArray>()
        runCatching {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            for (alias in store.aliases()) {
                if (CertificateBundle.isSystemAnchor(alias)) store.getCertificate(alias)?.let { anchors[alias] = it.encoded }
            }
        }.onFailure { Log.e(TAG, "Unable to read the system CA store", it) }
        File(layout.certificateBundle).writeText(CertificateBundle.build(anchors))
    }

    companion object {
        private const val TAG = "LadybirdInstaller"
    }
}

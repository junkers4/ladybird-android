package io.github.junkers4.ladybird.core.resources

/**
 * Decides when Ladybird's resources must be (re)extracted from the APK (requirement ARCH-008) and what the
 * on-disk layout the engine expects looks like (requirement ARCH-001).
 *
 *   <files>/ladybird/bin/                     LibWebView's "binary directory" (empty)
 *   <files>/ladybird/libexec/<Helper>      -> <nativeLibraryDir>/lib<Helper>.so
 *   <files>/ladybird/share/Lagom/             resources
 *   <files>/ladybird/config/.lagom         -> ../share/Lagom (so helpers find resources via XDG_CONFIG_HOME)
 *   <files>/ladybird/{config,data,cache,downloads,tmp}
 */
object ResourceInstallPlan {
    const val STAMP_FILE = ".installed-version"

    fun needsInstall(installedStamp: String?, appVersionCode: Long, apkUpdateTime: Long): Boolean =
        installedStamp?.trim() != stamp(appVersionCode, apkUpdateTime)

    fun stamp(appVersionCode: Long, apkUpdateTime: Long) = "$appVersionCode:$apkUpdateTime"

    data class Layout(val root: String) {
        val bin = "$root/bin"
        val libexec = "$root/libexec"
        val resources = "$root/share/Lagom"
        val config = "$root/config"
        val data = "$root/data"
        val cache = "$root/cache"
        val downloads = "$root/downloads"
        val tmp = "$root/tmp"
        val certificateBundle = "$root/cacert.pem"
        val lagomLink = "$config/.lagom"

        fun helperLink(helper: String) = "$libexec/$helper"

        /** Environment for the browser process; helper processes inherit it. */
        fun environment(nativeLibraryDir: String): Map<String, String> = linkedMapOf(
            "HOME" to root,
            "XDG_CONFIG_HOME" to config,
            "XDG_DATA_HOME" to data,
            "XDG_CACHE_HOME" to cache,
            "XDG_RUNTIME_DIR" to tmp,
            "XDG_DOWNLOAD_DIR" to downloads,
            "TMPDIR" to tmp,
            "LD_LIBRARY_PATH" to nativeLibraryDir,
        )

        /** Command line for LibWebView::Application. */
        fun engineArguments(): List<String> = listOf("--certificate", certificateBundle)
    }
}

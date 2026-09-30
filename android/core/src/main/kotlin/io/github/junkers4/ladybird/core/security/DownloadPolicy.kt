package io.github.junkers4.ladybird.core.security

/** Download naming and risk classification (requirement SEC-018). */
object DownloadPolicy {
    private val DANGEROUS_EXTENSIONS = setOf("apk", "apks", "xapk", "apkm", "aab", "dex", "jar", "sh", "bat", "cmd", "ps1", "exe", "msi", "dll", "so", "elf", "bin", "run")
    private const val MAX_NAME_LENGTH = 200

    fun sanitizeFileName(suggested: String): String {
        val lastSegment = suggested.replace('\\', '/').substringAfterLast('/')
        val cleaned = buildString {
            for (character in lastSegment) {
                when {
                    character.isISOControl() -> Unit
                    character in "<>:\"|?*" -> append('_')
                    else -> append(character)
                }
            }
        }.trim().trimStart('.').trim()
        val name = cleaned.ifEmpty { "download" }
        if (name.length <= MAX_NAME_LENGTH) return name
        val extension = name.substringAfterLast('.', "").take(16)
        val base = name.substringBeforeLast('.').take(MAX_NAME_LENGTH - extension.length - 1)
        return if (extension.isEmpty()) name.take(MAX_NAME_LENGTH) else "$base.$extension"
    }

    fun isDangerous(fileName: String): Boolean {
        val lower = fileName.lowercase()
        // "report.pdf.apk" must be caught too: check every suffix, not just the last one.
        return lower.split('.').drop(1).any { it in DANGEROUS_EXTENSIONS }
    }
}

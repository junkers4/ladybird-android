package io.github.junkers4.ladybird.core.repo

import java.io.File

/** Access to files elsewhere in the repository (the Gradle test task passes its root). */
object Repo {
    val root: File by lazy {
        System.getProperty("repo.root")?.let(::File)
            ?: generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "requirements").isDirectory }
    }

    fun file(path: String): File = File(root, path).also { check(it.exists()) { "missing $it" } }

    fun text(path: String): String = file(path).readText()
}

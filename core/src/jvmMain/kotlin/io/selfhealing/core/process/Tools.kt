package io.selfhealing.core.process

import java.io.File

/**
 * Locates CLI tools. Apps started from Finder/Dock get a minimal PATH, so the usual install
 * locations (Homebrew, Maestro's ~/.maestro/bin) are checked as well.
 */
object Tools {
    private val home = System.getProperty("user.home")
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun find(name: String, vararg extraDirs: String): String? {
        val exe = if (isWindows) listOf("$name.exe", "$name.bat", "$name.cmd", name) else listOf(name)
        val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator) + extraDirs + listOf("/opt/homebrew/bin", "/usr/local/bin")
        return dirs.asSequence().filter { it.isNotBlank() }
            .flatMap { dir -> exe.asSequence().map { File(dir, it) } }
            .firstOrNull { it.isFile && it.canExecute() }?.path
    }

    /** [configured] as given when it is a path, else resolved (Maestro installs into ~/.maestro/bin). */
    fun maestro(configured: String): String =
        if (configured.contains(File.separatorChar)) configured
        else find(configured.ifBlank { "maestro" }, "$home/.maestro/bin") ?: configured.ifBlank { "maestro" }

    /** AXe drives iOS simulator input (taps, swipes, buttons); null when not installed. */
    fun axe(): String? = find("axe")
}

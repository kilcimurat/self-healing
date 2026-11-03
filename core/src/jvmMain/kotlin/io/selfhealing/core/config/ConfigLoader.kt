package io.selfhealing.core.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

object ConfigLoader {
    private val envPattern = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}""")
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    fun load(file: File): AppConfig {
        require(file.isFile) { "Config file not found: ${file.absolutePath}" }
        return parse(file.readText()).copy(baseDir = file.absoluteFile.parent)
    }

    fun parse(text: String, env: Map<String, String> = System.getenv()): AppConfig =
        yaml.decodeFromString(AppConfig.serializer(), substituteEnv(text, env))

    /** Replaces `${VAR}` and `${VAR:-default}` with environment values so secrets can stay out of the file. */
    fun substituteEnv(text: String, env: Map<String, String>): String =
        text.lines().joinToString("\n") { line ->
            // `#` at line start or after whitespace begins a YAML comment; leave comments untouched.
            val comment = Regex("""(^|\s)#""").find(line)?.range?.last ?: line.length
            substituteLine(line.substring(0, comment), env) + line.substring(comment)
        }

    private fun substituteLine(text: String, env: Map<String, String>): String =
        envPattern.replace(text) { m ->
            val value = env[m.groupValues[1]] ?: m.groups[2]?.value
                ?: error("Environment variable ${m.groupValues[1]} referenced in config is not set")
            // A placeholder that is the whole scalar is emitted quoted so empty values and `:`/`#` survive YAML.
            val wholeScalar = text.substring(0, m.range.first).trimEnd().let { it.endsWith(":") || it.endsWith("-") } &&
                text.substring(m.range.last + 1).isBlank()
            if (wholeScalar) Json.encodeToString(String.serializer(), value) else value
        }
}

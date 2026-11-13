import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** One key of wallet.properties, with the comment above it. */
internal data class WalletSetting(val key: String, val value: String, val doc: String, val allowed: LongRange) {
    /** `scan.stop.after.unused.keys` is `scanStopAfterUnusedKeys`. */
    val field = key.split('.').let { words -> words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) } }

    /** `scan.stop.after.unused.keys` is `WW_SCAN_STOP_AFTER_UNUSED_KEYS`. */
    val env = "WW_" + key.uppercase().replace('.', '_')

    val isMillis = key.endsWith(".millis")
}

private val ALLOWED = Regex("""\s*Allowed: (\d+) to (\d+)(?: \(([^)]*)\))?\.""")
private val DEFAULT = Regex("""Default: (\S+)\.""")

/** 300000 is `5m`: the largest of d, h, m and s it is a whole number of, else ms. */
internal fun millisAsDuration(millis: Long): String =
    if (millis == 0L) "0"
    else
        listOf("d" to 86_400_000L, "h" to 3_600_000L, "m" to 60_000L, "s" to 1000L)
            .firstOrNull { (_, unit) -> millis % unit == 0L }
            ?.let { (name, unit) -> "${millis / unit}$name" } ?: "${millis}ms"

/**
 * The keys of wallet.properties: a key's documentation is the comment right above it, which ends with what it allows. A `.millis` key's
 * comment also says its default and bounds as durations (`Default: 5m. Allowed: 1000 to 86400000 (1s to 1d).`), which must match.
 */
internal fun parseWalletSettings(text: String): List<WalletSetting> {
    val settings = mutableListOf<WalletSetting>()
    var comment = mutableListOf<String>()
    for (line in text.lines().map(String::trim)) {
        when {
            line.isEmpty() -> comment = mutableListOf()
            line.startsWith("#") -> comment += line.removePrefix("#").trim()
            else -> {
                val (key, value) = line.split('=', limit = 2)
                val doc = comment.joinToString(" ")
                val allowed = checkNotNull(ALLOWED.find(doc)) { "wallet.properties: $key needs a comment ending in 'Allowed: 1 to 10.'" }
                val (min, max, durations) = allowed.destructured
                val setting = WalletSetting(key, value, doc.replace(ALLOWED, ""), min.toLong()..max.toLong())
                if (setting.isMillis) {
                    val bounds = "${millisAsDuration(min.toLong())} to ${millisAsDuration(max.toLong())}"
                    check(durations == bounds) {
                        "wallet.properties: $key needs its bounds as durations, 'Allowed: $min to $max ($bounds).'"
                    }
                    value.trim().toLongOrNull()?.let(::millisAsDuration)?.let { default ->
                        check(DEFAULT.find(doc)?.groupValues?.get(1) == default) {
                            "wallet.properties: $key needs 'Default: $default.' in its comment"
                        }
                    }
                }
                settings += setting
                comment = mutableListOf()
            }
        }
    }
    return settings
}

/** The settings overridden for this build, by `-P<key>` or else the environment's `WW_<KEY>`. */
fun Project.walletSettingOverrides(): Map<String, String> {
    val file = rootProject.layout.projectDirectory.file("wallet.properties")
    return parseWalletSettings(providers.fileContents(file).asText.get())
        .mapNotNull { s -> providers.gradleProperty(s.key).orElse(providers.environmentVariable(s.env)).orNull?.let { s.key to it } }
        .toMap()
}

/** Writes wallet.properties, with this build's overrides, as the data class `WalletSettings`. */
abstract class GenerateWalletSettings : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val source: RegularFileProperty

    @get:Input abstract val overrides: MapProperty<String, String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val settings = parseWalletSettings(source.get().asFile.readText())
        val overridden = overrides.get()
        val fields =
            settings.joinToString("\n") { s ->
                val value = overridden[s.key] ?: s.value
                val number = value.trim().toLongOrNull()
                check(number != null && number in s.allowed) {
                    "${s.key} must be a whole number from ${s.allowed.first} to ${s.allowed.last}, not '$value'"
                }
                val type = if (s.isMillis) "Long = ${number}L" else "Int = $number"
                "    /** ${s.doc} */\n    val ${s.field}: $type,"
            }
        val checks =
            settings.joinToString("\n") { s ->
                val (min, max) =
                    if (s.isMillis) "${s.allowed.first}L" to "${s.allowed.last}L" else "${s.allowed.first}" to "${s.allowed.last}"
                "        require(${s.field} in $min..$max) { \"${s.key} must be from ${s.allowed.first} to ${s.allowed.last}\" }"
            }
        val file = outputDir.get().file("whitewallet/core/wallet/WalletSettings.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |// Generated from wallet.properties: change it there.
            |package whitewallet.core.wallet
            |
            |/** How the wallet behaves: wallet.properties at the root of the repository. Tests pass their own. */
            |data class WalletSettings(
            |$fields
            |) {
            |    init {
            |$checks
            |    }
            |}
            |
            """
                .trimMargin()
        )
    }
}

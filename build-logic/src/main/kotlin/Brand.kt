import java.io.File
import java.util.Properties
import org.gradle.api.Project

/** The brand directory: brand/, or another one with -PbrandDir=/path/to/brand. */
internal fun Project.brandDir(): File =
    providers.gradleProperty("brandDir").map { rootProject.file(it) }.getOrElse(rootProject.file("brand"))

internal fun Project.brandProperties(): Properties = Properties().apply { brandDir().resolve("brand.properties").reader().use(::load) }

/** A duration in milliseconds, written with its unit: `500ms`, `30s`, `15m`, `1h`, `7d`. */
internal fun parseBrandDuration(key: String, value: String): Long {
    val match =
        checkNotNull(Regex("""(\d+)\s*(ms|s|m|h|d)""").matchEntire(value.trim())) {
            "brand.properties: $key must be a number with a unit (ms, s, m, h, d), like 30s, not '$value'"
        }
    val (amount, unit) = match.destructured
    val millisPerUnit = mapOf("ms" to 1L, "s" to 1_000L, "m" to 60_000L, "h" to 3_600_000L, "d" to 86_400_000L)
    return amount.toLong() * millisPerUnit.getValue(unit)
}

/** [Properties.getProperty] as a duration in [min]..[max], [default] when unset. */
internal fun Properties.brandDuration(key: String, default: String, min: String, max: String? = null): Long {
    val millis = parseBrandDuration(key, getProperty(key, default))
    val lower = parseBrandDuration(key, min)
    val upper = max?.let { parseBrandDuration(key, it) } ?: Long.MAX_VALUE
    check(millis in lower..upper) {
        "brand.properties: $key must be at least $min" + (max?.let { " and at most $it" } ?: "") + ", not '${getProperty(key)}'"
    }
    return millis
}

/** [Properties.getProperty] as one of [allowed], [default] when unset. */
internal fun Properties.brandChoice(key: String, default: String, vararg allowed: String): String {
    val value = getProperty(key, default).trim()
    check(value in allowed) { "brand.properties: $key must be ${allowed.joinToString(", ")}, not '$value'" }
    return value
}

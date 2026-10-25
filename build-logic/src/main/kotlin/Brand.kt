import java.io.File
import java.util.Properties
import org.gradle.api.Project

/** The brand directory: brand/, or another one with -PbrandDir=/path/to/brand. */
internal fun Project.brandDir(): File =
    providers.gradleProperty("brandDir").map { rootProject.file(it) }.getOrElse(rootProject.file("brand"))

internal fun Project.brandProperties(): Properties = Properties().apply { brandDir().resolve("brand.properties").reader().use(::load) }

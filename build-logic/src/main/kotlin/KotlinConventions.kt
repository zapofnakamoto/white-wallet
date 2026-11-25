import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

// A Kotlin warning fails the build, so warnings are fixed when they appear instead of piling up. A brand's own code
// (:brand-code, from its brandDir) is the brand's to judge: there they stay warnings.
internal fun Project.configureKotlinWarnings() {
    if (path == ":brand-code") return
    tasks.withType<KotlinCompilationTask<*>>().configureEach { compilerOptions.allWarningsAsErrors.set(true) }
}

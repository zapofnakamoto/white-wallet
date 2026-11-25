import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType

// The heap of each test JVM, with up to org.gradle.workers.max of them running at once. 512m is Gradle's own default, set
// here where it can be seen and changed: the busiest test JVMs keep about 210 MB live and spend under 1% of their time in GC.
// Change it for one machine with whitewallet.test.maxHeap in ~/.gradle/gradle.properties or -Pwhitewallet.test.maxHeap=1g
// (docs/DEVELOPMENT.md, "Tests").
internal fun Project.configureTestJvms() {
    val maxHeap = providers.gradleProperty("whitewallet.test.maxHeap").orElse("512m")
    tasks.withType<Test>().configureEach { maxHeapSize = maxHeap.get() }
}

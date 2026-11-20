package whitewallet.screenshots

import io.github.classgraph.ClassGraph
import java.lang.reflect.Modifier
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DefaultBrandDesign

/**
 * The brand's design, as its app gets it: from the brand's code module (docs/WHITE_LABEL.md, "Custom code"), whose Dagger module provides
 * it — `@Provides fun design(): BrandDesign = …`, or `@Binds` to an object or a class made without arguments. The app's whole Hilt graph
 * isn't built here, so a design that needs something injected can't be drawn. Without a brand design: the default.
 */
object BrandDesigns {
    val design: BrandDesign by lazy { find() ?: DefaultBrandDesign }

    private fun find(): BrandDesign? {
        val providers =
            ClassGraph()
                .enableClassInfo()
                .enableAnnotationInfo()
                .enableMethodInfo()
                .rejectPackages("whitewallet", "androidx", "android")
                .scan()
                .use { scan ->
                    scan.getClassesWithAnnotation("dagger.Module").flatMap { module ->
                        module.declaredMethodInfo
                            .filter { it.hasAnnotation("dagger.Provides") || it.hasAnnotation("dagger.Binds") }
                            .map { it.loadClassAndGetMethod() to it.hasAnnotation("dagger.Binds") }
                            .filter { (method, _) -> method.returnType == BrandDesign::class.java }
                    }
                }
        check(providers.size <= 1) { "more than one BrandDesign in the brand's code: ${providers.map { it.first }}" }
        val (method, binds) = providers.singleOrNull() ?: return null
        if (binds) return instance(method.parameterTypes.single()) as BrandDesign
        check(method.parameterCount == 0) {
            "the brand's design needs something injected ($method); screenshots can draw only one made without"
        }
        val receiver = if (Modifier.isStatic(method.modifiers)) null else instance(method.declaringClass)
        method.isAccessible = true
        return method.invoke(receiver) as BrandDesign
    }

    /** A Kotlin object's instance, or a new one of a class made without arguments. */
    private fun <T> instance(type: Class<T>): T {
        type.declaredFields
            .firstOrNull { it.name == "INSTANCE" && Modifier.isStatic(it.modifiers) }
            ?.let {
                return type.cast(it.get(null))
            }
        return type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    }
}

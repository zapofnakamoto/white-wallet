package whitewallet.screenshots

import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DefaultBrandDesign

/**
 * The brand's design, as its app gets it: from the brand's code module (docs/WHITE_LABEL.md, "Custom code"), whose Dagger module provides
 * it — `@Provides fun design(): BrandDesign = …` in an object or a module's companion, or `@Binds` to an object or a class made without
 * arguments. The app's whole Hilt graph isn't built here, so a design that needs something injected can't be drawn. Without a brand design,
 * or without brand code: the default.
 */
object BrandDesigns {
    val design: BrandDesign by lazy {
        // The brand code module's directory (its classes are built under it), empty without one.
        val brandCode = System.getProperty("screenshots.brandCode").orEmpty()
        if (brandCode.isEmpty()) DefaultBrandDesign else find { filterClasspathElements { it.startsWith(brandCode) } } ?: DefaultBrandDesign
    }

    /** The design provided by a Dagger module among the classes [scope] picks; null when none provides one. */
    fun find(scope: ClassGraph.() -> ClassGraph): BrandDesign? {
        val providers =
            ClassGraph().enableClassInfo().enableAnnotationInfo().enableMethodInfo().scope().scan().use { scan ->
                scan.getClassesWithAnnotation("dagger.Module").flatMap { module ->
                    // A module's companion object holds its @Provides when the module itself is abstract (it has @Binds too).
                    listOfNotNull(module, scan.getClassInfo(module.name + "\$Companion")).flatMap(::designMethods)
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

    /** The methods of [type] that provide or bind a BrandDesign, each with whether it binds. */
    private fun designMethods(type: ClassInfo): List<Pair<Method, Boolean>> =
        type.declaredMethodInfo
            .filter { it.hasAnnotation("dagger.Provides") || it.hasAnnotation("dagger.Binds") }
            .map { it.loadClassAndGetMethod() to it.hasAnnotation("dagger.Binds") }
            .filter { (method, _) -> method.returnType == BrandDesign::class.java }

    /** A Kotlin object's instance (or a companion's), or a new one of a class made without arguments. */
    private fun <T> instance(type: Class<T>): T {
        type.declaredFields
            .firstOrNull { it.name == "INSTANCE" && Modifier.isStatic(it.modifiers) }
            ?.let {
                return checkNotNull(type.cast(it.get(null))) { "${type.name} has no instance" }
            }
        type.enclosingClass
            ?.declaredFields
            ?.firstOrNull { it.type == type && Modifier.isStatic(it.modifiers) }
            ?.let {
                it.isAccessible = true
                return checkNotNull(type.cast(it.get(null))) { "${type.name} has no instance" }
            }
        return type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    }
}

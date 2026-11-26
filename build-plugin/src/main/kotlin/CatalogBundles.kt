import java.util.concurrent.ConcurrentHashMap
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependencyBundle
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.kotlin.dsl.getByType

/**
 * The `libs.bundles.<name>` shorthand for version catalogs actually serializes the entire catalog when passing
 * the value into each module, which adds to configuration time.
 *
 * This replacement resolves each bundle once on first use and shares the result across all modules.
 * Gradle also copies each dependency when it is added, so a module's `exclude` or `because` does not escape.
 *
 * @param name the bundle name as written in `libs.versions.toml`, e.g. `shared-android`
 */
internal fun Project.bundle(name: String): Provider<ExternalModuleDependencyBundle> {
    val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val dependencies = gradle.sharedServices
        .registerIfAbsent("catalogBundles", CatalogBundles::class.java) {}
        .get()
        .bundles.computeIfAbsent(name) {
            catalog.findBundle(name)
                .orElseThrow { IllegalArgumentException("Bundle '$name' not found in version catalog") }
                .get()
        }

    // we must return Property<ExternalModuleDependencyBundle> so Gradle will detect and expand it
    return objects.property(ExternalModuleDependencyBundle::class.java).value(
        provider { CatalogBundle(dependencies) },
    )
}

internal abstract class CatalogBundles : BuildService<BuildServiceParameters.None> {
    val bundles = ConcurrentHashMap<String, List<MinimalExternalModuleDependency>>()
}

private class CatalogBundle(dependencies: List<MinimalExternalModuleDependency>) :
    ArrayList<MinimalExternalModuleDependency>(dependencies),
    ExternalModuleDependencyBundle

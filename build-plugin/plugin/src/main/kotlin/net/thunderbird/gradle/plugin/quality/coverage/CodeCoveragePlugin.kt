package net.thunderbird.gradle.plugin.quality.coverage

import kotlinx.kover.gradle.plugin.KoverGradlePlugin
import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import kotlinx.kover.gradle.plugin.dsl.KoverVerificationRulesConfig
import net.thunderbird.gradle.plugin.quality.coverage.filter.androidFilter
import net.thunderbird.gradle.plugin.quality.coverage.filter.commonFilter
import net.thunderbird.gradle.plugin.quality.coverage.filter.composeFilter
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
import org.gradle.process.JavaForkOptions

/**
 * A Gradle plugin that configures code coverage using the Kover plugin.
 *
 * It sets up default coverage thresholds and allows for customization via the [CodeCoverageExtension].
 *
 * Coverage is disabled by default and Kover is only applied when it is enabled for the build, using a Gradle property
 * or environment variable:
 *  - Gradle property: `-PcodeCoverageDisabled=false`
 *  - Environment variable: `CODE_COVERAGE_DISABLED=false`
 *
 * Example usage in a build script:
 *
 * ```kotlin
 * plugins {
 *    id("net.thunderbird.gradle.plugin.quality.coverage")
 * }
 *
 * codeCoverage {
 *    disabled.set(true) // Opt this module out when coverage is enabled for the build
 *    lineCoverage = 80 // Set line coverage threshold
 *    branchCoverage = 70 // Set branch coverage threshold
 * }
 */
class CodeCoveragePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val extension = target.extensions.create<CodeCoverageExtension>("codeCoverage")

        val gradleProperty = target.providers.gradleProperty("codeCoverageDisabled").map { it.toBoolean() }
        val environmentProperty = target.providers.environmentVariable("CODE_COVERAGE_DISABLED")
            .map { it.equals("true", ignoreCase = true) }
        val disabledProvider = environmentProperty.orElse(gradleProperty).orElse(true)

        extension.initialize()
        extension.disabled.convention(disabledProvider)
        extension.finalizeValueOnRead()

        // skip Kover config when disabled
        if (disabledProvider.get()) return

        with(target) {
            with(pluginManager) {
                apply(KoverGradlePlugin::class)
            }

            // Ensure forked Kover JVMs get a larger CodeCache
            configureCodeCacheForKoverJvms(
                reservedCodeCacheSize = "256m",
                initialCodeCacheSize = "128m",
            )

            // Defer configuration until after all build scripts had a chance
            // to configure the `codeCoverage { ... }` extension.
            afterEvaluate {
                configureKover(
                    coverageExtension = extension,
                    isRoot = this == rootProject,
                )
            }
        }
    }

    private fun Project.configureKover(coverageExtension: CodeCoverageExtension, isRoot: Boolean) {
        extensions.configure<KoverProjectExtension>("kover") {
            if (coverageExtension.disabled.get()) {
                disable()
            }

            // See https://www.jacoco.org/jacoco/
            useJacoco("0.8.14")

            if (isRoot) {
                merge {
                    allProjects()
                }
            }

            currentProject {
                sources {
                    excludedSourceSets.addAll(
                        "androidMainResourceCollectors",
                        "commonMainResourceAccessors",
                        "commonMainResourceCollectors",
                        "commonResClass",
                        "jvmMainResourceCollectors",
                    )
                }
            }

            reports {
                total {
                    filters {
                        commonFilter()
                        composeFilter()
                        androidFilter()
                    }
                }

                verify {
                    warningInsteadOfFailure.set(false)

                    applyVerificationRules(coverageExtension)
                }
            }
        }
    }

    private fun KoverVerificationRulesConfig.applyVerificationRules(coverageExtension: CodeCoverageExtension) {
        rule("branchCoveragePercentage") {
            disabled.set(coverageExtension.disabled)
            bound {
                minValue.set(coverageExtension.branchCoverage)
                coverageUnits.set(CoverageUnit.BRANCH)
                aggregationForGroup.set(AggregationType.COVERED_PERCENTAGE)
            }
        }
        rule("lineCoveragePercentage") {
            disabled.set(coverageExtension.disabled)
            bound {
                minValue.set(coverageExtension.lineCoverage)
                coverageUnits.set(CoverageUnit.LINE)
                aggregationForGroup.set(AggregationType.COVERED_PERCENTAGE)
            }
        }
    }

    /**
     * Ensure forked Kover JVMs get a larger CodeCache. Test JVMs get theirs from the convention plugins.
     */
    private fun Project.configureCodeCacheForKoverJvms(
        reservedCodeCacheSize: String,
        initialCodeCacheSize: String,
    ) {
        val args = listOf(
            "-XX:ReservedCodeCacheSize=$reservedCodeCacheSize",
            "-XX:InitialCodeCacheSize=$initialCodeCacheSize",
        )

        // Kover-related tasks that fork JVMs (varies by Kover version)
        tasks.matching { it.name.contains("kover", ignoreCase = true) }.configureEach {
            (this as? JavaForkOptions)?.let { fork ->
                fork.jvmArgs((fork.jvmArgs ?: emptyList()) + args)
            }
        }
    }
}

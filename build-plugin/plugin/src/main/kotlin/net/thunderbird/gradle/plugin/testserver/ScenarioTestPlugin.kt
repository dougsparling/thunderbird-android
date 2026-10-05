package net.thunderbird.gradle.plugin.testserver

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.file.Directory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.registerIfAbsent
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider

/**
 * Wires scenario tests (end-to-end tests of the app against a real IMAP server) into an app module's unit tests.
 *
 * Scenario tests live in [ScenarioTestExtension.packageName] and are excluded from normal unit test runs. They run
 * when either
 * - `-PscenarioTests` is set: they run in [ScenarioTestExtension.defaultTestTask] only, or
 * - a `--tests` filter on the command line mentions "scenario" (this is what Android Studio does when running a single
 *   scenario test or the scenario package): they run in whichever unit test task was requested.
 *
 * Only then is the test mail server wired in. By default an Apache James server is started on first use by
 * [JamesTestServerService] and stopped when the build finishes. To use an already running server instead, pass
 * `-Ptestserver.imap=host:port -Ptestserver.domain=... [-Ptestserver.admin=url] [-Ptestserver.smtp=host:port]
 * [-Ptestserver.pop3=host:port] [-Ptestserver.kind=james]`.
 *
 * Test JVMs receive the endpoint as `testserver.*` system properties, computed only when the tests actually start.
 *
 * The decision is made from the start parameters during configuration, but nothing that needs the network is
 * resolved during configuration: James is declared in the `jamesServer` configuration, which is only resolved when a
 * scenario test task executes.
 */
class ScenarioTestPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {

            val extension = extensions.create<ScenarioTestExtension>("scenarioTests").apply {
                packageName.convention("")
                defaultTestTask.convention("test")
                domain.convention("scenario.test")
                startupTimeoutSeconds.convention(DEFAULT_STARTUP_TIMEOUT_SECONDS)
            }

            val jamesClasspath = configurations.create(JAMES_CONFIGURATION) {
                description = "Apache James server started for scenario tests. Resolved only when they run."
                isCanBeConsumed = false
                isCanBeResolved = true
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
                    attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
                    attribute(
                        TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                        objects.named(TargetJvmEnvironment.STANDARD_JVM),
                    )
                    attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, JAMES_JAVA_VERSION)
                }
            }

            val requestedTestFilters = requestedTestFilters()
            val scenarioProperty = providers.gradleProperty(SCENARIO_PROPERTY)
                .map { it.isBlank() || it.toBoolean() }
                .getOrElse(false)
            val filterTargetsScenarios = requestedTestFilters.any { it.contains("scenario", ignoreCase = true) }
            val anyScenarioRequested = scenarioProperty || filterTargetsScenarios

            if (anyScenarioRequested) {
                // Scenario tests boot the real Application under Robolectric, which needs the merged resources.
                pluginManager.withPlugin("com.android.application") {
                    extensions.configure(ApplicationExtension::class.java) {
                        testOptions.unitTests.isIncludeAndroidResources = true
                    }
                }
            }

            val serverArguments: Provider<CommandLineArgumentProvider> = provider {
                externalServerArguments() ?: JamesServerArguments(registerJamesService(extension, jamesClasspath))
            }

            tasks.withType<Test>().configureEach {
                val scenarioPattern = "${extension.packageName.get()}.*"
                val runScenarios = filterTargetsScenarios ||
                    (scenarioProperty && name == extension.defaultTestTask.get())

                if (!runScenarios) {
                    filter.excludeTestsMatching(scenarioPattern)
                    return@configureEach
                }

                configureForScenarios(serverArguments.get())
            }
        }
    }

    private fun Test.configureForScenarios(arguments: CommandLineArgumentProvider) {
        if (arguments is JamesServerArguments) {
            usesService(arguments.service)
        }
        jvmArgumentProviders.add(arguments)

        // The app keeps state in Kotlin objects that can't be reset between tests, so every test class gets a fresh
        // JVM. Scenario tests use one user per test on a shared server, so classes can run in parallel.
        forkEvery = 1
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, MAX_PARALLEL_FORKS)

        // Results depend on an external server, never on inputs Gradle can see.
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Scenario tests run against a live test mail server") { true }

        // Scenario tests print the network transcript when they fail; show it on the console.
        testLogging {
            events(TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = true
        }
    }

    private fun Project.registerJamesService(
        extension: ScenarioTestExtension,
        jamesClasspath: org.gradle.api.artifacts.Configuration,
    ): Provider<JamesTestServerService> {
        val rootDirectory = isolated.rootProject.projectDirectory
        val buildDirectory: Directory = rootDirectory.dir("build")

        return gradle.sharedServices.registerIfAbsent("jamesTestServer", JamesTestServerService::class) {
            parameters {
                classpath.from(jamesClasspath)
                configDirectory = extension.jamesConfigDirectory.orElse(rootDirectory.dir(DEFAULT_CONFIG_DIRECTORY))
                workDirectory = buildDirectory.dir("testserver/james")
                domain = extension.domain
                startupTimeoutSeconds = extension.startupTimeoutSeconds
            }
        }
    }

    private fun Project.externalServerArguments(): StaticServerArguments? {
        val imap = providers.gradleProperty("testserver.imap").orNull ?: return null
        val domain = providers.gradleProperty("testserver.domain").orNull
            ?: error("-Ptestserver.imap is set, so -Ptestserver.domain must be set too")

        return StaticServerArguments(
            buildList {
                add("-Dtestserver.kind=${providers.gradleProperty("testserver.kind").getOrElse("james")}")
                add("-Dtestserver.imap=$imap")
                add("-Dtestserver.domain=$domain")
                providers.gradleProperty("testserver.admin").orNull?.let { add("-Dtestserver.admin=$it") }
                providers.gradleProperty("testserver.smtp").orNull?.let { add("-Dtestserver.smtp=$it") }
                providers.gradleProperty("testserver.pop3").orNull?.let { add("-Dtestserver.pop3=$it") }
            },
        )
    }

    /** Values of all `--tests` options on the command line, e.g. from Android Studio. */
    private fun Project.requestedTestFilters(): List<String> {
        return gradle.startParameter.taskRequests.flatMap { request ->
            val args = request.args
            args.flatMapIndexed { index, arg ->
                when {
                    arg == "--tests" -> listOfNotNull(args.getOrNull(index + 1))
                    arg.startsWith("--tests=") -> listOf(arg.removePrefix("--tests="))
                    else -> emptyList()
                }
            }
        }
    }

    private companion object {
        const val JAMES_CONFIGURATION = "jamesServer"
        const val SCENARIO_PROPERTY = "scenarioTests"
        const val DEFAULT_CONFIG_DIRECTORY = "mail/testserver/james/conf"

        // James 3.9 is built for Java 21.
        const val JAMES_JAVA_VERSION = 21
        const val DEFAULT_STARTUP_TIMEOUT_SECONDS = 180
        const val MAX_PARALLEL_FORKS = 4
    }
}

abstract class ScenarioTestExtension {
    /** Package containing the scenario tests, e.g. `net.thunderbird.android.scenario`. */
    abstract val packageName: Property<String>

    /** Unit test task that runs scenario tests when `-PscenarioTests` is set. */
    abstract val defaultTestTask: Property<String>

    /** Mail domain that test users are created in. */
    abstract val domain: Property<String>

    abstract val startupTimeoutSeconds: Property<Int>

    /** Directory with James configuration templates. Defaults to `mail/testserver/james/conf` in the root project. */
    abstract val jamesConfigDirectory: org.gradle.api.file.DirectoryProperty
}

/** Passes the endpoint of the build's James server; starts it on first use. */
internal class JamesServerArguments(
    @get:Internal val service: Provider<JamesTestServerService>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val endpoint = service.get().endpoint()
        return listOf(
            "-Dtestserver.kind=james",
            "-Dtestserver.imap=${endpoint.imapHost}:${endpoint.imapPort}",
            "-Dtestserver.smtp=${endpoint.imapHost}:${endpoint.smtpPort}",
            "-Dtestserver.pop3=${endpoint.imapHost}:${endpoint.pop3Port}",
            "-Dtestserver.admin=${endpoint.adminUrl}",
            "-Dtestserver.domain=${endpoint.domain}",
        )
    }
}

/** Passes the endpoint of a server that was started outside of Gradle. */
internal class StaticServerArguments(
    @get:Input val arguments: List<String>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = arguments
}

pluginManagement {
    // Optional mainland-China mirrors, off by default. Must be applied before any repositories are declared.
    // This build resolves plugins as regular dependencies, so it also needs the Plugin Portal mirror there.
    settings.extra["chinaMirrors.pluginMirrorForDependencies"] = true
    apply(from = "../gradle/china-mirrors.settings.gradle.kts")

    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    @Suppress("UnstableApiUsage")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }

    versionCatalogs.create("libs") {
        from(files("../gradle/libs.versions.toml"))
    }
}

rootProject.name = "build-plugin"

include(":plugin")

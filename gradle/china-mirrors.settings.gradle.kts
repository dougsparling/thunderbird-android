// Optional mainland-China Maven mirrors, see docs/developer/china-mirrors.md
//
// Enable with `useChinaMirrors=true` in ~/.gradle/gradle.properties or the project's local.properties,
// `-PuseChinaMirrors=true`, or the environment variable `USE_CHINA_MIRRORS=true`. When disabled, this script does
// nothing.
//
// Apply this at the top of the `pluginManagement {}` block in a settings file, before any repositories are declared,
// so the mirrors are tried before the official repositories.
//
// Builds that resolve Gradle plugins as regular dependencies (like build-plugin) set
// `settings.extra["chinaMirrors.pluginMirrorForDependencies"] = true` before applying this script, so the
// Gradle Plugin Portal mirror is also used in `dependencyResolutionManagement`.

val pluginMirrorForDependencies = settings.extra.properties["chinaMirrors.pluginMirrorForDependencies"] == true

// The main build's directory, also when applied from build-plugin or components, which sit one level below it
val projectRoot = generateSequence(settings.rootDir) { it.parentFile }
    .first { File(it, "gradle/china-mirrors.settings.gradle.kts").isFile }

// local.properties isn't checked in, so it can turn on the mirrors for one checkout (and Android Studio) only
val localPropertiesSwitch = providers
    .fileContents(settings.layout.rootDirectory.file(File(projectRoot, "local.properties").absolutePath))
    .asText
    .map { text -> java.util.Properties().apply { load(text.reader()) }.getProperty("useChinaMirrors").orEmpty() }
    .filter { it.isNotBlank() }

val useChinaMirrors = providers.gradleProperty("useChinaMirrors")
    .orElse(providers.environmentVariable("USE_CHINA_MIRRORS"))
    .orElse(localPropertiesSwitch)
    .map(String::toBoolean)
    .getOrElse(false)

if (useChinaMirrors) {
    // Groups that settings.gradle.kts routes to a dedicated repository (Mozilla Maven, JitPack). The mirrors don't
    // proxy those repositories, and some hold incomplete copies (e.g. a POM without its jar), which would break
    // resolution because Gradle downloads artifacts from the repository the metadata came from.
    val dedicatedRepositoryGroups = listOf(
        "org.mozilla.components",
        "org.mozilla.telemetry",
        "com.github.ByteHamster",
        "com.github.cketti",
    )

    // Groups that the general mirrors (Aliyun public, Tencent) hold incomplete copies of, but that the Plugin Portal
    // mirror has in full. They're left to the Plugin Portal mirror.
    val pluginPortalGroups = listOf(
        // Tencent has the POM of gradle-versions-plugin 0.64.0 but not its jar
        "io.github.ben-manes",
    )

    fun MavenRepositoryContentDescriptor.mirrorContent() {
        releasesOnly()
        dedicatedRepositoryGroups.forEach(::excludeGroup)
    }

    fun MavenRepositoryContentDescriptor.generalMirrorContent() {
        mirrorContent()
        pluginPortalGroups.forEach(::excludeGroup)
    }

    fun RepositoryHandler.aliyunGoogle() = maven(url = "https://maven.aliyun.com/repository/google") {
        name = "AliyunGoogle"
        mavenContent {
            releasesOnly()
            includeGroupAndSubgroups("androidx")
            includeGroupAndSubgroups("com.android")
            includeGroupAndSubgroups("com.google")
        }
    }

    fun RepositoryHandler.aliyunGradlePlugin() = maven(url = "https://maven.aliyun.com/repository/gradle-plugin") {
        name = "AliyunGradlePlugin"
        mavenContent { mirrorContent() }
    }

    fun RepositoryHandler.aliyunPublic() = maven(url = "https://maven.aliyun.com/repository/public") {
        name = "AliyunPublic"
        mavenContent { generalMirrorContent() }
    }

    fun RepositoryHandler.tencentPublic() = maven(url = "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") {
        name = "TencentPublic"
        mavenContent { generalMirrorContent() }
    }

    settings.pluginManagement.repositories {
        aliyunGoogle()
        aliyunGradlePlugin()
        aliyunPublic()
        tencentPublic()
    }

    settings.dependencyResolutionManagement.repositories {
        aliyunGoogle()
        aliyunPublic()
        tencentPublic()
        // Last among the mirrors, so Central and Google artifacts don't go through the Plugin Portal proxy. Only
        // artifacts that exist solely on the Plugin Portal (e.g. plugin marker artifacts) should come from here.
        if (pluginMirrorForDependencies) aliyunGradlePlugin()
    }
}

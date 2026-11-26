import net.thunderbird.gradle.plugin.ProjectConfig

plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("net.thunderbird.gradle.plugin.quality.coverage")
    id("net.thunderbird.gradle.plugin.quality.detekt")
    id("net.thunderbird.gradle.plugin.quality.spotless")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        compileSdk = ProjectConfig.Android.sdkCompile
        minSdk = ProjectConfig.Android.sdkMin

        androidResources.enable = true

        withHostTest {
            isIncludeAndroidResources = true
        }

        compilerOptions {
            jvmTarget.set(ProjectConfig.Compiler.jvmTarget)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(ProjectConfig.Compiler.jvmTarget)
        }
    }

    sourceSets {
        val isComponentsBuild = rootProject.name == "components"

        commonMain.dependencies {
            implementation(project.dependencies.platform(libs.kotlin.bom))
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(project.dependencies.platform(libs.tb.mobile.components.bom))

            implementation(bundle("shared-kmp-common"))
            implementation(bundle("shared-kmp-compose-common"))

            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.ui)
            implementation(libs.jetbrains.compose.components.resources)
            implementation(libs.jetbrains.compose.components.ui.preview)

            implementation(bundle("shared-tmc-components"))
            if (!isComponentsBuild) {
                implementation(libs.tb.mobile.components.ui.bolt)
            }
        }

        commonTest.dependencies {
            implementation(bundle("shared-kmp-common-test"))
            implementation(bundle("shared-kmp-compose-common-test"))

            implementation(bundle("shared-tmc-components-test"))
            if (!isComponentsBuild) {
                implementation(libs.tb.mobile.components.ui.testing)
            }
        }

        androidMain.dependencies {
            implementation(bundle("shared-kmp-android"))
            implementation(bundle("shared-kmp-compose-android"))
        }

        androidHostTest.dependencies {
            implementation(bundle("shared-kmp-android-test"))
            implementation(bundle("shared-kmp-compose-android-test"))
        }

        jvmMain.dependencies {
            implementation(bundle("shared-kmp-jvm"))
            implementation(bundle("shared-kmp-compose-jvm"))
        }

        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(bundle("shared-kmp-jvm-test"))
            implementation(bundle("shared-kmp-compose-jvm-test"))
        }
    }
}

configureKotlinJavaCompatibility()

tasks.withType<Test>().configureEach {
    jvmArgs(ProjectConfig.Testing.robolectricJvmArgs)
}

tasks.register("testsOnCi") {
    dependsOn(
        tasks.withType<Test>(),
    )
}

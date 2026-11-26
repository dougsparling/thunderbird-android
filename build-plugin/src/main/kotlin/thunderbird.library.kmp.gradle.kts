import net.thunderbird.gradle.plugin.ProjectConfig

plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
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

        // Without this, Android library dependencies resolve to their release variant, so debug app builds compile
        // those modules a second time.
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("debug", "release"))
        }

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
        commonMain.dependencies {
            implementation(project.dependencies.platform(libs.kotlin.bom))
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(project.dependencies.platform(libs.tb.mobile.components.bom))

            implementation(bundle("shared-kmp-common"))
            implementation(bundle("shared-tmc-components"))
        }

        commonTest.dependencies {
            implementation(bundle("shared-kmp-common-test"))
            implementation(bundle("shared-tmc-components-test"))
        }

        androidMain.dependencies {
            implementation(bundle("shared-kmp-android"))
        }

        androidHostTest.dependencies {
            implementation(bundle("shared-kmp-android-test"))
        }

        jvmMain.dependencies {
            implementation(bundle("shared-kmp-jvm"))
        }

        jvmTest.dependencies {
            implementation(bundle("shared-kmp-jvm-test"))
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

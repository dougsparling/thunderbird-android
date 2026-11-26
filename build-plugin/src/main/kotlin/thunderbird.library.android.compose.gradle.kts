import com.android.build.api.variant.HostTestBuilder

plugins {
    id("thunderbird.library.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("net.thunderbird.gradle.plugin.quality.coverage")
    id("net.thunderbird.gradle.plugin.quality.detekt")
    id("net.thunderbird.gradle.plugin.quality.spotless")
}

android {
    testOptions {
        // AGP's Compose Preview task (since AGP 9.5) requires Android resources in unit tests, like the KMP setup
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    beforeVariants { variantBuilder ->
        // AGP's Compose Preview task (since AGP 9.5) requires unit tests for every variant
        @Suppress("UnstableApiUsage")
        variantBuilder.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        variantBuilder.enableAndroidTest = false
    }
}

dependencies {
    val isComponentsBuild = rootProject.name == "components"
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(bundle("shared-android"))
    implementation(bundle("shared-android-compose"))
    implementation(bundle("shared-tmc-components"))

    debugImplementation(bundle("shared-android-compose-debug"))

    testImplementation(bundle("shared-android-test"))
    testImplementation(bundle("shared-android-compose-test"))
    testImplementation(bundle("shared-tmc-components-test"))

    androidTestImplementation(bundle("shared-android-compose-androidTest"))

    if (!isComponentsBuild) {
        implementation(libs.tb.mobile.components.ui.bolt)
        testImplementation(libs.tb.mobile.components.ui.testing)
    }
}

import com.android.build.api.variant.HostTestBuilder

plugins {
    id("thunderbird.app.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("net.thunderbird.gradle.plugin.quality.coverage")
    id("net.thunderbird.gradle.plugin.quality.detekt")
    id("net.thunderbird.gradle.plugin.quality.spotless")
}

android {
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testOptions {
        // AGP's Compose Preview task (since AGP 9.5) requires Android resources in unit tests
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        jniLibs {
            keepDebugSymbols += listOf(
                "**/libandroidx.graphics.path.so",
                "**/libdatastore_shared_counter.so",
                "**/libimage_processing_util_jni.so",
                "**/libsurface_util_jni.so",
            )
        }
    }
}

androidComponents {
    beforeVariants { variantBuilder ->
        // AGP's Compose Preview task (since AGP 9.5) requires unit tests for every variant
        @Suppress("UnstableApiUsage")
        variantBuilder.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

dependencies {
    val isComponentsBuild = rootProject.name == "components"
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(bundle("shared-android-app"))
    implementation(bundle("shared-android-app-compose"))
    implementation(bundle("shared-tmc-components"))

    debugImplementation(bundle("shared-android-app-compose-debug"))

    testImplementation(bundle("shared-android-app-test"))
    testImplementation(bundle("shared-android-app-compose-test"))
    testImplementation(bundle("shared-tmc-components-test"))

    androidTestImplementation(bundle("shared-android-app-compose-androidTest"))

    implementation(libs.androidx.activity.compose)

    if (!isComponentsBuild) {
        implementation(libs.tb.mobile.components.ui.bolt)
        testImplementation(libs.tb.mobile.components.ui.testing)
    }
}

plugins {
    id(ThunderbirdPlugins.Library.android)
}

android {
    namespace = "net.thunderbird.feature.mail.sync.internal"
}

dependencies {
    // Temporary: the implementation still works on the legacy local store, backends and notifications.
    implementation(projects.legacy.core)

    api(projects.feature.mail.sync.api)

    implementation(projects.core.logging.api)

    testImplementation(projects.core.logging.testing)
    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
}

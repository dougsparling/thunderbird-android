plugins {
    id(ThunderbirdPlugins.Library.jvm)
    alias(libs.plugins.android.lint)
}

dependencies {
    testImplementation(libs.assertk)
    testImplementation(libs.mime4j.core)
    testImplementation(libs.mime4j.dom)
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}

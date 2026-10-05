plugins {
    id(ThunderbirdPlugins.Library.jvm)
    alias(libs.plugins.android.lint)
}

dependencies {
    api(projects.mail.testserver.fixture)

    testImplementation(libs.assertk)
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}

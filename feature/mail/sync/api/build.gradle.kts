plugins {
    id(ThunderbirdPlugins.Library.android)
}

android {
    namespace = "net.thunderbird.feature.mail.sync.api"
}

dependencies {
    api(projects.core.common)
    api(projects.feature.account.api)
    api(projects.feature.search.implLegacy)
    api(projects.mail.common)

    // Temporary: messages are still identified by the legacy MessageReference.
    api(projects.legacy.message)
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}

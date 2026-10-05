package net.thunderbird.gradle.plugin.quality.coverage

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.gradle.kotlin.dsl.newInstance
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

class CodeCoverageExtensionTest {

    private val project = ProjectBuilder.builder().build()
    private val testSubject = project.objects.newInstance<CodeCoverageExtension>()

    @Test
    fun `initialize should use the given default for disabled`() {
        // Act
        testSubject.initialize(disabledByDefault = project.provider { false })

        // Assert
        assertThat(testSubject.disabled.get()).isFalse()
    }

    @Test
    fun `initialize should keep a value set by the build script`() {
        // Arrange
        testSubject.initialize(disabledByDefault = project.provider { false })

        // Act
        testSubject.disabled.set(true)

        // Assert
        assertThat(testSubject.disabled.get()).isTrue()
    }

    @Test
    fun `initialize should set default coverage thresholds`() {
        // Act
        testSubject.initialize(disabledByDefault = project.provider { true })

        // Assert
        assertThat(testSubject.branchCoverage.get()).isEqualTo(DEFAULT_MIN_BRANCH_COVERAGE)
        assertThat(testSubject.lineCoverage.get()).isEqualTo(DEFAULT_MIN_LINE_COVERAGE)
    }
}

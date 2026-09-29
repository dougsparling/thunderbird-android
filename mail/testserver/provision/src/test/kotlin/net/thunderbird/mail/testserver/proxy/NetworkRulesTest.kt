package net.thunderbird.mail.testserver.proxy

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class NetworkRulesTest {

    @Test
    fun `DSL builds the rule model`() {
        val testSubject = networkRules {
            imap.onCommand("uid store").afterServerResponds { disconnect() }.once()
            imap.onCommand("UID FETCH").beforeServerSees { reset() }.times(2)
            imap.onResponse("3 EXPUNGE") { line -> line.startsWith("* 3 EXPUNGE") }.then { delay(500.milliseconds) }
            onConnect(2) { refuse() }
            afterBytes(4096, Direction.DOWNSTREAM) {
                delay(10.milliseconds)
                disconnect()
            }.always()
            latency(50.milliseconds)
            throttle(bytesPerSecond = 16_000)
            refuseConnections()
        }

        assertThat(testSubject.faults.map { it.describe() }).containsExactly(
            "onCommand UID STORE afterServerResponds -> disconnect [once]",
            "onCommand UID FETCH beforeServerSees -> reset [2 times]",
            "onResponse 3 EXPUNGE -> delay 500ms [once]",
            "onConnect #2 -> refuse [once]",
            "afterBytes 4096 DOWNSTREAM -> delay 10ms, disconnect [always]",
        )
        assertThat(testSubject.latency).isEqualTo(50.milliseconds)
        assertThat(testSubject.bytesPerSecond).isEqualTo(16_000L)
        assertThat(testSubject.refuseConnections).isTrue()
        val predicate = (testSubject.faults[2].trigger as FaultTrigger.ImapResponse).predicate
        assertThat(predicate("* 3 EXPUNGE")).isTrue()
        assertThat(predicate("* 4 EXPUNGE")).isFalse()
    }

    @Test
    fun `empty rules describe as none`() {
        val testSubject = networkRules { }

        assertThat(testSubject).isEqualTo(NetworkRules.NONE)
        assertThat(testSubject.describe()).isEqualTo("none")
        assertThat(testSubject.bytesPerSecond).isNull()
    }

    @Test
    fun `refuse is only allowed in onConnect`() {
        assertFailure {
            networkRules { imap.onCommand("NOOP").beforeServerSees { refuse() } }
        }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `actions after a terminal action are rejected`() {
        assertFailure {
            networkRules {
                onConnect {
                    disconnect()
                    delay(1.milliseconds)
                }
            }
        }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `a rule needs an action`() {
        assertFailure {
            networkRules { onConnect { } }
        }.isInstanceOf<IllegalArgumentException>()
    }
}

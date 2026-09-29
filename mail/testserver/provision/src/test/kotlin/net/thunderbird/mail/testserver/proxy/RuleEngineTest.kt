package net.thunderbird.mail.testserver.proxy

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test

class RuleEngineTest {

    @Test
    fun `once rule fires a single time`() {
        val testSubject = RuleEngine(networkRules { imap.onCommand("NOOP").beforeServerSees { disconnect() } })

        val results = List(3) { testSubject.matchCommand("NOOP") }

        assertThat(results.map { it != null }).containsExactly(true, false, false)
    }

    @Test
    fun `times and always`() {
        val testSubject = RuleEngine(
            networkRules {
                imap.onCommand("NOOP").beforeServerSees { disconnect() }.times(2)
                imap.onCommand("IDLE").beforeServerSees { disconnect() }.always()
            },
        )

        val noop = List(3) { testSubject.matchCommand("NOOP") != null }
        val idle = List(5) { testSubject.matchCommand("IDLE") != null }

        assertThat(noop).containsExactly(true, true, false)
        assertThat(idle).containsExactly(true, true, true, true, true)
    }

    @Test
    fun `first rule with uses left wins`() {
        val testSubject = RuleEngine(
            networkRules {
                imap.onCommand("NOOP").beforeServerSees { disconnect() }
                imap.onCommand("NOOP").afterServerResponds { reset() }
            },
        )

        val first = testSubject.matchCommand("NOOP")
        val second = testSubject.matchCommand("NOOP")

        assertThat(first?.actions).isEqualTo(listOf(FaultAction.Disconnect))
        assertThat(second?.actions).isEqualTo(listOf(FaultAction.Reset))
        assertThat(testSubject.matchCommand("NOOP")).isNull()
    }

    @Test
    fun `command names must match exactly`() {
        val testSubject = RuleEngine(networkRules { imap.onCommand("FETCH").beforeServerSees { disconnect() } })

        assertThat(testSubject.matchCommand("UID FETCH")).isNull()
        assertThat(testSubject.matchCommand("FETCH")).isNotNull()
    }

    @Test
    fun `argument predicates see the rest of the command line`() {
        val seen = mutableListOf<String>()
        val testSubject = RuleEngine(
            networkRules {
                imap.onCommand("UID FETCH", label = "flags only") { args ->
                    seen += args
                    "FLAGS" in args && "BODY" !in args
                }.beforeServerSees { disconnect() }.always()
            },
        )

        val bodyFetch = testSubject.matchCommand("UID FETCH", "1:* (UID BODY.PEEK[])")
        val otherCommand = testSubject.matchCommand("UID STORE", "1 +FLAGS (\\Seen)")
        val flagFetch = testSubject.matchCommand("UID FETCH", "1:* (UID FLAGS)")

        assertThat(bodyFetch).isNull()
        assertThat(otherCommand).isNull()
        assertThat(flagFetch).isNotNull()
        assertThat(seen).containsExactly("1:* (UID BODY.PEEK[])", "1:* (UID FLAGS)")
    }

    @Test
    fun `connect rules match by connection number since applied`() {
        val testSubject = RuleEngine(networkRules { onConnect(2) { refuse() } })

        val results = List(3) { testSubject.matchConnect(testSubject.nextConnectionNumber()) != null }

        assertThat(results).containsExactly(false, true, false)
    }

    @Test
    fun `response and byte rules`() {
        val testSubject = RuleEngine(
            networkRules {
                imap.onResponse { it.endsWith("EXPUNGE") }.then { disconnect() }
                afterBytes(10, Direction.DOWNSTREAM) { disconnect() }
                afterBytes(5, Direction.DOWNSTREAM) { disconnect() }
                afterBytes(7, Direction.UPSTREAM) { disconnect() }
            },
        )

        assertThat(testSubject.matchResponse("* 1 EXISTS")).isNull()
        assertThat(testSubject.matchResponse("* 1 EXPUNGE")).isNotNull()
        assertThat(testSubject.byteThresholds(Direction.DOWNSTREAM)).containsExactly(5L, 10L)
        assertThat(testSubject.matchBytes(Direction.UPSTREAM, 10)).isNull()
        assertThat(testSubject.matchBytes(Direction.DOWNSTREAM, 10)).isNotNull()
    }
}

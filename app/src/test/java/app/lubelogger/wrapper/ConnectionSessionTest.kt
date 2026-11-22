package app.lubelogger.wrapper

import org.junit.Assert.*
import org.junit.Test

class ConnectionSessionTest {
    private val addresses = (ServerAddresses.parse("https://home.example/garage", "https://away.example/cars") as AddressConfiguration.Valid).addresses
    private val clock = TestClock()
    private val probe = TestProbe()
    private val decisions = mutableListOf<ConnectionDecision>()
    private fun session(restored: ServerAddress? = null) = ConnectionSession(addresses, clock, probe, restored, decisions::add)

    @Test fun `fresh selection tries preferred then fallback and ignores late paused results`() {
        val session = session()
        session.resume()
        assertEquals(listOf(ConnectionDecision.Checking), decisions)
        assertEquals(addresses.preferred, probe.pending[0].address)
        probe.complete(0, false)
        assertEquals(addresses.fallback, probe.pending[1].address)
        session.pause()
        probe.complete(1, true)
        assertEquals(listOf(ConnectionDecision.Checking), decisions)
        session.resume()
        probe.complete(2, true)
        assertEquals(ConnectionDecision.Selected(addresses.preferred, null), decisions.last())
    }

    @Test fun `healthy fallback is sticky and failed active selection preserves timing`() {
        val session = session(addresses.fallback)
        session.resume()
        session.resume()
        clock.advance(1199)
        assertTrue(probe.pending.isEmpty())
        clock.advance(1)
        assertEquals(addresses.fallback, probe.pending[0].address)
        assertEquals(1500, probe.pending[0].timeout)
        probe.complete(0, true)
        assertTrue(decisions.isEmpty())
        session.connectionChanged()
        clock.advance(600)
        session.connectionChanged()
        clock.advance(1199)
        assertEquals(1, probe.pending.size)
        clock.advance(1)
        probe.complete(1, false)
        assertEquals(addresses.preferred, probe.pending[2].address)
        probe.complete(2, false)
        assertTrue(decisions.isEmpty())
        clock.advance(3999)
        assertEquals(3, probe.pending.size)
        clock.advance(1)
        probe.complete(3, false)
        probe.complete(4, true)
        assertEquals(listOf(ConnectionDecision.Selected(addresses.preferred, addresses.fallback)), decisions)
    }

    @Test fun `initial failure waits for manual retry and close is terminal`() {
        val session = session()
        session.resume()
        assertEquals(2500, probe.pending[0].timeout)
        probe.complete(0, false)
        probe.complete(1, false)
        assertEquals(ConnectionDecision.Unavailable, decisions.last())
        clock.advance(10000)
        assertEquals(2, probe.pending.size)
        session.retry()
        assertEquals(ConnectionDecision.Checking, decisions.last())
        val beforeClose = decisions.toList()
        session.close()
        session.close()
        probe.complete(2, true)
        session.resume()
        session.retry()
        session.connectionChanged()
        clock.advance(10000)
        assertEquals(beforeClose, decisions)
        assertEquals(3, probe.pending.size)
    }

    @Test fun `old completion cannot interfere with a resumed active check`() {
        val session = session(addresses.preferred)
        session.resume()
        clock.advance(1200)
        session.pause()
        session.connectionChanged()
        clock.advance(10000)
        assertEquals(1, probe.pending.size)
        session.resume()
        clock.advance(1200)
        probe.complete(0, false)
        session.connectionChanged()
        clock.advance(1200)
        assertEquals(2, probe.pending.size)
        probe.complete(1, true)
        clock.advance(1200)
        assertEquals(3, probe.pending.size)
        probe.complete(2, true)
        assertTrue(decisions.isEmpty())
    }

    @Test fun `pause cancels queued validation and valid restoration avoids an initial load`() {
        val session = session(addresses.fallback)
        session.resume()
        session.pause()
        clock.advance(10000)
        assertTrue(probe.pending.isEmpty())
        assertTrue(decisions.isEmpty())
        session.resume()
        clock.advance(1200)
        probe.complete(0, true)
        assertTrue(decisions.isEmpty())
    }

    @Test fun `unconfigured restored address falls back to fresh selection`() {
        val foreign = (ServerAddresses.parse("https://other.example") as AddressConfiguration.Valid).addresses.preferred
        val session = session(foreign)
        assertTrue(decisions.isEmpty())
        session.resume()
        assertEquals(ConnectionDecision.Checking, decisions.single())
        assertEquals(addresses.preferred, probe.pending.single().address)
    }

    @Test fun `closing a replaced session isolates its late result from a new session`() {
        val old = session()
        old.resume()
        old.close()
        val replacement = session()
        replacement.resume()
        val beforeLateResult = decisions.toList()
        probe.complete(0, false)
        assertEquals(beforeLateResult, decisions)
        probe.complete(1, true)
        assertEquals(ConnectionDecision.Selected(addresses.preferred, null), decisions.last())
    }

    private class TestClock : ConnectionScheduler {
        private data class Task(val time: Long, val action: () -> Unit, var canceled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        private var now = 0L
        override fun schedule(delayMs: Long, action: () -> Unit): Cancellation {
            val task = Task(now + delayMs, action)
            tasks += task
            return Cancellation { task.canceled = true }
        }
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val task = tasks.filter { !it.canceled && it.time <= target }.minByOrNull { it.time } ?: break
                tasks.remove(task)
                now = task.time
                task.action()
            }
            now = target
        }
    }

    private class TestProbe : ConnectionProbe {
        data class Pending(val address: ServerAddress, val timeout: Int, val result: (Boolean) -> Unit)
        val pending = mutableListOf<Pending>()
        override fun check(address: ServerAddress, timeoutMs: Int, result: (Boolean) -> Unit): Cancellation {
            pending += Pending(address, timeoutMs, result)
            // Deliberately allow late completion after cancellation, as a real transport may do.
            return Cancellation { }
        }
        fun complete(index: Int, works: Boolean) = pending[index].result(works)
        override fun close() = Unit
    }
}
